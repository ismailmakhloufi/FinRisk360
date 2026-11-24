package tn.finrisk.alertcaseservice.web.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import tn.finrisk.alertcaseservice.client.exceptions.DownstreamUnavailableException;
import tn.finrisk.alertcaseservice.client.exceptions.RemoteResourceNotFoundException;

/**
 * Une seule ouverture d'erreur pour tout le service :
 *  - 404 : ressource inconnue ICI (alerte, case) ou CHEZ l'autre service (404 distant retransmis)
 *  - 409 : transition illegale, doublon, etat terminal, contrainte SQL violee (FK users / unicite)
 *  - 503 : risk-service appele est arrete ou en erreur (indispo d'infrastructure)
 *  - 400 : validation / parametres (dont commentaire sans X-User-Id)
 *  - 500 : bug inattendu -> jamais de stacktrace au client
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({RemoteResourceNotFoundException.class,
            AlertNotFoundException.class, CaseNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(404)
                .body(ErrorResponse.of(404, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(409)
                .body(ErrorResponse.of(409, ex.getMessage(), request.getRequestURI()));
    }

    /**
     * Dernier rempart des contraintes PostgreSQL verifiees par le SGBD lui-meme :
     * FK vers users (auth-service inexistant en M4) ou unicite (alert_code,
     * uq_cases_alert) en cas de course — jamais de SELECT users ici (M0 §2).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
                                                         HttpServletRequest request) {
        log.warn("Violation de contrainte SQL : {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(409).body(ErrorResponse.of(409,
                "Violation de contrainte d'integrite (cle etrangere ou unicite invalide)",
                request.getRequestURI()));
    }

    @ExceptionHandler(DownstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDownstream(DownstreamUnavailableException ex,
                                                          HttpServletRequest request) {
        log.warn("Service distant indisponible : {}", ex.getServiceName(), ex.getCause());
        return ResponseEntity.status(503)
                .body(ErrorResponse.of(503, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(400)
                .body(ErrorResponse.of(400, ex.getMessage(), request.getRequestURI()));
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
        return ResponseEntity.status(400)
                .body(ErrorResponse.of(400, "Corps de requete illisible ou JSON invalide",
                        request.getRequestURI()));
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
                "Erreur interne du alert-case-service", request.getRequestURI()));
    }
}
