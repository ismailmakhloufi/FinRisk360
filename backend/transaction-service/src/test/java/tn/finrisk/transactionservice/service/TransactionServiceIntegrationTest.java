package tn.finrisk.transactionservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.transactionservice.client.AccountClient;
import tn.finrisk.transactionservice.domain.enums.MerchantStatus;
import tn.finrisk.transactionservice.domain.enums.TransactionChannel;
import tn.finrisk.transactionservice.domain.enums.TransactionStatus;
import tn.finrisk.transactionservice.domain.enums.TransactionType;
import tn.finrisk.transactionservice.web.dto.MerchantCreateRequest;
import tn.finrisk.transactionservice.web.dto.MerchantResponse;
import tn.finrisk.transactionservice.web.dto.TransactionCreateRequest;
import tn.finrisk.transactionservice.web.dto.TransactionResponse;
import tn.finrisk.transactionservice.web.error.MerchantNotFoundException;
import tn.finrisk.transactionservice.web.error.TransactionNotModifiableException;

/**
 * Tests d'INTEGRATION : le vrai Hibernate + PostgreSQL (finrisk_test).
 * @Transactional => ROLLBACK a la fin de chaque test : la base reste propre.
 * account-service MOCKE (@MockitoBean AccountClient) : accepter une transaction
 * declenche la REGULATION chez account-service, aucun reseau en test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionServiceIntegrationTest {

    @MockitoBean
    private AccountClient accountClient;

    @MockitoBean
    private FinancialAttemptGuard financialGuard;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private MerchantService merchantService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void creation_relecture_et_filtre_dansPostgres() {
        Long accountId = insertAccount();
        MerchantResponse merchant = merchantService.create(
                new MerchantCreateRequest("Marchand Integration", "RETAIL", "tn", "Tunis"));

        TransactionResponse created = transactionService.create(new TransactionCreateRequest(
                accountId, merchant.merchantId(), new BigDecimal("42.50"), "tnd",
                TransactionType.PAYMENT, "tn", TransactionChannel.WEB,
                "10.0.0.5", "itest-1", "Marchand Integration", null, null));

        assertThat(created.transactionId()).isNotNull();
        assertThat(created.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(created.currency()).isEqualTo("TND");
        assertThat(created.country()).isEqualTo("TN");
        assertThat(created.merchantId()).isEqualTo(merchant.merchantId());

        TransactionResponse reloaded = transactionService.getById(created.transactionId());
        assertThat(reloaded).isEqualTo(created);

        List<TransactionResponse> byAccount = transactionService
                .getAll(org.springframework.data.domain.PageRequest.of(0, 50),
                        accountId, null, null, null, null, null)
                .getContent();
        assertThat(byAccount).containsExactly(created);

        List<TransactionResponse> byStatus = transactionService
                .getAll(org.springframework.data.domain.PageRequest.of(0, 50), null,
                        TransactionStatus.PENDING, null, null, null, null)
                .getContent();
        assertThat(byStatus).contains(created);

        // Filtre "since" : la transaction d'aujourd'hui sort avec hier, pas avec demain.
        java.time.LocalDate hier = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        java.time.LocalDate demain = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
        assertThat(transactionService
                .getAll(org.springframework.data.domain.PageRequest.of(0, 50),
                        null, null, hier, null, null, null)
                .getContent()).contains(created);
        assertThat(transactionService
                .getAll(org.springframework.data.domain.PageRequest.of(0, 50),
                        null, null, demain, null, null, null)
                .getContent()).doesNotContain(created);
    }

    @Test
    void getAll_filtresNomClientEtDateDeFin_dansLaBasseReelle() {
        Long customerId = jdbc.queryForObject("""
                INSERT INTO customers (first_name, last_name, email, status)
                VALUES ('Filtre', 'Dashboard', ?, 'ACTIVE')
                RETURNING customer_id
                """, Long.class, "filtre-" + UUID.randomUUID() + "@mail.tn");
        Long accountId = jdbc.queryForObject("""
                INSERT INTO accounts (customer_id, account_number, account_type, currency, balance, status)
                VALUES (?, ?, 'CHECKING', 'TND', 100.00, 'ACTIVE')
                RETURNING account_id
                """, Long.class, customerId, "FD" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 10).toUpperCase());

        TransactionResponse trabelsi = transactionService.create(new TransactionCreateRequest(
                accountId, null, new BigDecimal("320.00"), "TND",
                TransactionType.TRANSFER, "TN", null, null, null,
                "Mohamed Trabelsi", null, null));
        TransactionResponse autre = transactionService.create(new TransactionCreateRequest(
                accountId, null, new BigDecimal("75.00"), "TND",
                TransactionType.PAYMENT, "TN", null, null, null,
                "Autre Beneficiaire", null, null));

        // beneficiaire : sous-chaine, insensible a la casse
        assertThat(transactionService
                .getAll(org.springframework.data.domain.PageRequest.of(0, 50),
                        null, null, null, "trabelsi", null, null)
                .getContent()).containsExactly(trabelsi);

        // client : tous ses comptes
        assertThat(transactionService
                .getAll(org.springframework.data.domain.PageRequest.of(0, 50),
                        null, null, null, null, customerId, null)
                .getContent()).contains(trabelsi, autre);
        // client inconnu (aucun compte) -> 0 resultat
        assertThat(transactionService
                .getAll(org.springframework.data.domain.PageRequest.of(0, 50),
                        null, null, null, null, 999999L, null)
                .getContent()).isEmpty();

        // until = jour inclusif (scope = ce client pour etre hermetique aux rows anterieurs)
        java.time.LocalDate hier = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        java.time.LocalDate demain = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
        assertThat(transactionService
                .getAll(org.springframework.data.domain.PageRequest.of(0, 50),
                        null, null, null, null, customerId, hier)
                .getContent()).isEmpty();
        assertThat(transactionService
                .getAll(org.springframework.data.domain.PageRequest.of(0, 50),
                        null, null, null, null, customerId, demain)
                .getContent()).contains(trabelsi, autre);
    }

    @Test
    void create_marchandInconnu_dansLaBasseReelle() {
        Long accountId = insertAccount();

        assertThatThrownBy(() -> transactionService.create(new TransactionCreateRequest(
                accountId, 999999L, new BigDecimal("10.00"), "TND",
                TransactionType.PAYMENT, "TN", null, null, null, null, null, null)))
                .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    void changeStatus_statutsTerminesSontDefinitifs() {
        Long accountId = insertAccount();
        TransactionResponse created = transactionService.create(new TransactionCreateRequest(
                accountId, null, new BigDecimal("15.00"), "TND",
                TransactionType.TRANSFER, "TN", null, null, null, null, null, null));

        TransactionResponse completed = transactionService.changeStatus(
                created.transactionId(), TransactionStatus.COMPLETED);
        assertThat(completed.status()).isEqualTo(TransactionStatus.COMPLETED);

        assertThatThrownBy(() -> transactionService.changeStatus(
                created.transactionId(), TransactionStatus.FAILED))
                .isInstanceOf(TransactionNotModifiableException.class);
    }

    @Test
    void create_merchantStatusEtDoublon_dansLaBasseReelle() {
        merchantService.create(new MerchantCreateRequest("Marchand Doublon", "FOOD", "TN", "Sfax"));

        assertThatThrownBy(() -> merchantService.create(
                new MerchantCreateRequest("  marchand doublon ", "RETAIL", "TN", "Tunis")))
                .isInstanceOf(tn.finrisk.transactionservice.web.error.MerchantAlreadyExistsException.class);

        List<MerchantResponse> marchands = merchantService.getAll().stream()
                .filter(m -> m.merchantName().equalsIgnoreCase("Marchand Doublon"))
                .toList();
        assertThat(marchands).hasSize(1);
        assertThat(marchands.getFirst().status()).isEqualTo(MerchantStatus.ACTIVE);
    }

    /** accounts (autre service) : on INSERTe directement la FK, comme en production. */
    private Long insertAccount() {
        Long customerId = jdbc.queryForObject("""
                INSERT INTO customers (first_name, last_name, email, status)
                VALUES ('Tx', 'Integration', ?, 'ACTIVE')
                RETURNING customer_id
                """, Long.class, "tx-" + UUID.randomUUID() + "@mail.tn");
        return jdbc.queryForObject("""
                INSERT INTO accounts (customer_id, account_number, account_type, currency, balance, status)
                VALUES (?, ?, 'CHECKING', 'TND', 100.00, 'ACTIVE')
                RETURNING account_id
                """, Long.class, customerId, "TX" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 10).toUpperCase());
    }
}
