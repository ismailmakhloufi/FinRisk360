package tn.finrisk.alertcaseservice.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.alertcaseservice.domain.NightAgentDecision;
import tn.finrisk.alertcaseservice.domain.enums.AgentAction;
import tn.finrisk.alertcaseservice.service.NightAgentService;

/**
 * Couche Web seule (MockMvc + service MOCKE) : codes HTTP et mappage JSON
 * de l'agent nocturne. Aucune base, aucun reseau.
 */
@WebMvcTest(NightAgentController.class)
class NightAgentApiTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private NightAgentService nightAgentService;

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-01-15T12:00:00Z");

    private static NightAgentDecision row(AgentAction action) {
        NightAgentDecision row = new NightAgentDecision();
        row.setTransactionId(7L);
        row.setAssessmentId(43L);
        row.setAlertId(9L);
        row.setAmount(new BigDecimal("500.00"));
        row.setRiskLevel("LOW");
        row.setAiVerdict("NORMAL");
        row.setAgentAction(action);
        row.setAgentReason(action == AgentAction.VALIDATED
                ? "Moteur LOW + IA NORMAL (confiance 90%) : validee avec surveillance"
                : "Divergence : moteur HIGH mais IA NORMAL : revue humaine prioritaire");
        row.setRunAt(T);
        return row;
    }

    @Test
    void rapport_200_avecLignes() throws Exception {
        when(nightAgentService.report(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        row(AgentAction.VALIDATED), row(AgentAction.LEFT_PENDING))));

        mvc.perform(get("/api/night-agent/decisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].agentAction").value("VALIDATED"))
                .andExpect(jsonPath("$.content[0].agentReason", containsString("validee")))
                .andExpect(jsonPath("$.content[1].agentAction").value("LEFT_PENDING"))
                .andExpect(jsonPath("$.content[0].aiVerdict").value("NORMAL"));
    }

    @Test
    void rapport_vide_200() throws Exception {
        when(nightAgentService.report(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/night-agent/decisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void status_200() throws Exception {
        when(nightAgentService.status())
                .thenReturn(new NightAgentService.Status(true, 17, 8, false, T, false));

        mvc.perform(get("/api/night-agent/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.startHour").value(17))
                .andExpect(jsonPath("$.endHour").value(8))
                .andExpect(jsonPath("$.autoValidationEnabled").value(false))
                .andExpect(jsonPath("$.inWindowNow").value(false))
                .andExpect(jsonPath("$.lastRunAt").value("2026-01-15T12:00:00Z"));
    }

    @Test
    void run_200_synthese() throws Exception {
        when(nightAgentService.runManual())
                .thenReturn(new NightAgentService.RunSummary(5, 2, 2, 1, T));

        mvc.perform(post("/api/night-agent/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(5))
                .andExpect(jsonPath("$.validated").value(2))
                .andExpect(jsonPath("$.deferred").value(2))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.runAt").value("2026-01-15T12:00:00Z"));

        verify(nightAgentService).runManual();
    }
}
