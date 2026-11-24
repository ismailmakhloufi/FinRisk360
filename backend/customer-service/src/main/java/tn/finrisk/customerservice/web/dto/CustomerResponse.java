package tn.finrisk.customerservice.web.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import tn.finrisk.customerservice.domain.enums.CustomerStatus;
import tn.finrisk.customerservice.domain.enums.CustomerType;

/**
 * Charge utile de sortie : ce que le service choisit de montrer au client.
 * Ajouter / retirer un champ ici n'a AUCUN impact sur la base ni sur l'entite.
 */
public record CustomerResponse(
        Long customerId,
        String firstName,
        String lastName,
        String email,
        String phone,
        LocalDate dateOfBirth,
        String country,
        String city,
        CustomerType customerType,
        CustomerStatus status,
        OffsetDateTime registrationDate) {
}
