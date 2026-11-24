package tn.finrisk.aiservice.web.dto;

/**
 * L'avis de l'IA, rendu À CÔTÉ du score (jamais à la place) :
 * l'analyste garde la décision finale — c'est le contrat M0.
 */
public enum AiVerdict {
    /** Rien de significatif au-delà du score des règles. */
    NORMAL,
    /** Signaux moyens mais pas assez pour alerter fort. */
    INCERTAIN,
    /** Au moins un signal fort : fraude plausible, à creuser. */
    FRAUDE_SUSPECTE
}
