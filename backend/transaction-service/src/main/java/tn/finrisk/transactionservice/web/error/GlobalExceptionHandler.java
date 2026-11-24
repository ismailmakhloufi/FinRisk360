package tn.finrisk.transactionservice.web.error;

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
 * Concerne la façade REST ; la façade SOAP a son propre traducteur (SOAP fault).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Transaction ou marchand demande qui n'existe pas. */
    @ExceptionHandler({TransactionNotFoundException.class, MerchantNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /** Transaction deja terminee, ou marchand deja connu. */
    @ExceptionHandler({TransactionNotModifiableException.class, MerchantAlreadyExistsException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /** Mouvement de solde refuse par account-service (compte FROZEN/CLOSED...). */
    @ExceptionHandler(BalanceChangeRefusedException.class)
    public ResponseEntity<ErrorResponse> handleBalanceChangeRefused(
            BalanceChangeRefusedException ex, HttpServletRequest request) {
        log.warn("Mouvement de solde refuse : {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /** RIB du beneficiaire malforme ou designant le compte source. */
    @ExceptionHandler(InvalidRibException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRib(InvalidRibException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** Service distant (account-service) injoignable pendant la regulation. */
    @ExceptionHandler(DownstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDownstream(DownstreamUnavailableException ex,
                                                          HttpServletRequest request) {
        log.error("Service distant indisponible : {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    /** FK accounts violee (compte inconnu) : la base refuse l'insertion. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
                                                         HttpServletRequest request) {
        log.warn("Violation de contrainte d'integrite : {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT,
                "La requete viole une contrainte d'integrite de la base", request);
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

    /** Parametre d'URL ou de query invalide : /api/transactions/abc ou ?status=FOO. */
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

    /** Parametre obligatoire manquant. */
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
