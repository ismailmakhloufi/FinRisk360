package tn.finrisk.transactionservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tn.finrisk.transactionservice.client.AccountClient;
import tn.finrisk.transactionservice.domain.enums.TransactionStatus;
import tn.finrisk.transactionservice.domain.enums.TransactionType;
import tn.finrisk.transactionservice.web.dto.TransactionCreateRequest;

/** Verifie la serialisation sur PostgreSQL ; aucun appel financier reel. */
@SpringBootTest
@ActiveProfiles("test")
class TransactionDecisionConcurrencyTest {
    @Autowired TransactionService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AccountClient accounts;
    @MockitoBean FinancialAttemptGuard financialGuard;

    @Test
    void doubleAcceptation_unSeulMouvement() throws Exception {
        Long customer = jdbc.queryForObject("INSERT INTO customers (first_name,last_name,email,status) "
                + "VALUES ('Test','Concurrence',?,'ACTIVE') RETURNING customer_id", Long.class,
                UUID.randomUUID() + "@example.test");
        Long account = null;
        Long transaction = null;
        try {
            account = jdbc.queryForObject("INSERT INTO accounts (customer_id,account_number,account_type,currency,balance,status) "
                    + "VALUES (?,?,'CHECKING','TND',100,'ACTIVE') RETURNING account_id", Long.class,
                    customer, "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
            transaction = service.create(new TransactionCreateRequest(account, null, BigDecimal.TEN,
                    "TND", TransactionType.TRANSFER, "TN", null, null, null, null, null, null)).transactionId();
            verifyNoInteractions(accounts);
            Long id = transaction;
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> { start.await(); try {
                    service.changeStatus(id, TransactionStatus.COMPLETED); return true;
                } catch (RuntimeException e) { return false; } });
                var second = executor.submit(() -> { start.await(); try {
                    service.changeStatus(id, TransactionStatus.COMPLETED); return true;
                } catch (RuntimeException e) { return false; } });
                start.countDown();
                assertThat((first.get(15, TimeUnit.SECONDS) ? 1 : 0)
                        + (second.get(15, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
            }
            verify(accounts, times(1)).applyBalanceIdempotent(any(), any(), any(), any());
            assertThat(service.getById(id).status()).isEqualTo(TransactionStatus.COMPLETED);
        } finally {
            if (transaction != null) {
                jdbc.update("DELETE FROM financial_operation_journal WHERE transaction_id = ?", transaction);
                jdbc.update("DELETE FROM financial_attempt_guard WHERE transaction_id = ?", transaction);
                jdbc.update("DELETE FROM transactions WHERE transaction_id = ?", transaction);
            }
            if (account != null) jdbc.update("DELETE FROM accounts WHERE account_id = ?", account);
            jdbc.update("DELETE FROM customers WHERE customer_id = ?", customer);
        }
    }

    @Test
    void refusAvantValidation_neDeclencheAucunMouvement() {
        Long customer = jdbc.queryForObject("INSERT INTO customers (first_name,last_name,email,status) "
                + "VALUES ('Test','Refus',?,'ACTIVE') RETURNING customer_id", Long.class,
                UUID.randomUUID() + "@example.test");
        Long account = null;
        Long transaction = null;
        try {
            account = jdbc.queryForObject("INSERT INTO accounts (customer_id,account_number,account_type,currency,balance,status) "
                    + "VALUES (?,?,'CHECKING','TND',100,'ACTIVE') RETURNING account_id", Long.class,
                    customer, "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
            transaction = service.create(new TransactionCreateRequest(account, null, BigDecimal.TEN,
                    "TND", TransactionType.TRANSFER, "TN", null, null, null, null, null, null)).transactionId();
            assertThat(service.changeStatus(transaction, TransactionStatus.CANCELLED).status())
                    .isEqualTo(TransactionStatus.CANCELLED);
            verifyNoInteractions(accounts);
        } finally {
            if (transaction != null) {
                jdbc.update("DELETE FROM financial_operation_journal WHERE transaction_id = ?", transaction);
                jdbc.update("DELETE FROM financial_attempt_guard WHERE transaction_id = ?", transaction);
                jdbc.update("DELETE FROM transactions WHERE transaction_id = ?", transaction);
            }
            if (account != null) jdbc.update("DELETE FROM accounts WHERE account_id = ?", account);
            jdbc.update("DELETE FROM customers WHERE customer_id = ?", customer);
        }
    }
}
