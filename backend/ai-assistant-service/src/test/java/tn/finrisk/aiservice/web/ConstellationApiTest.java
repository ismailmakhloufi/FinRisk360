package tn.finrisk.aiservice.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.aiservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;
import tn.finrisk.aiservice.service.ConstellationService;
import tn.finrisk.aiservice.web.dto.ConstellationEdge;
import tn.finrisk.aiservice.web.dto.ConstellationNarrative;
import tn.finrisk.aiservice.web.dto.ConstellationNode;
import tn.finrisk.aiservice.web.dto.ConstellationResponse;
import tn.finrisk.aiservice.web.dto.ScoreFactor;

/**
 * Façade HTTP de la constellation, service MOCKÉ : on vérifie le contrat
 * JSON (nœuds, arêtes, narratif, mode) sans appeler les services distants.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConstellationApiTest {

    @MockitoBean
    private ConstellationService constellationService;

    @Autowired
    private MockMvc mockMvc;

    private static ConstellationResponse sample() {
        ConstellationNode node = new ConstellationNode("ben:SARL MedTech", "BENEFICIARY",
                "SARL MedTech", "HIGH", "partagé par 3 comptes",
                Map.of("sharedAccounts", List.of(55L, 61L)));
        ConstellationEdge edge = new ConstellationEdge("tx:100->ben:SARL MedTech:TO",
                "tx:100", "ben:SARL MedTech", "TO", "bénéficiaire",
                "Virement vers SARL MedTech", 4);
        ConstellationNarrative narrative = new ConstellationNarrative(
                "Réseau de structuration détecté autour du bénéficiaire SARL MedTech.",
                List.of("Schéma de structuration entre trois comptes."),
                List.of("Vérifier le KYC des trois clients."),
                List.of("ben:SARL MedTech"), "COMPLETE");
        return new ConstellationResponse(42L, "ALT-1", List.of(node), List.of(edge),
                narrative, "LLM", OffsetDateTime.parse("2026-10-06T14:00:00Z"));
    }

    @Test
    void constellationReturnsGraphAndNarrative() throws Exception {
        when(constellationService.constellation(42L)).thenReturn(sample());

        mockMvc.perform(post("/api/ai/alerts/42/constellation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertId").value(42))
                .andExpect(jsonPath("$.alertCode").value("ALT-1"))
                .andExpect(jsonPath("$.mode").value("LLM"))
                .andExpect(jsonPath("$.nodes", hasSize(1)))
                .andExpect(jsonPath("$.nodes[0].id").value("ben:SARL MedTech"))
                .andExpect(jsonPath("$.nodes[0].type").value("BENEFICIARY"))
                .andExpect(jsonPath("$.nodes[0].risk").value("HIGH"))
                .andExpect(jsonPath("$.nodes[0].investigationFocus").value(false))
                .andExpect(jsonPath("$.edges", hasSize(1)))
                .andExpect(jsonPath("$.edges[0].type").value("TO"))
                .andExpect(jsonPath("$.edges[0].evidence").value("Virement vers SARL MedTech"))
                .andExpect(jsonPath("$.narrative.summary")
                        .value("Réseau de structuration détecté autour du bénéficiaire SARL MedTech."))
                .andExpect(jsonPath("$.narrative.hypotheses[0]")
                        .value("Schéma de structuration entre trois comptes."))
                .andExpect(jsonPath("$.narrative.recommendedActions[0]")
                        .value("Vérifier le KYC des trois clients."))
                .andExpect(jsonPath("$.narrative.focusNodes[0]").value("ben:SARL MedTech"))
                .andExpect(jsonPath("$.narrative.dataQuality").value("COMPLETE"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.hiddenSharedAccounts").value(0));
    }

    @Test
    void constellation404_whenAlertUnknown() throws Exception {
        when(constellationService.constellation(999L))
                .thenThrow(new RemoteResourceNotFoundException("alert", 999L));

        mockMvc.perform(post("/api/ai/alerts/999/constellation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void constellation503_whenDownstreamServiceDown() throws Exception {
        when(constellationService.constellation(7L))
                .thenThrow(new DownstreamUnavailableException("risk-service", null));

        mockMvc.perform(post("/api/ai/alerts/7/constellation"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void constellationExposesWhyPanelAndLlmScore() throws Exception {
        ConstellationNarrative narrative = new ConstellationNarrative(
                "Réseau autour du bénéficiaire partagé.",
                List.of("Hypothèse à vérifier."), List.of("Vérifier le KYC."),
                List.of("ben:SARL MedTech"), "COMPLETE", 55, "Signaux partagés sérieux.");
        ScoreFactor factor = new ScoreFactor("RULE-012", "Structuring", new BigDecimal("25"),
                BigDecimal.ONE, "3 opérations sous le seuil en 24h");
        ConstellationResponse response = new ConstellationResponse(42L, "ALT-1",
                sample().nodes(), sample().edges(), narrative, "LLM", false, 0,
                List.of(factor),
                "Compte TN-001 (Amina Ben Salah) a émis 12500 TND vers « SARL MedTech».",
                OffsetDateTime.parse("2026-10-06T14:00:00Z"));
        when(constellationService.constellation(42L)).thenReturn(response);

        mockMvc.perform(post("/api/ai/alerts/42/constellation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreFactors", hasSize(1)))
                .andExpect(jsonPath("$.scoreFactors[0].ruleCode").value("RULE-012"))
                .andExpect(jsonPath("$.scoreFactors[0].points").value(25))
                .andExpect(jsonPath("$.scoreFactors[0].weight").value(1))
                .andExpect(jsonPath("$.readingLine")
                        .value("Compte TN-001 (Amina Ben Salah) a émis 12500 TND vers « SARL MedTech»."))
                .andExpect(jsonPath("$.narrative.llmScore").value(55))
                .andExpect(jsonPath("$.narrative.llmScoreRationale")
                        .value("Signaux partagés sérieux."));
    }

    @Test
    void constellationDeterministicMode_whenLlmDown() throws Exception {
        ConstellationResponse det = new ConstellationResponse(42L, "ALT-1",
                sample().nodes(), sample().edges(),
                new ConstellationNarrative("Analyse déterministe du réseau.",
                        List.of("Hypothèse."), List.of("Action."), List.of(), "PARTIAL"),
                "DETERMINISTE", true, 3, OffsetDateTime.now());
        when(constellationService.constellation(42L)).thenReturn(det);

        mockMvc.perform(post("/api/ai/alerts/42/constellation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DETERMINISTE"))
                .andExpect(jsonPath("$.narrative.dataQuality").value("PARTIAL"))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.hiddenSharedAccounts").value(3));
    }
}
