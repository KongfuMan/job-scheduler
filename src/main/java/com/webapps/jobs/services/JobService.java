package com.webapps.jobs.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webapps.jobs.dto.JobRequest;
import com.webapps.jobs.entity.Job;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JobService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Job create(JobRequest request) throws Exception {
        String url = validateUrl(request.targetUrl());
        validateJob(request);
        return jdbc.queryForObject("""
                INSERT INTO job_definitions (job_id, job_name, metadata, target_url, created_by)
                VALUES (?, ?, CAST(? AS jsonb), ?, 'unauthenticated') RETURNING *
                """, this::job, UUID.randomUUID(), request.jobName(),
                mapper.writeValueAsString(request.metadata() == null ? mapper.createObjectNode() : request.metadata()), url);
    }

    @Transactional
    public Job update(UUID jobId, JobRequest request) throws Exception {
        String url = validateUrl(request.targetUrl());
        validateJob(request);
        if (jdbc.update("UPDATE job_definitions SET job_name = ?, metadata = CAST(? AS jsonb), target_url = ? WHERE job_id = ?",
                request.jobName(), mapper.writeValueAsString(request.metadata() == null ? mapper.createObjectNode() : request.metadata()), url, jobId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
        }
        return get(jobId);
    }

    public Job get(UUID jobId) {
        return jdbc.query("SELECT * FROM job_definitions WHERE job_id = ?", this::job, jobId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
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
