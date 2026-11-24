package tn.finrisk.aiservice.web;

import static org.hamcrest.Matchers.closeTo;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.aiservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;
import tn.finrisk.aiservice.service.AiAnalysisService;
import tn.finrisk.aiservice.web.dto.AiVerdict;
import tn.finrisk.aiservice.web.dto.AnalysisResponse;
import tn.finrisk.aiservice.web.dto.Insight;
import tn.finrisk.aiservice.web.dto.InsightCode;
import tn.finrisk.aiservice.web.dto.InsightSeverity;

/**
 * Façade HTTP de l'IA, service de calcul MOCKÉ : on vérifie le contrat
 * (JSON, codes 200/404/503) sans appeler les 5 services distants.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiApiTest {

    @MockitoBean
    private AiAnalysisService analysisService;

    @Autowired
    private MockMvc mockMvc;

    private static AnalysisResponse sample() {
        return new AnalysisResponse(42L, 1L, new BigDecimal("87"), "HIGH", "HOLD",
                AiVerdict.FRAUDE_SUSPECTE, 0.8,
                List.of(new Insight(InsightCode.BENEFICIARY_NETWORK, InsightSeverity.HIGH,
                        "Beneficiaire sur 2 autres comptes.", "[graph] otherAccounts=[42, 43]")),
                "Score moteur 87/100 → HOLD. Avis IA : FRAUDE_SUSPECTE (confiance 0.80)",
                OffsetDateTime.parse("2026-10-06T14:00:00Z"));
    }

    @Test
    void analyzeReturnsScoreAndVerdictSideBySide() throws Exception {
        when(analysisService.analyze(42L)).thenReturn(sample());

        mockMvc.perform(post("/api/ai/alerts/42/analyze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertId").value(42))
                .andExpect(jsonPath("$.riskScore").value(87))
                .andExpect(jsonPath("$.decision").value("HOLD"))
                .andExpect(jsonPath("$.verdict").value("FRAUDE_SUSPECTE"))
                .andExpect(jsonPath("$.confidence").value(closeTo(0.8, 0.001)))
                .andExpect(jsonPath("$.insights[0].code").value("BENEFICIARY_NETWORK"))
                .andExpect(jsonPath("$.insights[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.insights[0].evidence").value("[graph] otherAccounts=[42, 43]"))
                .andExpect(jsonPath("$.explanation").value(org.hamcrest.Matchers.containsString("87")));
    }

    @Test
    void analyze404_whenAlertUnknown() throws Exception {
        when(analysisService.analyze(999L))
                .thenThrow(new RemoteResourceNotFoundException("alert", 999L));

        mockMvc.perform(post("/api/ai/alerts/999/analyze"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/ai/alerts/999/analyze"));
    }

    @Test
    void analyze503_whenDownstreamServiceDown() throws Exception {
        when(analysisService.analyze(7L))
                .thenThrow(new DownstreamUnavailableException("risk-service", null));

        mockMvc.perform(post("/api/ai/alerts/7/analyze"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"));
    }
}
