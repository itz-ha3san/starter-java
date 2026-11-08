package edu.softwarelab.starter.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import edu.softwarelab.starter.messaging.dto.ExecuteExternalTransferData;

/**
 * Produces {@code ExecuteExternalTransfer.v3} commands on the
 * {@code lab.s3.s5.command.transfer.external.v3} topic.
 *
 * <p>Per the AsyncAPI contract the Kafka record key is the UTF-8 string form of
 * the {@code transaction_id}. The envelope is built with:
 * <ul>
 *   <li>{@code producer} = {@code "S3-PAY"}</li>
 *   <li>{@code message_type} = {@code "ExecuteExternalTransfer.v3"}</li>
 *   <li>{@code schema_version} = {@code "3.0"}</li>
 *   <li>{@code occurred_at} = current UTC time</li>
 *   <li>{@code operation_id} = a fresh UUID per logical command (caller-supplied)</li>
 * </ul>
 */
@Component
public class S5ExternalTransferProducer {

    private static final Logger log = LoggerFactory.getLogger(S5ExternalTransferProducer.class);

    /** AsyncAPI contract: S3 → S5 external-transfer command topic. */
    public static final String TOPIC = "lab.s3.s5.command.transfer.external.v3";

    public static final String PRODUCER = "S3-PAY";
    public static final String MESSAGE_TYPE = "ExecuteExternalTransfer.v3";
    public static final String SCHEMA_VERSION = "3.0";

    private final KafkaTemplate<String, MessageEnvelope> kafkaTemplate;

    @Autowired
    public S5ExternalTransferProducer(KafkaTemplate<String, MessageEnvelope> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Send an ExecuteExternalTransfer command.
     *
     * @param transactionId the S3 transfer id (also the Kafka record key)
     * @param correlationId propagated correlation id
     * @param operationId   logical command id (idempotency key for S5 dedup)
     * @param data          typed payload
     */
    public void sendExecuteExternalTransfer(UUID transactionId,
                                            UUID correlationId,
                                            UUID operationId,
                                            ExecuteExternalTransferData data) {

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
                        log.error("ExecuteExternalTransfer SEND FAILED txn={} op={} topic={}",
                                transactionId, operationId, TOPIC, ex);
                    } else if (result != null && result.getRecordMetadata() != null) {
                        log.info("ExecuteExternalTransfer sent txn={} op={} topic={} partition={} offset={}",
                                transactionId, operationId, TOPIC,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.info("ExecuteExternalTransfer sent txn={} op={} topic={}",
                                transactionId, operationId, TOPIC);
                    }
                });
    }
}
