package edu.softwarelab.starter.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain representation of a row in the {@code transfer_events} append-only log.
 *
 * @param id         primary key (UUID)
 * @param transferId foreign key to {@code transfers.id}
 * @param eventType  discriminating enum
 * @param payload    JSON-serialized event-specific data; stored as PostgreSQL {@code JSONB}
 * @param occurredAt event time
 */
public record TransferEvent(
        UUID id,
        UUID transferId,
        TransferEventType eventType,
        String payload,
        OffsetDateTime occurredAt
) {
}
