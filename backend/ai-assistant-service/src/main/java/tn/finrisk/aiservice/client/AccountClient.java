package tn.finrisk.aiservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.aiservice.client.dto.OwnedAccountView;
import tn.finrisk.aiservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Lecture CHEZ account-service (port 8082), par API : uniquement pour
 * remonter du compte au customer (customerId) — jamais de SQL chez eux.
 */
@Component
public class AccountClient {

    private final RestClient restClient;

    public AccountClient(@Value("${clients.account.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** GET /api/accounts/{id} */
    public OwnedAccountView getAccountById(Long accountId) {
        try {
            OwnedAccountView view = restClient.get()
                    .uri("/api/accounts/{id}", accountId)
                    .retrieve()
                    .body(OwnedAccountView.class);
            if (view == null || view.customerId() == null) {
                throw new RemoteResourceNotFoundException("account", accountId);
            }
            return view;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("account", accountId);
            }
            throw new DownstreamUnavailableException("account-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("account-service", e);
        }
    }
}
