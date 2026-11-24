package tn.finrisk.alertcaseservice.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.alertcaseservice.client.RiskClient;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.domain.Alert;
import tn.finrisk.alertcaseservice.domain.enums.AlertStatus;
import tn.finrisk.alertcaseservice.domain.enums.Severity;
import tn.finrisk.alertcaseservice.repository.AlertRepository;
import tn.finrisk.alertcaseservice.web.dto.AlertCreateRequest;
import tn.finrisk.alertcaseservice.web.error.AlertNotFoundException;
import tn.finrisk.alertcaseservice.web.error.ConflictException;

/**
 * Vie d'une alerte : creation depuis une evaluation, transitions, lectures.
 *
 * Regles metier (toutes -> 409 sauf mention) :
 *  - l'evaluation doit EXISTER chez risk-service avant d'alerter (sinon 404/503) ;
 *  - UNE SEULE alerte par evaluation (alert_code = "ALT-" + assessmentId) ;
 *  - toute transition doit suivre le graphe AlertStatus.canMoveTo ;
 *  - passage en etat terminal -> resolved_at renseigne (horloge UTC).
 *
 * Chaque mutation passe par AuditService : rien ne bouge sans trace.
 */
@Service
public class AlertService {

    static final String AUDIT_ALERT_CREATED = "ALERT_CREATED";
    static final String AUDIT_ALERT_STATUS_CHANGED = "ALERT_STATUS_CHANGED";
    static final String AUDIT_ALERT_ABSORBED = "ALERT_ABSORBED";

    private final AlertRepository alertRepository;
    private final RiskClient riskClient;
    private final AuditService auditService;
    private final AlertGroupService groupService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AlertService(AlertRepository alertRepository, RiskClient riskClient,
                        AuditService auditService, AlertGroupService groupService,
                        org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.alertRepository = alertRepository;
        this.riskClient = riskClient;
        this.auditService = auditService;
        this.groupService = groupService;
        this.jdbc = jdbc;
    }

    /**
     * POST /api/alerts : une evaluation devient une alerte.
     * 404 si l'evaluation n'existe pas chez risk-service, 409 si deja alertee.
     */
    @Transactional
    public Alert create(AlertCreateRequest request, Long userId, String ipAddress) {
        EvaluationView evaluation = riskClient.getEvaluationById(request.assessmentId());
        if (alertRepository.existsByAssessmentId(request.assessmentId())) {
            throw new ConflictException("Une alerte existe deja pour cette evaluation : assessmentId = "
                    + request.assessmentId() + " (alertCode = ALT-" + request.assessmentId() + ")");
        }
        Alert alert = new Alert();
        alert.setAssessmentId(request.assessmentId());
        alert.setAlertCode("ALT-" + request.assessmentId());
        alert.setAlertType(request.alertType());
        alert.setSeverity(request.severity() != null
                ? request.severity()
                : severityFrom(evaluation.riskLevel()));
        alert.setStatus(AlertStatus.OPEN);
        alert.setAssignedTo(request.assignedTo());
        Alert saved = alertRepository.save(alert);

        // La creation est tracee AVANT l'agregation : quoi qu'il arrive
        // ensuite (absorption, panne de transaction-service...), l'alerte
        // laisse sa trace d'ouverture.
        auditService.record(userId, AUDIT_ALERT_CREATED, "ALERT", saved.getAlertId(),
                null, AlertStatus.OPEN.name(), ipAddress);

        // Agregation : rattache l'alerte a son groupe et recalcule la severite
        // AGREEGEE. Defensif — si transaction-service est arrete, l'alerte
        // reste valide, seule la couche d'agregation manque (attach -> null).
        // Si le groupe existe deja, attach absorbe l'alerte (statut ABSORBED).
        groupService.attach(saved, evaluation, userId, ipAddress);
        if (saved.getStatus() == AlertStatus.ABSORBED) {
            auditService.record(userId, AUDIT_ALERT_ABSORBED, "ALERT", saved.getAlertId(),
                    AlertStatus.OPEN.name(), AlertStatus.ABSORBED.name(), ipAddress);
        }
        return saved;
    }

    /** PATCH /api/alerts/{id}/status : transitions selon le graphe de l'enum. */
    @Transactional
    public Alert changeStatus(Long alertId, AlertStatus target, Long userId, String ipAddress) {
        Alert alert = get(alertId);
        AlertStatus previous = alert.getStatus();
        if (!previous.canMoveTo(target)) {
            throw new ConflictException("Transition d'alerte illegale : "
                    + previous.name() + " -> " + target.name());
        }
        alert.setStatus(target);
        if (target.isTerminal()) {
            alert.setResolvedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        }
        Alert saved = alertRepository.save(alert);

        auditService.record(userId, AUDIT_ALERT_STATUS_CHANGED, "ALERT", saved.getAlertId(),
                previous.name(), target.name(), ipAddress);
        return saved;
    }

    /** GET /api/alerts/{id} — 404 si inconnue. */
    @Transactional(readOnly = true)
    public Alert get(Long alertId) {
        return alertRepository.findById(alertId)
                .orElseThrow(() -> new AlertNotFoundException("alertId", alertId));
    }

    /**
     * GET /api/alerts — page, filtree par statut si fourni.
     * SANS FILTRE : les alertes ABSORBEEs sont EXCLUES — la file principale
     * ne montre que ce qui doit etre traite (le groupe est le point
     * d'entree, pas ses doublons). Un filtre explicite status=ABSORBED les
     * reste accessible (audit / debug).
     */
    @Transactional(readOnly = true)
    public Page<Alert> page(AlertStatus status, Pageable pageable) {
        return status == null
                ? alertRepository.findByStatusNotOrderByCreatedAtDesc(AlertStatus.ABSORBED, pageable)
                : alertRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    /**
     * DELETE /api/alerts?customerId= : purge les ENQUETES puis les ALERTEES
     * d'un client, dans cet ordre (fk_cases_alert = ON DELETE RESTRICT).
     * Seules nos tables (cases, case_comments, alerts) sont ecrites ; les
     * sous-requetes ne font que LIRE risk_assessments/transactions/accounts.
     * Une trace part dans audit_logs : la purge aussi laisse une empreinte.
     */
    @Transactional
    public int deleteByCustomer(Long customerId, Long userId, String ipAddress) {
        String clientAlerts = """
                SELECT a.alert_id FROM alerts a
                JOIN risk_assessments ra ON ra.assessment_id = a.assessment_id
                JOIN transactions t ON t.transaction_id = ra.transaction_id
                JOIN accounts ac ON ac.account_id = t.account_id
                WHERE ac.customer_id = ?""";

        // Les GROUPEs qui vont perdre leurs membres : memorises AVANT la
        // suppression (fk_alerts_group = SET NULL ecrase la reference des
        // alertes une fois qu'elles partent, il faut donc les capturer d'abord).
        java.util.List<Long> doomedGroups = jdbc.queryForList(
                "SELECT DISTINCT group_id FROM alerts "
                + "WHERE group_id IS NOT NULL AND alert_id IN (" + clientAlerts + ")",
                Long.class, customerId);

        jdbc.update("DELETE FROM case_comments WHERE case_id IN "
                + "(SELECT c.case_id FROM cases c WHERE c.alert_id IN ("
                + clientAlerts + "))", customerId);
        jdbc.update("DELETE FROM cases WHERE alert_id IN (" + clientAlerts + ")", customerId);
        int deleted = jdbc.update("DELETE FROM alerts WHERE alert_id IN ("
                + clientAlerts + ")", customerId);

        // Purge des groupes devenus vides. Le NOT EXISTS rattrape aussi les
        // orphelins laisses par un precedent echec d'agregation.
        for (Long groupId : doomedGroups) {
            jdbc.update("DELETE FROM alert_groups WHERE group_id = ? "
                    + "AND NOT EXISTS (SELECT 1 FROM alerts WHERE group_id = ?)",
                    groupId, groupId);
        }

        auditService.record(userId, "ALERTS_DELETED", "CUSTOMER", customerId,
                null, deleted + " alerte(s), " + doomedGroups.size() + " groupe(s)", ipAddress);
        return deleted;
    }

    /**
     * Severite par defaut derivee du verdict distant : l'analyste n'a pas a
     * re-taper ce que le moteur a deja conclu (CRITICAL -> CRITICAL, etc.).
     */
    static Severity severityFrom(String riskLevel) {
        if (riskLevel == null) {
            return Severity.LOW;
        }
        return switch (riskLevel) {
            case "CRITICAL" -> Severity.CRITICAL;
            case "HIGH" -> Severity.HIGH;
            case "MEDIUM" -> Severity.MEDIUM;
            default -> Severity.LOW;
        };
    }
}
