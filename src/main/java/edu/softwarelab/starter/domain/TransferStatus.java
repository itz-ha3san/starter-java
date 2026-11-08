package edu.softwarelab.starter.domain;

/**
 * Lifecycle states for a Transfer (S3 Payment Orchestration).
 *
 * <p>PENDING: just created. RISK_CHECKING: AssessRisk sent to S4. RISK_REJECTED:
 * S4 said REJECTED. RISK_MANUAL_REVIEW: S4 said MANUAL_REVIEW or timed out. HOLDING:
 * HoldFunds sent to S2. HOLD_FAILED: S2 hold failed. EXECUTING: command sent to S2
 * (internal) or S5 (external). SETTLED: success. FAILED: terminal failure. REVERSED:
 * compensating reversal applied.
 */
public enum TransferStatus {
    PENDING,
    RISK_CHECKING,
    RISK_REJECTED,
    RISK_MANUAL_REVIEW,
    HOLDING,
    HOLD_FAILED,
    EXECUTING,
    SETTLED,
    FAILED,
    REVERSED
}
