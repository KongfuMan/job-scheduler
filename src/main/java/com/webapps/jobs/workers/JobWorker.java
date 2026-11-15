package com.webapps.jobs.workers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Polls PostgreSQL for executions; delivery is at least once, not exactly once. */
@Component
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final String workerId;
    private final int concurrency;
    private final long pollMs;
    private final long leaseMs;
    private final long heartbeatMs;
    private final long retryMs;
    private final long maxRetryMs;
    private final long httpTimeoutMs;
    private final HttpClient http;
    private final Semaphore slots;
    private ExecutorService executions;
    private ScheduledExecutorService poller;
    private ScheduledExecutorService heartbeats;

    public JobWorker(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, JobWorkerProperties properties) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(transactionManager);
        enabled = properties.enabled();
        workerId = properties.id();
        concurrency = properties.concurrency();
        pollMs = properties.pollIntervalMs();
        leaseMs = properties.leaseMs();
        heartbeatMs = properties.heartbeatIntervalMs();
        retryMs = properties.retryDelayMs();
        maxRetryMs = properties.maxRetryDelayMs();
        httpTimeoutMs = properties.httpTimeoutMs();
        if (workerId.isBlank() || workerId.indexOf('\0') >= 0 || workerId.codePointCount(0, workerId.length()) > 255 || concurrency < 1 || pollMs < 1 || leaseMs < 2
                || heartbeatMs < 1 || heartbeatMs >= leaseMs || retryMs < 1
                || maxRetryMs < retryMs || httpTimeoutMs < 1) {
            throw new IllegalArgumentException("Invalid jobs.worker configuration");
        }
        slots = new Semaphore(concurrency);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(httpTimeoutMs)).build();
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        executions = Executors.newFixedThreadPool(concurrency);
        poller = Executors.newSingleThreadScheduledExecutor();
        heartbeats = Executors.newScheduledThreadPool(concurrency);
        poller.scheduleWithFixedDelay(this::poll, 0, pollMs, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        try {
            // An expired final attempt cannot be claimed again, so terminate it separately.
            jdbc.update("""
                    UPDATE job_executions
                    SET status = 'FAILED',
                        worker_id = NULL,
                        lease_until = NULL,
                        next_retry_at = NULL,
                        last_error = 'Lease expired after final attempt',
                        updated_at = statement_timestamp()
                    WHERE status = 'RUNNING' AND
                          lease_until <= statement_timestamp() AND
                          attempts >= max_attempts
                    """);
            while (slots.tryAcquire()) {
                Claim claim;
                try {
                    claim = claim();
                } catch (RuntimeException e) {
                    slots.release();
                    throw e;
                }
                if (claim == null) {
                    slots.release();
                    break;
                }
                try {
                    executions.execute(() -> {
                        try {
                            execute(claim);
                        } finally {
                            slots.release();
                        }
                    });
                } catch (RejectedExecutionException e) {
                    slots.release();
                    // The lease expires and the execution is recovered by another worker.
                    throw e;
                }
            }
        } catch (Exception e) {
            log.error("Worker poll failed", e);
        }
    }

    private Claim claim() {
        return transactions.execute(status -> claimNext());
    }

    private Claim claimNext() {
        List<Claim> claims = jdbc.query("""
                WITH
                    candidate AS (
                        SELECT
                            e.execution_id,
                            d.target_url
                        FROM
                            job_executions e
                        JOIN
                            job_definitions d
                        ON
                            d.job_id = e.job_id
                        WHERE
                            e.attempts < e.max_attempts
                            AND (
                                (e.status IN ('PENDING', 'RETRY')
                                 AND (e.next_retry_at IS NULL
                                      OR e.next_retry_at <= statement_timestamp()))
                                OR (e.status = 'RUNNING'
                                    AND e.lease_until <= statement_timestamp())
                            )
                        ORDER BY
                            COALESCE(e.next_retry_at, e.lease_until, e.updated_at),
                            e.execution_id
                        FOR UPDATE OF e
                        SKIP LOCKED
                        LIMIT 1
                    )
                UPDATE
                    job_executions e
                SET
                    status = 'RUNNING',
                    attempts = e.attempts + 1,
                    worker_id = ?,
                    fencing_token = COALESCE(e.fencing_token, 0) + 1,
                    lease_until = statement_timestamp() + (? * interval '1 millisecond'),
                    next_retry_at = NULL,
                    updated_at = statement_timestamp()
                FROM
                    candidate c
                WHERE
                    e.execution_id = c.execution_id
                RETURNING
                    e.execution_id,
                    e.payload::text,
                    e.attempts,
                    e.max_attempts,
                    e.fencing_token,
                    e.external_idempotency_key,
                    c.target_url
                """,
                (rs, row) -> new Claim(rs.getObject("execution_id", UUID.class),
                rs.getString("payload"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                rs.getLong("fencing_token"), rs.getString("external_idempotency_key"),
                rs.getString("target_url")), workerId, leaseMs);
        return claims.isEmpty() ? null : claims.get(0);
    }

    private void execute(Claim claim) {
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = heartbeats.scheduleWithFixedDelay(() -> heartbeat(claim),
                    heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
            HttpRequest request = HttpRequest.newBuilder(URI.create(claim.url()))
                    .timeout(Duration.ofMillis(httpTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", claim.key())
                    .header("X-Fencing-Token", Long.toString(claim.token()))
                    .POST(HttpRequest.BodyPublishers.ofString(claim.payload() == null ? "null" : claim.payload()))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                finish(claim, null);
            } else {
                finish(claim, "HTTP status " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Leave the lease to expire: the remote outcome may be unknown.
        } catch (Exception e) {
            log.warn("Execution {} failed", claim.id(), e);
            try {
                finish(claim, e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            } catch (RuntimeException dbError) {
                log.error("Could not record failure for execution {}", claim.id(), dbError);
            }
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
        }
    }

    private void heartbeat(Claim claim) {
        try {
            int updated = jdbc.update("""
                    UPDATE job_executions SET lease_until = statement_timestamp() + (? * interval '1 millisecond'),
                        updated_at = statement_timestamp()
                    WHERE execution_id = ? AND status = 'RUNNING' AND worker_id = ?
                        AND fencing_token = ? AND lease_until > statement_timestamp()
                    """, leaseMs, claim.id(), workerId, claim.token());
            if (updated == 0) log.warn("Lease lost for execution {}", claim.id());
        } catch (RuntimeException e) {
            log.error("Heartbeat failed for execution {}", claim.id(), e);
        }
    }

    private void finish(Claim claim, String error) {
        boolean retry = error != null && claim.attempts() < claim.maxAttempts();
        long delay = retryMs;
        for (int i = 1; i < claim.attempts() && delay < maxRetryMs; i++) {
            delay = delay > maxRetryMs / 2 ? maxRetryMs : Math.min(maxRetryMs, delay * 2);
        }
        int updated = jdbc.update("""
                UPDATE job_executions SET status = ?, worker_id = NULL, lease_until = NULL,
                    next_retry_at = statement_timestamp() + CASE WHEN ? THEN (? * interval '1 millisecond') ELSE NULL END,
                    last_error = ?, updated_at = statement_timestamp()
                WHERE execution_id = ? AND status = 'RUNNING' AND worker_id = ?
                    AND fencing_token = ? AND lease_until > statement_timestamp()
                """, error == null ? "SUCCEEDED" : retry ? "RETRY" : "FAILED", retry, delay,
                error == null ? null : error.substring(0, Math.min(error.length(), 4000)),
                claim.id(), workerId, claim.token());
        if (updated == 0) log.warn("Discarded stale result for execution {}", claim.id());
    }

    @PreDestroy
    public void stop() {
        if (!enabled) return;
        poller.shutdownNow();
        executions.shutdownNow();
        heartbeats.shutdownNow();
    }

    private record Claim(UUID id, String payload, int attempts, int maxAttempts,
                         long token, String key, String url) { }
}
