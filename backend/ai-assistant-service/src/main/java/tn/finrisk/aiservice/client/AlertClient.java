package tn.finrisk.aiservice.client;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.aiservice.client.dto.AlertView;
import tn.finrisk.aiservice.client.dto.PageView;
import tn.finrisk.aiservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Lecture des alertes CHEZ alert-case-service (port 8085), par API.
 *   - getAlertById : l'alerte à analyser (point d'entrée de Analyze)
 *   - list : les alertes voisines (graph : croisements entre clients)
 */
@Component
public class AlertClient {

    private final RestClient restClient;

    public AlertClient(@Value("${clients.alert.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** GET /api/alerts/{id} */
    public AlertView getAlertById(Long alertId) {
        try {
            AlertView view = restClient.get()
                    .uri("/api/alerts/{id}", alertId)
                    .retrieve()
                    .body(AlertView.class);
            if (view == null) {
                throw new RemoteResourceNotFoundException("alert", alertId);
            }
            return view;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("alert", alertId);
            }
            throw new DownstreamUnavailableException("alert-case-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("alert-case-service", e);
        }
    }

    /** GET /api/alerts?status=&page=&size= — statut optionnel. */
    public PageView<AlertView> list(String status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        try {
            PageView<AlertView> result = restClient.get()
                    .uri(uri -> uri.path("/api/alerts")
                            .queryParamIfPresent("status", Optional.ofNullable(status))
                            .queryParam("page", page)
                            .queryParam("size", safeSize)
                            .build())
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<PageView<AlertView>>() {
                    });
            if (result == null) {
                throw new DownstreamUnavailableException("alert-case-service", null);
            }
            return result;
        } catch (RestClientResponseException e) {
            throw new DownstreamUnavailableException("alert-case-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("alert-case-service", e);
        }
    }
}
