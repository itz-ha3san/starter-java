package edu.softwarelab.starter.messaging.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code data} payload of the {@code AssessmentResult.v1} reply (S4 → S3).
 *
 * <p>JSON shape per {@code asyncapi/asyncS4.yaml}: {@code decision} is one of
 * {@code APPROVED|REJECTED|MANUAL_REVIEW}; {@code risk_score} is normalized
 * 0–100; {@code rules_evaluated} is the list of risk-rule names that fired;
 * {@code assessed_at} is the UTC time S4 recorded its decision.
 *
 * @param decision       risk decision
 * @param riskScore      normalized risk score 0–100
 * @param rulesEvaluated names of the risk rules that fired or were checked
 * @param assessedAt     UTC time at which S4 recorded its decision
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessmentResultData(
        @JsonProperty("decision") String decision,
        @JsonProperty("risk_score") Integer riskScore,
        @JsonProperty("rules_evaluated") List<String> rulesEvaluated,
        @JsonProperty("assessed_at") OffsetDateTime assessedAt
) {
}
