package edu.softwarelab.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.softwarelab.starter.domain.Transfer;
import edu.softwarelab.starter.domain.TransferEvent;
import edu.softwarelab.starter.domain.TransferEventType;
import edu.softwarelab.starter.domain.TransferRepository;
import edu.softwarelab.starter.domain.OutboxRepository;
import edu.softwarelab.starter.domain.TransferStatus;
import edu.softwarelab.starter.messaging.S4RiskAssessmentProducer;
import edu.softwarelab.starter.messaging.S5ExternalTransferProducer;
import edu.softwarelab.starter.messaging.MessageEnvelope;
import edu.softwarelab.starter.messaging.dto.AssessRiskData;
import edu.softwarelab.starter.messaging.dto.ExecuteExternalTransferData;

/**
 * Orchestrates the payment saga for the S3 service.
 *
 * <p>The main transfer saga currently wires S3 ↔ S4 (risk assessment) and
 * S3 ↔ S5 (external transfer). The S1 HTTP consumer and its eligibility service
 * are implemented and tested independently for assignment Section 8, but are
 * not yet invoked by this method because the current S3 request contract does
 * not provide the Sheba values and S1 customer token required by that contract.
 * S2 (fund hold) remains out of scope; account-id fields are not fabricated.
 *
 * <p>Idempotency: the API-level idempotency key is checked first; on a replay
 * the previously-persisted Transfer is returned untouched. The Kafka
 * {@code operation_id} is a fresh UUID per call so that an internal retry
 * (after a transient failure) is deduplicated on the S4 side.
 *
 * <p>Every state transition is recorded with a semantically distinct event
 * type; the outbound command is persisted in the transactional outbox.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transferRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public TransferService(TransferRepository transferRepository,
                           OutboxRepository outboxRepository,
                           ObjectMapper objectMapper) {
        this.transferRepository = transferRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /* -----------------------------------------------------------------
     * Request DTO — exposed as a nested type so the controller can
     * accept it directly as a @RequestBody.
     * ----------------------------------------------------------------- */
    public record CreateTransferRequest(
            String sourceAccountNumber,
            String destAccountNumber,
            String destIban,
            long amount,
            String currency,
            String description
    ) {
        public boolean isExternal() {
            return destIban != null && !destIban.isBlank();
        }

        public boolean isInternal() {
            return destAccountNumber != null && !destAccountNumber.isBlank();
        }
    }

    /** Paginated event-page response returned to the controller. */
    public record EventPage(List<TransferEventDto> data, String nextCursor) {
    }

    /** API-layer view of a {@link TransferEvent}. */
    public record TransferEventDto(
            UUID id,
            UUID transferId,
            String eventType,
            String payload,
            OffsetDateTime occurredAt
    ) {
        public static TransferEventDto from(TransferEvent e) {
            return new TransferEventDto(
                    e.id(),
                    e.transferId(),
                    e.eventType() == null ? null : e.eventType().name(),
                    e.payload(),
                    e.occurredAt()
            );
        }
    }

    /* ----------------------------------------------------------------- */

    /**
     * Create a new transfer, persist it, kick off the risk-assessment saga,
     * and return the persisted Transfer.
     *
     * <p>Idempotent: if the idempotency key already exists, the existing
     * Transfer is returned untouched (no new events are appended, no new
     * Kafka command is sent).
     */
    @Transactional
    public Transfer createTransfer(CreateTransferRequest req,
                                   String idempotencyKey,
                                   UUID correlationId) {

        validate(req);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
        UUID correlation = correlationId != null ? correlationId : UUID.randomUUID();

        // GEN-07: same key + same canonical request replays the original resource;
        // same key + different request is a deterministic 409 conflict.
        String requestHash = fingerprint(req);
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayResponse(existing.get(), idempotencyKey, requestHash);
        }

        // 2. Build the new transfer.
        boolean isInternal = req.isInternal();
        if (!isInternal && !req.isExternal()) {
            throw new IllegalArgumentException(
                    "Either dest_account_number (internal) or dest_iban (external) must be provided");
        }

        UUID transferId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Transfer transfer = new Transfer(
                transferId,
                req.sourceAccountNumber(),
                isInternal ? req.destAccountNumber() : null,
                isInternal ? null : req.destIban(),
                null, // sourceAccountId — resolved by S1 (out of scope)
                null, // destAccountId   — resolved by S1 (out of scope)
                req.amount(),
                req.currency() == null ? "IRR" : req.currency(),
                TransferStatus.PENDING,
                isInternal,
                req.description(),
                idempotencyKey,
                correlation,
                requestHash,
                null,
                null,
                null,
                now,
                now,
                0L
        );

        Transfer saved = persistCreatedTransfer(transfer, correlation);
        // A concurrent request may have won the unique idempotency-key insert.
        // Repository returns that canonical row; do not append events or dispatch twice.
        if (!saved.id().equals(transfer.id())) {
            return replayResponse(saved, idempotencyKey, requestHash);
        }

        // 3. Persist the S4 command in the transactional outbox. The transfer,
        // CREATED event, state transition, and outbound command commit atomically.
        MessageEnvelope riskCommand = enqueueAssessRiskCommand(saved);

        boolean updated = transferRepository.updateStatus(saved.id(), TransferStatus.RISK_CHECKING, saved.version());
        if (!updated) {
            throw new IllegalStateException(
                    "Could not transition newly created transfer to RISK_CHECKING: " + saved.id());
        }
        recordEvent(
                saved.id(),
                TransferEventType.RISK_ASSESSMENT_REQUESTED,
                Map.of(
                        "transition", "PENDING -> RISK_CHECKING",
                        "transfer_id", saved.id().toString(),
                        "correlation_id", correlation.toString(),
                        "message_id", riskCommand.getMessageId().toString(),
                        "operation_id", riskCommand.getOperationId().toString(),
                        "topic", S4RiskAssessmentProducer.TOPIC
                )
        );

        Transfer response = transferRepository.findById(saved.id()).orElse(saved);
        try {
            String responseSnapshot = objectMapper.writeValueAsString(response);
            transferRepository.saveResponseSnapshot(idempotencyKey, saved.id(), responseSnapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize idempotency response snapshot", ex);
        }
        return response;
    }

    /**
     * Persist the new transfer and record the initial CREATED event in one
     * transaction.
     */
    protected Transfer persistCreatedTransfer(Transfer transfer, UUID correlationId) {
        Transfer saved = transferRepository.save(transfer);
        // A concurrent request with the same idempotency key may have inserted
        // first. In that case return its canonical aggregate without side effects.
        if (!saved.id().equals(transfer.id())) {
            return saved;
        }
        // LinkedHashMap permits null values. Map.of(...) does not, while one
        // of dest_account_number / dest_iban is intentionally null for each
        // transfer type (internal vs external).
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("transfer_id", saved.id().toString());
        eventPayload.put("correlation_id", correlationId.toString());
        eventPayload.put("amount", saved.amount());
        eventPayload.put("currency", saved.currency());
        eventPayload.put("is_internal", saved.isInternal());
        eventPayload.put("source_account_number", saved.sourceAccountNumber());
        eventPayload.put("dest_account_number", saved.destAccountNumber());
        eventPayload.put("dest_iban", saved.destIban());
        recordEvent(saved.id(), TransferEventType.CREATED, eventPayload);
        return saved;
    }

    /**
     * Build and send the AssessRisk command to S4. The {@code operation_id}
     * is a fresh UUID — it doubles as S4's idempotency key for this command.
     *
     * <p>NOTE: src_account_id and dest_account_id would normally be resolved
     * by an S1 lookup. S1 is out of scope for this session, so we synthesize
     * deterministic placeholder UUIDs (UUID v3 / name-based) from the account
     * number / IBAN. This keeps the contract valid for end-to-end plumbing.
     */
    private MessageEnvelope enqueueAssessRiskCommand(Transfer transfer) {
        UUID srcAccountId = nameUuid(transfer.sourceAccountNumber());
        UUID destAccountId = transfer.isInternal()
                ? nameUuid(transfer.destAccountNumber())
                : nameUuid(transfer.destIban());

        Map<String, Object> additional = Map.of(
                "transfer_type", transfer.isInternal() ? "INTERNAL" : "EXTERNAL",
                "channel", "API",
                "dest_iban", transfer.destIban() == null ? "" : transfer.destIban()
        );

        AssessRiskData data = new AssessRiskData(
                srcAccountId,
                destAccountId,
                transfer.amount(),
                transfer.currency(),
                additional
        );

        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .schemaVersion(S4RiskAssessmentProducer.SCHEMA_VERSION)
                .transactionId(transfer.id())
                .correlationId(transfer.correlationId())
                .occurredAt(OffsetDateTime.now())
                .producer(S4RiskAssessmentProducer.PRODUCER)
                .messageType(S4RiskAssessmentProducer.MESSAGE_TYPE)
                .operationId(UUID.randomUUID())
                .data(data)
                .build();

        enqueue(envelope, S4RiskAssessmentProducer.TOPIC, transfer.id());
        log.info("AssessRisk queued in outbox transfer={} messageId={}",
                transfer.id(), envelope.getMessageId());
        return envelope;
    }

    private void enqueue(MessageEnvelope envelope, String topic, UUID transferId) {
        try {
            String payload = objectMapper.writeValueAsString(envelope);
            outboxRepository.enqueue(
                    envelope.getMessageId(),
                    transferId,
                    topic,
                    transferId.toString(),
                    payload
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Could not serialize outbox message for transfer=" + transferId, ex);
        }
    }

    /**
     * External-transfer execution entry point — currently exercised by tests
     * and by future HOLDING→EXECUTING transitions. For this session we expose
     * it so the S3↔S5 ring is fully wired, but createTransfer does not invoke
     * it (the HOLDING→EXECUTING transition is out of scope).
     */
    @Transactional
    public void sendExecuteExternalTransfer(Transfer transfer) {
        if (transfer.isInternal()) {
            throw new IllegalArgumentException(
                    "External-transfer command only applies to external (IBAN) transfers");
        }
        if (transfer.destIban() == null) {
            throw new IllegalArgumentException("dest_iban is required for external transfer");
        }
        ExecuteExternalTransferData data = new ExecuteExternalTransferData(
                transfer.destIban(),
                transfer.amount(),
                transfer.currency()
        );
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .schemaVersion(S5ExternalTransferProducer.SCHEMA_VERSION)
                .transactionId(transfer.id())
                .correlationId(transfer.correlationId())
                .occurredAt(OffsetDateTime.now())
                .producer(S5ExternalTransferProducer.PRODUCER)
                .messageType(S5ExternalTransferProducer.MESSAGE_TYPE)
                .operationId(UUID.randomUUID())
                .data(data)
                .build();
        enqueue(envelope, S5ExternalTransferProducer.TOPIC, transfer.id());
    }

    /* ----------------------------------------------------------------- */

    public Transfer getTransfer(UUID id) {
        return transferRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Transfer not found: " + id));
    }

    /**
     * Returns a page of events for the given transfer, ordered by
     * {@code occurred_at} descending.
     *
     * @param transferId transfer id
     * @param cursor     ISO-8601 offset-date-time string of the oldest event in
     *                   the previous page; {@code null} for the first page
     * @param limit      page size (clamped to 1..200)
     */
    public EventPage getTransferEvents(UUID transferId, String cursor, Integer limit) {
        int pageSize = clamp(limit == null ? 50 : limit, 1, 200);

        // Confirm the transfer exists (404 if not).
        if (transferRepository.findById(transferId).isEmpty()) {
            throw new NoSuchElementException("Transfer not found: " + transferId);
        }

        if (cursor != null) {
            validateEventCursor(cursor);
        }
        List<TransferEvent> events = transferRepository.findEvents(transferId, cursor, pageSize + 1);

        String nextCursor = null;
        List<TransferEvent> page;
        if (events.size() > pageSize) {
            // There is a next page. Use the oldest event in THIS page as the cursor.
            page = events.subList(0, pageSize);
            TransferEvent oldestInPage = page.get(pageSize - 1);
            nextCursor = oldestInPage.occurredAt().toString() + "|" + oldestInPage.id();
        } else {
            page = events;
        }

        List<TransferEventDto> dtos = page.stream().map(TransferEventDto::from).toList();
        return new EventPage(dtos, nextCursor);
    }

    /* ----------------------------------------------------------------- */

    private void validate(CreateTransferRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (req.sourceAccountNumber() == null || req.sourceAccountNumber().isBlank()) {
            throw new IllegalArgumentException("source_account_number is required");
        }
        if (req.amount <= 0) {
            throw new IllegalArgumentException("amount must be a positive integer");
        }
        if (req.currency() != null && !"IRR".equals(req.currency())) {
            throw new IllegalArgumentException("currency must be IRR");
        }
        if (req.destAccountNumber() != null && req.destIban() != null) {
            throw new IllegalArgumentException(
                    "dest_account_number and dest_iban are mutually exclusive");
        }
    }

    private void recordEvent(UUID transferId, TransferEventType type, Object payload) {
        try {
            // transfer_events.payload is PostgreSQL JSONB. Map.toString() is
            // not JSON (it uses '=' between keys and values), so serialize
            // every event payload through Jackson before persisting it.
            String payloadJson = payload == null ? null : objectMapper.writeValueAsString(payload);
            transferRepository.saveEvent(new TransferEvent(
                    UUID.randomUUID(),
                    transferId,
                    type,
                    payloadJson,
                    OffsetDateTime.now()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize " + type + " event payload for transfer=" + transferId, e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to persist " + type + " event for transfer=" + transferId, e);
        }
    }

    private static void validateEventCursor(String cursor) {
        int separator = cursor.lastIndexOf('|');
        if (separator <= 0 || separator == cursor.length() - 1) {
            throw new IllegalArgumentException("Invalid event cursor");
        }
        try {
            OffsetDateTime.parse(cursor.substring(0, separator));
            UUID.fromString(cursor.substring(separator + 1));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid event cursor", ex);
        }
    }

    private Transfer replayResponse(Transfer existing, String idempotencyKey, String incomingHash) {
        assertSameRequest(existing, incomingHash);
        log.info("Idempotency replay transfer={} status={}", existing.id(), existing.status());
        Optional<String> snapshot = transferRepository.findResponseSnapshotByIdempotencyKey(idempotencyKey);
        if (snapshot.isPresent()) {
            try {
                return objectMapper.readValue(snapshot.get(), Transfer.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Stored idempotency response snapshot is invalid", ex);
            }
        }
        // Compatibility for transfers created before response snapshots were introduced.
        return existing;
    }

    private void assertSameRequest(Transfer existing, String incomingHash) {
        // Legacy rows created before request_hash existed are compared using
        // their persisted canonical business fields.
        String storedHash = existing.requestHash();
        if (storedHash == null) {
            storedHash = fingerprint(new CreateTransferRequest(
                    existing.sourceAccountNumber(),
                    existing.destAccountNumber(),
                    existing.destIban(),
                    existing.amount(),
                    existing.currency(),
                    existing.description()
            ));
        }
        if (!storedHash.equals(incomingHash)) {
            throw new IdempotencyKeyReusedException(
                    "Idempotency-Key was already used with a different request body");
        }
    }

    private String fingerprint(CreateTransferRequest req) {
        // Length-prefix each field: null (-1:) and empty (0:) are distinct,
        // and delimiters/newlines inside descriptions cannot cause collisions.
        StringBuilder canonical = new StringBuilder();
        appendCanonicalField(canonical, req.sourceAccountNumber());
        appendCanonicalField(canonical, req.destAccountNumber());
        appendCanonicalField(canonical, req.destIban());
        appendCanonicalField(canonical, Long.toString(req.amount()));
        appendCanonicalField(canonical, req.currency() == null ? "IRR" : req.currency());
        appendCanonicalField(canonical, req.description());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static void appendCanonicalField(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
    }

    public static final class IdempotencyKeyReusedException extends RuntimeException {
        public IdempotencyKeyReusedException(String message) {
            super(message);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Deterministic name-based UUID (RFC 4122 v3) from a string. Used as a
     * placeholder for the S1 account-id lookup which is out of scope here.
     */
    private static UUID nameUuid(String name) {
        if (name == null) {
            return UUID.randomUUID();
        }
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
