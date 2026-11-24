package tn.finrisk.alertcaseservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class NightAgentIntentStoreIntegrationTest {
    @Autowired NightAgentIntentStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void persisteEtRefuseLesReutilisationsDifferentes() {
        long transactionId = -Math.abs(UUID.randomUUID().getMostSignificantBits() / 2) - 1;
        UUID id = UUID.randomUUID();
        var intent = new NightAgentIntentStore.Intent(transactionId, id, 2, 3, BigDecimal.TEN,
                BigDecimal.ONE, "LOW", "APPROVED", "NORMAL", 0.9, "motif", "PREPARED");
        try {
            assertThat(store.prepare(intent).intentId()).isEqualTo(id);
            assertThat(store.find(transactionId).state()).isEqualTo("PREPARED");
            assertThat(store.prepare(intent).intentId()).isEqualTo(id);
            var different = new NightAgentIntentStore.Intent(transactionId, id, 2, 3,
                    new BigDecimal("11"), BigDecimal.ONE, "LOW", "APPROVED", "NORMAL", 0.9,
                    "motif", "PREPARED");
            assertThatThrownBy(() -> store.prepare(different)).isInstanceOf(IllegalStateException.class);
            assertThat(store.find(transactionId).amount()).isEqualByComparingTo(BigDecimal.TEN);
        } finally {
            jdbc.update("DELETE FROM night_agent_validation_intents WHERE transaction_id=?", transactionId);
        }
    }

    @Test
    void intentionSurvitAuRollbackDeLaTransactionAppelante() {
        long transactionId = -Math.abs(UUID.randomUUID().getMostSignificantBits() / 2) - 1;
        UUID id = UUID.randomUUID();
        var intent = new NightAgentIntentStore.Intent(transactionId, id, 2, 3, BigDecimal.TEN,
                BigDecimal.ONE, "LOW", "APPROVED", "NORMAL", 0.9, "motif", "PREPARED");
        try {
            TransactionTemplate outer = new TransactionTemplate(transactionManager);
            assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                store.prepare(intent);
                throw new IllegalStateException("panne apres preparation");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(store.find(transactionId).intentId()).isEqualTo(id);
            assertThat(store.find(transactionId).state()).isEqualTo("PREPARED");
        } finally {
            jdbc.update("DELETE FROM night_agent_validation_intents WHERE transaction_id=?", transactionId);
        }
    }
}
