package tn.finrisk.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tn.finrisk.gateway.config.GatewayProperties;
import tn.finrisk.gateway.config.GatewayProperties.Route;
import tn.finrisk.gateway.security.JwtException;
import tn.finrisk.gateway.security.JwtSupport;
import tn.finrisk.gateway.web.error.ErrorResponse;

/**
 * LE point d'entree du jalon M5 — arme les 2 codes exigés :
 *
 *   401 : pas de token, token malforme, signature fausse, expire...
 *          (« je ne sais pas qui vous etes »)
 *   403 : token VALIDE mais role absent de la matrice de la route
 *          (« je sais qui vous etes, et vous n'avez pas le droit »)
 *
 * Ordre de decision (jamais de requete inconnue n'atteint un backend) :
 *   actuator/local -> 404 route inconnue -> route publique (passe) ->
 *   token verifie -> matrice read/write-roles -> injection d'identite.
 *
 * ANTI-SPOOFING : les headers X-User-Id / X-User-Role / X-Forwarded-For
 * fournis par le CLIENT sont SUPPRIMES (TrustedHeadersWrapper) avant tout
 * passage en chaine — seuls les valeurs derives du token verifie sont
 * injectees. Sans ca, un client ecrirait « X-User-Id: 1 » a la main et
 * usurperait l'admin sur les services cibles.
 */
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTR = "finrisk.gateway.userId";
    public static final String ROLE_ATTR = "finrisk.gateway.role";

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthFilter.class);
    private static final List<String> UNTRUSTED_CLIENT_HEADERS = List.of(
            "X-User-Id", "X-User-Role", "X-Forwarded-For");

    private final GatewayProperties properties;
    private final JwtSupport jwt;
    private final ObjectMapper mapper = new ObjectMapper();

    public GatewayAuthFilter(GatewayProperties properties, JwtSupport jwt) {
        this.properties = properties;
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        // Ressources locales du gateway : ni proxy ni garde.
        if (uri.startsWith("/actuator") || uri.equals("/error")) {
            chain.doFilter(request, response);
            return;
        }

        Route route = properties.match(uri);
        if (route == null) {
            writeError(response, 404, "Route inconnue : " + uri, uri);
            return;
        }

        // On ne transmet QUE les headers de confiance du client.
        HttpServletRequest clean = new TrustedHeadersWrapper(request);

        if (properties.isPublic(uri)) {
            chain.doFilter(clean, response);
            return;
        }

        String authorization = clean.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            writeError(response, 401,
                    "Token manquant (header Authorization : Bearer <token>)", uri);
            return;
        }
        if (!authorization.startsWith("Bearer ")) {
            writeError(response, 401, "Format de token invalide (attendu : Bearer <token>)", uri);
            return;
        }

        JsonNode claims;
        try {
            claims = jwt.verify(authorization.substring("Bearer ".length()).trim());
        } catch (JwtException e) {
            writeError(response, 401, e.getMessage(), uri);
            return;
        }

        String role = claims.path("rol").asText("");
        List<String> required = properties.requiredRoles(route, clean.getMethod());
        boolean allowed = "BANK_SIMULATOR".equals(role)
                ? simulatorAllows(clean.getMethod(), uri)
                : required.contains("*") || required.contains(role);
        if (!allowed) {
            writeError(response, 403,
                    "Rôle insuffisant : " + String.join(" ou ", required)
                            + " requis (votre rôle : " + (role.isBlank() ? "aucun" : role) + ")",
                    uri);
            return;
        }

        clean.setAttribute(USER_ID_ATTR, claims.path("sub").asLong());
        clean.setAttribute(ROLE_ATTR, role);
        log.debug("{} {} role={} -> {} autorise", clean.getMethod(), uri, role, required);
        chain.doFilter(clean, response);
    }

    private boolean simulatorAllows(String method, String path) {
        return switch (method) {
            case "GET" -> path.matches("/api/(customers(/[0-9]+(/accounts)?)?"
                    + "|accounts(/[0-9]+)?|transactions(/[0-9]+)?"
                    + "|merchants(/[0-9]+)?|evaluations/transaction/[0-9]+"
                    + "|alerts/assessment/[0-9]+)");
            case "POST" -> path.matches("/api/(customers|accounts|transactions|merchants|evaluations)");
            case "DELETE" -> path.matches("/api/(customers/[0-9]+|merchants/[0-9]+"
                    + "|accounts|transactions|evaluations|alerts)");
            default -> false;
        };
    }

    private void writeError(HttpServletResponse response, int status, String message, String path)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                mapper.writeValueAsString(ErrorResponse.of(status, message, path)));
    }

    /**
     * Supprime du cote CLIENT les headers d'identite : ce qui sort de ce
     * wrapper ne peut provenir que du gateway lui-meme.
     */
    private static final class TrustedHeadersWrapper extends HttpServletRequestWrapper {

        private TrustedHeadersWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return isUntrusted(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isUntrusted(name)
                    ? Collections.emptyEnumeration()
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(
                    Collections.list(super.getHeaderNames()).stream()
                            .filter(n -> !isUntrusted(n))
                            .toList());
        }

        private boolean isUntrusted(String name) {
            return UNTRUSTED_CLIENT_HEADERS.stream()
                    .anyMatch(h -> h.equalsIgnoreCase(name));
        }
    }
}
