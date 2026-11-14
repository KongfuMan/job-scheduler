package com.webapps.jobs.controllers;

import com.webapps.jobs.entity.Execution;
import com.webapps.jobs.entity.Job;
import com.webapps.jobs.dto.ExecutionRequest;
import com.webapps.jobs.dto.JobRequest;
import com.webapps.jobs.services.JobExecutionService;
import com.webapps.jobs.services.JobService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class JobController {
    private final JobService jobService;
    private final JobExecutionService executionService;

    public JobController(JobService jobService, JobExecutionService executionService) {
        this.jobService = jobService;
        this.executionService = executionService;
    }

    @PostMapping("/v1/jobs")
    public ResponseEntity<Job> createJob(@RequestBody JobRequest request) throws Exception {
        Job job = jobService.create(request);
        return ResponseEntity.created(URI.create("/v1/jobs/" + job.jobId())).body(job);
    }

    @PutMapping("/v1/jobs/{jobId}")
    public Job updateJob(@PathVariable UUID jobId, @RequestBody JobRequest request) throws Exception {
        return jobService.update(jobId, request);
    }

    @GetMapping("/v1/jobs/{jobId}")
    public Job getJob(@PathVariable UUID jobId) {
        return jobService.get(jobId);
    }

    @PostMapping("/v1/executions")
    public ResponseEntity<Execution> createExecution(@RequestHeader(value = "Idempotency-Key", required = true) String key,
                                                       @RequestBody ExecutionRequest request) throws Exception {
        Execution execution = executionService.create(key, request);
        return ResponseEntity.accepted().location(URI.create("/v1/executions/" + execution.executionId())).body(execution);
    }

    @GetMapping("/v1/executions/{executionId}")
    public Execution getExecution(@PathVariable UUID executionId) {
        return executionService.get(executionId);
    }
}
