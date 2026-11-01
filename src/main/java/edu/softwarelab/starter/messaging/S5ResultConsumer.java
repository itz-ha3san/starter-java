package edu.softwarelab.starter.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.softwarelab.starter.domain.TransferEvent;
import edu.softwarelab.starter.domain.TransferEventType;
import edu.softwarelab.starter.domain.TransferRepository;
import edu.softwarelab.starter.domain.TransferStatus;
import edu.softwarelab.starter.messaging.dto.ExternalTransferResultData;

/**
 * Consumes {@code ExecuteExternalTransferResult.v3} replies from S5 on the
 * {@code lab.s5.s3.event.operation-result.v3} topic.
 *
 * <p>State-machine mapping (kept intentionally simple for this session):
 * <ul>
 *   <li>outcome=APPLIED, status=SETTLED    → transfer status=SETTLED, save TRANSFER_SETTLED event.</li>
 *   <li>outcome=APPLIED, status=ACCEPTED|PROCESSING → transfer status=EXECUTING (still in flight), save TRANSFER_EXECUTED event.</li>
 *   <li>outcome=APPLIED, status=FAILED|UNKNOWN     → transfer status=FAILED, save TRANSFER_FAILED event.</li>
 *   <li>outcome=REJECTED                            → transfer status=FAILED, save TRANSFER_FAILED event.</li>
 *   <li>outcome=UNKNOWN                             → log and ack; leave state alone.</li>
 * </ul>
 *
 * <p>The external_reference returned by S5 is persisted on the transfer row.
 */
@Component
public class S5ResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(S5ResultConsumer.class);

    public static final String TOPIC = "lab.s5.s3.event.operation-result.v3";
    public static final String GROUP_ID = "s3-payment-service";

    private final TransferRepository transferRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public S5ResultConsumer(TransferRepository transferRepository,
                            ObjectMapper objectMapper) {
        this.transferRepository = transferRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onExternalTransferResult(MessageEnvelope envelope, Acknowledgment ack) {
        UUID txnId = envelope.getTransactionId();
        log.info("S5 result received txn={} op={} outcome={} messageType={}",
                txnId, envelope.getOperationId(), envelope.getOutcome(), envelope.getMessageType());

        handle(envelope);
        if (ack != null) {
            ack.acknowledge();
        }
    }

    private void handle(MessageEnvelope envelope) {
        UUID txnId = envelope.getTransactionId();
        if (txnId == null) {
            log.warn("S5 result without transaction_id — ignoring. envelope={}", envelope);
            return;
        }

        var transfer = transferRepository.findById(txnId).orElse(null);
        if (transfer == null) {
            log.warn("S5 result for unknown transfer txn={} — ignoring.", txnId);
            return;
        }
        if (envelope.getMessageId() == null) {
            throw new IllegalArgumentException("S5 result message_id is required");
        }
        if (!transferRepository.registerProcessedMessage("S5", envelope.getMessageId(), txnId)) {
            log.info("Duplicate S5 result ignored txn={} messageId={}", txnId, envelope.getMessageId());
            return;
        }

        String outcome = envelope.getOutcome();
        if ("APPLIED".equals(outcome)) {
            ExternalTransferResultData result =
                    convertData(envelope.getData(), ExternalTransferResultData.class);
            if (result == null) {
                throw new IllegalArgumentException("S5 APPLIED result requires a valid data payload");
            }
            String s5Status = result.status();
            log.info("S5 external transfer txn={} extRef={} status={} updatedAt={}",
                    txnId, result.externalReference(), s5Status, result.updatedAt());

            TransferStatus nextStatus;
            TransferEventType eventType;
            switch (s5Status == null ? "" : s5Status) {
                case "SETTLED" -> {
                    nextStatus = TransferStatus.SETTLED;
                    eventType = TransferEventType.TRANSFER_SETTLED;
                }
                case "ACCEPTED", "PROCESSING" -> {
                    nextStatus = TransferStatus.EXECUTING;
                    eventType = TransferEventType.TRANSFER_EXECUTED;
                }
                case "FAILED", "UNKNOWN" -> {
                    nextStatus = TransferStatus.FAILED;
                    eventType = TransferEventType.TRANSFER_FAILED;
                }
                default -> throw new IllegalArgumentException(
                        "Unsupported S5 external transfer status: " + s5Status);
            }

            boolean updated = transferRepository.updateExternalReferenceAndStatus(
                    txnId,
                    result.externalReference(),
                    nextStatus,
                    transfer.version()
            );
            if (!updated) {
                log.warn("Ignoring stale S5 result txn={} currentStatus={}", txnId, transfer.status());
                return;
            }
            recordEvent(txnId, eventType, envelope, result);
        } else if ("REJECTED".equals(outcome)) {
            if (envelope.getError() == null) {
                throw new IllegalArgumentException("S5 REJECTED result requires error payload");
            }
            log.warn("S5 REJECTED outcome txn={} error={}", txnId, envelope.getError());
            if (transferRepository.updateStatus(txnId, TransferStatus.FAILED, transfer.version())) {
                recordEvent(txnId, TransferEventType.TRANSFER_FAILED, envelope, envelope.getError());
            }
        } else {
            log.warn("S5 UNKNOWN outcome txn={} — leaving state unchanged.", txnId);
        }
    }

    private void recordEvent(UUID txnId,
                             TransferEventType type,
                             MessageEnvelope envelope,
                             Object payload) {
        try {
            Map<String, Object> auditPayload = new java.util.LinkedHashMap<>();
            auditPayload.put("message_id", envelope.getMessageId());
            auditPayload.put("operation_id", envelope.getOperationId());
            auditPayload.put("correlation_id", envelope.getCorrelationId());
            auditPayload.put("producer", envelope.getProducer());
            auditPayload.put("message_type", envelope.getMessageType());
            auditPayload.put("outcome", envelope.getOutcome());
            auditPayload.put("result", payload);
            String json = objectMapper.writeValueAsString(auditPayload);
            transferRepository.saveEvent(new TransferEvent(
                    UUID.randomUUID(),
                    txnId,
                    type,
                    json,
                    OffsetDateTime.now()
            ));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to persist " + type + " event for transfer=" + txnId, e);
        }
    }

    private <T> T convertData(Object data, Class<T> type) {
        if (data == null) {
            throw new IllegalArgumentException("S5 result data payload is required");
        }
        if (type.isInstance(data)) {
            return type.cast(data);
        }
        try {
            return objectMapper.convertValue(data, type);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Could not convert S5 data payload to " + type.getSimpleName(), ex);
        }
    }
}
