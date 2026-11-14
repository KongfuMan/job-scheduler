package com.webapps.jobs;

import com.webapps.jobs.controllers.JobController;
import com.webapps.jobs.workers.JobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "jobs.worker.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class JobsApplicationTest {
    @MockBean JdbcTemplate jdbc;
    @Autowired JobWorker worker;
    @Autowired JobController controller;

    @Test
    void discoversControllerAndWorker() {
        assertThat(controller).isNotNull();
        assertThat(worker).isNotNull();
    }
}
