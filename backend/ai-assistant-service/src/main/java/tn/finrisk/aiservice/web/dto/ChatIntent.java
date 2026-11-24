package tn.finrisk.aiservice.web.dto;

/**
 * Les intentions que le chat déterministe sait servir — « répondre à toute
 * question » = toute question contenant un de ces concepts trouve une
 * réponse branchée sur les données ; sinon repli honnête (FALLBACK).
 */
public enum ChatIntent {
    SUMMARY,
    SCORE,
    FACTORS,
    WHY_SCORE,
    SIGNALS,
    VERDICT,
    DECISION,
    CONFIDENCE,
    ACTIONS,
    AMOUNT,
    BENEFICIAIRE,
    GEO,
    DEVICE,
    VELOCITY,
    TIME,
    CUSTOMER,
    ACCOUNTS,
    TRANSACTION,
    CONSTELLATION,
    EXPLAIN_ALERT,
    DATA_QUERY,
    GREETING,
    HELP,
    FALLBACK,
    LLM
}
