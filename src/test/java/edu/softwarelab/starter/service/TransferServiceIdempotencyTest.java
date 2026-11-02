package edu.softwarelab.starter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.softwarelab.starter.domain.OutboxRepository;
import edu.softwarelab.starter.domain.Transfer;
import edu.softwarelab.starter.domain.TransferRepository;
import edu.softwarelab.starter.messaging.S4RiskAssessmentProducer;

class TransferServiceIdempotencyTest {

    private TransferRepository transferRepository;
    private OutboxRepository outboxRepository;
    private TransferService service;
    private AtomicReference<Transfer> stored;

    @BeforeEach
    void setUp() {
        transferRepository = mock(TransferRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        stored = new AtomicReference<>();

        when(transferRepository.findByIdempotencyKey(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer transfer = invocation.getArgument(0);
            stored.compareAndSet(null, transfer);
            return stored.get();
        });
        when(transferRepository.updateStatus(any(), any(), anyLong())).thenReturn(true);
        when(transferRepository.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get()));

        service = new TransferService(
                transferRepository,
                outboxRepository,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void sameIdempotencyKeyAndSameBodyReturnsSameTransferAndDoesNotRepublish() {
        var request = new TransferService.CreateTransferRequest(
                "001200456789",
                "001200987654",
                null,
                2_500_000L,
                "IRR",
                "integration test"
        );

        Transfer first = service.createTransfer(request, "idem-test-001", UUID.randomUUID());
        Transfer replay = service.createTransfer(request, "idem-test-001", UUID.randomUUID());

        assertEquals(first.id(), replay.id());
        assertEquals(first.requestHash(), replay.requestHash());
        verify(outboxRepository, times(1)).enqueue(
                any(UUID.class),
                eq(first.id()),
                eq(S4RiskAssessmentProducer.TOPIC),
                eq(first.id().toString()),
                anyString()
        );
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyIsRejected() {
        var original = new TransferService.CreateTransferRequest(
                "001200456789",
                "001200987654",
                null,
                2_500_000L,
                "IRR",
                "original"
        );
        var changed = new TransferService.CreateTransferRequest(
                "001200456789",
                "001200987654",
                null,
                2_600_000L,
                "IRR",
                "changed"
        );

        service.createTransfer(original, "idem-test-002", UUID.randomUUID());

        assertThrows(
                TransferService.IdempotencyKeyReusedException.class,
                () -> service.createTransfer(changed, "idem-test-002", UUID.randomUUID())
        );

        verify(outboxRepository, times(1)).enqueue(
                any(UUID.class), any(UUID.class), anyString(), anyString(), anyString());
    }
}
