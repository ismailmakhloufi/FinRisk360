package tn.finrisk.aiservice.web.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tn.finrisk.aiservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Une seule ouverture d'erreur pour le service IA :
 *  - 404 : alerte/ressource inconnue ICI ou CHEZ un service lu (retransmis)
 *  - 503 : risk/transaction/customer/account/alert-case injoignable
 *  - 400 : parametres invalides
 *  - 500 : bug inattendu -> jamais de stacktrace au client
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RemoteResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(RemoteResourceNotFoundException ex,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(404)
                .body(ErrorResponse.of(404, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(DownstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDownstream(DownstreamUnavailableException ex,
                                                          HttpServletRequest request) {
        log.warn("Service distant indisponible : {}", ex.getServiceName(), ex.getCause());
        return ResponseEntity.status(503)
                .body(ErrorResponse.of(503, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<ErrorResponse.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> ErrorResponse.FieldViolation.of(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(400)
                .body(ErrorResponse.of(400, "Donnees invalides", request.getRequestURI(), violations));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(400).body(ErrorResponse.of(400,
                "Parametre invalide : " + ex.getName(), request.getRequestURI()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(400).body(ErrorResponse.of(400,
                "Corps de requete illisible ou JSON invalide", request.getRequestURI()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(400).body(ErrorResponse.of(400,
                "Parametre manquant : " + ex.getParameterName(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erreur inattendue", ex);
        return ResponseEntity.status(500).body(ErrorResponse.of(500,
                "Erreur interne du ai-assistant-service", request.getRequestURI()));
    }
}
