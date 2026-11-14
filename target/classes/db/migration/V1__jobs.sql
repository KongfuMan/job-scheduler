CREATE TABLE job_definitions (
    job_id uuid PRIMARY KEY,
    target_url text NOT NULL,
    job_name text NOT NULL DEFAULT 'unknown',
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by text NOT NULL DEFAULT 'unknown',
    CONSTRAINT job_target_nonblank CHECK (target_url ~ '[^[:space:]]' AND char_length(target_url) <= 2048),
    CONSTRAINT job_name_nonblank CHECK (job_name ~ '[^[:space:]]'),
    CONSTRAINT job_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT job_creator_nonblank CHECK (created_by ~ '[^[:space:]]')
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'citus') THEN
        PERFORM create_reference_table('job_definitions');
    END IF;
END $$;

CREATE TABLE job_executions (
    execution_id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES job_definitions(job_id),
    payload jsonb NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts integer NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    next_retry_at timestamptz DEFAULT now(),
    lease_until timestamptz,
    worker_id varchar(255),
    fencing_token bigint NOT NULL DEFAULT 0,
    client_idempotency_key varchar(255) NOT NULL UNIQUE,
    external_idempotency_key varchar(255) NOT NULL UNIQUE,
    last_error text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by text NOT NULL DEFAULT 'unknown',
    CONSTRAINT execution_payload_nonnull CHECK (payload <> 'null'::jsonb),
    CONSTRAINT execution_status CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT execution_attempt_bounds CHECK (attempts <= max_attempts),
    CONSTRAINT execution_attempt_state CHECK (
        (status = 'PENDING' AND attempts = 0) OR
        (status = 'RETRY' AND attempts > 0 AND attempts < max_attempts) OR
        (status IN ('RUNNING', 'SUCCEEDED', 'FAILED') AND attempts > 0)
    ),
    CONSTRAINT execution_lease_state CHECK (
        (status = 'RUNNING' AND lease_until IS NOT NULL AND worker_id IS NOT NULL AND worker_id ~ '[^[:space:]]') OR
        (status <> 'RUNNING' AND lease_until IS NULL AND worker_id IS NULL)
    ),
    CONSTRAINT execution_retry_state CHECK (
        (status IN ('PENDING', 'RETRY') AND next_retry_at IS NOT NULL) OR
        (status IN ('RUNNING', 'SUCCEEDED', 'FAILED') AND next_retry_at IS NULL)
    ),
    CONSTRAINT execution_fencing_token CHECK (fencing_token >= 0 AND (status <> 'RUNNING' OR fencing_token > 0)),
    CONSTRAINT execution_client_key CHECK (client_idempotency_key ~ '[^[:space:]]' AND client_idempotency_key !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT execution_external_key CHECK (external_idempotency_key ~ '[^[:space:]]' AND external_idempotency_key !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT execution_creator_nonblank CHECK (created_by ~ '[^[:space:]]')
);

CREATE INDEX job_executions_due_idx ON job_executions (status, next_retry_at);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'citus') THEN
        PERFORM create_reference_table('job_executions');
    END IF;
END $$;
