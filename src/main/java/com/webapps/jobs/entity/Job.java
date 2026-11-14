package com.webapps.jobs.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Job(UUID jobId, String jobName, JsonNode metadata, String targetUrl,
                  OffsetDateTime createdAt, String createdBy) {}
