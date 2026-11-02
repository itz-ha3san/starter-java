package edu.softwarelab.starter.domain;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the {@link TransferStatus} and {@link TransferEventType}
 * enums. No Spring context is loaded — these are simple value-of / count
 * checks against the lifecycle vocabularies defined in the S3 Payment service.
 */
class TransferStatusTest {

    /**
     * The S3 saga defines exactly 10 lifecycle states. Adding or removing a
     * state is a contract change — fail loudly here if the count drifts.
     */
    @Test
    void transferStatusHasExactlyTenValues() {
        TransferStatus[] values = TransferStatus.values();
        assertEquals(10, values.length,
                "TransferStatus must define exactly 10 lifecycle states");
    }

    /**
     * Lock in the named constants we rely on throughout the saga
     * (controller DTO mapping, repository status writes, consumer
     * state-machine transitions).
     */
    @Test
    void transferStatusContainsExpectedStates() {
        Set<TransferStatus> expected = EnumSet.of(
                TransferStatus.PENDING,
                TransferStatus.RISK_CHECKING,
                TransferStatus.RISK_REJECTED,
                TransferStatus.RISK_MANUAL_REVIEW,
                TransferStatus.HOLDING,
                TransferStatus.HOLD_FAILED,
                TransferStatus.EXECUTING,
                TransferStatus.SETTLED,
                TransferStatus.FAILED,
                TransferStatus.REVERSED
        );
        assertEquals(expected, EnumSet.allOf(TransferStatus.class),
                "TransferStatus enum set must match the contract exactly");
    }

    @Test
    void transferStatusIncludesHeadlineStates() {
        // Explicitly call out the states mentioned in the spec.
        assertNotNull(TransferStatus.PENDING);
        assertNotNull(TransferStatus.SETTLED);
        assertNotNull(TransferStatus.FAILED);
        assertNotNull(TransferStatus.RISK_REJECTED);
        assertTrue(EnumSet.allOf(TransferStatus.class).contains(TransferStatus.PENDING));
        assertTrue(EnumSet.allOf(TransferStatus.class).contains(TransferStatus.SETTLED));
        assertTrue(EnumSet.allOf(TransferStatus.class).contains(TransferStatus.FAILED));
        assertTrue(EnumSet.allOf(TransferStatus.class).contains(TransferStatus.RISK_REJECTED));
    }

    @Test
    void valueOfPendingWorks() {
        TransferStatus resolved = TransferStatus.valueOf("PENDING");
        assertNotNull(resolved);
        assertSame(TransferStatus.PENDING, resolved,
                "valueOf(\"PENDING\") must return the canonical PENDING constant");
    }

    @Test
    void valueOfAllKnownNamesRoundTrips() {
        // Every enum constant must be resolvable by its name() — guards
        // against accidental rename without a matching DB migration.
        for (TransferStatus s : TransferStatus.values()) {
            assertSame(s, TransferStatus.valueOf(s.name()),
                    "Round-trip valueOf(name()) failed for " + s);
        }
    }

    /**
     * The {@code transfer_events} append-only log discriminates on 11 event
     * types. Drift here would break the event-sourcing replay contract.
     */
    @Test
    void transferEventTypeHasExactlyElevenValues() {
        TransferEventType[] values = TransferEventType.values();
        assertEquals(11, values.length,
                "TransferEventType must define exactly 11 event types");
    }

    @Test
    void transferEventTypeContainsExpectedTypes() {
        Set<TransferEventType> expected = EnumSet.of(
                TransferEventType.CREATED,
                TransferEventType.RISK_ASSESSMENT_REQUESTED,
                TransferEventType.AUTHORIZATION_CHECKED,
                TransferEventType.RISK_ASSESSED,
                TransferEventType.FUNDS_HELD,
                TransferEventType.FUNDS_CAPTURED,
                TransferEventType.FUNDS_RELEASED,
                TransferEventType.TRANSFER_EXECUTED,
                TransferEventType.TRANSFER_SETTLED,
                TransferEventType.TRANSFER_FAILED,
                TransferEventType.TRANSFER_REVERSED
        );
        assertEquals(expected, EnumSet.allOf(TransferEventType.class),
                "TransferEventType enum set must match the contract exactly");
    }

    @Test
    void transferEventTypeValueOfRiskAssessedWorks() {
        assertSame(TransferEventType.RISK_ASSESSED,
                TransferEventType.valueOf("RISK_ASSESSED"));
    }
}
