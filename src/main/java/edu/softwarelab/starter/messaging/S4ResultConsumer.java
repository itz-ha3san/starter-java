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

import edu.softwarelab.starter.domain.Transfer;
import edu.softwarelab.starter.domain.TransferEvent;
import edu.softwarelab.starter.domain.TransferEventType;
import edu.softwarelab.starter.domain.TransferRepository;
import edu.softwarelab.starter.domain.TransferStatus;
import edu.softwarelab.starter.messaging.dto.AssessmentResultData;

/**
 * Consumes {@code AssessmentResult.v1} replies from S4 on the
 * {@code lab.s4.s3.event.operation-result.v1} topic.
 *
 * <p>The consumer group id is {@code s3-payment-service}; offsets are committed
 * manually ({@code ack-mode=manual}) via {@link Acknowledgment} so
 * that a crash mid-processing does not lose a result.
 *
 * <p>State-machine mapping (kept intentionally simple for this session):
 * <ul>
 *   <li>outcome=APPLIED, decision=APPROVED       → status=HOLDING, save RISK_ASSESSED event.</li>
 *   <li>outcome=APPLIED, decision=REJECTED        → status=RISK_REJECTED, save RISK_ASSESSED event.</li>
 *   <li>outcome=APPLIED, decision=MANUAL_REVIEW   → status=RISK_MANUAL_REVIEW, save RISK_ASSESSED event.</li>
 *   <li>outcome=REJECTED                          → log the error, keep current status, save TRANSFER_FAILED event.</li>
 *   <li>outcome=UNKNOWN                           → log and ack; leave state alone (timeout path will handle).</li>
 * </ul>
 *
 * <p>The downstream HOLDING → EXECUTING transition (S2 hold, then S5 execute)
 * is intentionally out of scope for this session — only the S3↔S4 and S3↔S5
 * rings are wired here.
 */
@Component
public class S4ResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(S4ResultConsumer.class);

    public static final String TOPIC = "lab.s4.s3.event.operation-result.v1";
    public static final String GROUP_ID = "s3-payment-service";

    private final TransferRepository transferRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public S4ResultConsumer(TransferRepository transferRepository,
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
    public void onAssessmentResult(MessageEnvelope envelope, Acknowledgment ack) {
        UUID txnId = envelope.getTransactionId();
        log.info("S4 result received txn={} op={} outcome={} messageType={}",
                txnId, envelope.getOperationId(), envelope.getOutcome(), envelope.getMessageType());

        handle(envelope);
        // MANUAL ack mode defers offset commit until the listener returns.
        // The DB transaction commits before that return, preventing message loss.
        if (ack != null) {
            ack.acknowledge();
        }
    }

    private void handle(MessageEnvelope envelope) {
        UUID txnId = envelope.getTransactionId();
        if (txnId == null) {
            log.warn("S4 result without transaction_id — ignoring. envelope={}", envelope);
            return;
        }

        Transfer transfer = transferRepository.findById(txnId).orElse(null);
        if (transfer == null) {
            log.warn("S4 result for unknown transfer txn={} — ignoring.", txnId);
            return;
        }
        if (envelope.getMessageId() == null) {
            throw new IllegalArgumentException("S4 result message_id is required");
        }
        if (!transferRepository.registerProcessedMessage("S4", envelope.getMessageId(), txnId)) {
            log.info("Duplicate S4 result ignored txn={} messageId={}", txnId, envelope.getMessageId());
            return;
        }

        String outcome = envelope.getOutcome();
        if ("APPLIED".equals(outcome)) {
            AssessmentResultData result = convertData(envelope.getData(), AssessmentResultData.class);
            if (result == null) {
                throw new IllegalArgumentException("S4 APPLIED result requires a valid data payload");
            }
            String decision = result.decision();
            if (decision == null || !java.util.Set.of("APPROVED", "REJECTED", "MANUAL_REVIEW").contains(decision)) {
                throw new IllegalArgumentException("Unsupported S4 risk decision: " + decision);
            }
            if (result.riskScore() == null || result.riskScore() < 0 || result.riskScore() > 100) {
                throw new IllegalArgumentException("S4 risk_score is required and must be between 0 and 100");
            }
            log.info("S4 risk decision txn={} decision={} score={} rules={}",
                    txnId, decision, result.riskScore(), result.rulesEvaluated());

            TransferStatus nextStatus = switch (decision) {
                case "APPROVED" -> TransferStatus.HOLDING;
                case "REJECTED" -> TransferStatus.RISK_REJECTED;
                case "MANUAL_REVIEW" -> TransferStatus.RISK_MANUAL_REVIEW;
                default -> throw new IllegalArgumentException("Unsupported S4 risk decision: " + decision);
            };

            boolean updated = transferRepository.updateRiskAndStatus(
                    txnId,
                    decision,
                    result.riskScore(),
                    nextStatus,
                    transfer.version()
            );
            if (!updated) {
                log.warn("Ignoring stale S4 result txn={} currentStatus={}", txnId, transfer.status());
                return;
            }
            recordEvent(txnId, TransferEventType.RISK_ASSESSED, envelope, result);
        } else if ("REJECTED".equals(outcome)) {
            if (envelope.getError() == null) {
                throw new IllegalArgumentException("S4 REJECTED result requires error payload");
            }
            log.warn("S4 REJECTED outcome txn={} error={}", txnId, envelope.getError());
            if (transferRepository.updateStatus(txnId, TransferStatus.FAILED, transfer.version())) {
                recordEvent(txnId, TransferEventType.TRANSFER_FAILED, envelope, envelope.getError());
            }
        } else {
            // UNKNOWN — S3 must time out and treat as MANUAL_REVIEW (handled elsewhere).
            log.warn("S4 UNKNOWN outcome txn={} — leaving status unchanged.", txnId);
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
            throw new IllegalArgumentException("S4 result data payload is required");
        }
        if (type.isInstance(data)) {
            return type.cast(data);
        }
        try {
            return objectMapper.convertValue(data, type);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Could not convert S4 data payload to " + type.getSimpleName(), ex);
        }
    }
}
