package tn.finrisk.riskservice.web.dto;

import jakarta.validation.constraints.NotNull;

/** Charge utile d'entree de POST /api/evaluations. */
public record EvaluationRequest(
        @NotNull(message = "transactionId est obligatoire") Long transactionId) {
}
