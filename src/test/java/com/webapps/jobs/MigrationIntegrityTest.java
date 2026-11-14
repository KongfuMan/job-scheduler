package com.webapps.jobs;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Runs only against an explicitly supplied empty, disposable database. All DDL is rolled back. */
@EnabledIfEnvironmentVariable(named = "JOBS_CONSTRAINT_TEST_URL", matches = ".+")
class MigrationIntegrityTest {
    @Test
    void constraintsAndTransitions() throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getenv("JOBS_CONSTRAINT_TEST_URL"),
                System.getenv().getOrDefault("JOBS_CONSTRAINT_TEST_USER", "postgres"),
                System.getenv().getOrDefault("JOBS_CONSTRAINT_TEST_PASSWORD", "postgres"))) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                try {
                    // Never operate on existing application tables, even within a transaction.
                    try (var result = sql.executeQuery("SELECT to_regclass('job_definitions'), to_regclass('job_executions')")) {
                        result.next();
                        assertThat(result.getString(1)).isNull();
                        assertThat(result.getString(2)).isNull();
                    }
                    String migration;
                    try (var resource = getClass().getResourceAsStream("/db/migration/V1__jobs.sql")) {
                        migration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    sql.execute(migration);
                    sql.execute("INSERT INTO job_definitions(job_id, target_url) VALUES ('00000000-0000-0000-0000-000000000001', 'http://example.com')");
                    for (String change : new String[] {
                            "job_name = '  '", "target_url = ''", "target_url = repeat('x', 2049)",
                            "metadata = '[]'", "created_at = NULL", "created_by = E'\\t'"}) {
                        rejected(connection, sql, "UPDATE job_definitions SET " + change);
                    }
                    sql.execute("INSERT INTO job_executions(execution_id, job_id, payload, client_idempotency_key, external_idempotency_key) VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', '{}', 'client', 'external')");
                    for (String change : new String[] {
                            "status = 'BOGUS'", "attempts = -1", "attempts = 4", "max_attempts = 0",
                            "attempts = 1", "status = 'RETRY'", "next_retry_at = NULL",
                            "worker_id = 'worker'", "lease_until = now()", "fencing_token = -1",
                            "client_idempotency_key = ' key'", "external_idempotency_key = ''",
                            "client_idempotency_key = repeat('x', 256)", "payload = 'null'",
                            "created_by = ' '", "created_at = NULL", "updated_at = NULL"}) {
                        rejected(connection, sql, "UPDATE job_executions SET " + change);
                    }
                    sql.execute("UPDATE job_executions SET status='RUNNING', attempts=1, next_retry_at=NULL, worker_id='worker', lease_until=now() - interval '1 second', fencing_token=1");
                    rejected(connection, sql, "UPDATE job_executions SET worker_id = ' '");
                    rejected(connection, sql, "UPDATE job_executions SET lease_until = NULL");
                    rejected(connection, sql, "UPDATE job_executions SET fencing_token = 0");
                    sql.execute("UPDATE job_executions SET status='RETRY', worker_id=NULL, lease_until=NULL, next_retry_at=now()");
                    rejected(connection, sql, "UPDATE job_executions SET attempts=max_attempts");
                    sql.execute("UPDATE job_executions SET status='RUNNING', attempts=2, next_retry_at=NULL, worker_id='worker', lease_until=now(), fencing_token=2");
                    // Expired lease reclaim, then either final failure or success.
                    sql.execute("UPDATE job_executions SET attempts=3, fencing_token=3, lease_until=now()");
                    Savepoint running = connection.setSavepoint();
                    sql.execute("UPDATE job_executions SET status='FAILED', worker_id=NULL, lease_until=NULL");
                    connection.rollback(running);
                    sql.execute("UPDATE job_executions SET status='SUCCEEDED', worker_id=NULL, lease_until=NULL");
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private void rejected(Connection connection, Statement sql, String statement) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            sql.execute(statement);
            fail("Expected constraint violation: " + statement);
        } catch (SQLException e) {
            assertThat(e.getSQLState()).as(statement).isIn("23502", "23505", "23514", "22001");
        } finally {
            connection.rollback(savepoint);
        }
    }
}
