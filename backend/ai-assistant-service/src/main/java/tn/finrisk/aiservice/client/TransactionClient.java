package tn.finrisk.aiservice.client;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.aiservice.client.dto.PageView;
import tn.finrisk.aiservice.client.dto.TransactionView;
import tn.finrisk.aiservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Lecture des transactions CHEZ transaction-service (port 8083), par API.
 * Deux usages pour l'IA :
 *   - l'historique d'un compte (écart au profil, vélocité) : list(...)
 *   - le balayage global (graph : même bénéficiaire que d'autres alertes)
 */
@Component
public class TransactionClient {

    private final RestClient restClient;

    public TransactionClient(@Value("${clients.transaction.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** GET /api/transactions/{id} */
    public TransactionView getTransactionById(Long transactionId) {
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
     * GET /api/transactions?accountId=&since=&page=&size=
     * accountId/since optionnels : sans eux = balayage général (graph).
     * max 100 éléments par appel (limite du service distant).
     */
    public PageView<TransactionView> list(Long accountId, LocalDate since, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        try {
            PageView<TransactionView> result = restClient.get()
                    .uri(uri -> uri.path("/api/transactions")
                            .queryParamIfPresent("accountId", Optional.ofNullable(accountId))
                            .queryParamIfPresent("since", Optional.ofNullable(since))
                            .queryParam("page", page)
                            .queryParam("size", safeSize)
                            .build())
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<PageView<TransactionView>>() {
                    });
            if (result == null) {
                throw new DownstreamUnavailableException("transaction-service", null);
            }
            return result;
        } catch (RestClientResponseException e) {
            throw new DownstreamUnavailableException("transaction-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("transaction-service", e);
        }
    }
}
