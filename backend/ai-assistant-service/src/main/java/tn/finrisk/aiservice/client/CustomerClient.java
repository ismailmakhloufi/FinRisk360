package tn.finrisk.aiservice.client;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.aiservice.client.dto.AccountView;
import tn.finrisk.aiservice.client.dto.CustomerView;
import tn.finrisk.aiservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Lecture CHEZ customer-service (port 8081), par API : le profil du client
 * (contexte) et la liste de ses comptes — le pont vers l'historique des
 * transactions (par accountId).
 */
@Component
public class CustomerClient {

    private final RestClient restClient;

    public CustomerClient(@Value("${clients.customer.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** GET /api/customers/{id} */
    public CustomerView getCustomerById(Long customerId) {
        try {
            CustomerView view = restClient.get()
                    .uri("/api/customers/{id}", customerId)
                    .retrieve()
                    .body(CustomerView.class);
            if (view == null) {
                throw new RemoteResourceNotFoundException("customer", customerId);
            }
            return view;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("customer", customerId);
            }
            throw new DownstreamUnavailableException("customer-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("customer-service", e);
        }
    }

    /** GET /api/customers/{id}/accounts — 404 si le client est inconnu. */
    public List<AccountView> getAccountsOf(Long customerId) {
        try {
            List<AccountView> accounts = restClient.get()
                    .uri("/api/customers/{id}/accounts", customerId)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<AccountView>>() {
                    });
            return accounts == null ? List.of() : accounts;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("customer", customerId);
            }
            throw new DownstreamUnavailableException("customer-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("customer-service", e);
        }
    }
}
