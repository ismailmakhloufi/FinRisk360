package tn.finrisk.riskservice.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Creation d'une alerte CHEZ alert-case-service (port 8085), STRICTEMENT par
 * API : ce service est le proprietaire exclusif de la table alerts (M0).
 *
 * Toute transaction evaluee doit laisser une ALERTE, meme si son score est
 * anodin : sans elle il n'existe aucune trace exploitable par un analyste, et
 * la transaction passerait en COMPLETED hors de tout controle (aucun Evenement
 * ne serait jamais examine).
 *
 * Attention : alert-case-service relit l'evaluation AVANT de creer l'alerte
 * (il verifie qu'elle existe). L'appel ne peut donc PAS etre fait dans la
 * transaction de evaluate() — l'assessment ne serait pas encore commite et le
 * rappel reculerait un 404. C'est l'appelant (controller) qui declenche.
 */
@Component
public class AlertClient {

    private final RestClient restClient;

    @Autowired
    public AlertClient(@Value("${clients.alert.base-url}") String baseUrl) {
        // Fabrique statique : aucun bean RestClient.Builder n'est requis (Boot 4.1)
        // et l'URL vient d'un seul endroit (application.yml).
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Usage test uniquement : permet de brancher un MockRestServiceServer. */
    AlertClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** POST /api/alerts */
    public Long createAlert(Long assessmentId, String alertType) {
        try {
            AlertCreated created = restClient.post()
                    .uri("/api/alerts")
                    .body(new AlertCreate(assessmentId, alertType))
                    .retrieve()
                    .body(AlertCreated.class);
            return created == null ? null : created.alertId();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("evaluation", assessmentId);
            }
            throw new DownstreamUnavailableException("alert-case-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("alert-case-service", e);
        }
    }

    /** Sous-ensemble de la charge utile envoyee a alert-case-service. */
    private record AlertCreate(Long assessmentId, String alertType) {
    }

    /** Sous-ensemble de la reponse dont on n'a besoin que de l'identifiant. */
    private record AlertCreated(Long alertId) {
    }
}
