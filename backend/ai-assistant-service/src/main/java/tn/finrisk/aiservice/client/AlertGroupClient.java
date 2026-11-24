package tn.finrisk.aiservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.aiservice.client.dto.AlertGroupView;
import tn.finrisk.aiservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Lecture des groupes d'alertes CHEZ alert-case-service (port 8085), par API.
 * Sert à la constellation : quand une alerte appartient à un groupe ouvert
 * (ex. 10 × 100 TND → structuring), le groupe apparaît comme NŒUD dans le
 * graphe et le LLM le voit dans son prompt.
 *
 * L'appel est DEFENSIF : si alert-case-service est injoignable, le groupe
 * est simplement absent de la constellation (nœud non ajouté) — l'analyse
 * ne doit jamais dépendre de la disponibilité d'un service en aval.
 */
@Component
public class AlertGroupClient {

    private final RestClient restClient;

    public AlertGroupClient(@Value("${clients.alert.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** GET /api/alert-groups/{id} */
    public AlertGroupView getGroupById(Long groupId) {
        try {
            AlertGroupView view = restClient.get()
                    .uri("/api/alert-groups/{id}", groupId)
                    .retrieve()
                    .body(AlertGroupView.class);
            if (view == null) {
                throw new RemoteResourceNotFoundException("alert-group", groupId);
            }
            return view;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("alert-group", groupId);
            }
            throw new DownstreamUnavailableException("alert-case-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("alert-case-service", e);
        }
    }
}
