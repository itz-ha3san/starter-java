package edu.softwarelab.starter.integration.s1;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Consumer-side test: S1 is replaced by WireMock; assertions target S3's decisions. */
class S1TransferAccountCheckServiceTest {
    private static final String SOURCE_SHEBA = "IR120990000000000000000001";
    private static final String DESTINATION_SHEBA = "IR330990000000000000000002";
    private static final String TOKEN = "s1-customer-access-token";
    private static final String CORRELATION_ID = "f18c2e22-80c8-4e1f-bc73-1ccfc21d1c9f";
    private static final String SOURCE_ID = "0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11";
    private static final String DESTINATION_ID = "0192a7e5-1c2d-7a90-8b33-4d6e9f0a1b22";

    private WireMockServer s1Stub;
    private S1TransferAccountCheckService service;

    @BeforeEach
    void setUp() {
        s1Stub = new WireMockServer(0);
        s1Stub.start();
        service = new S1TransferAccountCheckService(new S1AccountClient(s1Stub.baseUrl()));
    }

    @AfterEach
    void tearDown() {
        if (s1Stub != null) s1Stub.stop();
    }

    @Test
    void allowsTransferWhenBothAccountsAreActiveAndAmountIsWithinLimit() {
        stubS1(200, destinationFoundResponse());

        S1TransferAccountCheckService.CheckedAccounts checked = service.check(
                SOURCE_SHEBA, DESTINATION_SHEBA, TOKEN, 25_000_000L, CORRELATION_ID);

        assertEquals(UUID.fromString(SOURCE_ID), checked.sourceAccountId());
        assertEquals(UUID.fromString(DESTINATION_ID), checked.destinationAccountId());
        assertEquals(50_000_000L, checked.sourceTransferLimit());
        assertEquals("IRR", checked.currency());
        verifyExpectedRequest();
    }

    @Test
    void rejectsTransferWhenDestinationIsNotFound() {
        stubS1(200, destinationNotFoundResponse());

        var ex = assertThrows(S1TransferAccountCheckService.TransferNotAllowedException.class,
                () -> service.check(SOURCE_SHEBA, DESTINATION_SHEBA, TOKEN, 1_000L, CORRELATION_ID));

        assertEquals("Destination account was not found by S1", ex.getMessage());
        verifyExpectedRequest();
    }

    @Test
    void rejectsTransferWhenSourceAccountIsInactive() {
        stubS1(200, destinationFoundResponse(false, true));

        var ex = assertThrows(S1TransferAccountCheckService.TransferNotAllowedException.class,
                () -> service.check(SOURCE_SHEBA, DESTINATION_SHEBA, TOKEN, 1_000L, CORRELATION_ID));

        assertEquals("Source account is not active", ex.getMessage());
    }

    @Test
    void rejectsTransferWhenDestinationAccountIsInactive() {
        stubS1(200, destinationFoundResponse(true, false));

        var ex = assertThrows(S1TransferAccountCheckService.TransferNotAllowedException.class,
                () -> service.check(SOURCE_SHEBA, DESTINATION_SHEBA, TOKEN, 1_000L, CORRELATION_ID));

        assertEquals("Destination account is not active", ex.getMessage());
    }

    @Test
    void rejectsTransferWhenAmountExceedsSourceLimit() {
        stubS1(200, destinationFoundResponse());

        var ex = assertThrows(S1TransferAccountCheckService.TransferNotAllowedException.class,
                () -> service.check(SOURCE_SHEBA, DESTINATION_SHEBA, TOKEN, 50_000_001L, CORRELATION_ID));

        assertEquals("Transfer amount exceeds source account limit", ex.getMessage());
    }

    @Test
    void mapsS1BadRequestToRequestRejected() {
        stubS1(400, problem(400, "VALIDATION_ERROR"));
        assertThrows(S1AccountClient.S1RequestRejectedException.class,
                () -> service.check(SOURCE_SHEBA, DESTINATION_SHEBA, TOKEN, 1_000L, CORRELATION_ID));
    }

    @Test
    void mapsS1UnauthorizedToAuthenticationFailure() {
        stubS1(401, problem(401, "UNAUTHORIZED"));
        assertThrows(S1AccountClient.S1AuthenticationException.class,
                () -> service.check(SOURCE_SHEBA, DESTINATION_SHEBA, TOKEN, 1_000L, CORRELATION_ID));
    }

    @Test
    void mapsS1SourceNotFoundToSourceAccountNotFound() {
        stubS1(404, problem(404, "NOT_FOUND"));
        assertThrows(S1AccountClient.S1SourceAccountNotFoundException.class,
                () -> service.check(SOURCE_SHEBA, DESTINATION_SHEBA, TOKEN, 1_000L, CORRELATION_ID));
    }

    @Test
    void mapsS1ServiceUnavailableToUpstreamFailure() {
        stubS1(503, problem(503, "SERVICE_UNAVAILABLE"));
        assertThrows(S1AccountClient.S1UnavailableException.class,
                () -> service.check(SOURCE_SHEBA, DESTINATION_SHEBA, TOKEN, 1_000L, CORRELATION_ID));
    }

    @Test
    void reportsConnectionFailureAsS1Unavailable() {
        s1Stub.stop();
        assertThrows(S1AccountClient.S1UnavailableException.class,
                () -> service.check(SOURCE_SHEBA, DESTINATION_SHEBA, TOKEN, 1_000L, CORRELATION_ID));
    }

    @Test
    void rejectsInvalidShebaBeforeMakingHttpCall() {
        assertThrows(IllegalArgumentException.class,
                () -> service.check("001200456789", DESTINATION_SHEBA, TOKEN, 1_000L, CORRELATION_ID));
        s1Stub.verify(0, postRequestedFor(urlEqualTo(S1AccountClient.TRANSFER_CHECK_PATH)));
    }

    @Test
    void rejectsMissingCustomerTokenBeforeMakingHttpCall() {
        assertThrows(IllegalArgumentException.class,
                () -> service.check(SOURCE_SHEBA, DESTINATION_SHEBA, " ", 1_000L, CORRELATION_ID));
        s1Stub.verify(0, postRequestedFor(urlEqualTo(S1AccountClient.TRANSFER_CHECK_PATH)));
    }

    private static String problem(int status, String code) {
        return "{\"type\":\"about:blank\",\"title\":\"S1 error\",\"status\":"
                + status + ",\"code\":\"" + code + "\",\"detail\":\"S1 test error\"}";
    }

    private void stubS1(int status, String responseBody) {
        s1Stub.stubFor(post(urlEqualTo(S1AccountClient.TRANSFER_CHECK_PATH))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));
    }

    private void verifyExpectedRequest() {
        s1Stub.verify(postRequestedFor(urlEqualTo(S1AccountClient.TRANSFER_CHECK_PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("X-Correlation-ID", equalTo(CORRELATION_ID))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson("{\"source_sheba\":\"" + SOURCE_SHEBA
                        + "\",\"destination_sheba\":\"" + DESTINATION_SHEBA + "\"}")));
    }

    // These payloads intentionally mirror the named 200 examples in openapi/S1.yaml.
    private static String destinationFoundResponse() {
        return destinationFoundResponse(true, true);
    }

    private static String destinationFoundResponse(boolean sourceActive, boolean destinationActive) {
        return """
                {
                  "source": {
                    "account_id": "%s",
                    "active": %s,
                    "limit": {"amount": 50000000, "currency": "IRR"}
                  },
                  "destination": {
                    "found": true,
                    "account_id": "%s",
                    "active": %s
                  }
                }
                """.formatted(SOURCE_ID, sourceActive, DESTINATION_ID, destinationActive);
    }

    private static String destinationNotFoundResponse() {
        return """
                {
                  "source": {
                    "account_id": "%s",
                    "active": true,
                    "limit": {"amount": 50000000, "currency": "IRR"}
                  },
                  "destination": {
                    "found": false,
                    "account_id": null,
                    "active": null
                  }
                }
                """.formatted(SOURCE_ID);
    }
}
