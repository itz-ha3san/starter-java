package edu.softwarelab.starter.messaging.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code data} payload of the {@code ExecuteExternalTransfer.v3} command
 * (S3 → S5).
 *
 * <p>JSON shape per {@code asyncapi/asyncS5.yaml}: snake_case keys
 * ({@code dest_iban}, {@code amount}, {@code currency}).
 *
 * @param destIban Iranian IBAN of the destination (GEN-13)
 * @param amount   positive integer Iranian rials
 * @param currency ISO 4217 currency code (always {@code IRR})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecuteExternalTransferData(
        @JsonProperty("dest_iban") String destIban,
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency
) {
}
