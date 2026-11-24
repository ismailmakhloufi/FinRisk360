package tn.finrisk.transactionservice.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import tn.finrisk.transactionservice.domain.enums.TransactionChannel;
import tn.finrisk.transactionservice.domain.enums.TransactionType;

/**
 * Charge utile de creation d'une transaction (POST /api/transactions).
 * account_id et amount etre obligatoires ; merchant_id facultatif
 * (un virement de compte a compte n'a pas de marchand).
 * Les traces (ip, device) sont facultatives mais tres utiles au Risk Service (M3).
 */
public record TransactionCreateRequest(
        @NotNull(message = "l'identifiant du compte est obligatoire")
        @Positive(message = "l'identifiant du compte doit etre positif")
        Long accountId,

        Long merchantId,

        @NotNull(message = "le montant est obligatoire")
        @Positive(message = "le montant doit etre superieur a 0")
        BigDecimal amount,

        @Size(min = 3, max = 3, message = "la devise doit faire 3 lettres (ISO-4217)")
        String currency,

        @NotNull(message = "le type de transaction est obligatoire")
        TransactionType transactionType,

        @NotNull(message = "le pays est obligatoire")
        @Size(min = 2, max = 2, message = "le pays doit faire 2 lettres (ISO-3166)")
        String country,

        TransactionChannel channel,

        @Size(max = 50, message = "l'adresse IP depasse 50 caracteres")
        String ipAddress,

        @Size(max = 50, message = "l'identifiant d'appareil depasse 50 caracteres")
        String deviceId,

        @Size(max = 100, message = "le nom du beneficiaire depasse 100 caracteres")
        String beneficiaryName,

        /**
         * Compte INTERNE a crediter lors de la regulation (quand l'analyste
         * accepte). Facultatif : null = destinataire externe, on ne fait que
         * debiter le compte source. Retrouve par RIB cote simulateur.
         */
        @Positive(message = "l'identifiant du compte beneficiaire doit etre positif")
        Long beneficiaryAccountId,

        /**
         * RIB tunisien du beneficiaire (20 chiffres, cle modulo 97).
         * Si fourni, il PREVaut sur beneficiaryAccountId : le service le
         * valide et le resout (interne vs externe) avant de creer la
         * transaction. Un RIB malforme = 400, rien n'est cree.
         */
        @Size(max = 26, message = "le RIB du beneficiaire depasse 26 caracteres")
        String beneficiaryRIB) {
}
