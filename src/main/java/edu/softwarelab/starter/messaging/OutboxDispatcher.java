package edu.softwarelab.starter.messaging;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.softwarelab.starter.domain.OutboxMessage;
import edu.softwarelab.starter.domain.OutboxRepository;

/**
 * Publishes committed outbox records to Kafka. Delivery is at-least-once:
 * a crash after Kafka accepts a record but before the DB marks it published
 * can cause a resend. The stable message_id and consumer inbox make that safe.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, MessageEnvelope> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public OutboxDispatcher(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, MessageEnvelope> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${s3.outbox.batch-size:25}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
    }

    @Scheduled(fixedDelayString = "${s3.outbox.poll-interval-ms:500}")
    public void dispatch() {
        List<OutboxMessage> batch = outboxRepository.claimBatch(batchSize);
        for (OutboxMessage item : batch) {
            try {
                MessageEnvelope envelope =
                        objectMapper.readValue(item.payload(), MessageEnvelope.class);
                kafkaTemplate.send(item.topic(), item.messageKey(), envelope)
                        .get(15, TimeUnit.SECONDS);
                outboxRepository.markPublished(item.id());
                log.info("Outbox message published messageId={} topic={} attempt={}",
                        item.id(), item.topic(), item.attempts());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                outboxRepository.markFailed(item.id(), ex.toString());
                log.error("Outbox publish interrupted messageId={} topic={} attempt={}",
                        item.id(), item.topic(), item.attempts(), ex);
                return;
            } catch (Exception ex) {
                outboxRepository.markFailed(item.id(), ex.toString());
                log.error("Outbox publish failed messageId={} topic={} attempt={}",
                        item.id(), item.topic(), item.attempts(), ex);
            }
        }
    }
}
