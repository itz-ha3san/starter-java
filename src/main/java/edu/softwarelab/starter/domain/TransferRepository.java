package edu.softwarelab.starter.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC-based repository for {@link Transfer} and {@link TransferEvent}.
 *
 * <p>Uses {@link JdbcTemplate} directly (no JPA/Hibernate). Snake-case columns
 * are mapped manually because the domain records use camelCase field names.
 * UUIDs are stored natively (PostgreSQL {@code uuid}); timestamps are stored as
 * {@code TIMESTAMPTZ} and read back as {@link java.time.OffsetDateTime}.
 *
 * <p>The {@code save} method uses the database-enforced unique idempotency key
 * and {@code INSERT ... ON CONFLICT (idempotency_key) DO NOTHING}. Concurrent
 * requests therefore converge on the same canonical transfer row.
 */
@Repository
public class TransferRepository {

    private static final Logger log = LoggerFactory.getLogger(TransferRepository.class);

    private static final String SQL_FIND_BY_ID =
            """
            SELECT id, source_account_number, dest_account_number, dest_iban,
                   source_account_id, dest_account_id, amount, currency, status,
                   is_internal, description, idempotency_key, correlation_id,
                   risk_decision, risk_score, external_reference,
                   request_hash, created_at, updated_at, version
              FROM transfers
             WHERE id = ?
            """;

    private static final String SQL_FIND_BY_IDEMPOTENCY_KEY =
            """
            SELECT id, source_account_number, dest_account_number, dest_iban,
                   source_account_id, dest_account_id, amount, currency, status,
                   is_internal, description, idempotency_key, correlation_id,
                   risk_decision, risk_score, external_reference,
                   request_hash, created_at, updated_at, version
              FROM transfers
             WHERE idempotency_key = ?
            """;

    private static final String SQL_INSERT_TRANSFER =
            """
            INSERT INTO transfers (
                id, source_account_number, dest_account_number, dest_iban,
                source_account_id, dest_account_id, amount, currency, status,
                is_internal, description, idempotency_key, correlation_id,
                risk_decision, risk_score, external_reference, request_hash, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

    private static final String SQL_UPDATE_STATUS =
            """
            UPDATE transfers
               SET status = ?
             WHERE id = ?
               AND status NOT IN ('SETTLED','FAILED','REVERSED','RISK_REJECTED')
               AND version = ?
            """;

    private static final String SQL_UPDATE_RISK =
            """
            UPDATE transfers
               SET risk_decision = ?, risk_score = ?, status = ?, updated_at = now()
             WHERE id = ?
               AND status = 'RISK_CHECKING'
               AND version = ?
            """;

    private static final String SQL_UPDATE_EXTERNAL_REFERENCE =
            """
            UPDATE transfers
               SET external_reference = COALESCE(?, external_reference),
                   status = ?, updated_at = now()
             WHERE id = ?
               AND status IN ('HOLDING','EXECUTING')
               AND version = ?
            """;

    private static final String SQL_EXISTS_BY_IDEMPOTENCY_KEY =
            "SELECT COUNT(*) FROM transfers WHERE idempotency_key = ?";

    private static final String SQL_INSERT_EVENT =
            """
            INSERT INTO transfer_events (id, transfer_id, event_type, payload, occurred_at)
            VALUES (?, ?, ?, ?::jsonb, ?)
            """;

    private static final String SQL_COUNT_EVENTS =
            "SELECT COUNT(*) FROM transfer_events WHERE transfer_id = ?";

    private static final String SQL_FIND_EVENTS_BY_ID_CURSOR =
            """
            SELECT id, transfer_id, event_type, payload::text, occurred_at
              FROM transfer_events
             WHERE transfer_id = ?
               AND (
                    ?::text IS NULL
                    OR (occurred_at, id) < (?::timestamptz, ?::uuid)
               )
             ORDER BY occurred_at DESC, id DESC
             LIMIT ?
            """;

    private static final RowMapper<Transfer> TRANSFER_ROW_MAPPER = TransferRepository::mapRow;

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ----------------------------------------------------------------- */

    public Optional<Transfer> findById(UUID id) {
        return jdbc.query(SQL_FIND_BY_ID, TRANSFER_ROW_MAPPER, id).stream().findFirst();
    }

    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query(SQL_FIND_BY_IDEMPOTENCY_KEY, TRANSFER_ROW_MAPPER, idempotencyKey)
                .stream()
                .findFirst();
    }

    public Optional<String> findResponseSnapshotByIdempotencyKey(String idempotencyKey) {
        return jdbc.query(
                "SELECT response_snapshot::text FROM idempotency_responses WHERE idempotency_key = ?",
                (rs, rowNum) -> rs.getString(1),
                idempotencyKey
        ).stream().findFirst();
    }

    @Transactional
    public void saveResponseSnapshot(String idempotencyKey, UUID transferId, String responseJson) {
        jdbc.update(
                """
                INSERT INTO idempotency_responses (idempotency_key, transfer_id, response_snapshot)
                VALUES (?, ?, ?::jsonb)
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
                idempotencyKey, transferId, responseJson
        );
    }

    public boolean existsByIdempotencyKey(String idempotencyKey) {
        Integer count = jdbc.queryForObject(SQL_EXISTS_BY_IDEMPOTENCY_KEY, Integer.class, idempotencyKey);
        return count != null && count > 0;
    }

    /**
     * Insert the transfer. Uses {@code ON CONFLICT DO NOTHING} so retries are
     * safe; the returned value is the canonical row read back from the DB.
     */
    @Transactional
    public Transfer save(Transfer t) {
        jdbc.update(
                SQL_INSERT_TRANSFER,
                t.id(),
                t.sourceAccountNumber(),
                t.destAccountNumber(),
                t.destIban(),
                t.sourceAccountId(),
                t.destAccountId(),
                t.amount(),
                t.currency(),
                t.status() == null ? null : t.status().name(),
                t.isInternal(),
                t.description(),
                t.idempotencyKey(),
                t.correlationId(),
                t.riskDecision(),
                t.riskScore(),
                t.externalReference(),
                t.requestHash(),
                t.createdAt(),
                t.updatedAt()
        );
        return findByIdempotencyKey(t.idempotencyKey()).orElseThrow(
                () -> new IllegalStateException("Transfer insert completed but row could not be reloaded"));
    }

    @Transactional
    public boolean updateStatus(UUID id, TransferStatus status, long expectedVersion) {
        int rows = jdbc.update(SQL_UPDATE_STATUS, status.name(), id, expectedVersion);
        log.debug("updateStatus id={} status={} rows={}", id, status, rows);
        return rows > 0;
    }

    @Transactional
    public boolean updateRiskAndStatus(UUID id,
                                       String riskDecision,
                                       Integer riskScore,
                                       TransferStatus newStatus,
                                       long expectedVersion) {
        int rows = jdbc.update(
                SQL_UPDATE_RISK,
                riskDecision,
                riskScore,
                newStatus.name(),
                id,
                expectedVersion
        );
        log.debug("updateRiskAndStatus id={} decision={} score={} status={} rows={}",
                id, riskDecision, riskScore, newStatus, rows);
        return rows > 0;
    }

    @Transactional
    public boolean updateExternalReferenceAndStatus(UUID id,
                                                    String externalReference,
                                                    TransferStatus newStatus,
                                                    long expectedVersion) {
        int rows = jdbc.update(
                SQL_UPDATE_EXTERNAL_REFERENCE,
                externalReference,
                newStatus.name(),
                id,
                expectedVersion
        );
        log.debug("updateExternalReferenceAndStatus id={} extRef={} status={} rows={}",
                id, externalReference, newStatus, rows);
        return rows > 0;
    }

    /**
     * Registers a Kafka message in the transactional inbox.
     *
     * @return true only for the first delivery; false means it was already
     *         committed by an earlier delivery and must not be applied again.
     */
    @Transactional
    public boolean registerProcessedMessage(String consumerName, UUID messageId, UUID transferId) {
        int inserted = jdbc.update(
                """
                INSERT INTO processed_messages (consumer_name, message_id, transfer_id)
                VALUES (?, ?, ?)
                ON CONFLICT (consumer_name, message_id) DO NOTHING
                """,
                consumerName, messageId, transferId
        );
        if (inserted == 1) {
            return true;
        }
        UUID originalTransferId = jdbc.queryForObject(
                "SELECT transfer_id FROM processed_messages WHERE consumer_name = ? AND message_id = ?",
                UUID.class,
                consumerName,
                messageId
        );
        if (!transferId.equals(originalTransferId)) {
            throw new IllegalStateException(
                    "Kafka message_id was reused for a different transfer: " + messageId);
        }
        return false;
    }

    @Transactional
    public void saveEvent(TransferEvent event) {
        jdbc.update(
                SQL_INSERT_EVENT,
                event.id(),
                event.transferId(),
                event.eventType() == null ? null : event.eventType().name(),
                event.payload(),
                event.occurredAt()
        );
    }

    /**
     * Returns a page of events for a transfer, ordered by {@code occurred_at}
     * descending. The opaque cursor is {@code ISO-8601 timestamp|event UUID};
     * pass {@code null} for the first page.
     */
    public List<TransferEvent> findEvents(UUID transferId, String cursorIso, int limit) {
        return jdbc.query(
                SQL_FIND_EVENTS_BY_ID_CURSOR,
                (rs, rowNum) -> mapEventRow(rs),
                transferId,
                cursorIso,
                cursorIso == null ? null : cursorIso.substring(0, cursorIso.lastIndexOf('|')),
                cursorIso == null ? null : UUID.fromString(cursorIso.substring(cursorIso.lastIndexOf('|') + 1)),
                limit
        );
    }

    public int countEvents(UUID transferId) {
        Integer count = jdbc.queryForObject(SQL_COUNT_EVENTS, Integer.class, transferId);
        return count == null ? 0 : count;
    }

    /* ----------------------------------------------------------------- */

    private static Transfer mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Transfer(
                rs.getObject("id", UUID.class),
                rs.getString("source_account_number"),
                rs.getString("dest_account_number"),
                rs.getString("dest_iban"),
                rs.getObject("source_account_id", UUID.class),
                rs.getObject("dest_account_id", UUID.class),
                rs.getLong("amount"),
                rs.getString("currency"),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getBoolean("is_internal"),
                rs.getString("description"),
                rs.getString("idempotency_key"),
                rs.getObject("correlation_id", UUID.class),
                rs.getString("request_hash"),
                rs.getString("risk_decision"),
                rs.getObject("risk_score", Integer.class),
                rs.getString("external_reference"),
                toOffsetDateTime(rs, "created_at"),
                toOffsetDateTime(rs, "updated_at"),
                rs.getLong("version")
        );
    }

    private static TransferEvent mapEventRow(ResultSet rs) throws SQLException {
        return new TransferEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("transfer_id", UUID.class),
                TransferEventType.valueOf(rs.getString("event_type")),
                rs.getString("payload"),
                toOffsetDateTime(rs, "occurred_at")
        );
    }

    /**
     * Read a {@code TIMESTAMPTZ} column as {@link java.time.OffsetDateTime}.
     * PostgreSQL JDBC returns {@link java.sql.Timestamp} from
     * {@link ResultSet#getTimestamp(String)}; we convert via the JVM default
     * zone to UTC. Prefer {@code rs.getObject(col, OffsetDateTime.class)} when
     * the driver supports it (it does for PG), with a fallback.
     */
    private static OffsetDateTime toOffsetDateTime(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        // Last-resort fallback for unexpected driver behavior.
        return rs.getTimestamp(column).toInstant().atOffset(ZoneOffset.UTC);
    }
}
