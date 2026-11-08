-- Read-only S3 database integrity checks.
-- Run: docker exec -i s3-db psql -U postgres -d paymentdb < scripts/verify-transfer-db.sql

\echo 'Transfer counts by status'
SELECT status, count(*) FROM transfers GROUP BY status ORDER BY status;

\echo 'Duplicate idempotency keys (must return zero rows)'
SELECT idempotency_key, count(*) FROM transfers
GROUP BY idempotency_key HAVING count(*) > 1;

\echo 'Transfers with invalid destinations (must return zero rows)'
SELECT id, is_internal, dest_account_number, dest_iban
FROM transfers
WHERE ((CASE WHEN dest_account_number IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN dest_iban IS NOT NULL THEN 1 ELSE 0 END)) <> 1
   OR (is_internal AND (dest_account_number IS NULL OR dest_iban IS NOT NULL))
   OR (NOT is_internal AND (dest_iban IS NULL OR dest_account_number IS NOT NULL));

\echo 'Orphan events (must return zero rows)'
SELECT e.id, e.transfer_id
FROM transfer_events e
LEFT JOIN transfers t ON t.id = e.transfer_id
WHERE t.id IS NULL;

\echo 'Outbox backlog'
SELECT status, count(*) FROM outbox_messages GROUP BY status ORDER BY status;

\echo 'Oldest pending/failed outbox rows'
SELECT id, aggregate_id, topic, status, attempts, available_at, last_error
FROM outbox_messages
WHERE status IN ('PENDING','FAILED')
ORDER BY created_at
LIMIT 50;

\echo 'Processed Kafka messages by consumer'
SELECT consumer_name, count(*) FROM processed_messages
GROUP BY consumer_name ORDER BY consumer_name;
