package tn.finrisk.riskservice.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.riskservice.client.dto.AccountView;
import tn.finrisk.riskservice.client.dto.PagedView;

/**
 * Lecture des comptes CHEZ account-service (port 8082), STRICTEMENT par API.
 * Utilite : passer de accountId (porte par la transaction) a customerId (cle du profil).
 */
@Component
public class AccountClient {

    /** Garde-fou : 100 pages x 100 = 1 000 comptes max par client. */
    private static final int MAX_PAGES = 100;

    private static final int PAGE_SIZE = 100;

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

    /**
     * GET /api/accounts?customerId= (pagination).
     * Pourquoi : le profil, la velocite et les cumuls sont calcules au niveau CLIENT,
     * pas du compte : un client a 3 comptes doit etre vu en global (sinon la
     * velocite reelle est divisee par 3).
     *
     * Memes garde-fous que TransactionClient : MAX_PAGES (pas de boucle infinie
     * si l'amont ignore la pagination) + dedoublonnage par accountId (offsets
     * susceptibles de glisser entre deux pages).
     */
    public List<AccountView> listByCustomer(Long customerId) {
        Map<Long, AccountView> byId = new LinkedHashMap<>();
        int page = 0;
        while (page < MAX_PAGES) {
            int currentPage = page; // copie final : utilisable dans la lambda
            PagedView<AccountView> paged;
            try {
                paged = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/api/accounts")
                                .queryParam("customerId", customerId)
                                .queryParam("page", currentPage)
                                .queryParam("size", PAGE_SIZE)
                                .queryParam("sort", "accountId")
                                .build())
                        .retrieve()
                        .body(new ParameterizedTypeReference<PagedView<AccountView>>() {
                        });
            } catch (RestClientResponseException e) {
                throw new DownstreamUnavailableException("account-service", e);
            } catch (ResourceAccessException e) {
                throw new DownstreamUnavailableException("account-service", e);
            }
            List<AccountView> content = paged == null ? null : paged.content();
            if (content == null || content.isEmpty()) {
                break;
            }
            for (AccountView view : content) {
                if (view.accountId() != null) {
                    byId.putIfAbsent(view.accountId(), view);
                }
            }
            if (content.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return new ArrayList<>(byId.values());
    }
}
