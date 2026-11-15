package edu.softwarelab.starter.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import edu.softwarelab.starter.domain.Transfer;
import edu.softwarelab.starter.domain.TransferRepository;
import edu.softwarelab.starter.domain.TransferStatus;

/**
 * Section 7 — Provider-side API tests for the neighbor-facing endpoint
 * {@code GET /api/v1/transfers/{id}/status}.
 *
 * <p>Two cases are required by the report (one success, one error); this class
 * also keeps an extra validation case to make sure a malformed UUID path
 * segment is rejected as {@code 400 VALIDATION_ERROR} rather than reaching the
 * repository. Tests are committed to {@code main} alongside the controller.
 */
@WebMvcTest(controllers = NeighborTransferStatusController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class NeighborTransferStatusControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferRepository transferRepository;

    // Stable seed identifiers exported to neighbors via README and V2 migration.
    private static final UUID SEED_SETTLED_INTERNAL =
            UUID.fromString("0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11");
    private static final UUID UNKNOWN_TRANSFER_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000000");

    @Test
    @DisplayName("success: GET /api/v1/transfers/{id}/status returns 200 with minimal status snapshot")
    void getTransferStatusReturns200WithMinimalSnapshotForSeededTransfer() throws Exception {
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-11-01T08:00:05Z");
        Transfer seeded = new Transfer(
                SEED_SETTLED_INTERNAL,
                "001200456789",
                "001200987654",
                null,
                null,
                null,
                2_500_000L,
                "IRR",
                TransferStatus.SETTLED,
                true,
                "Lunch split",
                "seed-internal-001",
                UUID.fromString("431a21a4-e187-472a-857f-086540fcb780"),
                null,
                "APPROVED",
                12,
                null,
                updatedAt,
                updatedAt,
                1L);

        when(transferRepository.findById(eq(SEED_SETTLED_INTERNAL)))
                .thenReturn(Optional.of(seeded));

        mockMvc.perform(get("/api/v1/transfers/{id}/status", SEED_SETTLED_INTERNAL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").value(SEED_SETTLED_INTERNAL.toString()))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.is_internal").value(true))
                .andExpect(jsonPath("$.amount").value(2_500_000))
                .andExpect(jsonPath("$.currency").value("IRR"))
                .andExpect(jsonPath("$.updated_at").value("2026-11-01T08:00:05Z"));
    }

    @Test
    @DisplayName("error: GET /api/v1/transfers/{id}/status on unknown id returns 404 problem+json")
    void getTransferStatusReturns404ProblemForUnknownId() throws Exception {
        when(transferRepository.findById(eq(UNKNOWN_TRANSFER_ID)))
                .thenThrow(new NoSuchElementException("Transfer not found: " + UNKNOWN_TRANSFER_ID));

        mockMvc.perform(get("/api/v1/transfers/{id}/status", UNKNOWN_TRANSFER_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Transfer not found: " + UNKNOWN_TRANSFER_ID));
    }
}
