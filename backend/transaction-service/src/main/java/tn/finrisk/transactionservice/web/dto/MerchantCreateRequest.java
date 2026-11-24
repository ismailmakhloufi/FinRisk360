package tn.finrisk.transactionservice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Charge utile de creation d'un marchand (POST /api/merchants). */
public record MerchantCreateRequest(
        @NotBlank(message = "le nom du marchand est obligatoire")
        @Size(max = 100, message = "le nom depasse 100 caracteres")
        String merchantName,

        @Size(max = 50, message = "la categorie depasse 50 caracteres")
        String merchantCategory,

        @Size(min = 2, max = 2, message = "le pays doit faire 2 lettres (ISO-3166)")
        String country,

        @Size(max = 50, message = "la ville depasse 50 caracteres")
        String city) {
}
