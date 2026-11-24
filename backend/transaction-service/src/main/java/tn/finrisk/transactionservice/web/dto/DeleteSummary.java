package tn.finrisk.transactionservice.web.dto;

/** Bilan d'une suppression : le nombre de lignes reellement retirees. */
public record DeleteSummary(int deleted) {
}
