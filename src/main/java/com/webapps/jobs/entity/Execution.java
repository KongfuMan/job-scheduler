package com.webapps.jobs.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Execution(UUID executionId, UUID jobId, JsonNode payload, String status,
                        int attempts, int maxAttempts, OffsetDateTime nextRetryAt,
                        OffsetDateTime leaseUntil, String workerId, long fencingToken,
                        String externalIdempotencyKey, String lastError, OffsetDateTime updatedAt,
                        OffsetDateTime createdAt, String createdBy) {}
