package tn.finrisk.transactionservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import tn.finrisk.transactionservice.client.AccountClient;
import tn.finrisk.transactionservice.domain.enums.TransactionStatus;
import tn.finrisk.transactionservice.domain.enums.TransactionType;
import tn.finrisk.transactionservice.web.dto.TransactionCreateRequest;
import tn.finrisk.transactionservice.web.error.DownstreamUnavailableException;
import tn.finrisk.transactionservice.web.error.TransactionNotModifiableException;

@SpringBootTest
@ActiveProfiles("test")
class FinancialAttemptIntegrationTest {
    @Autowired TransactionService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AccountClient accounts;

    @Test
    void acceptationReussie_marqueLaTentativeTerminee() {
        Long customer = jdbc.queryForObject("INSERT INTO customers (first_name,last_name,email,status) "
                + "VALUES ('Test','Succes',?,'ACTIVE') RETURNING customer_id", Long.class,
                UUID.randomUUID() + "@example.test");
        Long account = null;
        Long transaction = null;
        try {
            account = jdbc.queryForObject("INSERT INTO accounts (customer_id,account_number,account_type,currency,balance,status) "
                    + "VALUES (?,?,'CHECKING','TND',100,'ACTIVE') RETURNING account_id", Long.class,
                    customer, "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
            transaction = service.create(new TransactionCreateRequest(account, null, BigDecimal.TEN,
                    "TND", TransactionType.TRANSFER, "TN", null, null, null, null, null, null)).transactionId();
            assertThat(service.changeStatus(transaction, TransactionStatus.COMPLETED).status())
                    .isEqualTo(TransactionStatus.COMPLETED);
            assertThat(jdbc.queryForObject("SELECT state FROM financial_attempt_guard WHERE transaction_id = ?",
                    String.class, transaction)).isEqualTo("FINISHED");
            verify(accounts, times(1)).applyBalanceIdempotent(any(), any(), any(), any());
            // L'etape est confirmee au journal (preuve d'execution durable).
            assertThat(jdbc.queryForObject(
                    "SELECT state FROM financial_operation_journal "
                            + "WHERE transaction_id = ? AND step = 'SETTLE_SOURCE'",
                    String.class, transaction)).isEqualTo("CONFIRMED");
        } finally {
            cleanup(transaction, account, customer);
        }
    }

    /**
     * REPRISE DE BOUT EN BOUT : une reponse perdue ne double JAMAIS le
     * mouvement — le rejeu de la meme cible reutilise la meme cle d'operation,
     * tandis qu'une decision opposee (annulation) reste bloquee jusqu'a
     * reconciliation.
     */
    @Test
    void reponsePerdue_rejoueAvecLaMemeCle_etBloqueLesDecisionsOpposees() {
        Long customer = jdbc.queryForObject("INSERT INTO customers (first_name,last_name,email,status) "
                + "VALUES ('Test','Incertain',?,'ACTIVE') RETURNING customer_id", Long.class,
                UUID.randomUUID() + "@example.test");
        Long account = null;
        Long transaction = null;
        try {
            account = jdbc.queryForObject("INSERT INTO accounts (customer_id,account_number,account_type,currency,balance,status) "
                    + "VALUES (?,?,'CHECKING','TND',100,'ACTIVE') RETURNING account_id", Long.class,
                    customer, "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
            transaction = service.create(new TransactionCreateRequest(account, null, BigDecimal.TEN,
                    "TND", TransactionType.TRANSFER, "TN", null, null, null, null, null, null)).transactionId();
            Long id = transaction;
            doThrow(new DownstreamUnavailableException("account-service", new RuntimeException("timeout")))
                    .doNothing()
                    .when(accounts).applyBalanceIdempotent(any(), any(), any(), any());

            // 1) reponse perdue : tentative incertaine, la tx reste PENDING.
            assertThatThrownBy(() -> service.changeStatus(id, TransactionStatus.COMPLETED))
                    .isInstanceOf(DownstreamUnavailableException.class);
            assertThat(service.getById(id).status()).isEqualTo(TransactionStatus.PENDING);
            assertThat(jdbc.queryForObject(
                    "SELECT state FROM financial_attempt_guard WHERE transaction_id = ?",
                    String.class, id)).isEqualTo("IN_PROGRESS");

            // 2) decision OPPOSEE : interdite tant que l'etat reel est inconnu.
            assertThatThrownBy(() -> service.changeStatus(id, TransactionStatus.CANCELLED))
                    .isInstanceOf(TransactionNotModifiableException.class)
                    .hasMessageContaining("MOUVEMENT_INCERTAIN");

            // 3) meme cible : REPRISE idempotente, puis tentative terminee.
            assertThat(service.changeStatus(id, TransactionStatus.COMPLETED).status())
                    .isEqualTo(TransactionStatus.COMPLETED);
            assertThat(jdbc.queryForObject(
                    "SELECT state FROM financial_attempt_guard WHERE transaction_id = ?",
                    String.class, id)).isEqualTo("FINISHED");

            ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
            verify(accounts, times(2)).applyBalanceIdempotent(keys.capture(), any(), any(), any());
            // LA preuve client : les deux envois portent la MEME cle — le
            // deduplage account-service (testes chez lui) n'applique qu'un debit.
            assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
            assertThat(jdbc.queryForObject(
                    "SELECT state FROM financial_operation_journal "
                            + "WHERE transaction_id = ? AND step = 'SETTLE_SOURCE'",
                    String.class, transaction)).isEqualTo("CONFIRMED");
        } finally {
            cleanup(transaction, account, customer);
        }
    }

    @Test
    void intentionNocturne_gardeLaPreuveEtReutiliseLesClesApresUneReponsePerdue() {
        Long customer = jdbc.queryForObject("INSERT INTO customers (first_name,last_name,email,status) "
                + "VALUES ('Test','Nuit',?,'ACTIVE') RETURNING customer_id", Long.class,
                UUID.randomUUID() + "@example.test");
        Long source = null;
        Long beneficiary = null;
        Long id = null;
        UUID intent = UUID.randomUUID();
        try {
            source = nightAccount(customer);
            beneficiary = nightAccount(customer);
            id = service.create(new TransactionCreateRequest(source, null, BigDecimal.TEN,
                    "TND", TransactionType.TRANSFER, "TN", null, null, null, null, null, null)).transactionId();
            jdbc.update("UPDATE transactions SET beneficiary_account_id = ? WHERE transaction_id = ?",
                    beneficiary, id);
            jdbc.update("INSERT INTO night_agent_validation_intents (transaction_id,intent_id,assessment_id,"
                    + "alert_id,amount,risk_level,ai_verdict,agent_reason,state) "
                    + "VALUES (?,?,1,1,10,'LOW','NORMAL','Test','PREPARED')", id, intent);
            Long txId = id;
            java.time.ZoneId tunis = java.time.ZoneId.of("Africa/Tunis");
            java.time.LocalTime start = java.time.LocalTime.of(17, 0);
            java.time.LocalTime end = java.time.LocalTime.of(8, 0);
            try (var time = mockStatic(java.time.LocalTime.class, CALLS_REAL_METHODS)) {
                time.when(() -> java.time.LocalTime.now(tunis)).thenReturn(end);
                assertThatThrownBy(() -> service.completeNightIntent(txId, intent))
                        .isInstanceOf(TransactionNotModifiableException.class);
                verifyNoInteractions(accounts);

                time.when(() -> java.time.LocalTime.now(tunis)).thenReturn(start);
                doNothing().doThrow(new DownstreamUnavailableException("account-service",
                        new RuntimeException("credit response lost"))).doNothing()
                        .when(accounts).applyBalanceIdempotent(any(), any(), any(), any());
                assertThatThrownBy(() -> service.completeNightIntent(txId, intent))
                        .isInstanceOf(DownstreamUnavailableException.class);
                assertThat(service.getById(txId).status()).isEqualTo(TransactionStatus.PENDING);
                assertThat(nightReceipt(txId)).isFalse();

                assertThat(service.completeNightIntent(txId, intent).status()).isEqualTo(TransactionStatus.COMPLETED);
                assertThat(nightReceipt(txId)).isTrue();
                assertThat(service.completeNightIntent(txId, intent).status()).isEqualTo(TransactionStatus.COMPLETED);

                ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
                verify(accounts, times(3)).applyBalanceIdempotent(keys.capture(), any(), any(), any());
                assertThat(keys.getAllValues().getFirst()).isNotEqualTo(keys.getAllValues().get(1));
                assertThat(keys.getAllValues().get(1)).isEqualTo(keys.getAllValues().getLast());
            }
        } finally {
            if (id != null) jdbc.update("DELETE FROM night_agent_validation_intents WHERE transaction_id = ?", id);
            if (beneficiary != null && id != null) {
                jdbc.update("UPDATE transactions SET beneficiary_account_id = NULL WHERE transaction_id = ?", id);
            }
            if (beneficiary != null) jdbc.update("DELETE FROM accounts WHERE account_id = ?", beneficiary);
            cleanup(id, source, customer);
        }
    }

    private Long nightAccount(Long customer) {
        return jdbc.queryForObject("INSERT INTO accounts (customer_id,account_number,account_type,currency,balance,status) "
                        + "VALUES (?,?,'CHECKING','TND',100,'ACTIVE') RETURNING account_id",
                Long.class, customer, "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
    }

    private boolean nightReceipt(Long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM night_agent_validation_intents WHERE transaction_id=?",
                Boolean.class, id));
    }

    private void cleanup(Long transaction, Long account, Long customer) {
        if (transaction != null) {
            jdbc.update("DELETE FROM financial_operation_journal WHERE transaction_id = ?", transaction);
            jdbc.update("DELETE FROM financial_attempt_guard WHERE transaction_id = ?", transaction);
            jdbc.update("DELETE FROM transactions WHERE transaction_id = ?", transaction);
        }
        if (account != null) jdbc.update("DELETE FROM accounts WHERE account_id = ?", account);
        jdbc.update("DELETE FROM customers WHERE customer_id = ?", customer);
    }
}
