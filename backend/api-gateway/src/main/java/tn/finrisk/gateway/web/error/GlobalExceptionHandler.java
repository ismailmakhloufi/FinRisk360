package tn.finrisk.gateway.web.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tn.finrisk.gateway.web.GatewayAuthFilter;

/**
 * Erreurs PROPUES au gateway (les 401/403 sont ecrites directement par
 * GatewayAuthFilter : un filtre n'est pas dans la chaine MVC et ne peut
 * donc pas passer par ce conseil).
 *  - 404 : route inconnue (emis par le gateway, jamais transmis aux backends)
 *  - 503 : cible arretee (panne d'infra, distinct des 4xx metier proxyes)
 *  - 500 : bug du gateway -> pas de stacktrace au client
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RouteNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoute(RouteNotFoundException ex,
                                                     HttpServletRequest request) {
        return ResponseEntity.status(404)
                .body(ErrorResponse.of(404, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(DownstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDownstream(DownstreamUnavailableException ex,
                                                          HttpServletRequest request) {
        log.warn("Cible indisponible : {}", ex.getTarget());
        return ResponseEntity.status(503)
                .body(ErrorResponse.of(503, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erreur inattendue du gateway", ex);
        return ResponseEntity.status(500).body(ErrorResponse.of(500,
                "Erreur interne du api-gateway", request.getRequestURI()));
    }
}
