package tn.finrisk.customerservice.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import tn.finrisk.customerservice.domain.enums.CustomerType;

/**
 * Charge utile d'entree (POST/PUT /api/customers).
 * Les annotations de validation sont executees par Bean Validation
 * AVANT d'atteindre le service : toute erreur -> 400 automatique.
 *
 * Ce qui n'est PAS ici (customer_id, status, registration_date) ne peut
 * pas etre envoye par le client : ces champs sont cotes serveur.
 */
public record CustomerRequest(
        @NotBlank(message = "le prenom est obligatoire")
        @Size(max = 50, message = "le prenom depasse 50 caracteres")
        String firstName,

        @NotBlank(message = "le nom est obligatoire")
        @Size(max = 50, message = "le nom depasse 50 caracteres")
        String lastName,

        @Email(message = "l'email doit etre une adresse valide")
        @Size(max = 100, message = "l'email depasse 100 caracteres")
        String email,

        @Size(max = 20, message = "le telephone depasse 20 caracteres")
        String phone,

        @Past(message = "la date de naissance doit etre dans le passe")
        LocalDate dateOfBirth,

        @Size(min = 2, max = 2, message = "le pays doit faire 2 lettres (ISO-3166)")
        String country,

        @Size(max = 50, message = "la ville depasse 50 caracteres")
        String city,

        CustomerType customerType) {
}
