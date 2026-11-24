package tn.finrisk.alertcaseservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import tn.finrisk.alertcaseservice.domain.enums.AgentAction;

/**
 * Entite mappee sur "night_agent_decisions" : CE QUE L'AGENT A FAIT la nuit.
 *
 * Le rapport du matin : chaque transaction en attente examinee laisse une
 * ligne — verdict moteur ET avis IA cote a cote, action prise, motif.
 * L'analyste revient, lit la colonne, et voit d'un coup d'oeil :
 *   - ce qui a ete valide pendant la nuit (VALIDATED) ;
 *   - ce qui l'attend (LEFT_PENDING, avec le motif : CRITICAL, divergence,
 *     IA inquiete, IA indisponible...).
 *
 * Politique : l'agent n'annule JAMAIS. Il valide (LOW/MEDIUM + IA NORMAL)
 * ou il laisse — la colonne agent_action ne contient que ces deux valeurs.
 */
@Entity
@Table(name = "night_agent_decisions")
public class NightAgentDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "decision_id")
    private Long decisionId;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "assessment_id")
    private Long assessmentId;

    @Column(name = "alert_id")
    private Long alertId;

    /** Copie du montant au moment de la decision (le rapport affiche sans re-appel). */
    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "risk_score", precision = 5, scale = 2)
    private BigDecimal riskScore;

    @Column(name = "risk_level", length = 20)
    private String riskLevel;

    @Column(name = "engine_decision", length = 30)
    private String engineDecision;

    /** Avis IA : NORMAL / INCERTAIN / FRAUDE_SUSPECTE (String, regle M0). */
    @Column(name = "ai_verdict", length = 30)
    private String aiVerdict;

    @Column(name = "ai_confidence")
    private Double aiConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_action", nullable = false, length = 20)
    private AgentAction agentAction;

    @Column(name = "agent_reason", nullable = false, length = 500)
    private String agentReason;

    @Column(name = "run_at", nullable = false)
    private OffsetDateTime runAt;

    public NightAgentDecision() {
    }

    @PrePersist
    void prePersist() {
        if (runAt == null) {
            runAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getDecisionId() {
        return decisionId;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public Long getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(Long assessmentId) {
        this.assessmentId = assessmentId;
    }

    public Long getAlertId() {
        return alertId;
    }

    public void setAlertId(Long alertId) {
        this.alertId = alertId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(BigDecimal riskScore) {
        this.riskScore = riskScore;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getEngineDecision() {
        return engineDecision;
    }

    public void setEngineDecision(String engineDecision) {
        this.engineDecision = engineDecision;
    }

    public String getAiVerdict() {
        return aiVerdict;
    }

    public void setAiVerdict(String aiVerdict) {
        this.aiVerdict = aiVerdict;
    }

    public Double getAiConfidence() {
        return aiConfidence;
    }

    public void setAiConfidence(Double aiConfidence) {
        this.aiConfidence = aiConfidence;
    }

    public AgentAction getAgentAction() {
        return agentAction;
    }

    public void setAgentAction(AgentAction agentAction) {
        this.agentAction = agentAction;
    }

    public String getAgentReason() {
        return agentReason;
    }

    public void setAgentReason(String agentReason) {
        this.agentReason = agentReason;
    }

    public OffsetDateTime getRunAt() {
        return runAt;
    }

    public void setRunAt(OffsetDateTime runAt) {
        this.runAt = runAt;
    }
}
