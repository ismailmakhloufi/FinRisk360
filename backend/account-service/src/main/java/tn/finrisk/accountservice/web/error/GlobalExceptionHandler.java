package tn.finrisk.accountservice.web.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Traducteur unique : exception metier / Spring -> code HTTP + format ErrorResponse.
 * Aucun autre endroit du projet ne construit de reponse d'erreur.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Compte demande qui n'existe pas (id ou numero). */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(AccountNotFoundException ex,
                                                        HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /** Numero de compte deja pris (controle applicatif). */
    @ExceptionHandler(AccountNumberAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleConflict(AccountNumberAlreadyExistsException ex,
                                                        HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /** Contrainte UNIQUE / FK violee en base (course entre 2 requetes). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
                                                         HttpServletRequest request) {
        log.warn("Violation de contrainte d'integrite : {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT,
                "La requete viole une contrainte d'integrite de la base", request);
    }

    /** Compte FROZEN / CLOSED : operation refusee. */
    @ExceptionHandler(AccountNotOperableException.class)
    public ResponseEntity<ErrorResponse> handleNotOperable(AccountNotOperableException ex,
                                                           HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /** DEBIT superieur au solde. */
    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficient(InsufficientBalanceException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /** RIB malforme : longueur ou cle de controle (modulo 97). */
    @ExceptionHandler(InvalidRibException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRib(InvalidRibException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** Bean Validation sur @Valid : on details chaque champ en erreur. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<ErrorResponse.FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> ErrorResponse.FieldViolation.of(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation echouee", request, errors);
    }

    /** Parametre d'URL ou de query invalide : /api/accounts/abc ou ?status=FOO. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        String message = "Parametre '" + ex.getName() + "' invalide : " + ex.getValue();
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /** Corps JSON absent / malforme. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Corps JSON invalide ou illisible", request);
    }

    /** Parametre obligatoire manquant : ?status= absent. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Parametre obligatoire manquant : " + ex.getParameterName(),
                request);
    }

    /** Filet de sécurité : TOUT le reste -> 500, sans jamais exposer la stacktrace. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erreur non geree sur {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne du serveur", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                HttpServletRequest request) {
        return build(status, message, request, List.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                HttpServletRequest request,
                                                List<ErrorResponse.FieldViolation> errors) {
        ErrorResponse body = ErrorResponse.of(status.value(), message, request.getRequestURI(), errors);
        return ResponseEntity.status(status).body(body);
    }
}
