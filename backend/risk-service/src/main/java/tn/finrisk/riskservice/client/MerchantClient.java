package tn.finrisk.riskservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.finrisk.riskservice.client.dto.MerchantView;

/**
 * Lecture des marchands CHEZ transaction-service (/api/merchants, port 8083),
 * strictement par API. Sert a RULE-005 (anciennete du marchand).
 */
@Component
public class MerchantClient {

    private final RestClient restClient;

    public MerchantClient(@Value("${clients.transaction.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** GET /api/merchants/{id} */
    public MerchantView getById(Long merchantId) {
        try {
            MerchantView view = restClient.get()
                    .uri("/api/merchants/{id}", merchantId)
                    .retrieve()
                    .body(MerchantView.class);
            if (view == null) {
                throw new RemoteResourceNotFoundException("merchant", merchantId);
            }
            return view;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new RemoteResourceNotFoundException("merchant", merchantId);
            }
            throw new DownstreamUnavailableException("transaction-service", e);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException("transaction-service", e);
        }
    }
}
