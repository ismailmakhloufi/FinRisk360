package tn.finrisk.aiservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.aiservice.client.dto.EvaluationView;
import tn.finrisk.aiservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Lecture de l'évaluation CHEZ risk-service (port 8084), STRICTEMENT par API :
 * aucune table d'un autre service n'est lue en SQL (règle M0 §2).
 *
 * Apporte à l'IA le score, la décision et les facteurs du moteur — la base
 * de l'explication (Explain) : on ne re-calcule PAS le score des règles,
 * on le reprend tel quel.
 */
@Component
public class RiskClient {

    private final RestClient restClient;

    public RiskClient(@Value("${clients.risk.base-url}") String baseUrl) {
        // Fabrique statique : aucun bean RestClient.Builder n'est requis (Boot 4.1)
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
}
