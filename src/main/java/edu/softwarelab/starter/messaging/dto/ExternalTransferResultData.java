package edu.softwarelab.starter.messaging.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code data} payload of the {@code ExecuteExternalTransferResult.v3} reply
 * (S5 → S3).
 *
 * <p>JSON shape per {@code asyncapi/asyncS5.yaml}: {@code status} is one of
 * {@code ACCEPTED|PROCESSING|SETTLED|FAILED|UNKNOWN}; {@code external_reference}
 * is the S5 settlement reference; {@code updated_at} is the UTC time S5 last
 * updated the transfer's state.
 *
 * @param destIban          Iranian IBAN of the destination
 * @param amount            positive integer Iranian rials
 * @param currency          ISO 4217 currency code (always {@code IRR})
 * @param externalReference S5 settlement reference
 * @param status            S5-side status
 * @param updatedAt         UTC time at which S5 last updated the transfer state
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalTransferResultData(
        @JsonProperty("dest_iban") String destIban,
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("external_reference") String externalReference,
        @JsonProperty("status") String status,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {
}
