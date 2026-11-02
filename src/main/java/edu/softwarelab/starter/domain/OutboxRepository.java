package edu.softwarelab.starter.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL transactional outbox. Claiming uses SKIP LOCKED so multiple
 * application instances can dispatch concurrently without claiming the same
 * row. Expired PROCESSING leases are reclaimable after a process crash.
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void enqueue(UUID messageId, UUID aggregateId, String topic,
                        String messageKey, String payloadJson) {
        jdbc.update("""
                INSERT INTO outbox_messages
                    (id, aggregate_id, topic, message_key, payload, status)
                VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING')
                """, messageId, aggregateId, topic, messageKey, payloadJson);
    }

    @Transactional
    public List<OutboxMessage> claimBatch(int batchSize) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT id
                      FROM outbox_messages
                     WHERE (status = 'PENDING' AND available_at <= now())
                        OR (status = 'PROCESSING' AND locked_until < now())
                     ORDER BY created_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE outbox_messages o
                   SET status = 'PROCESSING',
                       attempts = o.attempts + 1,
                       locked_until = now() + interval '30 seconds'
                  FROM candidates c
                 WHERE o.id = c.id
                RETURNING o.id, o.topic, o.message_key, o.payload::text, o.attempts
                """, (rs, rowNum) -> new OutboxMessage(
                    rs.getObject("id", UUID.class),
                    rs.getString("topic"),
                    rs.getString("message_key"),
                    rs.getString("payload"),
                    rs.getInt("attempts")
                ), batchSize);
    }

    @Transactional
    public void markPublished(UUID id) {
        jdbc.update("""
                UPDATE outbox_messages
                   SET status = 'PUBLISHED',
                       published_at = now(),
                       locked_until = NULL,
                       last_error = NULL
                 WHERE id = ? AND status = 'PROCESSING'
                """, id);
    }

    @Transactional
    public void markFailed(UUID id, String error) {
        jdbc.update("""
                UPDATE outbox_messages
                   SET status = CASE WHEN attempts >= 12 THEN 'FAILED' ELSE 'PENDING' END,
                       available_at = now() + make_interval(secs =>
                           LEAST(300, power(2, LEAST(attempts, 8))::integer)),
                       locked_until = NULL,
                       last_error = left(?, 2000)
                 WHERE id = ? AND status = 'PROCESSING'
                """, error == null ? "Unknown Kafka publishing error" : error, id);
    }
}
