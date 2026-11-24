package tn.finrisk.riskservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Construction de l'URL du PATCH de verdict : la variable de chemin {id} doit
 * etre resolue par UriBuilder.build(id). Une forme varargs uri(template, args)
 * N'EST PAS ACCEPTEE avec une lambda — cette erreur ne se voit qu'a l'EXECUTION
 * (le compilateur JDT la laisse passer, et le mock du service d'integration
 * masque ledefaut). D'ou ce test sur le client reel.
 */
class TransactionClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TransactionClient client =
            new TransactionClient(builder.baseUrl("http://localhost:8083").build());

    @Test
    void changeStatus_appelleLeBonUrlAvecLeStatut() {
        server.expect(requestTo("http://localhost:8083/api/transactions/42/status?status=COMPLETED"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());

        client.changeStatus(42L, "COMPLETED");

        server.verify();
    }

    @Test
    void changeStatus_heLdEstUnStatutValide() {
        server.expect(requestTo("http://localhost:8083/api/transactions/7/status?status=HELD"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());

        client.changeStatus(7L, "HELD");

        server.verify();
    }

    @Test
    void changeStatus_409DejaTerminee_estIgnore() {
        // Un analyste a tranche avant nous : course legitime, pas une erreur.
        server.expect(requestTo("http://localhost:8083/api/transactions/9/status?status=COMPLETED"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        client.changeStatus(9L, "COMPLETED");

        server.verify();
    }

    @Test
    void changeStatus_503_remonteUneErreurDeServiceDistant() {
        server.expect(requestTo("http://localhost:8083/api/transactions/9/status?status=COMPLETED"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.changeStatus(9L, "COMPLETED"))
                .isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void changeStatus_404_remonteIntrouvable() {
        server.expect(requestTo("http://localhost:8083/api/transactions/4044/status?status=HELD"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.changeStatus(4044L, "HELD"))
                .isInstanceOf(RemoteResourceNotFoundException.class);
    }

    @Test
    void getById_urlAttendue() {
        server.expect(requestTo("http://localhost:8083/api/transactions/5"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"transactionId\":5,\"accountId\":1,\"amount\":10.0,"
                                + "\"currency\":\"TND\",\"country\":\"TN\","
                                + "\"status\":\"PENDING\",\"channel\":\"API\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(client.getById(5L).transactionId()).isEqualTo(5L);

        server.verify();
    }
}
