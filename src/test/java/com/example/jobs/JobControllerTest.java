package com.example.jobs;

import com.example.jobs.entity.Execution;
import com.example.jobs.entity.ExecutionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JobControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new JobController(jdbc, new ObjectMapper())).build();
    }

    @Test
    void rejectsInvalidJobFieldsBeforeDatabaseAccess() throws Exception {
        for (String body : new String[] {
                "{\"jobName\":\"job\",\"targetUrl\":\"http://user:pass@example.com\"}",
                "{\"jobName\":\" \",\"targetUrl\":\"https://example.com\"}",
                "{\"jobName\":\"job\",\"metadata\":[],\"targetUrl\":\"https://example.com\"}",
                "{\"jobName\":\"\\u0000\",\"targetUrl\":\"https://example.com\"}",
                "{\"jobName\":\"job\",\"metadata\":{\"nested\":[\"\\u0000\"]},\"targetUrl\":\"https://example.com\"}",
                "{\"jobName\":\"job\",\"metadata\":{\"\\u0000\":1},\"targetUrl\":\"https://example.com\"}"
        }) {
            mvc.perform(post("/v1/jobs").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsInvalidExecutionRequestsBeforeDatabaseAccess() throws Exception {
        String valid = "{\"jobId\":\"00000000-0000-0000-0000-000000000001\",\"payload\":{}}";
        mvc.perform(post("/v1/executions").contentType("application/json").content(valid))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/executions").header("Idempotency-Key", " key ")
                .contentType("application/json").content(valid)).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/executions").header("Idempotency-Key", "key")
                .contentType("application/json").content("{\"payload\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/executions").header("Idempotency-Key", "key")
                .contentType("application/json")
                .content("{\"jobId\":\"00000000-0000-0000-0000-000000000001\",\"payload\":{},\"maxAttempts\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/executions").header("Idempotency-Key", "key")
                .contentType("application/json")
                .content("{\"jobId\":\"00000000-0000-0000-0000-000000000001\",\"payload\":{\"a\":\"\\u0000\"}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(jdbc);
    }

    @Test
    void createsIndependentExternalKeys() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("query")) return List.of(execution());
            return null;
        });
        JobController controller = new JobController(db, new ObjectMapper());
        var request = new ExecutionRequest(UUID.randomUUID(), new ObjectMapper().createObjectNode(), null);
        controller.createExecution("client-key", request);
        controller.createExecution("another-client-key", request);

        var calls = mockingDetails(db).getInvocations().stream().toList();
        assertThat(calls).hasSize(2);
        String sql = (String) calls.get(0).getArgument(0);
        assertThat(sql).contains("ON CONFLICT (client_idempotency_key)");
        assertThat((String) calls.get(0).getArgument(5)).isEqualTo("client-key");
        String externalKey = calls.get(0).getArgument(6);
        assertThat(UUID.fromString(externalKey)).isNotNull();
        assertThat(externalKey).isNotEqualTo("client-key")
                .isNotEqualTo((String) calls.get(1).getArgument(6));
    }

    @Test
    void replaysAndRejectsConflictsByClientKey() throws Exception {
        for (boolean matches : new boolean[] {true, false}) {
            JdbcTemplate db = mock(JdbcTemplate.class, invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.contains("INSERT INTO")) return List.of();
                assertThat(sql).contains("WHERE client_idempotency_key = ?");
                return sql.contains("AS matches") ? List.of(matches) : List.of(execution());
            });
            JobController controller = new JobController(db, new ObjectMapper());
            var request = new ExecutionRequest(UUID.randomUUID(), new ObjectMapper().createObjectNode(), null);
            if (matches) {
                var response = controller.createExecution("client-key", request);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                assertThat(response.getBody().externalIdempotencyKey()).isEqualTo("preserved-external-key");
            } else {
                assertThatThrownBy(() -> controller.createExecution("client-key", request))
                        .hasMessageContaining("Idempotency-Key already used");
            }
        }
    }

    private static Execution execution() {
        return new Execution(UUID.randomUUID(), UUID.randomUUID(), new ObjectMapper().createObjectNode(),
                "PENDING", 0, 3, null, null, null, 0, "preserved-external-key", null, null, null, "test");
    }
}
