package com.webapps.jobs.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webapps.jobs.dto.ExecutionRequest;
import com.webapps.jobs.entity.Execution;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobExecutionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JobExecutionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Execution create(String key, ExecutionRequest request) throws Exception {
        validateRequest(key, request);
        int maxAttempts = request.maxAttempts() == null ? 3 : request.maxAttempts();
        String payload = mapper.writeValueAsString(request.payload());
        List<Execution> inserted = jdbc.query("""
                INSERT INTO job_executions (execution_id, job_id, payload, max_attempts, client_idempotency_key, external_idempotency_key, created_by)
                SELECT ?, job_id, CAST(? AS jsonb), ?, ?, ?, 'unauthenticated' FROM job_definitions WHERE job_id = ?
                ON CONFLICT (client_idempotency_key) DO NOTHING RETURNING *
                """, this::execution, UUID.randomUUID(), payload, maxAttempts, key, UUID.randomUUID().toString(), request.jobId());
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        List<Boolean> matches = jdbc.query("""
                SELECT job_id = ? AND payload = CAST(? AS jsonb) AND max_attempts = ? AS matches
                FROM job_executions WHERE client_idempotency_key = ?
                """, (rs, row) -> rs.getBoolean("matches"), request.jobId(), payload, maxAttempts, key);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
        }
        if (!matches.get(0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key already used for a different request");
        }
        return jdbc.query("SELECT * FROM job_executions WHERE client_idempotency_key = ?", this::execution, key).get(0);
    }

    public Execution get(UUID executionId) {
        return jdbc.query("SELECT * FROM job_executions WHERE execution_id = ?", this::execution, executionId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));
    }

    private Execution execution(ResultSet rs, int row) throws SQLException {
        try {
            return new Execution(rs.getObject("execution_id", UUID.class), rs.getObject("job_id", UUID.class),
                    mapper.readTree(rs.getString("payload")), rs.getString("status"), rs.getInt("attempts"),
                    rs.getInt("max_attempts"), rs.getObject("next_retry_at", OffsetDateTime.class),
                    rs.getObject("lease_until", OffsetDateTime.class), rs.getString("worker_id"),
                    rs.getLong("fencing_token"), rs.getString("external_idempotency_key"),
                    rs.getString("last_error"), rs.getObject("updated_at", OffsetDateTime.class),
                    rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"));
        } catch (java.io.IOException e) {
            throw new SQLException("Invalid stored execution payload", e);
        }
    }

    private void validateRequest(String key, ExecutionRequest request) {
        if (key == null || key.isBlank() || key.codePointCount(0, key.length()) > 255 || !key.equals(key.strip()) || key.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be nonblank, at most 255 characters, and have no surrounding whitespace");
        }
        if (request.jobId() == null || request.payload() == null || request.payload().isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId and non-null payload are required");
        }
        validateJson(request.payload());
        if (request.maxAttempts() != null && request.maxAttempts() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxAttempts must be positive");
        }
    }

    private void validateJson(JsonNode node) {
        if (node.isTextual() && node.textValue().indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON strings must not contain NUL");
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (field.getKey().indexOf('\0') >= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON keys must not contain NUL");
                }
                validateJson(field.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::validateJson);
        }
    }
}
