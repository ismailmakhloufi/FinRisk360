package tn.finrisk.transactionservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tn.finrisk.transactionservice.web.error.BalanceChangeRefusedException;
import tn.finrisk.transactionservice.web.error.DownstreamUnavailableException;
import tn.finrisk.transactionservice.web.error.InvalidRibException;

/**
 * Tests UNITAIRES du client de mouvement de solde : aucune base, aucun compte
 * reel — le serveur HTTP est simule (MockRestServiceServer).
 */
class AccountClientTest {

    private static final String URL = "http://account-service/api/accounts/";

    private MockRestServiceServer server;
    private AccountClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://account-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AccountClient(builder);
    }

    @Test
    void mouvementIdempotent_utiliseLaMemeClePourChaqueRejeu() {
        UUID key = UUID.fromString("38891f63-1421-43b6-9f25-a249c93eb741");
        server.expect(requestTo(URL + "7/balance-operations/" + key))
                .andExpect(method(HttpMethod.PUT)).andRespond(withSuccess());
        server.expect(requestTo(URL + "7/balance-operations/" + key))
                .andExpect(method(HttpMethod.PUT)).andRespond(withSuccess());
        client.applyBalanceIdempotent(key, 7L, BigDecimal.TEN, "DEBIT");
        client.applyBalanceIdempotent(key, 7L, BigDecimal.TEN, "DEBIT");
        server.verify();
    }

    @Test
    void mouvementIdempotent_erreurServeurEstIncertaine() {
        UUID key = UUID.randomUUID();
        server.expect(requestTo(URL + "7/balance-operations/" + key))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.applyBalanceIdempotent(key, 7L, BigDecimal.TEN, "DEBIT"))
                .isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void applyBalance_succes_neLeveRien() {
        server.expect(requestTo(URL + "7/balance"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());

        client.applyBalance(7L, new BigDecimal("88.75"), "CREDIT");

        server.verify();
    }

    @Test
    void applyBalance_409_leveBalanceChangeRefusedAvecLeMotif() {
        server.expect(requestTo(URL + "1/balance"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Compte 957398324794 FROZEN : operation de solde interdite\"}"));

        assertThatThrownBy(() -> client.applyBalance(1L, new BigDecimal("10"), "CREDIT"))
                .isInstanceOf(BalanceChangeRefusedException.class)
                .hasMessageContaining("FROZEN")
                .hasMessageContaining("(409)");
    }

    @Test
    void applyBalance_reseauCasse_leveDownstreamUnavailable() {
        server.expect(requestTo(URL + "7/balance"))
                .andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> client.applyBalance(7L, new BigDecimal("10"), "CREDIT"))
                .isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void resolveByRib_compteInterne_retrouveLidentifiant() {
        server.expect(requestTo(URL + "rib/01090011234567890105"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"accountId\":42}"));

        assertThat(client.resolveByRib("01090011234567890105")).isEqualTo(42L);
        server.verify();
    }

    @Test
    void resolveByRib_404_autreBanque_retourneNull() {
        server.expect(requestTo(URL + "rib/02070011234567890188"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"RIB inconnu\"}"));

        assertThat(client.resolveByRib("02070011234567890188")).isNull();
    }

    @Test
    void resolveByRib_400_leveInvalidRib() {
        server.expect(requestTo(URL + "rib/01090011234567890100"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"RIB invalide : cle de controle incorrecte\"}"));

        assertThatThrownBy(() -> client.resolveByRib("01090011234567890100"))
                .isInstanceOf(InvalidRibException.class)
                .hasMessageContaining("cle de controle");
    }

    @Test
    void resolveByRib_reseauCasse_leveDownstreamUnavailable() {
        server.expect(requestTo(URL + "rib/01090011234567890105"))
                .andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> client.resolveByRib("01090011234567890105"))
                .isInstanceOf(DownstreamUnavailableException.class);
    }
}
