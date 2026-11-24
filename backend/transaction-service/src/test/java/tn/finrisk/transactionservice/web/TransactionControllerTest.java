package tn.finrisk.transactionservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.transactionservice.domain.enums.TransactionChannel;
import tn.finrisk.transactionservice.domain.enums.TransactionStatus;
import tn.finrisk.transactionservice.domain.enums.TransactionType;
import tn.finrisk.transactionservice.service.TransactionService;
import tn.finrisk.transactionservice.web.dto.TransactionResponse;
import tn.finrisk.transactionservice.web.error.MerchantNotFoundException;
import tn.finrisk.transactionservice.web.error.TransactionNotFoundException;
import tn.finrisk.transactionservice.web.error.TransactionNotModifiableException;

/**
 * Tests HTTP simules : Spring MVC + Jackson + GlobalExceptionHandler,
 * mais AUCUNE logique metier (le service est un mock).
 */
@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionService transactionService;

    @MockitoBean
    private tn.finrisk.transactionservice.service.StatsService statsService;

    @Test
    void stats_routeStatique_200() throws Exception {
        when(statsService.dashboard(eq(7))).thenReturn(
                new tn.finrisk.transactionservice.web.dto.TransactionStatsResponse(
                        258,
                        List.of(new tn.finrisk.transactionservice.web.dto.TransactionStatsResponse
                                .TopBeneficiary("ACME", 9L, new BigDecimal("900"))),
                        List.of(new tn.finrisk.transactionservice.web.dto.TransactionStatsResponse
                                .DayCount("2026-10-12", 4L)),
                        new BigDecimal("1250.50"), 12.5,
                        java.util.Map.of("TRANSFER", 140L),
                        java.util.Map.of("MOBILE", 96L)));

        mockMvc.perform(get("/api/transactions/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(258))
                .andExpect(jsonPath("$.topBeneficiaries[0].name").value("ACME"))
                .andExpect(jsonPath("$.topBeneficiaries[0].count").value(9))
                .andExpect(jsonPath("$.activity[0].day").value("2026-10-12"))
                .andExpect(jsonPath("$.volume").value(1250.50))
                .andExpect(jsonPath("$.trendPct").value(12.5))
                .andExpect(jsonPath("$.byType.TRANSFER").value(140))
                .andExpect(jsonPath("$.byChannel.MOBILE").value(96));
        verify(statsService).dashboard(7);
    }

    @Test
    void stats_parametreDays_transmisAuService() throws Exception {
        when(statsService.dashboard(eq(30))).thenReturn(
                new tn.finrisk.transactionservice.web.dto.TransactionStatsResponse(
                        0, List.of(), List.of(), BigDecimal.ZERO, null,
                        java.util.Map.of(), java.util.Map.of()));

        mockMvc.perform(get("/api/transactions/stats").param("days", "30"))
                .andExpect(status().isOk());
        verify(statsService).dashboard(30);
    }

    @Test
    void stats_joursInvalides_serviceNormalise() throws Exception {
        when(statsService.dashboard(eq(-5))).thenReturn(
                new tn.finrisk.transactionservice.web.dto.TransactionStatsResponse(
                        0, List.of(), List.of(), BigDecimal.ZERO, null,
                        java.util.Map.of(), java.util.Map.of()));

        // Le contrôleur transmet tel quel ; c'est le service qui borne (>= 1 -> 7).
        mockMvc.perform(get("/api/transactions/stats").param("days", "-5"))
                .andExpect(status().isOk());
        verify(statsService).dashboard(-5);
    }

    private static final String CREATE_OK = """
            {"accountId":1,"amount":25.50,"transactionType":"PAYMENT","country":"TN"}
            """;

    @Test
    void getAll_retourneLaPage() throws Exception {
        TransactionResponse transaction = response(1L, TransactionStatus.PENDING);
        when(transactionService.getAll(any(PageRequest.class), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(null)))
                .thenReturn(new PageImpl<>(List.of(transaction), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId").value(1L))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getAll_filtresEtTriDesc_transmisAuService() throws Exception {
        PageRequest attendu = PageRequest.of(0, 25,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "transactionDate"));
        when(transactionService.getAll(eq(attendu),
                eq(null), eq(null), eq(null),
                eq("Trabelsi"), eq(7L), eq(java.time.LocalDate.of(2026, 10, 12))))
                .thenReturn(new PageImpl<>(List.of(), attendu, 0));

        mockMvc.perform(get("/api/transactions")
                        .param("beneficiary", "Trabelsi")
                        .param("customerId", "7")
                        .param("until", "2026-10-12")
                        .param("sort", "-transactionDate")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(transactionService).getAll(eq(attendu),
                eq(null), eq(null), eq(null),
                eq("Trabelsi"), eq(7L), eq(java.time.LocalDate.of(2026, 10, 12)));
    }

    @Test
    void create_donneesValides_retourne201AvecLocation() throws Exception {
        when(transactionService.create(any())).thenReturn(response(42L, TransactionStatus.PENDING));

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_OK))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/transactions/42"))
                .andExpect(jsonPath("$.transactionId").value(42L))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void create_donneesInvalides_retourne400AvecErreurs() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'accountId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'transactionType')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'country')]").exists());
    }

    @Test
    void create_marchandInconnu_retourne404() throws Exception {
        when(transactionService.create(any())).thenThrow(new MerchantNotFoundException(999L));

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_OK))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Marchand introuvable : id = 999"));
    }

    @Test
    void getById_inexistant_retourne404Formate() throws Exception {
        when(transactionService.getById(999L)).thenThrow(new TransactionNotFoundException(999L));

        mockMvc.perform(get("/api/transactions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Transaction introuvable : id = 999"))
                .andExpect(jsonPath("$.path").value("/api/transactions/999"));
    }

    @Test
    void getById_identifiantNonNumerique_retourne400() throws Exception {
        mockMvc.perform(get("/api/transactions/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parametre 'id' invalide : abc"));
    }

    @Test
    void changeStatus_transactionTerminee_retourne409() throws Exception {
        when(transactionService.changeStatus(eq(3L), eq(TransactionStatus.FAILED)))
                .thenThrow(new TransactionNotModifiableException(3L, "COMPLETED"));

        mockMvc.perform(patch("/api/transactions/3/status").param("status", "FAILED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message")
                        .value("Transaction 3 (COMPLETED) est definitive : aucune modification possible"));
    }

    private TransactionResponse response(Long id, TransactionStatus status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new TransactionResponse(id, 1L, null, new BigDecimal("25.50"), "TND",
                TransactionType.PAYMENT, "TN", now, TransactionChannel.WEB,
                null, null, null, null, status, now);
    }
}
