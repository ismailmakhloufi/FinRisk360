package tn.finrisk.transactionservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FinancialOperationJournalIntegrationTest {
    @Autowired FinancialOperationJournal journal;
    @Autowired JdbcTemplate jdbc;

    @Test
    void detecteLesDeuxTypesDeCompensation() {
        UUID attempt = UUID.randomUUID();
        try {
            assertThat(journal.hasCompensation(attempt)).isFalse();
            journal.prepare(attempt, "REVERSE_COMPENSATION", 1, 2, BigDecimal.TEN, "CREDIT");
            assertThat(journal.hasCompensation(attempt)).isTrue();
            jdbc.update("DELETE FROM financial_operation_journal WHERE attempt_id=?", attempt);
            journal.prepare(attempt, "COMPENSATE_SOURCE", 1, 2, BigDecimal.TEN, "CREDIT");
            assertThat(journal.hasCompensation(attempt)).isTrue();
        } finally {
            jdbc.update("DELETE FROM financial_operation_journal WHERE attempt_id=?", attempt);
        }
    }

    @Test
    void repriseConserveCleEtRefuseModificationDesDonnees() {
        UUID attempt = UUID.randomUUID();
        try {
            var initial = journal.prepare(attempt, "SOURCE", 1, 2, BigDecimal.TEN, "DEBIT");
            var retry = journal.prepare(attempt, "SOURCE", 1, 2, new BigDecimal("10.00"), "DEBIT");
            assertThat(initial.state()).isEqualTo("PREPARED");
            assertThat(retry.operationId()).isEqualTo(initial.operationId());
            assertThatThrownBy(() -> journal.prepare(attempt, "SOURCE", 1, 2,
                    new BigDecimal("11"), "DEBIT")).isInstanceOf(IllegalStateException.class);
            journal.confirm(attempt, "SOURCE", initial.operationId());
            assertThat(journal.prepare(attempt, "SOURCE", 1, 2, BigDecimal.TEN, "DEBIT").state())
                    .isEqualTo("CONFIRMED");
        } finally {
            jdbc.update("DELETE FROM financial_operation_journal WHERE attempt_id=?", attempt);
        }
    }
}
