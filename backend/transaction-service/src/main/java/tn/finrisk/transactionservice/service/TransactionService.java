package tn.finrisk.transactionservice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.persistence.criteria.Predicate;
import tn.finrisk.transactionservice.client.AccountClient;
import tn.finrisk.transactionservice.domain.Merchant;
import tn.finrisk.transactionservice.domain.Transaction;
import tn.finrisk.transactionservice.domain.enums.TransactionStatus;
import tn.finrisk.transactionservice.domain.enums.TransactionType;
import tn.finrisk.transactionservice.repository.MerchantRepository;
import tn.finrisk.transactionservice.repository.TransactionRepository;
import tn.finrisk.transactionservice.web.dto.TransactionCreateRequest;
import tn.finrisk.transactionservice.web.dto.TransactionResponse;
import tn.finrisk.transactionservice.web.error.InvalidRibException;
import tn.finrisk.transactionservice.web.error.BalanceChangeRefusedException;
import tn.finrisk.transactionservice.web.error.MerchantNotFoundException;
import tn.finrisk.transactionservice.web.error.TransactionNotModifiableException;
import tn.finrisk.transactionservice.web.error.TransactionNotFoundException;
import tn.finrisk.transactionservice.web.mapper.TransactionMapper;

/**
 * Logique metier des transactions (REST et SOAP partagent cette meme classe).
 * Regles appliquees ici :
 *  - montant strictement positif (DTO + CHECK SQL)
 *  - le compte exister doit exister : garantie par la FK PostgreSQL
 *  - un marchand indique doit exister dans NOTRE table -> 404
 *  - toute transaction nait PENDING, en attente d'evaluation de risque (M3)
 *  - COMPLETED / FAILED sont definitifs : aucun changement de statut ulterieur
 *  - AUCUN solde ne bouge a la creation : l'argent ne bouge qu'a l'ACCEPTATION
 *    de l'analyste (COMPLETED -> REGULATION chez account-service). Tant que le
 *    statut est PENDING, annuler ne bouge rien non plus.
 *  - la suppression DURE par client purge ICI ses transactions (la sous-requete
 *    ne fait que LIRE la table accounts du voisin : l'ecriture reste exclusive)
 */
@Service
@Transactional(readOnly = true)
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;
    private final TransactionMapper transactionMapper;
    private final JdbcTemplate jdbc;
    private final AccountClient accountClient;
    private final FinancialAttemptGuard financialGuard;
    private final FinancialOperationJournal journal;

    public TransactionService(TransactionRepository transactionRepository,
                              MerchantRepository merchantRepository,
                              TransactionMapper transactionMapper,
                              JdbcTemplate jdbc,
                              AccountClient accountClient, FinancialAttemptGuard financialGuard,
                              FinancialOperationJournal journal) {
        this.transactionRepository = transactionRepository;
        this.merchantRepository = merchantRepository;
        this.transactionMapper = transactionMapper;
        this.jdbc = jdbc;
        this.accountClient = accountClient;
        this.financialGuard = financialGuard;
        this.journal = journal;
    }

    /**
     * GET /api/transactions?page&size&accountId&status&since&beneficiary&customerId&until
     * Filtres composes dynamiquement via Specification (aucune variante de methode derivee).
     * since = date ISO (jour, inclusive, debut) ; until = jour inclusif de fin ;
     * comparaison en UTC, comme stocke transactionDate.
     * beneficiary = sous-chaine insensible a la casse sur le beneficiaire.
     * customerId = resolu en liste de comptes (lecture de la table accounts
     *   du voisin via jdbc, meme mecanisme que deleteByCustomer).
     */
    public Page<TransactionResponse> getAll(Pageable pageable, Long accountId,
                                            TransactionStatus status, LocalDate since,
                                            String beneficiary, Long customerId,
                                            LocalDate until) {
        // Resolution AVANT la Specification : la lambda ne doit pas ouvrir
        // de deuxieme connexion JDBC pendant la construction des predicats.
        List<Long> customerAccountIds = customerId == null ? null
                : jdbc.queryForList("SELECT account_id FROM accounts WHERE customer_id = ?",
                        Long.class, customerId);
        Specification<Transaction> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (accountId != null) {
                predicates.add(cb.equal(root.get("accountId"), accountId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (since != null) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("transactionDate"), since.atStartOfDay().atOffset(ZoneOffset.UTC)));
            }
            if (beneficiary != null && !beneficiary.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("beneficiaryName")),
                        "%" + beneficiary.trim().toLowerCase() + "%"));
            }
            if (customerId != null) {
                if (customerAccountIds.isEmpty()) {
                    predicates.add(cb.disjunction()); // client sans compte -> 0 resultat
                } else {
                    predicates.add(root.get("accountId").in(customerAccountIds));
                }
            }
            if (until != null) {
                predicates.add(cb.lessThan(root.get("transactionDate"),
                        until.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return transactionRepository.findAll(spec, pageable).map(transactionMapper::toResponse);
    }

    /** GET /api/transactions/{id} */
    public TransactionResponse getById(Long id) {
        return transactionRepository.findById(id)
                .map(transactionMapper::toResponse)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }

    /**
     * DELETE /api/transactions?customerId= : purge les transactions d'un client.
     * Doit preceder la suppression des comptes (fk_tx_account = RESTRICT).
     */
    @Transactional
    public int deleteByCustomer(Long customerId) {
        return jdbc.update(
                "DELETE FROM transactions WHERE account_id IN "
                        + "(SELECT account_id FROM accounts WHERE customer_id = ?)",
                customerId);
    }

    /** POST /api/transactions (REST) et façade SOAP : une seule implementation. */
    @Transactional
    public TransactionResponse create(TransactionCreateRequest request) {
        Transaction transaction = transactionMapper.toEntity(request);

        if (request.merchantId() != null) {
            Merchant merchant = merchantRepository.findById(request.merchantId())
                    .orElseThrow(() -> new MerchantNotFoundException(request.merchantId()));
            transaction.setMerchant(merchant);
        }

        applyBeneficiaryRib(transaction, request);

        return transactionMapper.toResponse(transactionRepository.save(transaction));
    }

    /**
     * RIB DU BENEFICIAIRE (virement) : la transaction n'est creee QUE si le
     * RIB est saisi correctement.
     *
     *   - 20 chiffres + cle modulo 97 correcte, sinon 400 (InvalidRib) ;
     *   - RIB trouve en base (GET /api/accounts/rib/{rib})  -> beneficiaire
     *     INTERNE : beneficiaryAccountId est resolu et le compte sera credite
     *     a la REGULATION ;
     *   - RIB bien forme mais d'une autre banque (ou inconnu) -> EXTERNE :
     *     beneficiaryAccountId reste null, seul le debit de la source s'applique.
     *
     * Quand un RIB est fourni, il PREVAUT sur un beneficiaryAccountId brut :
     * c'est le RIB qui identifie le beneficiaire, jamais un identifiant
     * interne ni un nom.
     */
    private void applyBeneficiaryRib(Transaction transaction, TransactionCreateRequest request) {
        String rib = request.beneficiaryRIB();
        if (rib == null || rib.isBlank()) {
            return;  // pas de RIB : paiement marchand, flux de demonstration...
        }

        String digits = rib.replaceAll("\\D", "");
        if (digits.length() != 20) {
            throw new InvalidRibException(
                    "RIB invalide : 20 chiffres attendus (forme TN 0109 001 xxxxxxxxxx xx)");
        }
        if (!isValidRibKey(digits)) {
            throw new InvalidRibException(
                    "RIB invalide : cle de controle incorrecte (verifiez le RIB saisi)");
        }

        Long internalAccountId = accountClient.resolveByRib(digits);  // null = externe
        if (internalAccountId != null && internalAccountId.equals(request.accountId())) {
            throw new InvalidRibException("le RIB du beneficiaire est votre compte source");
        }

        transaction.setBeneficiaryRIB(formatRib(digits));
        transaction.setBeneficiaryAccountId(internalAccountId);
    }

    /**
     * DEUX conventions de cle modulo 97 coexistent en Tunisie :
     *   - (body % 97) + 1 : celle des comptes FinRisk360 (banque 0109) ;
     *   - 98 - (body % 97) : la cle ISO 13616 standard des AUTRES banques.
     * Un RIB d'une autre banque doit passer pour BIEN FORME (il designe
     * alors un destinataire externe), pas etre rejete comme malforme.
     */
    private static boolean isValidRibKey(String digits) {
        long body = Long.parseLong(digits.substring(0, 18));
        String provided = digits.substring(18);
        return provided.equals(String.format("%02d", (body % 97) + 1))
                || provided.equals(String.format("%02d", 98 - (body % 97)));
    }

    /** 20 chiffres -> RIB canonique : banque(4) agence(3) compte(11) cle(2). */
    private static String formatRib(String digits) {
        return "TN " + digits.substring(0, 4) + " " + digits.substring(4, 7) + " "
                + digits.substring(7, 18) + " " + digits.substring(18);
    }

    /**
     * PATCH /api/transactions/{id}/status
     *
     * Regles de transition :
     *  - PENDING / HELD -> COMPLETED (REGULATION : l'argent bouge) ,
     *    FAILED, HELD, CANCELLED (aucun solde n'avait bouge) ;
     *  - COMPLETED -> CANCELLED uniquement : annulation AVEC REMBOURSEMENT
     *    (reverseSettle, miroir de la REGULATION) — c'est le refus de
     *    GROUPE (fraude confirmee) qui l'utilise ;
     *  - CANCELLED -> CANCELLED : idempotent (no-op 200) — l'orchestrateur
     *    de refus de groupe est REJOUABLE apres un echec partiel ;
     *  - FAILED et les autres cibles depuis un etat terminal : 409.
     */
    @Transactional
    public TransactionResponse changeStatus(Long id, TransactionStatus status) {
        Transaction transaction = transactionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new TransactionNotFoundException(id));
        return changeStatus(transaction, status);
    }

    @Transactional
    public TransactionResponse completeNightIntent(Long id, java.util.UUID intentId) {
        Transaction transaction = transactionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new TransactionNotFoundException(id));
        var receipts = jdbc.query(
                "SELECT completed_at IS NOT NULL FROM night_agent_validation_intents "
                        + "WHERE transaction_id = ? AND intent_id = ? AND state = 'PREPARED'",
                (rs, index) -> rs.getBoolean(1), id, intentId);
        if (receipts.isEmpty()) {
            throw new TransactionNotModifiableException(id, "INTENTION_NOCTURNE_INVALIDE");
        }
        if (receipts.getFirst()) {
            return transactionMapper.toResponse(transaction);
        }
        int hour = java.time.LocalTime.now(java.time.ZoneId.of("Africa/Tunis")).getHour();
        if ((hour >= 8 && hour < 17) || transaction.getStatus() != TransactionStatus.PENDING) {
            throw new TransactionNotModifiableException(id, "VALIDATION_NOCTURNE_NON_AUTORISEE");
        }
        TransactionResponse response = changeStatus(transaction, TransactionStatus.COMPLETED);
        jdbc.update("UPDATE night_agent_validation_intents SET completed_at = now(), updated_at = now()"
                + " WHERE transaction_id = ? AND intent_id = ?", id, intentId);
        return response;
    }

    private TransactionResponse changeStatus(Transaction transaction, TransactionStatus status) {
        Long id = transaction.getTransactionId();
        TransactionStatus current = transaction.getStatus();

        // GARDE-FOU FINANCIER + REPRISE :
        //  - tentative deja terminee (statut enregistre, marqueur non
        //    finalise) -> etat connu, on finalise le marqueur ;
        //  - meme cible, aucune compensation douteuse -> REPRISE autorisee :
        //    les cles idempotentes du journal font que le rejeu n'applique
        //    jamais deux fois le meme mouvement ;
        //  - toute AUTRE decision (annulation pendant un credit incertain)
        //    -> bloquee : reconciliation humaine.
        boolean resume = false;
        Optional<FinancialAttemptGuard.Pending> pending = financialGuard.pending(id);
        if (pending.isPresent()) {
            FinancialAttemptGuard.Pending attempt = pending.get();
            if (current.name().equals(attempt.target())) {
                financialGuard.finish(id);
            } else if (attempt.target().equals(status.name())
                    && !journal.hasCompensation(FinancialOperationJournal.attemptFor(id))) {
                resume = true;
            } else {
                throw new TransactionNotModifiableException(id,
                        "MOUVEMENT_INCERTAIN_RECONCILIATION_REQUISE");
            }
        }

        // Idempotence de l'annulation : re-CANCELLED = no-op sans solde.
        if (current == TransactionStatus.CANCELLED && status == TransactionStatus.CANCELLED) {
            return transactionMapper.toResponse(transaction);
        }

        if (current == TransactionStatus.COMPLETED) {
            if (status != TransactionStatus.CANCELLED) {
                throw new TransactionNotModifiableException(id, current.name());
            }
            // L'argent a bouge (REGULATION) : on le REVERSE avant d'annuler.
            // Si le reversement echoue, la transaction reste COMPLETED
            // (rollback) — jamais d'annulation sans remboursement effectue.
            beginFinancialAttempt(id, status, resume);
            reverseSettle(transaction);
            transaction.setStatus(status);
            return transactionMapper.toResponse(transactionRepository.save(transaction));
        }

        if (current == TransactionStatus.FAILED || current == TransactionStatus.CANCELLED) {
            throw new TransactionNotModifiableException(id, current.name());
        }

        // REGULATION : l'argent ne bouge qu'ici, a l'ACCEPTATION de l'analyste
        // (ou de l'agent dans sa fenetre). Si account-service refuse (compte
        // FROZEN, solde insuffisant...) on propage l'erreur et la transaction
        // JPA n'est PAS sauvegardee (rollback) — jamais de COMPLETED sans
        // mouvement de solde effectue.
        if (status == TransactionStatus.COMPLETED) {
            beginFinancialAttempt(id, status, resume);
            settle(transaction);
        }

        // CANCELLED depuis PENDING/HELD : RIEN a reverser, aucun solde n'avait bouge.

        transaction.setStatus(status);
        return transactionMapper.toResponse(transactionRepository.save(transaction));
    }

    /**
     * @param alreadyBegun true en REPRISE : le marqueur est deja IN_PROGRESS
     *        avec cette meme cible, on ne le re-cree pas (seul le nettoyage
     *        afterCommit est re-enregistre).
     */
    private void beginFinancialAttempt(Long id, TransactionStatus target, boolean alreadyBegun) {
        if (!alreadyBegun) {
            financialGuard.begin(id, target.name());
        }
        // Si le commit echoue, le marqueur persiste et bloque les retries
        // CONCURRENTS : la reprise ne se fait que par un nouvel appel explicite.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    financialGuard.finish(id);
                } catch (RuntimeException e) {
                    log.error("Reconciliation requise pour la transaction {} : marqueur non finalise", id, e);
                }
            }
        });
    }

    /**
     * REGULATION (M4) : applique les mouvements de solde quand la transaction
     * est VALIDEE (analyste le jour, agent nocturne dans sa fenetre).
     *
     *   DEPOSIT                         -> le compte source est CREDITE
     *   TRANSFER / WITHDRAWAL / PAYMENT -> le compte source est DEBITE
     *   + beneficiaire interne          -> il est CREDITE (si son compte a ete
     *     retrouve par RIB ; externe = null, on ne fait que debiter la source)
     *
     * Chaque etape passe d'abord par le JOURNAL (cle idempotente durable)
     * AVANT l'appel distant : un rejeu reutilise la MEME cle et
     * account-service n'applique le mouvement qu'une seule fois — c'est ce
     * qui rend la reprise apres panne sans double debit.
     *
     * Le debit de la source part en premier : c'est l'operation a risque
     * (solde insuffisant). Refus metier (4xx) du credit -> compensation,
     * journal et marqueur conserves pour reconciliation. Timeout/5xx ->
     * le credit a PU avoir lieu : aucune compensation, marqueur conserve
     * (reprise meme cible, ou reconciliation si compensation douteuse).
     */
    private void settle(Transaction transaction) {
        Long sourceId = transaction.getAccountId();
        Long beneficiaryId = transaction.getBeneficiaryAccountId();

        // Defensif : virement vers soi-meme -> mouvement net nul, rien a faire.
        if (beneficiaryId != null && beneficiaryId.equals(sourceId)) {
            return;
        }

        boolean creditsSource = transaction.getTransactionType() == TransactionType.DEPOSIT;
        String sourceDirection = creditsSource ? "CREDIT" : "DEBIT";
        BigDecimal amount = transaction.getAmount();
        UUID attempt = FinancialOperationJournal.attemptFor(transaction.getTransactionId());

        runStep(attempt, "SETTLE_SOURCE", transaction, sourceId, amount, sourceDirection);

        if (beneficiaryId != null) {
            try {
                runStep(attempt, "SETTLE_BENEFICIARY", transaction, beneficiaryId, amount, "CREDIT");
            } catch (RuntimeException refused) {
                // Timeout/5xx : le credit peut avoir ete execute. Compenser
                // immediatement pourrait creer de l'argent : laisser le
                // marqueur bloque pour reprise ou reconciliation.
                if (refused instanceof BalanceChangeRefusedException) {
                    compensateSource(attempt, transaction, sourceId, amount, creditsSource, refused);
                }
                throw refused;
            }
        }
    }

    /**
     * Une etape financiere : preparee dans le journal (REQUIRES_NEW, durable),
     * appliquee a distance avec sa cle, puis confirmee. Une etape deja
     * CONFIRMEE (reprise apres panne) est sautee sans nouvel appel ; une etape
     * PREPARED est REJOUEE avec la meme cle — account-service deduplique.
     */
    private void runStep(UUID attempt, String step, Transaction transaction, Long accountId,
                         BigDecimal amount, String direction) {
        FinancialOperationJournal.Entry entry = journal.prepare(attempt, step,
                transaction.getTransactionId(), accountId, amount, direction);
        if (journal.isConfirmed(entry)) {
            log.info("Etape {} transaction {} deja confirmee : rejeu sans effet",
                    step, transaction.getTransactionId());
            return;
        }
        accountClient.applyBalanceIdempotent(entry.operationId(), accountId, amount, direction);
        journal.confirm(attempt, step, entry.operationId());
    }

    /** Rend a la source ce qui vient d'etre retire, si le credit n'a pas pu se faire. */
    private void compensateSource(UUID attempt, Transaction transaction, Long sourceId,
                                  BigDecimal amount, boolean creditsSource,
                                  RuntimeException original) {
        String compensationDirection = creditsSource ? "DEBIT" : "CREDIT";
        try {
            runStep(attempt, "COMPENSATE_SOURCE", transaction, sourceId, amount,
                    compensationDirection);
            // Conserver journal et marqueur ensemble, meme si la compensation
            // est confirmee : une purge separee du marqueur creerait une fenetre
            // de reprise dangereuse apres panne. Reconciliation obligatoire.
        } catch (RuntimeException compensationFailure) {
            log.error("COMPENSATION ECHOUEE sur le compte {} ({} {}), anomalie a traiter "
                            + "a la main : echec initial = {}",
                    sourceId, compensationDirection, amount, original.getMessage(),
                    compensationFailure);
            // Marqueur + journal conserves : aucun rejeu sans reconciliation.
        }
    }

    /**
     * REVERSEMENT (refus de groupe = fraude confirmee) : annule une
     * transaction DEJA REGLEE. Miroir exact de settle(), dans l'ordre
     * inverse des risques :
     *
     *   1. on RE-DEBYTE le beneficiaire interne (operation a risque : il a
     *      pu depenser l'argent) — si cela echoue, la source n'est pas
     *      touchee, echec propre ;
     *   2. on re-credite la source (ou re-debite pour un DEPOSIT, dont le
     *      credit initial est ici inverse).
     *
     * Si etape 2 echoue apres l'etape 1 (refus 4xx), on compense en
     * re-creditant le beneficiaire ; journal et marqueur sont conserves.
     * Sous timeout/5xx de l'etape 2, aucune compensation (le
     * remboursement de la source a PU avoir lieu) : marqueur conserve.
     */
    private void reverseSettle(Transaction transaction) {
        Long sourceId = transaction.getAccountId();
        Long beneficiaryId = transaction.getBeneficiaryAccountId();

        // Defensif : virement vers soi-meme -> mouvement net nul, rien a faire.
        if (beneficiaryId != null && beneficiaryId.equals(sourceId)) {
            return;
        }

        boolean creditedSource = transaction.getTransactionType() == TransactionType.DEPOSIT;
        BigDecimal amount = transaction.getAmount();
        UUID attempt = FinancialOperationJournal.attemptFor(transaction.getTransactionId());

        if (beneficiaryId != null) {
            runStep(attempt, "REVERSE_BENEFICIARY", transaction, beneficiaryId, amount, "DEBIT");
        }
        try {
            runStep(attempt, "REVERSE_SOURCE", transaction, sourceId, amount,
                    creditedSource ? "DEBIT" : "CREDIT");
        } catch (RuntimeException sourceRefused) {
            if (beneficiaryId != null && sourceRefused instanceof BalanceChangeRefusedException) {
                try {
                    runStep(attempt, "REVERSE_COMPENSATION", transaction, beneficiaryId, amount, "CREDIT");
                    // Ne pas effacer l'historique du reglement ni liberer le
                    // marqueur sans procedure de reconciliation atomique.
                } catch (RuntimeException compensationFailure) {
                    log.error("COMPENSATION ECHOUEE (remboursement) sur le compte {} "
                                    + "(CREDIT {}), anomalie a traiter a la main : "
                                    + "echec initial = {}",
                            beneficiaryId, amount, sourceRefused.getMessage(),
                            compensationFailure);
                }
            }
            throw sourceRefused;
        }
    }
}
