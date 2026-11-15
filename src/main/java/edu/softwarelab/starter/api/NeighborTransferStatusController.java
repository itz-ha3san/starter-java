package edu.softwarelab.starter.api;

import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import edu.softwarelab.starter.domain.Transfer;
import edu.softwarelab.starter.domain.TransferRepository;

/**
 * Neighbor-facing REST endpoint (Provider side, Section 7).
 *
 * <p>This controller exposes a <em>minimal, read-only</em> status snapshot of a
 * transfer that a neighbor team (e.g. S5 Settlement, S4 Risk, the frontend of
 * another team) can poll without depending on the full {@code TransferDto}
 * shape used by the S3 frontend.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code GET /api/v1/transfers/{id}/status} — returns
 *       {@code {id, status, is_internal, amount, currency, external_reference, updated_at}}.</li>
 *   <li>{@code 200 OK} on success.</li>
 *   <li>{@code 404 NOT_FOUND} with {@code application/problem+json} when the
 *       transfer does not exist (handled by {@link GlobalExceptionHandler}).</li>
 * </ul>
 *
 * <p>The endpoint is REST, stateless, and available as soon as
 * {@code docker compose up --build -d} has started the {@code app} container
 * with PostgreSQL and the Flyway seed migration (V2) applied.
 */
@RestController
@RequestMapping("/api/v1")
public class NeighborTransferStatusController {

    private static final Logger log = LoggerFactory.getLogger(NeighborTransferStatusController.class);

    private final TransferRepository transferRepository;

    public NeighborTransferStatusController(TransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransferStatusDto(
            @JsonProperty("id") UUID id,
            @JsonProperty("status") String status,
            @JsonProperty("is_internal") boolean isInternal,
            @JsonProperty("amount") long amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("external_reference") String externalReference,
            @JsonProperty("updated_at") String updatedAt
    ) {
        public static TransferStatusDto from(Transfer t) {
            return new TransferStatusDto(
                    t.id(),
                    t.status() == null ? null : t.status().name(),
                    t.isInternal(),
                    t.amount(),
                    t.currency(),
                    t.externalReference(),
                    t.updatedAt() == null ? null
                            : DateTimeFormatter.ISO_INSTANT.format(t.updatedAt().toInstant())
            );
        }
    }

    @GetMapping("/transfers/{id}/status")
    public ResponseEntity<TransferStatusDto> getTransferStatus(@PathVariable UUID id) {
        log.info("GET /api/v1/transfers/{}/status (neighbor)", id);
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Transfer not found: " + id));
        return ResponseEntity.ok(TransferStatusDto.from(transfer));
    }
}
