package edu.softwarelab.starter.messaging.dto;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code data} payload of the {@code AssessRisk.v1} command (S3 → S4).
 *
 * <p>JSON shape is dictated by {@code asyncapi/asyncS4.yaml}: snake_case keys
 * ({@code src_account_id}, {@code dest_account_id}, {@code amount},
 * {@code currency}, {@code additional_data}).
 *
 * @param srcAccountId   UUID of the source account (resolved by S1)
 * @param destAccountId  UUID of the destination account (resolved by S1)
 * @param amount         positive integer Iranian rials
 * @param currency       ISO 4217 currency code (always {@code IRR})
 * @param additionalData free-form context for the risk engine
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessRiskData(
        @JsonProperty("src_account_id") UUID srcAccountId,
        @JsonProperty("dest_account_id") UUID destAccountId,
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("additional_data") Map<String, Object> additionalData
) {
}
