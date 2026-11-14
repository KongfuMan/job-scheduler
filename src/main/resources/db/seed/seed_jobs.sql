-- Development seed data. Run manually against a disposable database.
INSERT INTO job_definitions (job_id, job_name, metadata, target_url, created_at, created_by)
VALUES
    ('c4e94fb8-c912-4b40-9f74-4a6cd4a2a8e9', 'Send welcome email',
     '{"team":"notifications","environment":"development","priority":"high"}'::jsonb,
     'https://example.com/webhooks/welcome-email', '2026-01-01T09:00:00Z', 'seed-data'),
    ('f4c17afa-2cbb-4603-a8b7-fea1f07bf240', 'Generate daily report',
     '{"team":"analytics","environment":"development","report":"daily"}'::jsonb,
     'https://example.com/webhooks/daily-report', '2026-01-02T09:00:00Z', 'seed-data'),
    ('7e3b20e7-679f-492f-b142-78c7366dd796', 'Synchronize inventory',
     '{"team":"catalog","environment":"development","region":"us-east-1"}'::jsonb,
     'https://example.com/webhooks/inventory-sync', '2026-01-03T09:00:00Z', 'seed-data'),
    ('ad2df184-5b3e-406e-9eac-01dfb3e43054', 'Refresh customer cache',
     '{"team":"platform","environment":"development","cache":"customers"}'::jsonb,
     'https://example.com/webhooks/customer-cache', '2026-01-04T09:00:00Z', 'seed-data'),
    ('dd024042-699f-4fc7-a982-115b892567dc', 'Process billing export',
     '{"team":"billing","environment":"development","format":"csv"}'::jsonb,
     'https://example.com/webhooks/billing-export', '2026-01-05T09:00:00Z', 'seed-data')
ON CONFLICT (job_id) DO NOTHING;
