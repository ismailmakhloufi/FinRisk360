package tn.finrisk.alertcaseservice.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.alertcaseservice.client.TransactionClient;
import tn.finrisk.alertcaseservice.client.dto.PageView;
import tn.finrisk.alertcaseservice.domain.NightAgentDecision;
import tn.finrisk.alertcaseservice.domain.enums.AgentAction;
import tn.finrisk.alertcaseservice.repository.NightAgentDecisionRepository;

/**
 * Integration de l'agent nocturne sur finrisk_test :
 *  - persistance REELLE de night_agent_decisions (garantit aussi les
 *    GRANT de la migration 12 : le role applicatif doit pouvoir lire
 *    et ecrire — « permission denied » sur finrisk_demo/finrisk_test
 *    a deja prouve ce defaut) ;
 *  - API GET /decisions + POST /run joignables via le contexte Spring
 *    (TransactionClient MOCKE : aucune attente sur la file reelle).
 *
 * Purge complete apres chaque test (meme convention que
 * AlertCaseIntegrationTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NightAgentIntegrationTest {

    private static final long TX = 900113;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private NightAgentDecisionRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    /** La file EN ATTENTE reelle n'est pas concernee : page vide. */
    @MockitoBean
    private TransactionClient transactionClient;

    @BeforeEach
    void fileVide() {
        when(transactionClient.listByStatus(eq("PENDING"), anyInt(), anyInt()))
                .thenReturn(new PageView<>(java.util.List.of(), 0));
    }

    @AfterEach
    void purge() {
        jdbc.update("DELETE FROM night_agent_decisions WHERE transaction_id = ?", TX);
    }

    private NightAgentDecision ligne() {
        NightAgentDecision row = new NightAgentDecision();
        row.setTransactionId(TX);
        row.setAssessmentId(900114L);
        row.setAlertId(900115L);
        row.setAmount(new BigDecimal("500.00"));
        row.setRiskScore(new BigDecimal("20.00"));
        row.setRiskLevel("MEDIUM");
        row.setEngineDecision("MONITORING");
        row.setAiVerdict("INCERTAIN");
        row.setAiConfidence(0.55);
        row.setAgentAction(AgentAction.LEFT_PENDING);
        row.setAgentReason("IA hesite (confiance 55%) : laissee a l'analyste");
        row.setRunAt(OffsetDateTime.now(ZoneOffset.UTC));
        return row;
    }

    @Test
    void persistanceReelle_puisRapportLisible() throws Exception {
        repository.save(ligne());

        // NB : JsonPath rend les nombres petits en Integer — on compare
        // en int (un Long ici echouerait sur equals sans coercion).
        mvc.perform(get("/api/night-agent/decisions?page=0&size=25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].transactionId").value((int) TX))
                .andExpect(jsonPath("$.content[0].agentAction").value("LEFT_PENDING"))
                .andExpect(jsonPath("$.content[0].aiConfidence").value(0.55));
    }

    @Test
    void statutJoignable() throws Exception {
        mvc.perform(get("/api/night-agent/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)); // test profile
    }

    @Test
    void runDesactive_estRefuse() throws Exception {
        mvc.perform(post("/api/night-agent/run"))
                .andExpect(status().isConflict());
    }
}
