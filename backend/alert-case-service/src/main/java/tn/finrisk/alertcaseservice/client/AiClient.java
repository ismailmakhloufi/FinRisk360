package tn.finrisk.alertcaseservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.alertcaseservice.client.dto.AiAnalysisView;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Analyse d'une alerte CHEZ ai-assistant-service (port 8086), STRICTEMENT
 * par API (regle M0). Utilise par l'AGENT NOCTURNE : le deuxieme verdict
 * du design hybride (moteur + IA cote a cote).
 *
 * L'analyse est un moteur d'insights DETERMINISTE (pas d'appel LLM) :
 * rapide, reproductible, testable — la nuit, aucune dependance a Ollama.
 *
 * Base-url fournie par application.yml (clients.ai.base-url).
 */
@Component
public class AiClient {

    private final RestClient restClient;

    public AiClient(@Value("${clients.ai.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** POST /api/ai/alerts/{alertId}/analyze */
    public AiAnalysisView analyze(Long alertId) {
        try {
            AiAnalysisView view = restClient.post()
                    .uri("/api/ai/alerts/{alertId}/analyze", alertId)
                    .retrieve()
                    .body(AiAnalysisView.class);
            if (view == null) {
                throw new RemoteResourceNotFoundException("analyse IA", alertId);
            }
            return view;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("analyse IA", alertId);
            }
            throw new DownstreamUnavailableException("ai-assistant-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("ai-assistant-service", e);
        }
    }
}
