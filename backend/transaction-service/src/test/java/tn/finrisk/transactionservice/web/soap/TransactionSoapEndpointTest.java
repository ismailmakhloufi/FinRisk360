package tn.finrisk.transactionservice.web.soap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests d'INTEGRATION de la faade SOAP : le vrai MessageDispatcherServlet,
 * la validation XSD, et surtout la PERSISTANCE en base (critere M2).
 * Exiges : le contexte complet demarre sur un port aleatoire (pas de @MockitoBean).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TransactionSoapEndpointTest {

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Long accountId;
    private Long merchantId;

    @BeforeEach
    void fixtures() {
        Long customerId = jdbc.queryForObject("""
                INSERT INTO customers (first_name, last_name, email, status)
                VALUES ('Soap', 'Test', ?, 'ACTIVE')
                RETURNING customer_id
                """, Long.class, "soap-" + UUID.randomUUID() + "@mail.tn");
        accountId = jdbc.queryForObject("""
                INSERT INTO accounts (customer_id, account_number, account_type, currency, balance, status)
                VALUES (?, ?, 'CHECKING', 'TND', 100.00, 'ACTIVE')
                RETURNING account_id
                """, Long.class, customerId, "SOAP" + UUID.randomUUID()
                .toString().replace("-", "").substring(0, 10).toUpperCase());
        merchantId = jdbc.queryForObject("""
                INSERT INTO merchants (merchant_name, merchant_category, country, city)
                VALUES ('Marchand SOAP', 'RETAIL', 'TN', 'Tunis')
                RETURNING merchant_id
                """, Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM transactions WHERE device_id LIKE 'soap-test%'");
        jdbc.update("DELETE FROM merchants WHERE merchant_id = ?", merchantId);
        jdbc.update("DELETE FROM accounts WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM customers WHERE email LIKE 'soap-%'");
    }

    @Test
    void wsdl_exposeLOperationCreateTransaction() throws Exception {
        HttpResponse<String> response = httpSend(
                HttpRequest.newBuilder(URI.create(base() + "/ws/transactionWsdlDefinition.wsdl"))
                        .GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("createTransaction");
        assertThat(response.body()).contains("http://finrisk.tn/transactions");
    }

    @Test
    void createTransaction_parSoap_persisteEnBase() throws Exception {
        String body = envelope(accountId, merchantId, "42.50", "soap-test-1");

        HttpResponse<String> response = post(body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<status>PENDING</status>");
        Long transactionId = extractId(response.body());
        assertThat(transactionId).isNotNull();

        String status = jdbc.queryForObject(
                "SELECT status FROM transactions WHERE transaction_id = ?", String.class, transactionId);
        String deviceId = jdbc.queryForObject(
                "SELECT device_id FROM transactions WHERE transaction_id = ?", String.class, transactionId);
        assertThat(status).isEqualTo("PENDING");
        assertThat(deviceId).isEqualTo("soap-test-1");
    }

    @Test
    void montantNegatif_renvoieUnSoapFaultValideParLeXsd() throws Exception {
        String body = envelope(accountId, merchantId, "-5", "soap-test-fault");

        HttpResponse<String> response = post(body);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("<SOAP-ENV:Fault>");
        assertThat(response.body()).contains("minExclusive");
        assertThat(response.body()).doesNotContain("<transactionId>");
    }

    private HttpResponse<String> post(String xml) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/ws"))
                .header("Content-Type", "text/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
                .build();
        return httpSend(request);
    }

    private HttpResponse<String> httpSend(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String base() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }

    private String envelope(Long accountId, Long merchantId, String amount, String deviceId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"
                        xmlns:tns="http://finrisk.tn/transactions">
                  <SOAP-ENV:Body>
                    <tns:createTransactionRequest>
                      <tns:accountId>%d</tns:accountId>
                      <tns:merchantId>%d</tns:merchantId>
                      <tns:amount>%s</tns:amount>
                      <tns:currency>TND</tns:currency>
                      <tns:transactionType>PAYMENT</tns:transactionType>
                      <tns:country>TN</tns:country>
                      <tns:channel>API</tns:channel>
                      <tns:deviceId>%s</tns:deviceId>
                    </tns:createTransactionRequest>
                  </SOAP-ENV:Body>
                </SOAP-ENV:Envelope>
                """.formatted(accountId, merchantId, amount, deviceId);
    }

    private Long extractId(String responseBody) {
        Matcher matcher = Pattern.compile("<transactionId>(\\d+)</transactionId>").matcher(responseBody);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }
}
