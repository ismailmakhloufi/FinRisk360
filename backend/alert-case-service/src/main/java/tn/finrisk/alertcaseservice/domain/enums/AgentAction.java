package tn.finrisk.alertcaseservice.domain.enums;

/**
 * Ce que l'AGENT NOCTURNE a fait d'une transaction en attente.
 *
 * VALIDATED   : validee par l'agent (PENDING -> COMPLETED). Seulement si le
 *               moteur dit LOW/MEDIUM ET que l'IA est d'accord (NORMAL).
 * LEFT_PENDING : laissee EN ATTENTE pour l'analyste, avec motif. Tout le
 *               reste (HIGH, CRITICAL, IA inquiete ou indisponible) —
 *               l'agent ne detruit jamais d'argent, il valide ou il attend.
 */
public enum AgentAction {
    VALIDATED,
    LEFT_PENDING
}
