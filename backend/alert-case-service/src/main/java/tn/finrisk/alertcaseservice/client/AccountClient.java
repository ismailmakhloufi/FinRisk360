package tn.finrisk.alertcaseservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.alertcaseservice.client.dto.AccountView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Lecture d'un compte CHEZ account-service (port 8082), STRICTEMENT par API.
 *
 * Utilise par l'agregation d'alertes pour rattacher un groupe au CLIENT
 * emetteur. Comme TransactionClient, l'appel est DEFENSIF : si account-service
 * est arrete, le groupe est cree SANS customer_id (il reste utilisable, seule
 * l'affichage « client X » manque).
 *
 * Base-url fournie par application.yml (clients.account.base-url).
 */
@Component
public class AccountClient {

    private final RestClient restClient;

    public AccountClient(@Value("${clients.account.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** GET /api/accounts/{id} */
    public AccountView getById(Long accountId) {
        try {
            AccountView view = restClient.get()
                    .uri("/api/accounts/{id}", accountId)
                    .retrieve()
                    .body(AccountView.class);
            if (view == null) {
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
