package tn.finrisk.accountservice.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.accountservice.domain.Account;
import tn.finrisk.accountservice.domain.enums.AccountStatus;
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
 * Logique metier des comptes.
 * Regles appliquees ici (jamais dans le controleur ni dans le repository) :
 *  - le numero de compte est unique et genere cote serveur
 *  - un compte FROZEN / CLOSED n'accepte aucune operation de solde
 *  - un DEBIT ne peut pas rendre le solde negatif
 *  - suppression douce par defaut (status CLOSED) ; la suppression DURE
 *    (DELETE ?customerId=) n'est possible qu'une fois les transactions
 *    purgees (FK fk_tx_account = ON DELETE RESTRICT)
 *  - toute ecriture de solde prend un verrou pessimiste (FOR UPDATE)
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;
    private final AccountMapper accountMapper;
    private final JdbcTemplate jdbc;

    public AccountService(AccountRepository accountRepository, AccountMapper accountMapper,
                          JdbcTemplate jdbc) {
        this.accountRepository = accountRepository;
        this.accountMapper = accountMapper;
        this.jdbc = jdbc;
    }

    /** GET /api/accounts?page=0&size=20&customerId=42 */
    public Page<AccountResponse> getAll(Pageable pageable, Long customerId) {
        Page<Account> page = (customerId == null)
                ? accountRepository.findAll(pageable)
                : accountRepository.findByCustomerId(customerId, pageable);
        return page.map(accountMapper::toResponse);
    }

    /** GET /api/accounts/{id} */
    public AccountResponse getById(Long id) {
        return accountMapper.toResponse(getAccountOrThrow(id));
    }

    /** GET /api/accounts/number/{number} */
    public AccountResponse getByNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .map(accountMapper::toResponse)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    /**
     * Prefixe RIB de cette banque : banque 0109 + agence 001. Un RIB d'une
     * AUTRE banque est pourtant bien forme (sa cle modulo 97 est correcte) :
     * il designe alors un destinataire EXTERNE, pas un compte FinRisk360.
     */
    private static final String RIB_BANK_PREFIX = "0109001";

    /**
     * GET /api/accounts/rib/{rib} — resolution d'un RIB tunisien.
     *
     *   - RIB malforme (longueur ou cle)          -> 400 (InvalidRibException)
     *   - autre banque (prefixe != 0109 001)      -> 404 (beneficiaire externe)
     *   - RIB inconnu de la banque                -> 404 (beneficiaire externe)
     *   - RIB d'un compte de la banque            -> 200 (beneficiaire interne)
     *
     * rib_for n'est PAS reversible : le champ "compte" du RIB ne garde que
     * les 11 derniers chiffres du numero de compte (12 chiffres). On sonde
     * donc les 10 numeros candidats (chiffre tete 0..9) en une seule requete.
     */
    public AccountResponse getByRib(String rib) {
        String digits = rib == null ? "" : rib.replaceAll("\\D", "");
        if (digits.length() != 20) {
            throw new InvalidRibException(
                    "RIB invalide : 20 chiffres attendus (forme TN 0109 001 xxxxxxxxxx xx)");
        }
        if (!isValidRibKey(digits)) {
            throw new InvalidRibException(
                    "RIB invalide : cle de controle incorrecte (verifiez le RIB saisi)");
        }
        if (!digits.startsWith(RIB_BANK_PREFIX)) {
            // Bien forme mais pas notre banque : destinataire externe.
            throw new AccountNotFoundException("RIB " + rib + " (autre banque)");
        }
        List<String> candidates = new ArrayList<>(10);
        for (int head = 0; head <= 9; head++) {
            candidates.add(head + digits.substring(7, 18));
        }
        return accountRepository.findByAccountNumberIn(candidates).stream()
                .findFirst()
                .map(accountMapper::toResponse)
                .orElseThrow(() -> new AccountNotFoundException("RIB " + rib));
    }

    /**
     * DEUX conventions de cle modulo 97 coexistent en Tunisie :
     *   - (body % 97) + 1 : celle de NOS comptes (rib_for, banque 0109) ;
     *   - 98 - (body % 97) : la cle ISO 13616 standard des AUTRES banques.
     * Sans la deuxieme, un RIB pourtant valide d'une autre banque serait
     * rejete au lieu d'etre traite comme destinataire externe.
     */
    private static boolean isValidRibKey(String digits) {
        long body = Long.parseLong(digits.substring(0, 18));
        String provided = digits.substring(18);
        return provided.equals(String.format("%02d", (body % 97) + 1))
                || provided.equals(String.format("%02d", 98 - (body % 97)));
    }

    /** POST /api/accounts */
    @Transactional
    public AccountResponse create(AccountCreateRequest request) {
        String accountNumber = (request.accountNumber() != null && !request.accountNumber().isBlank())
                ? request.accountNumber().trim().toUpperCase()
                : generateAccountNumber();
        assertNumberIsFree(accountNumber);

        Account account = accountMapper.toEntity(request);
        account.setAccountNumber(accountNumber);
        return accountMapper.toResponse(accountRepository.save(account));
    }

    /** PATCH /api/accounts/{id}/status */
    @Transactional
    public AccountResponse changeStatus(Long id, AccountStatus status) {
        Account account = getAccountOrThrow(id);
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountNotOperableException(
                    "Compte " + account.getAccountNumber() + " cloture : statut definitif");
        }
        account.setStatus(status);
        return accountMapper.toResponse(accountRepository.save(account));
    }

    /**
     * DELETE /api/accounts?customerId= : purge les comptes d'un client.
     * Appelable UNIQUEMENT apres la purge des transactions (RESTRICT) —
     * l'ordonnancement est le travail de l'appelant (simulateur bancaire).
     */
    @Transactional
    public int deleteByCustomer(Long customerId) {
        return jdbc.update("DELETE FROM accounts WHERE customer_id = ?", customerId);
    }

    /** PATCH /api/accounts/{id}/balance */
    @Transactional
    public AccountResponse operateBalance(Long id, BalanceOperationRequest request) {
        // Verrou de ligne : deux operations simultanees sont serializees par la base.
        Account account = accountRepository.findWithLockByAccountId(id)
                .orElseThrow(() -> new AccountNotFoundException(id));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotOperableException(
                    "Compte " + account.getAccountNumber() + " " + account.getStatus()
                            + " : operation de solde interdite");
        }

        if (request.direction() == BalanceDirection.DEBIT) {
            if (account.getBalance().compareTo(request.amount()) < 0) {
                throw new InsufficientBalanceException(account.getAccountNumber());
            }
            account.setBalance(account.getBalance().subtract(request.amount()));
        } else {
            account.setBalance(account.getBalance().add(request.amount()));
        }
        return accountMapper.toResponse(accountRepository.save(account));
    }

    private Account getAccountOrThrow(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    private void assertNumberIsFree(String accountNumber) {
        if (accountRepository.existsByAccountNumber(accountNumber)) {
            throw new AccountNumberAlreadyExistsException(accountNumber);
        }
    }

    /** Numero genere par le serveur : 12 chiffres, verifie en base. */
    private String generateAccountNumber() {
        for (int attempt = 0; attempt < 10; attempt++) {
            long value = Math.abs(RANDOM.nextLong()) % 1_000_000_000_000L;
            String candidate = String.format("%012d", value);
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Impossible de generer un numero de compte libre");
    }
}
