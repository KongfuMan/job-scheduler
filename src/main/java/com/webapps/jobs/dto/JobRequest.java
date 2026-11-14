package com.webapps.jobs.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record JobRequest(String jobName, JsonNode metadata, String targetUrl) {}
