# HTTP Job Execution Service

This Spring Boot service stores job definitions and asynchronously sends JSON payloads to their target URLs. It uses PostgreSQL and can optionally use Citus. The API and worker run in the same process.

## Local Database Setup

The examples below assume a local Citus coordinator is running at `localhost:5442` and that the `postgres` user can create databases and extensions. Use a dedicated `job_scheduler` database. Do not run migrations or create application tables in an existing `postgres` database.

Verify that the database name is available:

```sh
psql -h localhost -p 5442 -U postgres -d postgres -l
```

Create the database only when it does not already exist, then enable Citus:

```sh
psql -h localhost -p 5442 -U postgres -d postgres -v ON_ERROR_STOP=1 -c 'CREATE DATABASE job_scheduler'
psql -h localhost -p 5442 -U postgres -d job_scheduler -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS citus'
```

The first command connects to `postgres` only to create a separate database and does not modify its schema. Citus must be installed and configured on the server. A multi-node cluster also requires its worker nodes to be configured in advance. If Citus is not enabled, the migration creates regular PostgreSQL tables.

At startup, Flyway runs V1 from `src/main/resources/db/migration`, creating the application tables and Flyway history table. When the Citus extension is present, both application tables become reference tables. Use an account with sufficient migration permissions for the dedicated database.

V1 consolidates earlier development V2 and V3 migrations. A database that has already applied those migrations will fail Flyway validation; use a new dedicated database instead. Existing databases are never deleted or modified automatically. Plan a separate data migration when data must be retained.

The database enforces consistent status, attempts, leases, retry times, creation timestamps, identifiers, fencing tokens, idempotency-key lengths, and metadata object types. It does not validate complete URL formats or identities, require leases to be in the future, preserve transition history, or guarantee monotonically increasing timestamps.

## Testing

`mvn clean test` runs tests that do not require a database. The optional database-integrity test runs only when `JOBS_CONSTRAINT_TEST_URL` is explicitly set. Use a new disposable database, never a shared or production database. The test runs V1 and constraint checks in a transaction and rolls it back; it refuses to run when application tables already exist in the target search path.

Enable and configure Citus on the test database before Citus testing. Configure credentials with `JOBS_CONSTRAINT_TEST_USER` and `JOBS_CONSTRAINT_TEST_PASSWORD`; both default to `postgres`.

```sh
JOBS_CONSTRAINT_TEST_URL='jdbc:postgresql://localhost:5442/disposable_test' mvn test
```

## Running the Application

The default connection is `jdbc:postgresql://localhost:5442/job_scheduler`, with `postgres` as both username and password. Override it with `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`:

```sh
export DB_URL='jdbc:postgresql://localhost:5442/job_scheduler'
export DB_USERNAME='postgres'
export DB_PASSWORD='postgres'
```

Java 17+ and Maven are required. Run the application from the project directory:

```sh
mvn spring-boot:run
```

Spring Boot uses port `8080` by default; override it with `--server.port=8081`. The worker starts with the application. To run only the API, add `--jobs.worker.enabled=false`.

## API

Requests and responses use JSON, and IDs are UUIDs. The creator is currently always `unauthenticated`; it does not indicate an authenticated identity.

| Method and path | Purpose | Successful response |
| --- | --- | --- |
| `POST /v1/jobs` | Create a job definition | `201`, `Location: /v1/jobs/{jobId}`, and the job |
| `PUT /v1/jobs/{jobId}` | Replace a job's name, metadata, and target URL | `200` and the job; `404` when absent |
| `GET /v1/jobs/{jobId}` | Retrieve a job | `200`; `404` when absent |
| `POST /v1/executions` | Create or idempotently replay an execution | `202`, `Location: /v1/executions/{executionId}`, and the execution |
| `GET /v1/executions/{executionId}` | Retrieve an execution | `200`; `404` when absent |

A job request has a nonblank `jobName`, optional object `metadata` (defaulting to `{}`), and `targetUrl`. Strings and JSON keys cannot contain NUL characters. URLs must be absolute HTTP(S) URLs with a host, no user information, no surrounding whitespace, and a maximum length of 2048. Updates are complete replacements, so omitted `metadata` resets to `{}`. Job responses contain `jobId`, `jobName`, `metadata`, `targetUrl`, `createdAt`, and `createdBy`.

An execution request has `jobId`, a non-null JSON `payload`, and optional positive integer `maxAttempts` (default `3`, including the first attempt). The `Idempotency-Key` header is required: it must be nonblank, at most 255 characters, and have no surrounding whitespace. It is globally unique across executions. Repeating the same key with the same `jobId`, JSON payload, and effective `maxAttempts` returns the existing execution; differing values return `400`. A missing job with no existing key returns `404`.

Execution responses contain `executionId`, `jobId`, `payload`, `status`, `attempts`, `maxAttempts`, `nextRetryAt`, `leaseUntil`, `workerId`, `fencingToken`, `externalIdempotencyKey`, `lastError`, `updatedAt`, `createdAt`, and `createdBy`. A new external idempotency key is generated independently from the client key and remains unchanged through replay and retries. Statuses are `PENDING`, `RUNNING`, `RETRY`, `SUCCEEDED`, and `FAILED`.

### Example Requests

The target must be a trusted HTTP service accessible from the worker. The service on port `9000` must be provided separately. Replace `JOB_UUID` and `EXECUTION_UUID` with IDs returned by the API.

```sh
curl -i -X POST http://localhost:8080/v1/jobs \
  -H 'Content-Type: application/json' \
  -d '{"jobName":"example job","metadata":{"team":"ops"},"targetUrl":"http://localhost:9000/webhook"}'

curl -i http://localhost:8080/v1/jobs/JOB_UUID

curl -i -X PUT http://localhost:8080/v1/jobs/JOB_UUID \
  -H 'Content-Type: application/json' \
  -d '{"jobName":"updated job","metadata":{"team":"ops"},"targetUrl":"http://localhost:9000/webhook"}'

curl -i -X POST http://localhost:8080/v1/executions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-execution-001' \
  -d '{"jobId":"JOB_UUID","payload":{"message":"hello"},"maxAttempts":3}'

curl -i http://localhost:8080/v1/executions/EXECUTION_UUID
```

## Worker Configuration and Delivery

Configure these Spring properties or command-line arguments, for example `--jobs.worker.concurrency=8`. All time values are milliseconds.

| Property | Default | Description |
| --- | --- | --- |
| `jobs.worker.enabled` | `true` | Whether to start the worker |
| `jobs.worker.id` | `worker-` plus a random UUID | Worker identifier; nonblank, no NUL, at most 255 characters |
| `jobs.worker.concurrency` | `4` | Maximum simultaneous executions per process; at least 1 |
| `jobs.worker.poll-interval-ms` | `1000` | Polling interval; at least 1 |
| `jobs.worker.lease-ms` | `30000` | Lease duration; at least 2 |
| `jobs.worker.heartbeat-interval-ms` | `10000` | Lease renewal interval; at least 1 and less than lease duration |
| `jobs.worker.retry-delay-ms` | `1000` | Initial retry delay after failure; at least 1 |
| `jobs.worker.max-retry-delay-ms` | `300000` | Retry-delay maximum; not less than the initial delay |
| `jobs.worker.http-timeout-ms` | `600000` | HTTP connection and request timeout; at least 1 |

When claiming an execution, the worker increments its attempt count and fencing token, periodically renews its lease, and sends a POST request to the target URL read at claim time. The request body is the execution `payload`. Headers include `Content-Type: application/json`, `Idempotency-Key` set to the execution's `externalIdempotencyKey`, and incrementing `X-Fencing-Token`. Updating a job URL affects future claims, not already claimed work.

Only HTTP 2xx responses are successful. Non-2xx responses, connection failures, and timeouts fail an attempt. Before `maxAttempts` is reached, retries use exponential backoff: the initial delay follows the first failure, then doubles until the configured maximum. There is no jitter. Actual execution timing also depends on polling and available concurrency. The worker marks an execution `FAILED` after its final attempt. An interrupted worker or lost lease leaves the remote outcome unknown; the execution can be reclaimed when the lease expires. An expired final-attempt lease is marked failed.

Delivery is **at least once, not exactly once**. Timeouts, process failures, lease-renewal failures, and result-write failures can cause a remote endpoint to process a request more than once or overlap requests. The API idempotency key prevents duplicate execution creation only; it does not guarantee exactly-once remote side effects. Recipients must persistently deduplicate using `Idempotency-Key` and must independently enforce fencing tokens when needed.

## Security and Scaling Limitations

- **No authentication or authorization:** anyone who can reach the API can create and modify jobs and trigger requests. Use only on a trusted network until authentication, authorization, and network isolation are in place.
- **SSRF risk:** URL validation checks only format. It does not block internal, loopback, or cloud metadata addresses and does not use an allowlist or DNS safety checks. Do not allow untrusted users to choose targets. Enforce strict target restrictions in both the application and network egress controls for production.
- In Citus mode, `job_definitions` and `job_executions` are reference tables replicated to nodes, not execution-sharded tables. Writes, storage, and global polling can become scaling bottlenecks. Validate migration, locking, claim-query compatibility, and performance for the target Citus version and topology.
- The API currently supports only create, update, and lookup by ID. It has no list, delete, cancellation, manual-retry, scheduled-time, or recurring-job endpoints. Database and downstream-service availability, data retention, and monitoring remain operational responsibilities.
