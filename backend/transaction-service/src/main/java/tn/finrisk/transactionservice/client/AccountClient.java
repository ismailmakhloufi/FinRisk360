package tn.finrisk.transactionservice.client;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.transactionservice.web.error.BalanceChangeRefusedException;
import tn.finrisk.transactionservice.web.error.DownstreamUnavailableException;
import tn.finrisk.transactionservice.web.error.InvalidRibException;

/**
 * Client sortant vers account-service (port 8082), par API — jamais de SQL
 * hors soi (regle M0). Sert a appliquer un mouvement de solde :
 *   - la REGULATION, quand l'analyste VALIDE (COMPLETED) : debit du compte
 *     source + credit eventuel du beneficiaire interne ;
 *   - la compensation, si le credit du beneficiaire echoue apres le debit.
 *
 * Aucun solde ne bouge a la creation de la transaction : tant que le statut
 * est PENDING, l'argent n'a pas bouge — d'ou rien a reverser si l'analyste
 * annule.
 *
 * Deux modes d'echec distincts, car le traitement differe pour l'analyste :
 *   - 4xx de account-service (compte FROZEN/CLOSED, solde insuffisant...)
 *     -> BalanceChangeRefusedException (409) : le motif est renvoye, pas de
 *        rollback technique a signaler, c'est un refus METIER.
 *   - injoignable / erreur reseau -> DownstreamUnavailableException (503).
 * Dans les deux cas le statut COMPLETED n'est PAS enregistre (rollback) :
 * jamais de transaction validee sans mouvement de solde effectue.
 */
@Component
public class AccountClient {

    private static final Logger log = LoggerFactory.getLogger(AccountClient.class);

    private final RestClient restClient;

    /** Constructeur Spring : seul celui-ci est retenu pour l'injection. */
    @Autowired
    public AccountClient(@Value("${clients.account.base-url}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl));
    }

    /** Constructeur testable : permet de brancher un MockRestServiceServer. */
    AccountClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /**
     * PATCH /api/accounts/{id}/balance — applique un mouvement de solde.
     * direction : "DEBIT"  pour prendre de l'argent (TRANSFER/WITHDRAWAL/PAYMENT),
     *             "CREDIT" pour en donner (DEPOSIT, ou beneficiaire interne).
     */
    public void applyBalance(Long accountId, BigDecimal amount, String direction) {
        try {
            restClient.patch()
                    .uri("/api/accounts/{id}/balance", accountId)
                    .body(new BalanceRequest(amount, direction))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Solde compte {} : {} {} effectue", accountId, direction, amount);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                // Une reponse 5xx ne prouve pas que le mouvement n'a pas eu lieu.
                throw new DownstreamUnavailableException("account-service", e);
            }
            String reason = extractMessage(e);
            log.warn("Mouvement de solde refuse sur account-service ({} {} {}) : {}",
                    accountId, direction, amount, reason);
            throw new BalanceChangeRefusedException(reason, e);
        } catch (RestClientException e) {
            log.error("account-service injoignable pour le mouvement {} : {}",
                    accountId, e.getMessage());
            throw new DownstreamUnavailableException("account-service", e);
        }
    }

    /**
     * Operation rejouable uniquement avec une cle stable deja conservee par
     * l'orchestrateur. Aucune nouvelle cle ne doit etre creee lors d'un retry.
     * L'appelant doit deployer la migration 13 et le nouveau account-service
     * avant d'utiliser ce contrat.
     */
    public void applyBalanceIdempotent(UUID operationId, Long accountId,
            BigDecimal amount, String direction) {
        try {
            restClient.put()
                    .uri("/api/accounts/{id}/balance-operations/{operationId}", accountId, operationId)
                    .body(new BalanceRequest(amount, direction))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new DownstreamUnavailableException("account-service", e);
            }
            throw new BalanceChangeRefusedException(extractMessage(e), e);
        } catch (RestClientException e) {
            throw new DownstreamUnavailableException("account-service", e);
        }
    }

    /**
     * GET /api/accounts/rib/{rib} — resolution d'un RIB tunisien.
     * Retourne l'identifiant du compte interne designe, ou null si le RIB
     * (bien forme) ne designe aucun compte de la banque : destinataire
     * EXTERNE (autre banque), seul le debit de la source sera applique.
     *
     * Un RIB malforme (400 cote account-service) est propage en 400 local :
     * la transaction n'est jamais creee sur un RIB saisi n'importe comment.
     */
    public Long resolveByRib(String digits20) {
        try {
            AccountInfo info = restClient.get()
                    .uri("/api/accounts/rib/{rib}", digits20)
                    .retrieve()
                    .body(AccountInfo.class);
            return info == null ? null : info.accountId();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;  // autre banque ou RIB inconnu -> externe
            }
            if (e.getStatusCode().value() == 400) {
                String reason = extractMessage(e);
                log.warn("RIB refuse par account-service ({}) : {}", digits20, reason);
                throw new InvalidRibException(reason);
            }
            log.warn("Resolution de RIB refusee ({} {}) : {}", digits20,
                    e.getStatusCode().value(), extractMessage(e));
            throw new BalanceChangeRefusedException(extractMessage(e), e);
        } catch (RestClientException e) {
            log.error("account-service injoignable pour la resolution du RIB {} : {}",
                    digits20, e.getMessage());
            throw new DownstreamUnavailableException("account-service", e);
        }
    }

    /** Corps minimal de GET /api/accounts/rib/{rib} (on n'utilise que l'id). */
    record AccountInfo(Long accountId) {
    }

    /** Le corps d'erreur d'account-service est un ErrorResponse { message }. */
    private String extractMessage(RestClientResponseException e) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = e.getResponseBodyAs(Map.class);
            Object message = body != null ? body.get("message") : null;
            if (message != null && !message.toString().isBlank()) {
                return message + " (" + e.getStatusCode().value() + ")";
            }
        } catch (Exception ignored) {
            // corps non JSON ou absent : on retombe sur le code HTTP.
        }
        return "erreur " + e.getStatusCode().value();
    }

    /** Charge utile de PATCH /api/accounts/{id}/balance (montant > 0). */
    record BalanceRequest(BigDecimal amount, String direction) {
    }
}
