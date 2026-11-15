package edu.softwarelab.starter.integration.s1;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** HTTP consumer for the S1 transfer-check contract (openapi/S1.yaml). */
@Component
public class S1AccountClient {
    static final String TRANSFER_CHECK_PATH = "/api/v1/accounts/transfer-check";

    private final RestClient restClient;

    public S1AccountClient(@Value("${s1.base-url}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public TransferCheckResponse checkTransferAccounts(String sourceSheba,
                                                        String destinationSheba,
                                                        String bearerToken,
                                                        String correlationId) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("S1 customer Bearer token is required");
        }
        TransferCheckResponse response;
        try {
            response = restClient.post()
                    .uri(TRANSFER_CHECK_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .headers(headers -> {
                        if (correlationId != null && !correlationId.isBlank()) {
                            headers.set("X-Correlation-ID", correlationId);
                        }
                    })
                    .body(new TransferCheckRequest(sourceSheba, destinationSheba))
                    .retrieve()
                    .body(TransferCheckResponse.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 400) throw new S1RequestRejectedException("S1 rejected the transfer-check request", ex);
            if (status == 401) throw new S1AuthenticationException("S1 rejected the customer access token", ex);
            if (status == 404) throw new S1SourceAccountNotFoundException("S1 could not find the source account", ex);
            throw new S1UnavailableException("S1 returned HTTP " + status, ex);
        } catch (RestClientException ex) {
            throw new S1UnavailableException("S1 transfer-check request failed", ex);
        }
        if (response == null || response.source() == null || response.source().limit() == null
                || response.destination() == null) {
            throw new S1UnavailableException("S1 returned an incomplete transfer-check response");
        }
        return response;
    }

    public record TransferCheckRequest(String source_sheba, String destination_sheba) {
        public TransferCheckRequest {
            requireSheba(source_sheba, "source_sheba");
            requireSheba(destination_sheba, "destination_sheba");
        }
        private static void requireSheba(String value, String field) {
            if (value == null || !value.matches("^IR[0-9]{24}$")) {
                throw new IllegalArgumentException(field + " must be an Iranian Sheba (IR + 24 digits)");
            }
        }
    }

    public record Money(long amount, String currency) { }
    public record SourceAccount(UUID account_id, boolean active, Money limit) { }
    public record DestinationAccount(boolean found, UUID account_id, Boolean active) { }
    public record TransferCheckResponse(SourceAccount source, DestinationAccount destination) { }

    public static class S1UnavailableException extends RuntimeException {
        public S1UnavailableException(String message) { super(message); }
        public S1UnavailableException(String message, Throwable cause) { super(message, cause); }
    }
    public static class S1RequestRejectedException extends RuntimeException {
        public S1RequestRejectedException(String message, Throwable cause) { super(message, cause); }
    }
    public static class S1AuthenticationException extends RuntimeException {
        public S1AuthenticationException(String message, Throwable cause) { super(message, cause); }
    }
    public static class S1SourceAccountNotFoundException extends RuntimeException {
        public S1SourceAccountNotFoundException(String message, Throwable cause) { super(message, cause); }
    }
}
