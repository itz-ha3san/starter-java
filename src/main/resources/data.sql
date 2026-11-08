-- LabBank S3 Payment Service — seed data for local development and neighbor testing.
-- Run with: docker exec -i s3-db psql -U postgres -d paymentdb < src/main/resources/data.sql
-- OR Spring Boot runs it automatically on startup if spring.sql.init.mode=always.

-- Sample transfers that the neighbor (S5) or frontend can query against.
-- These use deterministic UUIDs so Bruno collection and tests can reference them.

INSERT INTO transfers (id, source_account_number, dest_account_number, dest_iban, source_account_id, dest_account_id, amount, currency, status, is_internal, description, idempotency_key, correlation_id, risk_decision, risk_score, external_reference, created_at, updated_at)
VALUES
-- 1) Internal transfer, settled (happy path demo)
('0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11', '001200456789', '001200987654', NULL,
 '515a888a-c8e9-4750-9621-273849409a44', '7e9a62d2-77b3-4bf8-a19c-de2c51effcab',
 2500000, 'IRR', 'SETTLED', true, 'Lunch split',
 'seed-internal-001', '431a21a4-e187-472a-857f-086540fcb780',
 'APPROVED', 12, NULL,
 '2026-11-01T08:00:00Z', '2026-11-01T08:00:05Z'),

-- 2) External transfer, settled (happy path with S5)
('0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c12', '001200456789', NULL, 'IR430120000000000000000001',
 '515a888a-c8e9-4750-9621-273849409a44', NULL,
 5000000, 'IRR', 'SETTLED', false, 'Rent payment',
 'seed-external-001', '431a21a4-e187-472a-857f-086540fcb781',
 'APPROVED', 8, 'S5-DEMO-REFERENCE-001',
 '2026-11-01T09:00:00Z', '2026-11-01T09:00:10Z'),

-- 3) Risk-rejected transfer (S4 said REJECTED)
('0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c13', '001200456789', NULL, 'IR430120000000000000000002',
 '515a888a-c8e9-4750-9621-273849409a44', NULL,
 99000000, 'IRR', 'RISK_REJECTED', false, 'Suspicious large transfer',
 'seed-rejected-001', '431a21a4-e187-472a-857f-086540fcb782',
 'REJECTED', 87, NULL,
 '2026-11-01T10:00:00Z', '2026-11-01T10:00:01Z'),

-- 4) Pending transfer (for GET status testing)
('0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c14', '001200456789', '001200987654', NULL,
 '515a888a-c8e9-4750-9621-273849409a44', '7e9a62d2-77b3-4bf8-a19c-de2c51effcab',
 100000, 'IRR', 'RISK_CHECKING', true, 'Small test transfer',
 'seed-pending-001', '431a21a4-e187-472a-857f-086540fcb783',
 NULL, NULL, NULL,
 '2026-11-01T11:00:00Z', '2026-11-01T11:00:00Z')
ON CONFLICT (id) DO NOTHING;

-- Events for the settled internal transfer
INSERT INTO transfer_events (id, transfer_id, event_type, payload, occurred_at) VALUES
('11111111-0000-4000-8000-000000000001', '0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11', 'CREATED',
 '{"source_account_number":"001200456789","dest_account_number":"001200987654","amount":2500000}'::jsonb,
 '2026-11-01T08:00:00Z'),
('11111111-0000-4000-8000-000000000002', '0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11', 'RISK_ASSESSED',
 '{"decision":"APPROVED","risk_score":12}'::jsonb,
 '2026-11-01T08:00:01Z'),
('11111111-0000-4000-8000-000000000003', '0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11', 'FUNDS_HELD',
 '{"amount":2500000}'::jsonb,
 '2026-11-01T08:00:02Z'),
('11111111-0000-4000-8000-000000000004', '0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11', 'TRANSFER_SETTLED',
 '{"external_reference":null}'::jsonb,
 '2026-11-01T08:00:05Z')
ON CONFLICT (id) DO NOTHING;
