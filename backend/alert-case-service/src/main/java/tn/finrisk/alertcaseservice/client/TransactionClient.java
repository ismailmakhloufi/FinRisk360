package tn.finrisk.alertcaseservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.alertcaseservice.client.dto.PageView;
import tn.finrisk.alertcaseservice.client.dto.TransactionView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Lecture d'une transaction CHEZ transaction-service (port 8083), STRICTEMENT
 * par API : aucune table d'un autre service n'est lue en SQL (regle M0 §2).
 *
 * Utilise par l'agregation d'alertes : pour rattacher une alerte a un GROUPE,
 * il faut savoir de quel COMPTE elle part. L'appel est DEFENSIF — si
 * transaction-service est arrete, l'alerte est creee SANS groupe (voir
 * AlertGroupService) : une creation d'alerte ne doit jamais dependre de la
 * disponibilite d'un service en aval.
 *
 * Base-url fournie par application.yml (clients.transaction.base-url).
 */
@Component
public class TransactionClient {

    private final RestClient restClient;

    public TransactionClient(@Value("${clients.transaction.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** GET /api/transactions/{id} */
    public TransactionView getById(Long transactionId) {
        try {
            TransactionView view = restClient.get()
                    .uri("/api/transactions/{id}", transactionId)
                    .retrieve()
                    .body(TransactionView.class);
            if (view == null) {
                throw new RemoteResourceNotFoundException("transaction", transactionId);
            }
            return view;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("transaction", transactionId);
            }
            throw new DownstreamUnavailableException("transaction-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("transaction-service", e);
        }
    }

    /**
     * PATCH /api/transactions/{id}/status?status=CANCELLED — annulation (et
     * remboursement si la transaction etait deja reglee) demandee par la
     * DECISION DE GROUPE (refus = fraude confirmee). Idempotent cote
     * transaction-service (CANCELLED -> CANCELLED = no-op 200) : le refus de
     * groupe est rejouable apres un echec partiel.
     */
    public TransactionView cancelAndRefund(Long transactionId) {
        return changeStatus(transactionId, "CANCELLED");
    }

    /** Demande le reglement lie a une intention durable de l'agent nocturne. */
    public void completeNightIntent(long transactionId, java.util.UUID intentId) {
        restClient.put()
                .uri("/internal/night-settlements/{transactionId}/{intentId}", transactionId, intentId)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * GET /api/transactions?status=PENDING&page&size — la file que l'agent
     * nocturne balaie a chaque execution.
     */
    public PageView<TransactionView> listByStatus(String status, int page, int size) {
        try {
            PageView<TransactionView> view = restClient.get()
                    .uri("/api/transactions?status={status}&page={page}&size={size}",
                            status, page, size)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<
                            PageView<TransactionView>>() {});
            if (view == null) {
                throw new DownstreamUnavailableException("transaction-service",
                        new IllegalStateException("reponse vide"));
            }
            return view;
        } catch (RestClientResponseException e) {
            throw new DownstreamUnavailableException("transaction-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("transaction-service", e);
        }
    }

    private TransactionView changeStatus(Long transactionId, String status) {
        try {
            TransactionView view = restClient.patch()
                    .uri("/api/transactions/{id}/status?status=" + status, transactionId)
                    .retrieve()
                    .body(TransactionView.class);
            if (view == null) {
                throw new RemoteResourceNotFoundException("transaction", transactionId);
            }
            return view;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("transaction", transactionId);
            }
            throw new DownstreamUnavailableException("transaction-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("transaction-service", e);
        }
    }
}
