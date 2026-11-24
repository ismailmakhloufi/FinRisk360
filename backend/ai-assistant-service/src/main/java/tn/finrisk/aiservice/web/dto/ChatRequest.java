package tn.finrisk.aiservice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Question du chat : sur UNE alerte, un message libre. */
public record ChatRequest(
        @NotNull(message = "alertId est obligatoire")
        Long alertId,
        @NotBlank(message = "le message ne peut pas etre vide")
        String message) {
}
