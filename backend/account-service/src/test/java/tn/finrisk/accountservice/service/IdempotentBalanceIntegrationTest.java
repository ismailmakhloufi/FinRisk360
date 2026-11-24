package tn.finrisk.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import tn.finrisk.accountservice.domain.enums.AccountType;
import tn.finrisk.accountservice.domain.enums.BalanceDirection;
import tn.finrisk.accountservice.web.dto.AccountCreateRequest;
import tn.finrisk.accountservice.web.dto.BalanceOperationRequest;
import tn.finrisk.accountservice.web.error.AccountNotOperableException;
import tn.finrisk.accountservice.web.error.InsufficientBalanceException;

/** PostgreSQL reel : seuls les objets crees par chaque test sont supprimes. */
@SpringBootTest
@ActiveProfiles("test")
class IdempotentBalanceIntegrationTest {
    @Autowired private AccountService accounts;
    @Autowired private IdempotentBalanceService service;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void deuxRequetesConcurrentes_memeCle_unSeulDebit() throws Exception {
        UUID key = UUID.randomUUID();
        Long customer = jdbc.queryForObject("INSERT INTO customers (first_name, last_name, email, status) "
                + "VALUES ('Integration', 'Idempotence', ?, 'ACTIVE') RETURNING customer_id",
                Long.class, key + "@example.test");
        Long account = null;
        try {
            account = accounts.create(new AccountCreateRequest(customer, AccountType.CHECKING,
                    "TND", new BigDecimal("100.00"), null)).accountId();
            Long id = account;
            var request = new BalanceOperationRequest(new BigDecimal("10.00"), BalanceDirection.DEBIT);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> { start.await(); return service.apply(key, id, request); });
                var second = pool.submit(() -> { start.await(); return service.apply(key, id, request); });
                start.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            }
            assertThat(accounts.getById(id).balance()).isEqualByComparingTo("90.00");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_operation_keys WHERE operation_id = ?",
                    Long.class, key)).isEqualTo(1L);
            assertThatThrownBy(() -> service.apply(key, id,
                    new BalanceOperationRequest(new BigDecimal("11"), BalanceDirection.DEBIT)))
                    .isInstanceOf(AccountNotOperableException.class);
        } finally {
            jdbc.update("DELETE FROM balance_operation_keys WHERE operation_id = ?", key);
            if (account != null) jdbc.update("DELETE FROM accounts WHERE account_id = ?", account);
            jdbc.update("DELETE FROM customers WHERE customer_id = ?", customer);
        }
    }

    @Test
    void operationRefusee_neConservePasLeMarqueur() {
        UUID key = UUID.randomUUID();
        Long customer = jdbc.queryForObject("INSERT INTO customers (first_name, last_name, email, status) "
                + "VALUES ('Integration', 'Idempotence', ?, 'ACTIVE') RETURNING customer_id",
                Long.class, key + "@example.test");
        Long account = null;
        try {
            account = accounts.create(new AccountCreateRequest(customer, AccountType.CHECKING,
                    "TND", BigDecimal.ZERO, null)).accountId();
            Long id = account;
            assertThatThrownBy(() -> service.apply(key, id,
                    new BalanceOperationRequest(BigDecimal.TEN, BalanceDirection.DEBIT)))
                    .isInstanceOf(InsufficientBalanceException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_operation_keys WHERE operation_id = ?",
                    Long.class, key)).isZero();
        } finally {
            jdbc.update("DELETE FROM balance_operation_keys WHERE operation_id = ?", key);
            if (account != null) jdbc.update("DELETE FROM accounts WHERE account_id = ?", account);
            jdbc.update("DELETE FROM customers WHERE customer_id = ?", customer);
        }
    }
}
