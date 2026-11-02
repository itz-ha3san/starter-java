package edu.softwarelab.starter.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain representation of a row in the {@code transfers} table.
 *
 * <p>This is a pure domain record — it carries no JSON annotations. The
 * {@code api} layer maps this to its own DTO whose JSON shape is dictated by
 * the OpenAPI contract (snake_case keys). All timestamps are UTC
 * {@link OffsetDateTime}; the JDBC driver maps them directly to PostgreSQL
 * {@code TIMESTAMPTZ}.
 *
 * @param id                 primary key (UUID v4)
 * @param sourceAccountNumber LabBank internal account number of the payer
 * @param destAccountNumber   LabBank internal account number of the payee (internal transfers only)
 * @param destIban            destination IBAN (external transfers only)
 * @param sourceAccountId     UUID of the source account, resolved by S1 (may be {@code null})
 * @param destAccountId       UUID of the destination account, resolved by S1 (may be {@code null})
 * @param amount              positive integer Iranian rials
 * @param currency            ISO 4217 currency code; {@code IRR} per GEN-13
 * @param status              current lifecycle state
 * @param isInternal          {@code true} for internal transfers, {@code false} for external (IBAN)
 * @param description         free-form description from the user
 * @param idempotencyKey      API-level idempotency key (GEN-07)
 * @param correlationId       correlation id propagated across the saga
 * @param requestHash         SHA-256 fingerprint of the normalized API request (not exposed externally)
 * @param riskDecision        S4 risk decision ({@code APPROVED|REJECTED|MANUAL_REVIEW})
 * @param riskScore           S4 normalized risk score (0–100)
 * @param externalReference   S5 settlement reference
 * @param createdAt           row creation time
 * @param updatedAt           row last-update time
 * @param version             monotonically increasing optimistic-lock revision
 */
public record Transfer(
        UUID id,
        String sourceAccountNumber,
        String destAccountNumber,
        String destIban,
        UUID sourceAccountId,
        UUID destAccountId,
        long amount,
        String currency,
        TransferStatus status,
        boolean isInternal,
        String description,
        String idempotencyKey,
        UUID correlationId,
        String requestHash,
        String riskDecision,
        Integer riskScore,
        String externalReference,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version
) {
}
