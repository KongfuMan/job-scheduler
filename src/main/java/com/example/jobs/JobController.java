package com.example.jobs;

import com.example.jobs.entity.Execution;
import com.example.jobs.entity.ExecutionRequest;
import com.example.jobs.entity.Job;
import com.example.jobs.entity.JobRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class JobController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JobController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostMapping("/v1/jobs")
    @Transactional
    public ResponseEntity<Job> createJob(@RequestBody JobRequest request) throws Exception {
        String url = validateUrl(request.targetUrl());
        validateJob(request);
        UUID id = UUID.randomUUID();
        Job job = jdbc.queryForObject("""
                INSERT INTO job_definitions (job_id, job_name, metadata, target_url, created_by)
                VALUES (?, ?, CAST(? AS jsonb), ?, 'unauthenticated') RETURNING *
                """, this::job, id, request.jobName(), mapper.writeValueAsString(request.metadata() == null ? mapper.createObjectNode() : request.metadata()), url);
        return ResponseEntity.created(URI.create("/v1/jobs/" + id)).body(job);
    }

    @PutMapping("/v1/jobs/{jobId}")
    @Transactional
    public Job updateJob(@PathVariable UUID jobId, @RequestBody JobRequest request) throws Exception {
        String url = validateUrl(request.targetUrl());
        validateJob(request);
        if (jdbc.update("UPDATE job_definitions SET job_name = ?, metadata = CAST(? AS jsonb), target_url = ? WHERE job_id = ?",
                request.jobName(), mapper.writeValueAsString(request.metadata() == null ? mapper.createObjectNode() : request.metadata()), url, jobId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
        }
        return getJob(jobId);
    }

    @GetMapping("/v1/jobs/{jobId}")
    public Job getJob(@PathVariable UUID jobId) {
        return jdbc.query("SELECT * FROM job_definitions WHERE job_id = ?", this::job, jobId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    @PostMapping("/v1/executions")
    @Transactional
    public ResponseEntity<Execution> createExecution(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      @RequestBody ExecutionRequest request) throws Exception {
        if (key == null || key.isBlank() || key.codePointCount(0, key.length()) > 255 || !key.equals(key.strip()) || key.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be nonblank, at most 255 characters, and have no surrounding whitespace");
        }
        if (request.jobId() == null || request.payload() == null || request.payload().isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId and non-null payload are required");
        }
        validateJson(request.payload());
        int maxAttempts = request.maxAttempts() == null ? 3 : request.maxAttempts();
        if (maxAttempts < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxAttempts must be positive");
        }
        String payload = mapper.writeValueAsString(request.payload());
        UUID id = UUID.randomUUID();
        List<Execution> inserted = jdbc.query("""
                INSERT INTO job_executions (execution_id, job_id, payload, max_attempts, client_idempotency_key, external_idempotency_key, created_by)
                SELECT ?, job_id, CAST(? AS jsonb), ?, ?, ?, 'unauthenticated' FROM job_definitions WHERE job_id = ?
                ON CONFLICT (client_idempotency_key) DO NOTHING RETURNING *
                """, this::execution, id, payload, maxAttempts, key, UUID.randomUUID().toString(), request.jobId());
        if (!inserted.isEmpty()) {
            return ResponseEntity.accepted().location(URI.create("/v1/executions/" + id)).body(inserted.get(0));
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
        Execution existing = jdbc.query("SELECT * FROM job_executions WHERE client_idempotency_key = ?",
                this::execution, key).get(0);
        return ResponseEntity.accepted().location(URI.create("/v1/executions/" + existing.executionId())).body(existing);
    }

    @GetMapping("/v1/executions/{executionId}")
    public Execution getExecution(@PathVariable UUID executionId) {
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

    private Job job(ResultSet rs, int row) throws SQLException {
        try {
            return new Job(rs.getObject("job_id", UUID.class), rs.getString("job_name"),
                    mapper.readTree(rs.getString("metadata")), rs.getString("target_url"),
                    rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"));
        } catch (java.io.IOException e) {
            throw new SQLException("Invalid stored job metadata", e);
        }
    }

    private void validateJob(JobRequest request) {
        if (request.jobName() == null || request.jobName().isBlank() || request.jobName().indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobName is required");
        }
        if (request.metadata() != null && !request.metadata().isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metadata must be an object");
        }
        if (request.metadata() != null) validateJson(request.metadata());
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

    private String validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (value.length() > 2048 || !value.equals(value.trim()) || uri.getHost() == null
                    || uri.getUserInfo() != null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetUrl must be an absolute HTTP(S) URL without credentials, at most 2048 characters");
        }
    }
}
