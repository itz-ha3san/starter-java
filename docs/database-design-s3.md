# S3-PAY database model

## Ownership boundary

S3 is the payment-orchestration service. `transfers` stores the transfer request
and Saga lifecycle; it is not a ledger. S2 remains the system of record for
balances, holds, captures, releases and double-entry postings. S3 stores only
references returned by neighboring services.

## Tables

- `transfers`: one row per accepted transfer. Request intent and idempotency
  identity are immutable. Only lifecycle/risk/settlement-result fields can change.
- `transfer_events`: append-only audit timeline. PostgreSQL rejects UPDATE and
  DELETE. Events use a stable ordering `(occurred_at, id)` for cursor pagination.
- `outbox_messages`: outbound Kafka commands committed in the same DB transaction
  as the transfer state and event. Dispatcher delivery is at-least-once.
- `processed_messages`: consumer inbox keyed by `(consumer_name, message_id)`.
  It makes duplicate S4/S5 result deliveries harmless when DB commit succeeds but
  Kafka offset acknowledgement is repeated.
- `idempotency_responses`: immutable create-response snapshot. It allows GEN-07
  to return the original POST response even after the live transfer advances to a
  later Saga state.
- `idempotency_keys`: legacy table from the earlier schema, if present; it is
  not used by the current implementation. Canonical idempotency identity is
  `transfers.idempotency_key` plus `request_hash`.

## Integrity and concurrency

- Unique index on `transfers.idempotency_key` closes the concurrent-create race.
- `request_hash` is SHA-256 over a canonical, length-prefixed representation of
  the normalized request. Same key + same body returns the existing transfer;
  same key + different body returns HTTP 409 `IDEMPOTENCY_KEY_REUSED`. The original
  HTTP response snapshot is saved in the same transaction and replayed exactly.
- CHECK constraints enforce positive integer IRR amount, exactly one destination,
  destination/type consistency, supported states, and risk-score bounds.
- A database trigger increments `version` and `updated_at` on every update.
- A database trigger prevents changing request identity/financial intent, deleting
  transfers, or applying an illegal lifecycle transition.
- Transfer row, initial event, risk-request event, and S4 outbox message are
  committed atomically. A process crash cannot leave a committed transfer with
  no durable S4 command.
- Kafka outbox publishing is at-least-once. The stable message ID and consumer
  inbox prevent duplicate state mutation/event insertion on redelivery.

## Migrations

Flyway is the only schema owner. New databases run
`src/main/resources/db/migration/V1__harden_s3_transfer_model.sql`.
Existing non-empty databases without Flyway history are baselined at version 0,
then V1 adds constraints/indexes/triggers and the outbox/inbox tables. Existing
data is not deleted. If pre-existing duplicate idempotency keys or external
references exist, the unique-index migration fails deliberately; reconcile the
duplicates rather than silently discarding transactions.

`data.sql` is sample data only and is no longer run automatically at startup.

## Flyway on Spring Boot 4

This project uses Spring Boot 4.x. In Boot 4, declaring only `flyway-core` is not
sufficient to activate Spring Boot's Flyway auto-configuration. The build must include
`spring-boot-starter-flyway` and the database-specific `flyway-database-postgresql`
module. The application must fail startup if the migration fails; scheduled outbox
dispatch must never be used as a substitute for schema initialization.
