package tn.finrisk.gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tn.finrisk.gateway.config.GatewayProperties;
import tn.finrisk.gateway.config.GatewayProperties.Route;
import tn.finrisk.gateway.web.error.DownstreamUnavailableException;
import tn.finrisk.gateway.web.error.RouteNotFoundException;

/**
 * Proxy generique : la requete authentifiee par GatewayAuthFilter est
 * reenvoyee telle quelle (methode, chemin, query, corps, headers utiles)
 * vers la cible de la route, avec en PLUS les identites de confiance :
 *
 *   X-User-Id      <- claim sub du token verifie (les services M4 l'attendent)
 *   X-User-Role    <- claim rol du token verifie
 *   X-Forwarded-For<- adresse reelle du client (jamais celle du client...
 *                     enfin : celle annoncee par la connexion TCP)
 *
 * Les 4xx/5xx metier du service cible sont proxyes IDENTIQUEMENT (c'est
 * lui qui a raison sur sa metier) ; seule une panne de connexion devient
 * 503 par ici.
 */
@RestController
@RequestMapping({"/api", "/api/**"})
public class ProxyController {

    /** Headers de couche transport : jamais recopies (RestClient ou le
     *  serveur cible s'en occupent, les reprendre casserait la requete). */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "transfer-encoding", "upgrade",
            "te", "trailer", "expect", "host", "content-length");

    /** Jamais recopies depuis le client : forgeables. Seuls les attributs
     *  poses par le filtre (apres verification du token) sont envoyes. */
    private static final List<String> UNTRUSTED = List.of(
            "X-User-Id", "X-User-Role", "X-Forwarded-For");

    private final GatewayProperties properties;
    private final RestClient restClient = RestClient.builder().build();

    public ProxyController(GatewayProperties properties) {
        this.properties = properties;
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        String uri = request.getRequestURI();
        Route route = properties.match(uri);
        if (route == null) {
            // defense en profondeur : le filtre a deja repondu 404, ce
            // chemin ne devrait pas etre atteignable
            throw new RouteNotFoundException(uri);
        }

        byte[] body = request.getInputStream().readAllBytes();
        String query = request.getQueryString();
        URI target = URI.create(properties.target(route) + uri
                + (query == null || query.isBlank() ? "" : "?" + query));

        HttpHeaders headers = new HttpHeaders();
        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            if (!isHopByHop(name) && !isUntrusted(name)) {
                request.getHeaders(name).asIterator()
                        .forEachRemaining(value -> headers.add(name, value));
            }
        });
        // Identite de confiance (filtre) + source reelle du client.
        Object userId = request.getAttribute(GatewayAuthFilter.USER_ID_ATTR);
        if (userId != null) {
            headers.set("X-User-Id", String.valueOf(userId));
        }
        Object role = request.getAttribute(GatewayAuthFilter.ROLE_ATTR);
        if (role != null) {
            headers.set("X-User-Role", String.valueOf(role));
        }
        headers.set("X-Forwarded-For", request.getRemoteAddr());

        try {
            RestClient.RequestBodySpec spec = restClient
                    .method(HttpMethod.valueOf(request.getMethod()))
                    .uri(target)
                    .headers(h -> h.addAll(headers));
            // .body(null) provoque une NPE dans RestClient : on ne pose le
            // corps QUE s'il y en a un (GET/DELETE vides = requete sans body).
            if (body.length > 0) {
                spec.body(body);
            }
            return spec.exchange((req, res) -> {
                HttpHeaders out = new HttpHeaders();
                res.getHeaders().forEach((name, values) -> {
                    if (!isHopByHop(name)) {
                        out.addAll(name, values);
                    }
                });
                byte[] payload = res.getBody().readAllBytes();
                return ResponseEntity.status(res.getStatusCode())
                        .headers(out)
                        .body(payload);
            });
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException(properties.target(route));
        }
    }

    private boolean isHopByHop(String name) {
        return HOP_BY_HOP.contains(name.toLowerCase());
    }

    private boolean isUntrusted(String name) {
        return UNTRUSTED.stream().anyMatch(h -> h.equalsIgnoreCase(name));
    }
}
