package edu.softwarelab.starter.domain;

import java.util.UUID;

/** A claimed transactional-outbox message ready for Kafka delivery. */
public record OutboxMessage(
        UUID id,
        String topic,
        String messageKey,
        String payload,
        int attempts
) {
}
