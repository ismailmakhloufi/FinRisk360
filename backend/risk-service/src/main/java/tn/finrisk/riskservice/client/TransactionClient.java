package tn.finrisk.riskservice.client;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.riskservice.client.dto.PagedView;
import tn.finrisk.riskservice.client.dto.TransactionView;

/**
 * Lecture des transactions CHEZ transaction-service (port 8083), STRICTEMENT par API :
 * aucune table de l'autre service n'est lue en SQL (regle M0 §2).
 *
 * Base-url fournie par application.yml (clients.transaction.base-url).
 * Erreurs traduites en exceptions metier :
 *  - 404 distant            -> RemoteResourceNotFoundException  (-> 404, etape 8)
 *  - service arrete / 5xx   -> DownstreamUnavailableException   (-> 503, etape 8)
 */
@Component
public class TransactionClient {

    /** Taille des pages demandees au remote (maximum autorise par son API). */
    private static final int PAGE_SIZE = 100;

    /** Garde-fou : 100 pages x 100 = 10 000 lignes max par compte sur 90 j. */
    private static final int MAX_PAGES = 100;

    private final RestClient restClient;

    @Autowired
    public TransactionClient(@Value("${clients.transaction.base-url}") String baseUrl) {
        // Fabrique statique : aucun bean RestClient.Builder n'est requis (Boot 4.1
        // n'en auto-configurera pas ici) et l'URL vient d'un seul endroit (application.yml).
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Usage test uniquement : permet de brancher un MockRestServiceServer. */
    TransactionClient(RestClient restClient) {
        this.restClient = restClient;
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
     * GET /api/transactions?accountId=&since=&size=100&sort=transactionDate
     * Boucle de pagination jusqu'a epuise : l'historique 90 j d'un compte actif
     * peut depasser la page maximale (100) du remote.
     *
     * Deux garde-fous :
     *  - MAX_PAGES : si l'amont ignore le param "page" et renvoie toujours la
     *    meme page pleine, la boucle s'arrete (sinon : boucle infinie / OOM) ;
     *  - dedoublonnage par transactionId : la pagination par OFFSET est
     *    susceptible de glisser si de nouvelles transactions arrivent pendant
     *    la lecture (page suivante = rangs recales) — un doublon fausserait
     *    la baseline et la velocite.
     */
    public List<TransactionView> listByAccountSince(Long accountId, LocalDate since) {
        Map<Long, TransactionView> byId = new LinkedHashMap<>();
        int page = 0;
        while (page < MAX_PAGES) {
            PagedView<TransactionView> paged = fetchPage(accountId, since, page);
            List<TransactionView> content = paged == null ? null : paged.content();
            if (content == null || content.isEmpty()) {
                break;
            }
            for (TransactionView view : content) {
                if (view.transactionId() != null) {
                    byId.putIfAbsent(view.transactionId(), view);
                }
            }
            if (content.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return new ArrayList<>(byId.values());
    }

    private PagedView<TransactionView> fetchPage(Long accountId, LocalDate since, int page) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/transactions")
                            .queryParam("accountId", accountId)
                            .queryParam("since", since.toString())
                            .queryParam("page", page)
                            .queryParam("size", PAGE_SIZE)
                            .queryParam("sort", "transactionDate")
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<PagedView<TransactionView>>() {
                    });
        } catch (RestClientResponseException e) {
            throw new DownstreamUnavailableException("transaction-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("transaction-service", e);
        }
    }

    /**
     * PATCH /api/transactions/{id}/status : applique le VERDICT du moteur.
     *
     * Le moteur decide, mais la colonne status appartient a transaction-service
     * (proprietaire exclusif de sa table, regle M0) : on lui demande donc de
     * l'ecrire plutot que de la forcer de l'exterieur.
     *
     * Cas particulier du 409 (TransactionNotModifiable) : la transaction est
     * deja terminale — un analyste a tranche avant l'evaluation. Ce n'est PAS
     * une erreur, c'est une course legitime, on l'ignore.
     */
    public void changeStatus(Long transactionId, String status) {
        try {
            restClient.patch()
                    // La variable de chemin doit être résolue par UriBuilder.build(...)
                    // : la forme varargs uri(template, args) n'accepte pas de lambda.
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/transactions/{id}/status")
                            .queryParam("status", status)
                            .build(transactionId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 409) {
                return;
            }
            if (code == 404) {
                throw new RemoteResourceNotFoundException("transaction", transactionId);
            }
            throw new DownstreamUnavailableException("transaction-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("transaction-service", e);
        }
    }
}
