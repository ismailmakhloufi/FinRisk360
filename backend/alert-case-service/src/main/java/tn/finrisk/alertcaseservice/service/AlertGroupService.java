package tn.finrisk.alertcaseservice.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.alertcaseservice.client.AccountClient;
import tn.finrisk.alertcaseservice.client.RiskClient;
import tn.finrisk.alertcaseservice.client.TransactionClient;
import tn.finrisk.alertcaseservice.client.dto.AccountView;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.dto.TransactionView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.client.exceptions.RemoteResourceNotFoundException;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.AlertGroup;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.GroupScope;
import tn.finrisk.alertcaseservice.domain.enums.GroupStatus;
import tn.finrisk.alertcaseservice.domain.enums.Severity;
import tn.finrisk.alertcaseservice.repository.AlertGroupRepository;
import tn.finrisk.alertcaseservice.repository.AlertRepository;
import tn.finrisk.alertcaseservice.web.error.BadRequestException;
import tn.finrisk.alertcaseservice.web.error.ConflictException;

/**
 * AGREGATION ET ESCALADE DES ALERTES.
 *
 * Le probleme : 10 virements de 100 TND ouvrent 10 alertes LOW independantes.
 * Chacune prise isolement est anodine — c'est precisement le principe du
 * fractionnement. Aucune ne dit rien, et l'analyste voit du bruit plutot qu'un
 * schema. La severite d'une alerte est FIGEE a sa creation : on ne peut pas la
 * faire monter ap coup (ce serait falsifier le verdict du moteur).
 *
 * La solution : on ne touche PAS a la severite individuelle. On cree un
 * AGREGAT — le groupe — dont la severite est calculee sur l'ensemble :
 *
 *   severite(groupe) = MAX( severites individuelles,
 *                           plancher par le compteur )
 *                      plancher: count >= 5  -> au moins MEDIUM
 *                                count >= 10 -> au moins HIGH
 *
 * 10 alertes LOW deviennent donc un groupe HIGH : l'analyste voit le pattern.
 * Le verdict de chaque evaluation reste intact et lisible tel quel.
 *
 * PERIMETRE : le compte emetteur (GroupScope.ACCOUNT). C'est la ou un
 * fractionnement se produit : un meme compte qui siphonne en N pieces.
 *
 * FENETRE GLISSANTE : un groupe n'accueille que des alertes tombant dans
 * [window_start, window_start + window_hours). Passe le delai, la prochaine
 * alerte ouvre un NOUVEAU groupe : deux episodes espaces de 3 jours ne sont
 * pas le meme fractionnement.
 *
 * RESILIENCE : si transaction-service ou account-service est arrete, l'alerte
 * est creee SANS groupe (attach() rend null) et un log le signale. Creer une
 * alerte ne doit JAMAIS dependre de la disponibilite d'un service en aval —
 * sinon une panne empecherait d'ouvrir le dossier sur la panne.
 */
@Service
public class AlertGroupService {

    static final String AUDIT_GROUP_CREATED = "ALERT_GROUP_CREATED";
    static final String AUDIT_GROUP_ESCALATED = "ALERT_GROUP_ESCALATED";
    static final String AUDIT_GROUP_ACCEPTED = "ALERT_GROUP_ACCEPTED";
    static final String AUDIT_GROUP_REFUSED = "ALERT_GROUP_REFUSED";
    static final String AUDIT_GROUP_TX_CANCELLED = "ALERT_GROUP_TX_CANCELLED";

    private static final Logger log = LoggerFactory.getLogger(AlertGroupService.class);

    private final AlertGroupRepository groupRepository;
    private final AlertRepository alertRepository;
    private final TransactionClient transactionClient;
    private final AccountClient accountClient;
    private final RiskClient riskClient;
    private final AuditService auditService;

    private final int windowHours;
    private final int escalateAtCount;
    private final int escalateHighAtCount;

    public AlertGroupService(AlertGroupRepository groupRepository,
                             AlertRepository alertRepository,
                             TransactionClient transactionClient,
                             AccountClient accountClient,
                             RiskClient riskClient,
                             AuditService auditService,
                             @Value("${alert-grouping.window-hours:24}") int windowHours,
                             @Value("${alert-grouping.escalate-at-count:5}") int escalateAtCount,
                             @Value("${alert-grouping.escalate-high-at-count:10}") int escalateHighAtCount) {
        this.groupRepository = groupRepository;
        this.alertRepository = alertRepository;
        this.transactionClient = transactionClient;
        this.accountClient = accountClient;
        this.riskClient = riskClient;
        this.auditService = auditService;
        this.windowHours = windowHours;
        this.escalateAtCount = escalateAtCount;
        this.escalateHighAtCount = escalateHighAtCount;
    }

    /**
     * Rattache une alerte fraichement creee a son groupe, et le recalcule.
     *
     * @return le groupe, ou NULL si l'agregation n'a pas pu se faire
     *         (transaction-service injoignable, pas de compte emetteur...).
     *         L'alerte reste alors valide : seule la couche d'agregation manque.
     */
    @Transactional
    public AlertGroup attach(Alert alert, EvaluationView evaluation, Long userId, String ipAddress) {
        TransactionView transaction;
        try {
            transaction = transactionClient.getById(evaluation.transactionId());
        } catch (RemoteResourceNotFoundException | DownstreamUnavailableException e) {
            log.warn("Alerte {} creee SANS agregation : {}",
                    alert.getAlertCode(), e.getMessage());
            return null;
        }
        if (transaction.accountId() == null) {
            log.warn("Alerte {} creee SANS agregation : compte emetteur inconnu",
                    alert.getAlertCode());
            return null;
        }

        // Le montant est copie SUR L'ALERTE : sans elle, afficher le cumul du
        // groupe exigerait de re-interroger transaction-service a chaque
        // affichage, et le cumul disparaitrait avec ce service.
        alert.setAmount(transaction.amount());

        GroupScope scope = GroupScope.ACCOUNT;
        String scopeValue = String.valueOf(transaction.accountId());
        OffsetDateTime now = alert.getCreatedAt() != null
                ? alert.getCreatedAt()
                : OffsetDateTime.now(ZoneOffset.UTC);

        // 1. Ferme un eventuel groupe dont la fenetre est passee (idempotent).
        //    Le slot « ouvert » du perimetre est alors libre.
        groupRepository.closeExpiredOpenGroup(scope.name(), scopeValue, now);

        // 2. Ouvre le groupe s'il n'en existe aucun. ON CONFLICT DO NOTHING :
        //    si un concurrent a deja cree le groupe, on n'echoue PAS — un
        //    INSERT nu aborterait la transaction PostgreSQL et tuerait l'alerte.
        Long createdId = groupRepository.insertOpenGroupIfAbsent(
                scope.name(), scopeValue, now, windowHours,
                alert.getSeverity() == null ? Severity.LOW.name() : alert.getSeverity().name());
        if (createdId != null) {
            log.info("Groupe d'alertes ouvert : {}={} (fenetre {}h)",
                    scope, scopeValue, windowHours);
        }

        // 3. Le groupe ouvert, en projection NON GEREE. On ne charge jamais
        //    l'entite AlertGroup ici : une entite managee serait dirty-checked
        //    a la fin de la transaction (UPDATE avec @Version), et sous rafale
        //    deux transactions ecriraient la meme ligne — l'une echouerait avec
        //    StaleObjectStateException et PERDRAIT son alerte.
        var open = groupRepository.findOpenGroupRow(scope.name(), scopeValue)
                .orElse(null);
        if (open == null) {
            // Defensif : ne devrait pas arriver — l'index partiel garantit qu'un
            // groupe ouvert existe apres l'etape 2. On laisse l'alerte sans
            // agregation plutot que de faire echouer sa creation.
            log.warn("Alerte {} creee SANS agregation : groupe ouvert introuvable",
                    alert.getAlertCode());
            return null;
        }
        Long groupId = open.getGroupId();
        Severity before = Severity.valueOf(open.getSeverity());
        Long customerId = open.getCustomerId();

        // 4. CustomerId defensif : account-service peut etre arrete, le groupe
        //    reste utilisable — seule l'affichage « client X » manque. Ecriture
        //    idempotente SANS verrou optimiste (concurrent tolerated).
        if (customerId == null) {
            customerId = resolveCustomerId(transaction.accountId());
            if (customerId != null) {
                groupRepository.setCustomerIdIfAbsent(groupId, customerId);
            }
        }

        // 5. Incremente le compteur/cumul et recalcule la severite agregee en
        //    UNE statement atomique : pas de lecture-modification-ecriture, donc
        //    aucun conflit d'optimistic locking meme sous rafale.
        BigDecimal amount = transaction.amount() == null ? BigDecimal.ZERO : transaction.amount();
        AlertGroupRepository.GroupAggregateRow row = groupRepository.incrementAndEscalate(
                groupId, amount,
                alert.getSeverity() == null ? Severity.LOW.name() : alert.getSeverity().name(),
                escalateAtCount, escalateHighAtCount);
        if (row == null) {
            log.warn("Alerte {} creee SANS agregation : increment a echoue",
                    alert.getAlertCode());
            return null;
        }
        Severity after = Severity.valueOf(row.getSeverity());
        alert.setGroupId(groupId);

        if (row.getRelatedCount() == 1) {
            // Premiere alerte du groupe : elle reste OPEN — c'est le POINT
            // D'ENTREE du pattern pour l'analyste.
            auditService.record(userId, AUDIT_GROUP_CREATED, "ALERT_GROUP",
                    groupId, null,
                    describe(scope, scopeValue, row.getRelatedCount(), row.getTotalAmount()),
                    ipAddress);
        } else {
            // ABSORPTION : la 2e alerte (et les suivantes) du compte sont
            // absorbees par le groupe. Elles restent en base (membres du
            // groupe, trace regulamentaire) mais sortent de la file
            // principale — l'analyste voit le PATTERN, pas 10 doublons.
            alert.setStatus(AlertStatus.ABSORBED);
            if (before != after) {
                auditService.record(userId, AUDIT_GROUP_ESCALATED, "ALERT_GROUP",
                        groupId, before.name(), after.name(), ipAddress);
            }
        }

        // Vue COHERENTE de l'etat courant, detachee de JPA (aucun dirty-check).
        AlertGroup view = new AlertGroup();
        view.setGroupId(groupId);
        view.setScopeType(scope);
        view.setScopeValue(scopeValue);
        view.setCustomerId(customerId);
        view.setWindowStart(now);
        view.setWindowHours(windowHours);
        view.setStatus(GroupStatus.OPEN);
        view.setRelatedCount(row.getRelatedCount());
        view.setTotalAmount(row.getTotalAmount());
        view.setSeverity(after);
        view.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return view;
    }

    /**
     * DECISION DE L'ANALYSTE SUR UN GROUPE (PATCH /api/alert-groups/{id}/decision).
     *
     *   ACCEPT -> le pattern est LEGITIME (structuring declare, commerce
     *             normal...). Groupe RESOLVED, alertes membres
     *             FALSE_POSITIVE. Aucune transaction n'est touchee.
     *
     *   REFUSE -> fraude confirmee. TOUTES les transactions du groupe sont
     *             ANNULEES (et remboursees si deja reglees — cote
     *             transaction-service, miroir de la REGULATION). Groupe
     *             REFUSED, alertes membres RESOLVED (cloture FORCEE hors
     *             graphe : c'est une decision de GROUPE, pas une
     *             transition individuelle).
     *
     * REJOUABLE : l'annulation distante est idempotente (CANCELLED ->
     * CANCELLED = no-op). Si le remboursement echoue en route, la
     * transaction locale n'est PAS ecrite (rollback) et l'analyste peut
     * rejouer : les transactions deja annulees sont re-traites sans erreur.
     */
    @Transactional
    public AlertGroup decide(Long groupId, String decision, String reason,
                             Long userId, String ipAddress) {
        AlertGroup group = get(groupId);
        if (group.getStatus() != GroupStatus.OPEN) {
            throw new ConflictException("Groupe " + groupId + " deja tranche : "
                    + group.getStatus().name());
        }
        boolean refuse = switch (decision == null ? "" : decision.trim().toUpperCase()) {
            case "REFUSE" -> true;
            case "ACCEPT" -> false;
            default -> throw new BadRequestException("Decision inconnue : " + decision
                    + " (ACCEPT ou REFUSE attendu)");
        };
        List<Alert> members = alertRepository.findByGroupIdOrderByCreatedAtAsc(groupId);

        if (refuse) {
            // 1. Annulation + remboursement de CHAQUE transaction du groupe.
            //    Ordre chronologique : la plus ancienne part en premier.
            for (Alert member : members) {
                EvaluationView evaluation = riskClient.getEvaluationById(member.getAssessmentId());
                transactionClient.cancelAndRefund(evaluation.transactionId());
                auditService.record(userId, AUDIT_GROUP_TX_CANCELLED, "TRANSACTION",
                        evaluation.transactionId(), null, "CANCELLED", ipAddress);
            }
            // 2. Groupe -> REFUSED (premier decisionnaire gagne : 0 -> 409).
            if (groupRepository.closeGroupIfOpen(groupId, GroupStatus.REFUSED.name()) == 0) {
                throw new ConflictException("Groupe " + groupId
                        + " deja tranche par un concurrent");
            }
            // 3. Alertes membres -> RESOLVED (cloture forcee, hors graphe).
            for (Alert member : members) {
                forceClose(member, AlertStatus.RESOLVED);
            }
            auditService.record(userId, AUDIT_GROUP_REFUSED, "ALERT_GROUP", groupId,
                    GroupStatus.OPEN.name(),
                    "REFUSED : " + members.size() + " transaction(s) annulee(s), cumul "
                            + group.getTotalAmount() + " TND"
                            + (reason == null || reason.isBlank() ? "" : " — motif : " + reason),
                    ipAddress);
        } else {
            // ACCEPT : pattern legitime. Groupe RESOLVED, alertes ->
            // FALSE_POSITIVE (le verdict du moteur etait motives, mais le
            // contexte du groupe est innocent).
            if (groupRepository.closeGroupIfOpen(groupId, GroupStatus.RESOLVED.name()) == 0) {
                throw new ConflictException("Groupe " + groupId
                        + " deja tranche par un concurrent");
            }
            for (Alert member : members) {
                forceClose(member, AlertStatus.FALSE_POSITIVE);
            }
            auditService.record(userId, AUDIT_GROUP_ACCEPTED, "ALERT_GROUP", groupId,
                    GroupStatus.OPEN.name(),
                    "ACCEPTED : pattern legitime, " + members.size() + " alerte(s) close(s)"
                            + (reason == null || reason.isBlank() ? "" : " — motif : " + reason),
                    ipAddress);
        }

        // Vue fraiche (l'entite grouperee est devenue stale apres la native
        // query) : on reconstruit sans relecture pour eviter tout dirty-check.
        AlertGroup view = new AlertGroup();
        view.setGroupId(group.getGroupId());
        view.setScopeType(group.getScopeType());
        view.setScopeValue(group.getScopeValue());
        view.setCustomerId(group.getCustomerId());
        view.setWindowStart(group.getWindowStart());
        view.setWindowHours(group.getWindowHours());
        view.setStatus(refuse ? GroupStatus.REFUSED : GroupStatus.RESOLVED);
        view.setRelatedCount(group.getRelatedCount());
        view.setTotalAmount(group.getTotalAmount());
        view.setSeverity(group.getSeverity());
        view.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return view;
    }

    /**
     * Cloture FORCEE d'une alerte membre d'un groupe decide. Hors graphe
     * AlertStatus.canMoveTo : la decision porte sur le GROUPE, et les
     * alertes absorbees (ABSORBED, terminal) doivent pouvoir etre closees
     * avec le lot. Defensif : une alerte deja terminee n'est pas re-close.
     */
    private void forceClose(Alert alert, AlertStatus target) {
        if (alert.getStatus() != null && alert.getStatus().isTerminal()
                && alert.getStatus() != AlertStatus.ABSORBED) {
            return;
        }
        alert.setStatus(target);
        alert.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        alertRepository.save(alert);
    }

    /**
     * Severite planchee par le COMPTEUR, au-dela de la severite individuelle.
     * C'est la : que 5 ou 10 alertes LOW consecutives cessent d'etre ignorees.
     */
    Severity withCountFloor(Severity current, int count) {
        if (count >= escalateHighAtCount) {
            return Severity.max(current, Severity.HIGH);
        }
        if (count >= escalateAtCount) {
            return Severity.max(current, Severity.MEDIUM);
        }
        return current;
    }

    /** GET /api/alert-groups — groupes ouverts, du plus fraichement mis a jour. */
    @Transactional(readOnly = true)
    public List<AlertGroup> openGroups() {
        return groupRepository.findByStatusOrderByUpdatedAtDesc(GroupStatus.OPEN);
    }

    /** GET /api/alert-groups/{id} — 404 si inconnu. */
    @Transactional(readOnly = true)
    public AlertGroup get(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new tn.finrisk.alertcaseservice.web.error
                        .AlertNotFoundException("groupId", groupId));
    }

    /** Ids des alertes membres d'un groupe, en ordre chronologique. */
    @Transactional(readOnly = true)
    public List<Long> memberAlertIds(Long groupId) {
        return alertRepository.findByGroupIdOrderByCreatedAtAsc(groupId).stream()
                .map(Alert::getAlertId)
                .toList();
    }

    /** Defensif : account-service peut etre arrete, le groupe reste utilisable. */
    private Long resolveCustomerId(Long accountId) {
        try {
            AccountView account = accountClient.getById(accountId);
            return account == null ? null : account.customerId();
        } catch (RemoteResourceNotFoundException | DownstreamUnavailableException e) {
            log.warn("Compte {} : client emetteur non resolu ({})", accountId, e.getMessage());
            return null;
        }
    }

    /** Trace d'audit lisible a partir de l'etat APRES l'increment. */
    private static String describe(GroupScope scope, String scopeValue,
                                   int count, BigDecimal total) {
        return count + " alerte(s), cumul " + total
                + " TND, perimetre " + scope + "=" + scopeValue;
    }
}
