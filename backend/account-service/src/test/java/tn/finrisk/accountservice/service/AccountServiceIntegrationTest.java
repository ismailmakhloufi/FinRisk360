package tn.finrisk.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.accountservice.domain.enums.AccountStatus;
import tn.finrisk.accountservice.domain.enums.AccountType;
import tn.finrisk.accountservice.web.dto.AccountCreateRequest;
import tn.finrisk.accountservice.web.dto.AccountResponse;
import tn.finrisk.accountservice.web.error.AccountNumberAlreadyExistsException;

/**
 * Tests d'INTEGRATION : le vrai Hibernate + PostgreSQL (finrisk_test).
 * @Transactional => ROLLBACK a la fin de chaque test : la base reste propre.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountServiceIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void creation_relecture_et_doublon_dans_postgres() {
        Long customerId = insertCustomer("integration@mail.tn");
        String number = "TNITEST000001";

        AccountResponse created = accountService.create(
                new AccountCreateRequest(customerId, AccountType.CHECKING, "EUR",
                        new BigDecimal("75.25"), number));

        assertThat(created.accountId()).isNotNull();
        assertThat(created.balance()).isEqualByComparingTo("75.25");
        assertThat(created.currency()).isEqualTo("EUR");
        assertThat(created.status()).isEqualTo(AccountStatus.ACTIVE);

        AccountResponse reloaded = accountService.getByNumber(number);
        assertThat(reloaded.accountId()).isEqualTo(created.accountId());
        assertThat(reloaded).isEqualTo(created);

        assertThatThrownBy(() -> accountService.create(
                new AccountCreateRequest(customerId, AccountType.SAVINGS, "TND", null, number)))
                .isInstanceOf(AccountNumberAlreadyExistsException.class);
    }

    @Test
    void filtreParClient_dansLaBasseReelle() {
        Long customerId = insertCustomer("filtre@mail.tn");
        accountService.create(
                new AccountCreateRequest(customerId, AccountType.CARD, null, null, null));
        accountService.create(
                new AccountCreateRequest(customerId, AccountType.SAVINGS, null, null, null));

        List<AccountResponse> accounts = accountService
                .getAll(org.springframework.data.domain.PageRequest.of(0, 50), customerId)
                .getContent();

        assertThat(accounts).hasSize(2);
        assertThat(accounts).allSatisfy(a -> assertThat(a.customerId()).isEqualTo(customerId));
    }

    /** Le customer_id est une FK : on cree le client directement (appartient a un autre service). */
    private Long insertCustomer(String email) {
        jdbc.update("""
                INSERT INTO customers (first_name, last_name, email, status)
                VALUES ('Integration', 'Test', ?, 'ACTIVE')
                """, email);
        Long id = jdbc.queryForObject(
                "SELECT currval(pg_get_serial_sequence('customers', 'customer_id'))", Long.class);
        assertThat(id).isNotNull();
        return id;
    }
}
