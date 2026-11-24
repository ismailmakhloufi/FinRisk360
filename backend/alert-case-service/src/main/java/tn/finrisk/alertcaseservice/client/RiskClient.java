package tn.finrisk.alertcaseservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.alertcaseservice.client.dto.EvaluationView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Lecture de l'evaluation CHEZ risk-service (port 8084), STRICTEMENT par API :
 * aucune table d'un autre service n'est lue en SQL (regle M0 §2).
 *
 * Utilise avant toute creation d'alerte : l'evaluation doit EXISTER avant
 * d'etre alertee (404 si elle n'existe pas, 503 si risk-service est arrete).
 *
 * Base-url fournie par application.yml (clients.risk.base-url).
 */
@Component
public class RiskClient {

    private final RestClient restClient;

    public RiskClient(@Value("${clients.risk.base-url}") String baseUrl) {
        // Fabrique statique : aucun bean RestClient.Builder n'est requis (Boot 4.1)
        // et l'URL vient d'un seul endroit (application.yml).
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** GET /api/evaluations/{id} */
    public EvaluationView getEvaluationById(Long assessmentId) {
        try {
            EvaluationView view = restClient.get()
                    .uri("/api/evaluations/{id}", assessmentId)
                    .retrieve()
                    .body(EvaluationView.class);
            if (view == null) {
                throw new RemoteResourceNotFoundException("evaluation", assessmentId);
            }
            return view;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("evaluation", assessmentId);
            }
            throw new DownstreamUnavailableException("risk-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("risk-service", e);
        }
    }

    /**
     * GET /api/evaluations/transaction/{id} — l'evaluation D'UNE TRANSACTION
     * (l'agent nocturne part de la tx en attente, pas de l'assessment).
     * 404 = jamais evaluee : retourne null (l'agent saute, rien a juger).
     */
    public EvaluationView getEvaluationByTransaction(Long transactionId) {
        try {
            return restClient.get()
                    .uri("/api/evaluations/transaction/{id}", transactionId)
                    .retrieve()
                    .body(EvaluationView.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw new DownstreamUnavailableException("risk-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("risk-service", e);
        }
    }
}
