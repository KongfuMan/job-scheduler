package com.webapps.jobs.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record ExecutionRequest(UUID jobId, JsonNode payload, Integer maxAttempts) {}
