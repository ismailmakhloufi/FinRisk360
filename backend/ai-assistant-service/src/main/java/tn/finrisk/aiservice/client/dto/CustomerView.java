package tn.finrisk.aiservice.client.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Client lu CHEZ customer-service (GET /api/customers/{id}) : suffisant pour
 * situer l'analyse (pays, ville, type, statut). Enums distantes en String.
 */
public record CustomerView(
        Long customerId,
        String firstName,
        String lastName,
        String email,
        LocalDate dateOfBirth,
        String country,
        String city,
        String customerType,
        String status,
        OffsetDateTime registrationDate) {
}
