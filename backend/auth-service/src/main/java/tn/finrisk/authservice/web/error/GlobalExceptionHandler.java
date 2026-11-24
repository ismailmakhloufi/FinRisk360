package tn.finrisk.authservice.web.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tn.finrisk.authservice.security.JwtException;

/**
 * Toutes les erreurs de l'auth-service dans la forme commune du projet :
 *  - 401 : token absent / malforme / forge / expire, identifiants faux
 *  - 403 : token VALIDE mais role insuffisant ou compte desactive
 *  - 404 : utilisateur inconnu
 *  - 409 : username/email pris, auto-destruction refusee, contrainte SQL
 *  - 400 : validation / corps illisible / PATCH vide
 *  - 500 : bug -> jamais de stacktrace au client
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({JwtException.class, AuthenticationFailedException.class})
    public ResponseEntity<ErrorResponse> handleUnauthorized(RuntimeException ex,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(401)
                .body(ErrorResponse.of(401, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(403)
                .body(ErrorResponse.of(403, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(UserNotFoundException ex,
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

    /** Dernier rempart des contraintes PostgreSQL (unicite en cas de course). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
                                                         HttpServletRequest request) {
        log.warn("Violation de contrainte SQL : {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(409).body(ErrorResponse.of(409,
                "Violation de contrainte d'integrite (unicite ou cle etrangere invalide)",
                request.getRequestURI()));
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
                "Erreur interne du auth-service", request.getRequestURI()));
    }
}
