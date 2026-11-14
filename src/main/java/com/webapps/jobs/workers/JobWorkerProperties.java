package com.webapps.jobs.workers;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jobs.worker")
public record JobWorkerProperties(
        boolean enabled,
        String id,
        int concurrency,
        long pollIntervalMs,
        long leaseMs,
        long heartbeatIntervalMs,
        long retryDelayMs,
        long maxRetryDelayMs,
        long httpTimeoutMs) {}
