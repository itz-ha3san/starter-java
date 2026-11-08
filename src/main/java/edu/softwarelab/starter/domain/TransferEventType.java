package edu.softwarelab.starter.domain;

/**
 * Event types recorded in the {@code transfer_events} append-only log.
 *
 * <p>Each transition of a {@link Transfer} produces exactly one event; the event
 * payload is the JSON snapshot of relevant fields at the time of the transition.
 */
public enum TransferEventType {
    CREATED,
    RISK_ASSESSMENT_REQUESTED,
    AUTHORIZATION_CHECKED,
    RISK_ASSESSED,
    FUNDS_HELD,
    FUNDS_CAPTURED,
    FUNDS_RELEASED,
    TRANSFER_EXECUTED,
    TRANSFER_SETTLED,
    TRANSFER_FAILED,
    TRANSFER_REVERSED
}
