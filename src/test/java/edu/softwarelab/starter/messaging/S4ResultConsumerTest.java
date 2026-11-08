package edu.softwarelab.starter.messaging;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.softwarelab.starter.domain.Transfer;
import edu.softwarelab.starter.domain.TransferRepository;
import edu.softwarelab.starter.domain.TransferStatus;
import edu.softwarelab.starter.messaging.dto.AssessmentResultData;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link S4ResultConsumer}. No Spring context is loaded —
 * the {@link TransferRepository} is mocked with Mockito and a real
 * {@link ObjectMapper} is used for the consumer's event-payload serialization.
 *
 * <p>Source-of-truth notes (read from S4ResultConsumer.java):
 * <ul>
 *   <li>The constructor signature is
 *       {@code S4ResultConsumer(TransferRepository, ObjectMapper)} — both
 *       collaborators are required.</li>
 *   <li>The Kafka listener method is
 *       {@code onAssessmentResult(MessageEnvelope envelope, Acknowledgment ack)}.
 *       {@code ack} may be {@code null} (the source null-checks it before
 *       calling {@code acknowledge()}).</li>
 *   <li>For {@code outcome=APPLIED} the source calls
 *       {@code transferRepository.updateRiskAndStatus(txnId, decision, riskScore, nextStatus)}
 *       — <em>not</em> {@code updateStatus}. The {@code updateStatus} path is
 *       only taken for {@code outcome=REJECTED} (→ {@code FAILED}).
 *       This test verifies the method the source actually calls.</li>
 *   <li>The handler first resolves the transfer via
 *       {@code transferRepository.findById(txnId)} and bails out if it is
 *       missing; the mock therefore stubs {@code findById} to return an
 *       {@code Optional.of(transfer)} so the state-machine transitions run.</li>
 * </ul>
 */
class S4ResultConsumerTest {

    private TransferRepository transferRepository;
    private S4ResultConsumer consumer;

    private UUID txnId;
    private Transfer existingTransfer;

    @BeforeEach
    void setUp() {
        transferRepository = mock(TransferRepository.class);
        // A real ObjectMapper is the simplest choice — the consumer uses it
        // only to (a) convertData when the payload is NOT already the right
        // type, and (b) serialize the event payload inside recordEvent.
        // We register the JSR-310 module so OffsetDateTime fields (present
        // on AssessmentResultData and MessageEnvelope) serialize cleanly,
        // mirroring the Spring-managed ObjectMapper used in production.
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        consumer = new S4ResultConsumer(transferRepository, objectMapper);

        txnId = UUID.randomUUID();
        existingTransfer = new Transfer(
                txnId,
                "1234567890",
                null,
                "IR123456789012345678901234",
                null,
                null,
                10_000L,
                "IRR",
                TransferStatus.RISK_CHECKING,
                false,
                "test transfer",
                "idem-key",
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                0L
        );
        // The handler resolves the transfer before doing anything; make it
        // appear to exist for every test by default.
        when(transferRepository.findById(txnId)).thenReturn(Optional.of(existingTransfer));
        when(transferRepository.registerProcessedMessage(
                eq("S4"), any(UUID.class), eq(txnId))).thenReturn(true);
        when(transferRepository.updateRiskAndStatus(any(), any(), any(), any(), anyLong())).thenReturn(true);
        when(transferRepository.updateStatus(any(), any(), anyLong())).thenReturn(true);
    }

    /* ------------------------------------------------------------------
     * Helpers
     * ------------------------------------------------------------------ */

    private MessageEnvelope buildAppliedEnvelope(AssessmentResultData data) {
        return MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .schemaVersion("1.0")
                .transactionId(txnId)
                .correlationId(UUID.randomUUID())
                .causationId(UUID.randomUUID())
                .occurredAt(OffsetDateTime.now())
                .producer("S4")
                .messageType("AssessmentResult.v1")
                .operationId(UUID.randomUUID())
                .outcome("APPLIED")
                .data(data)
                .build();
    }

    private AssessmentResultData result(String decision, int riskScore) {
        return new AssessmentResultData(
                decision,
                riskScore,
                List.of("RULE_A", "RULE_B"),
                OffsetDateTime.now()
        );
    }

    /* ------------------------------------------------------------------
     * Scenario 1: outcome=APPLIED, decision=APPROVED → HOLDING
     * ------------------------------------------------------------------ */

    @Test
    void onAssessmentResult_appliedApproved_transitionsToHolding() {
        AssessmentResultData data = result("APPROVED", 12);
        MessageEnvelope envelope = buildAppliedEnvelope(data);

        consumer.onAssessmentResult(envelope, null);

        verify(transferRepository).updateRiskAndStatus(
                eq(txnId), eq("APPROVED"), eq(12), eq(TransferStatus.HOLDING), eq(0L));
        // The APPLIED path must not use updateStatus — it uses updateRiskAndStatus.
        verify(transferRepository, never())
                .updateStatus(any(), any(), anyLong());
        // And an event should be recorded for the transition.
        verify(transferRepository).saveEvent(any());
    }

    /* ------------------------------------------------------------------
     * Scenario 2: outcome=APPLIED, decision=REJECTED → RISK_REJECTED
     * ------------------------------------------------------------------ */

    @Test
    void onAssessmentResult_appliedRejected_transitionsToRiskRejected() {
        AssessmentResultData data = result("REJECTED", 99);
        MessageEnvelope envelope = buildAppliedEnvelope(data);

        consumer.onAssessmentResult(envelope, null);

        verify(transferRepository).updateRiskAndStatus(
                eq(txnId), eq("REJECTED"), eq(99), eq(TransferStatus.RISK_REJECTED), eq(0L));
        verify(transferRepository, never())
                .updateStatus(any(), any(), anyLong());
        verify(transferRepository).saveEvent(any());
    }

    /* ------------------------------------------------------------------
     * Scenario 3: outcome=APPLIED, decision=MANUAL_REVIEW
     *             → RISK_MANUAL_REVIEW
     * ------------------------------------------------------------------ */

    @Test
    void onAssessmentResult_appliedManualReview_transitionsToManualReview() {
        AssessmentResultData data = result("MANUAL_REVIEW", 50);
        MessageEnvelope envelope = buildAppliedEnvelope(data);

        consumer.onAssessmentResult(envelope, null);

        verify(transferRepository).updateRiskAndStatus(
                eq(txnId), eq("MANUAL_REVIEW"), eq(50),
                eq(TransferStatus.RISK_MANUAL_REVIEW), eq(0L));
        verify(transferRepository, never())
                .updateStatus(any(), any(), anyLong());
        verify(transferRepository).saveEvent(any());
    }

    /* ------------------------------------------------------------------
     * Scenario 4: outcome=REJECTED (transport-level rejection)
     *             → updateStatus(txnId, FAILED)
     *             This is the path that actually uses updateStatus.
     * ------------------------------------------------------------------ */

    @Test
    void onAssessmentResult_outcomeRejected_transitionsToFailedViaUpdateStatus() {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .schemaVersion("1.0")
                .transactionId(txnId)
                .correlationId(UUID.randomUUID())
                .causationId(UUID.randomUUID())
                .occurredAt(OffsetDateTime.now())
                .producer("S4")
                .messageType("AssessmentResult.v1")
                .operationId(UUID.randomUUID())
                .outcome("REJECTED")
                .error(Map.of("code", "S4_INTERNAL", "message", "boom"))
                .build();

        consumer.onAssessmentResult(envelope, null);

        // outcome=REJECTED is the ONLY path that calls updateStatus directly.
        verify(transferRepository).updateStatus(eq(txnId), eq(TransferStatus.FAILED), eq(0L));
        verify(transferRepository, never())
                .updateRiskAndStatus(any(), any(), any(), any(), anyLong());
        verify(transferRepository).saveEvent(any());
    }

    /* ------------------------------------------------------------------
     * Scenario 5: transfer not found → consumer must not touch the DB.
     * ------------------------------------------------------------------ */

    @Test
    void onAssessmentResult_unknownTransfer_doesNotMutateAnything() {
        when(transferRepository.findById(txnId)).thenReturn(Optional.empty());

        MessageEnvelope envelope = buildAppliedEnvelope(result("APPROVED", 5));

        consumer.onAssessmentResult(envelope, null);

        verify(transferRepository, never())
                .updateRiskAndStatus(any(), any(), any(), any(), anyLong());
        verify(transferRepository, never()).updateStatus(any(), any(), anyLong());
        verify(transferRepository, never()).saveEvent(any());
    }

    @Test
    void duplicateMessageIdIsIgnoredWithoutMutatingTransfer() {
        when(transferRepository.registerProcessedMessage(
                eq("S4"), any(UUID.class), eq(txnId))).thenReturn(false);

        consumer.onAssessmentResult(buildAppliedEnvelope(result("APPROVED", 12)), null);

        verify(transferRepository, never())
                .updateRiskAndStatus(any(), any(), any(), any(), anyLong());
        verify(transferRepository, never()).saveEvent(any());
    }

    /* ------------------------------------------------------------------
     * Scenario 6: outcome=UNKNOWN → status unchanged, no mutation.
     * ------------------------------------------------------------------ */

    @Test
    void onAssessmentResult_outcomeUnknown_leavesStatusUnchanged() {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .transactionId(txnId)
                .operationId(UUID.randomUUID())
                .messageType("AssessmentResult.v1")
                .outcome("UNKNOWN")
                .build();

        consumer.onAssessmentResult(envelope, null);

        verify(transferRepository, never())
                .updateRiskAndStatus(any(), any(), any(), any(), anyLong());
        verify(transferRepository, never()).updateStatus(any(), any(), anyLong());
        verify(transferRepository, never()).saveEvent(any());
    }

    /* ------------------------------------------------------------------
     * Sanity: the consumer instance must wire up cleanly.
     * ------------------------------------------------------------------ */

    @Test
    void consumerWiresUpWithMockedRepository() {
        assertNotNull(consumer,
                "S4ResultConsumer must instantiate with a mock repo + ObjectMapper");
    }
}
