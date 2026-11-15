package edu.softwarelab.starter.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import edu.softwarelab.starter.domain.Transfer;
import edu.softwarelab.starter.domain.TransferStatus;
import edu.softwarelab.starter.service.TransferService;

@WebMvcTest(controllers = TransferController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class TransferControllerMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    private static final UUID TRANSFER_ID = UUID.fromString("0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11");
    private static final UUID CORRELATION_ID = UUID.fromString("0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c21");

    @Test
    void createTransferReturns201LocationAndSnakeCaseBody() throws Exception {
        Transfer transfer = sampleTransfer();
        when(transferService.createTransfer(any(), eq("idem-001"), eq(CORRELATION_ID)))
                .thenReturn(transfer);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-001")
                        .header("X-Correlation-ID", CORRELATION_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source_account_number": "1234567890",
                                  "dest_account_number": "0987654321",
                                  "amount": 250000,
                                  "currency": "IRR",
                                  "description": "test transfer"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/transfers/" + TRANSFER_ID)))
                .andExpect(header().string("X-Correlation-ID", CORRELATION_ID.toString()))
                .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
                .andExpect(jsonPath("$.source_account_number").value("1234567890"))
                .andExpect(jsonPath("$.dest_account_number").value("0987654321"))
                .andExpect(jsonPath("$.amount").value(250000))
                .andExpect(jsonPath("$.currency").value("IRR"))
                .andExpect(jsonPath("$.status").value("RISK_CHECKING"));

        verify(transferService).createTransfer(
                new TransferService.CreateTransferRequest(
                        "1234567890", "0987654321", null, 250000L, "IRR", "test transfer"),
                "idem-001",
                CORRELATION_ID);
    }

    @Test
    void createTransferRejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validInternalTransferJson()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createTransferRejectsInvalidRequestBody() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source_account_number": "12AB",
                                  "dest_account_number": "0987654321",
                                  "amount": -10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createTransferRejectsMalformedCorrelationId() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idem-003")
                        .header("X-Correlation-ID", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validInternalTransferJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getTransferReturnsTransferDto() throws Exception {
        when(transferService.getTransfer(TRANSFER_ID)).thenReturn(sampleTransfer());

        mockMvc.perform(get("/api/v1/transfers/{id}", TRANSFER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
                .andExpect(jsonPath("$.source_account_number").value("1234567890"))
                .andExpect(jsonPath("$.is_internal").value(true));
    }

    @Test
    void getTransferMapsMissingResourceTo404Problem() throws Exception {
        when(transferService.getTransfer(TRANSFER_ID))
                .thenThrow(new NoSuchElementException("Transfer not found: " + TRANSFER_ID));

        mockMvc.perform(get("/api/v1/transfers/{id}", TRANSFER_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void getTransferEventsPassesPaginationParametersAndReturnsPage() throws Exception {
        TransferService.TransferEventDto event = new TransferService.TransferEventDto(
                UUID.fromString("0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c31"),
                TRANSFER_ID,
                "CREATED",
                "{}",
                OffsetDateTime.parse("2026-11-08T10:15:30Z"));
        when(transferService.getTransferEvents(TRANSFER_ID, "cursor-1", 10))
                .thenReturn(new TransferService.EventPage(List.of(event), "cursor-2"));

        mockMvc.perform(get("/api/v1/transfers/{id}/events", TRANSFER_ID)
                        .param("cursor", "cursor-1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$.next_cursor").value("cursor-2"));

        verify(transferService).getTransferEvents(TRANSFER_ID, "cursor-1", 10);
    }

    private static String validInternalTransferJson() {
        return """
                {
                  "source_account_number": "1234567890",
                  "dest_account_number": "0987654321",
                  "amount": 250000,
                  "currency": "IRR"
                }
                """;
    }

    private static Transfer sampleTransfer() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-08T10:15:30Z");
        return new Transfer(
                TRANSFER_ID,
                "1234567890",
                "0987654321",
                null,
                null,
                null,
                250000L,
                "IRR",
                TransferStatus.RISK_CHECKING,
                true,
                "test transfer",
                "idem-001",
                CORRELATION_ID,
                "request-hash",
                null,
                null,
                null,
                now,
                now,
                1L);
    }
}
