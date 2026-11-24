package tn.finrisk.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tn.finrisk.accountservice.domain.Account;
import tn.finrisk.accountservice.domain.enums.AccountStatus;
import tn.finrisk.accountservice.domain.enums.AccountType;
import tn.finrisk.accountservice.domain.enums.BalanceDirection;
import tn.finrisk.accountservice.repository.AccountRepository;
import tn.finrisk.accountservice.web.dto.AccountCreateRequest;
import tn.finrisk.accountservice.web.dto.AccountResponse;
import tn.finrisk.accountservice.web.dto.BalanceOperationRequest;
import tn.finrisk.accountservice.web.error.AccountNotOperableException;
import tn.finrisk.accountservice.web.error.AccountNumberAlreadyExistsException;
import tn.finrisk.accountservice.web.error.AccountNotFoundException;
import tn.finrisk.accountservice.web.error.InsufficientBalanceException;
import tn.finrisk.accountservice.web.error.InvalidRibException;
import tn.finrisk.accountservice.web.mapper.AccountMapper;

/**
 * Tests UNITAIRES : aucune base, aucun HTTP. Seule la logique est verifiee.
 * Le mapper reel est utilise, seul le repository est simule (Mockito).
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, new AccountMapper(),
                mock(JdbcTemplate.class));
    }

    @Test
    void create_sansNumero_genereNumeroEtValeursParDefaut() {
        when(accountRepository.existsByAccountNumber(any())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            saved.setAccountId(42L);
            return saved;
        });

        AccountResponse response = accountService.create(
                new AccountCreateRequest(7L, AccountType.CHECKING, "tnd",
                        new BigDecimal("100.00"), null));

        assertThat(response.accountId()).isEqualTo(42L);
        assertThat(response.accountNumber()).hasSize(12);
        assertThat(response.currency()).isEqualTo("TND");
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.balance()).isEqualByComparingTo("100.00");
        assertThat(response.customerId()).isEqualTo(7L);
    }

    @Test
    void create_numeroDejaUtilise_leveConflitEtNeSauvegardePas() {
        when(accountRepository.existsByAccountNumber("TN123")).thenReturn(true);

        assertThatThrownBy(() -> accountService.create(
                new AccountCreateRequest(1L, AccountType.SAVINGS, "TND", null, "TN123")))
                .isInstanceOf(AccountNumberAlreadyExistsException.class)
                .hasMessageContaining("TN123");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void getById_identifiantInconnu_leveNotFound() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getById(99L))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void operateBalance_debitSuperieurAuSolde_leveInsufficientBalance() {
        Account account = account(1L, AccountStatus.ACTIVE, "10.00");
        when(accountRepository.findWithLockByAccountId(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.operateBalance(1L,
                new BalanceOperationRequest(new BigDecimal("50.00"), BalanceDirection.DEBIT)))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void operateBalance_compteGele_refuseOperation() {
        Account account = account(2L, AccountStatus.FROZEN, "500.00");
        when(accountRepository.findWithLockByAccountId(2L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.operateBalance(2L,
                new BalanceOperationRequest(new BigDecimal("10.00"), BalanceDirection.CREDIT)))
                .isInstanceOf(AccountNotOperableException.class)
                .hasMessageContaining("FROZEN");
    }

    @Test
    void operateBalance_credit_valideAjouteAuSolde() {
        Account account = account(3L, AccountStatus.ACTIVE, "20.00");
        when(accountRepository.findWithLockByAccountId(3L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.operateBalance(3L,
                new BalanceOperationRequest(new BigDecimal("5.50"), BalanceDirection.CREDIT));

        assertThat(response.balance()).isEqualByComparingTo("25.50");
    }

    @Test
    void changeStatus_compteCloture_refuseTouteModification() {
        Account account = account(4L, AccountStatus.CLOSED, "0.00");
        when(accountRepository.findById(4L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.changeStatus(4L, AccountStatus.ACTIVE))
                .isInstanceOf(AccountNotOperableException.class)
                .hasMessageContaining("cloture");
    }

    @Test
    void getByRib_ribInterne_retrouveLeCompte() {
        Account account = account(5L, AccountStatus.ACTIVE, "100.00");
        account.setAccountNumber("012345678901");
        when(accountRepository.findByAccountNumberIn(any()))
                .thenReturn(Optional.of(account));

        AccountResponse response = accountService.getByRib(ribFor("12345678901"));

        assertThat(response.accountId()).isEqualTo(5L);
        assertThat(response.accountNumber()).isEqualTo("012345678901");
    }

    @Test
    void getByRib_autreBanque_leveNotFound() {
        // Meme corps de compte mais banque 0207 : bien forme, autre banque.
        String foreign = ribFor("12345678901").replace("0109", "0207");
        // la cle change avec le prefixe : on la recalcule pour rester valide
        String digits = foreign.replaceAll("\\D", "");
        String body = digits.substring(0, 18);
        String key = String.format("%02d", (Long.parseLong(body) % 97) + 1);
        String valid = "TN " + body.substring(0, 4) + " " + body.substring(4, 7) + " "
                + body.substring(7, 18) + " " + key;

        assertThatThrownBy(() -> accountService.getByRib(valid))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("autre banque");
        verify(accountRepository, never()).findByAccountNumberIn(any());
    }

    @Test
    void getByRib_ribInconnuDeLaBanque_leveNotFound() {
        when(accountRepository.findByAccountNumberIn(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getByRib(ribFor("12345678901")))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("RIB");
    }

    @Test
    void getByRib_longueurFausse_leveInvalidRib() {
        assertThatThrownBy(() -> accountService.getByRib("TN 0109 001 12345"))
                .isInstanceOf(InvalidRibException.class)
                .hasMessageContaining("20 chiffres");
    }

    @Test
    void getByRib_cleFausse_leveInvalidRib() {
        String rib = ribFor("12345678901");
        String bad = rib.substring(0, rib.length() - 2) + "00";

        assertThatThrownBy(() -> accountService.getByRib(bad))
                .isInstanceOf(InvalidRibException.class)
                .hasMessageContaining("cle de controle");
    }

    /** RIB TN canonique : banque 0109, agence 001, 11 chiffres, cle mod 97. */
    private static String ribFor(String accountDigits11) {
        String body = "0109001" + accountDigits11;
        String key = String.format("%02d", (Long.parseLong(body) % 97) + 1);
        String d = body + key;
        return "TN " + d.substring(0, 4) + " " + d.substring(4, 7) + " "
                + d.substring(7, 18) + " " + d.substring(18);
    }

    private Account account(Long id, AccountStatus status, String balance) {
        Account account = new Account();
        account.setAccountId(id);
        account.setCustomerId(1L);
        account.setAccountNumber("999999999999");
        account.setAccountType(AccountType.CHECKING);
        account.setBalance(new BigDecimal(balance));
        account.setStatus(status);
        return account;
    }
}
