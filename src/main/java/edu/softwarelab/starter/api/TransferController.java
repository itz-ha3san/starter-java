package edu.softwarelab.starter.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import edu.softwarelab.starter.domain.Transfer;
import edu.softwarelab.starter.service.TransferService;

/**
 * REST entry points for the S3 Payment service.
 *
 * <p>Endpoints (per openapi/payment.yaml):
 * <ul>
 *   <li>{@code POST /api/v1/transfers} — create a transfer, kick off the saga.</li>
 *   <li>{@code GET  /api/v1/transfers/{id}} — fetch a transfer by id.</li>
 *   <li>{@code GET  /api/v1/transfers/{id}/events} — paginated event history.</li>
 * </ul>
 *
 * <p>JSON keys are snake_case to match the OpenAPI contract. Validation is
 * enforced via jakarta.validation annotations on the request DTO.
 */
@RestController
@RequestMapping("/api/v1")
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /* -----------------------------------------------------------------
     * Request DTO — snake_case JSON per OpenAPI, with bean validation.
     * ----------------------------------------------------------------- */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateTransferRequestDto(
            @JsonProperty("source_account_number")
            @NotBlank(message = "source_account_number is required")
            @Pattern(regexp = "^[0-9]{10,12}$", message = "source_account_number must be 10-12 digits")
            String sourceAccountNumber,

            @JsonProperty("dest_account_number")
            @Pattern(regexp = "^[0-9]{10,12}$", message = "dest_account_number must be 10-12 digits")
            String destAccountNumber,

            @JsonProperty("dest_iban")
            @Pattern(regexp = "^IR[0-9]{24}$", message = "dest_iban must be a valid Iranian IBAN")
            String destIban,

            @JsonProperty("amount")
            @NotNull(message = "amount is required")
            @Positive(message = "amount must be a positive integer")
            Long amount,

            @JsonProperty("currency")
            @Pattern(regexp = "^IRR$", message = "currency must be IRR")
            String currency,

            @JsonProperty("description")
            @Size(max = 256, message = "description must be at most 256 characters")
            String description
    ) {
        public TransferService.CreateTransferRequest toServiceRequest() {
            return new TransferService.CreateTransferRequest(
                    sourceAccountNumber,
                    destAccountNumber,
                    destIban,
                    amount == null ? 0L : amount,
                    currency,
                    description
            );
        }
    }

    /* -----------------------------------------------------------------
     * Response DTOs — snake_case JSON per OpenAPI.
     * ----------------------------------------------------------------- */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransferDto(
            @JsonProperty("id") UUID id,
            @JsonProperty("source_account_number") String sourceAccountNumber,
            @JsonProperty("dest_account_number") String destAccountNumber,
            @JsonProperty("dest_iban") String destIban,
            @JsonProperty("source_account_id") UUID sourceAccountId,
            @JsonProperty("dest_account_id") UUID destAccountId,
            @JsonProperty("amount") long amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("status") String status,
            @JsonProperty("is_internal") boolean isInternal,
            @JsonProperty("description") String description,
            @JsonProperty("idempotency_key") String idempotencyKey,
            @JsonProperty("correlation_id") UUID correlationId,
            @JsonProperty("risk_decision") String riskDecision,
            @JsonProperty("risk_score") Integer riskScore,
            @JsonProperty("external_reference") String externalReference,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt
    ) {
        public static TransferDto from(Transfer t) {
            return new TransferDto(
                    t.id(),
                    t.sourceAccountNumber(),
                    t.destAccountNumber(),
                    t.destIban(),
                    t.sourceAccountId(),
                    t.destAccountId(),
                    t.amount(),
                    t.currency(),
                    t.status() == null ? null : t.status().name(),
                    t.isInternal(),
                    t.description(),
                    t.idempotencyKey(),
                    t.correlationId(),
                    t.riskDecision(),
                    t.riskScore(),
                    t.externalReference(),
                    t.createdAt() == null ? null : t.createdAt().toString(),
                    t.updatedAt() == null ? null : t.updatedAt().toString()
            );
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventPageDto(
            @JsonProperty("data") List<TransferService.TransferEventDto> data,
            @JsonProperty("next_cursor") String nextCursor
    ) {
        public static EventPageDto from(TransferService.EventPage page) {
            return new EventPageDto(page.data(), page.nextCursor());
        }
    }

    /* -----------------------------------------------------------------
     * Endpoints
     * ----------------------------------------------------------------- */

    @PostMapping("/transfers")
    public ResponseEntity<TransferDto> createTransfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationIdHeader,
            @Valid @RequestBody CreateTransferRequestDto body
    ) {
        UUID correlationId = parseCorrelationId(correlationIdHeader);
        log.info("POST /transfers idempotencyKey={} correlationId={} amount={}",
                idempotencyKey, correlationId, body.amount());

        TransferService.CreateTransferRequest serviceReq = body.toServiceRequest();
        Transfer created = transferService.createTransfer(serviceReq, idempotencyKey, correlationId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity
                .created(location)
                .header("X-Correlation-ID", created.correlationId().toString())
                .body(TransferDto.from(created));
    }

    @GetMapping("/transfers/{id}")
    public ResponseEntity<TransferDto> getTransfer(@PathVariable UUID id) {
        Transfer transfer = transferService.getTransfer(id);
        return ResponseEntity.ok(TransferDto.from(transfer));
    }

    @GetMapping("/transfers/{id}/events")
    public ResponseEntity<EventPageDto> getTransferEvents(
            @PathVariable UUID id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        TransferService.EventPage page = transferService.getTransferEvents(id, cursor, limit);
        return ResponseEntity.ok(EventPageDto.from(page));
    }

    /* ----------------------------------------------------------------- */

    private static UUID parseCorrelationId(String header) {
        if (header == null || header.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("X-Correlation-ID must be a valid UUID", ex);
        }
    }
}
