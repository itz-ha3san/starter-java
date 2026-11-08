package edu.softwarelab.starter.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import edu.softwarelab.starter.messaging.dto.AssessRiskData;

/**
 * Produces {@code AssessRisk.v1} commands on the
 * {@code lab.s3.s4.command.risk.assess.v1} topic.
 *
 * <p>Per the AsyncAPI contract the Kafka record key is the UTF-8 string form of
 * the {@code transaction_id}. The envelope is built with:
 * <ul>
 *   <li>{@code producer} = {@code "S3-PAY"}</li>
 *   <li>{@code message_type} = {@code "AssessRisk.v1"}</li>
 *   <li>{@code schema_version} = {@code "1.0"}</li>
 *   <li>{@code occurred_at} = current UTC time</li>
 *   <li>{@code operation_id} = a fresh UUID per logical command (caller-supplied)</li>
 * </ul>
 *
 * <p>Sending is fire-and-forget at the API level: the producer returns a
 * {@link org.springframework.kafka.core.KafkaTemplate.SendResult} future that
 * is logged on success and failure. The TransferService does not block the HTTP
 * request on the broker ack — the saga continues asynchronously when S4's
 * reply arrives on the result topic (consumed by {@link S4ResultConsumer}).
 */
@Component
public class S4RiskAssessmentProducer {

    private static final Logger log = LoggerFactory.getLogger(S4RiskAssessmentProducer.class);

    /** AsyncAPI contract: S3 → S4 risk-assessment command topic. */
    public static final String TOPIC = "lab.s3.s4.command.risk.assess.v1";

    public static final String PRODUCER = "S3-PAY";
    public static final String MESSAGE_TYPE = "AssessRisk.v1";
    public static final String SCHEMA_VERSION = "1.0";

    private final KafkaTemplate<String, MessageEnvelope> kafkaTemplate;

    @Autowired
    public S4RiskAssessmentProducer(KafkaTemplate<String, MessageEnvelope> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Send an AssessRisk command.
     *
     * @param transactionId the S3 transfer id (also the Kafka record key)
     * @param correlationId propagated correlation id
     * @param operationId   logical command id (idempotency key for S4 dedup)
     * @param data          typed payload
     */
    public void sendAssessRisk(UUID transactionId,
                               UUID correlationId,
                               UUID operationId,
                               AssessRiskData data) {

        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .schemaVersion(SCHEMA_VERSION)
                .transactionId(transactionId)
                .correlationId(correlationId)
                .occurredAt(OffsetDateTime.now())
                .producer(PRODUCER)
                .messageType(MESSAGE_TYPE)
                .operationId(operationId)
                .data(data)
                .build();

        String key = transactionId.toString();
        kafkaTemplate.send(TOPIC, key, envelope)
                .whenComplete((SendResult<String, MessageEnvelope> result, Throwable ex) -> {
                    if (ex != null) {
                        log.error("AssessRisk SEND FAILED txn={} op={} topic={}",
                                transactionId, operationId, TOPIC, ex);
                    } else if (result != null && result.getRecordMetadata() != null) {
                        log.info("AssessRisk sent txn={} op={} topic={} partition={} offset={}",
                                transactionId, operationId, TOPIC,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.info("AssessRisk sent txn={} op={} topic={}",
                                transactionId, operationId, TOPIC);
                    }
                });
    }
}
