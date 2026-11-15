package edu.softwarelab.starter.integration.s1;

import java.util.UUID;

import org.springframework.stereotype.Service;

/** Applies S3's transfer-eligibility rules to the account facts returned by S1. */
@Service
public class S1TransferAccountCheckService {
    private final S1AccountClient s1AccountClient;

    public S1TransferAccountCheckService(S1AccountClient s1AccountClient) {
        this.s1AccountClient = s1AccountClient;
    }

    public CheckedAccounts check(String sourceSheba, String destinationSheba,
                                 String customerBearerToken, long amount,
                                 String correlationId) {
        if (amount <= 0) throw new IllegalArgumentException("Transfer amount must be positive");
        S1AccountClient.TransferCheckResponse result = s1AccountClient.checkTransferAccounts(
                sourceSheba, destinationSheba, customerBearerToken, correlationId);

        if (!result.source().active()) {
            throw new TransferNotAllowedException("Source account is not active");
        }
        if (amount > result.source().limit().amount()) {
            throw new TransferNotAllowedException("Transfer amount exceeds source account limit");
        }
        if (!result.destination().found()) {
            throw new TransferNotAllowedException("Destination account was not found by S1");
        }
        if (!Boolean.TRUE.equals(result.destination().active())) {
            throw new TransferNotAllowedException("Destination account is not active");
        }
        return new CheckedAccounts(result.source().account_id(), result.destination().account_id(),
                result.source().limit().amount(), result.source().limit().currency());
    }

    public record CheckedAccounts(UUID sourceAccountId, UUID destinationAccountId,
                                  long sourceTransferLimit, String currency) { }

    public static class TransferNotAllowedException extends RuntimeException {
        public TransferNotAllowedException(String message) { super(message); }
    }
}
