package tn.finrisk.accountservice.web.error;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Format unique d'erreur impose par docs/architecture-services.md :
 *   { timestamp, status, message, path, errors[] }
 * Identique a customer-service : le front lit UNE seule structure partout.
 */
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String message,
        String path,
        List<FieldViolation> errors) {

    public record FieldViolation(String field, String message) {

        public static FieldViolation of(String field, String message) {
            return new FieldViolation(field, message);
        }
    }

    public static ErrorResponse of(int status, String message, String path) {
        return new ErrorResponse(OffsetDateTime.now(ZoneOffset.UTC), status, message, path, List.of());
    }

    public static ErrorResponse of(int status, String message, String path, List<FieldViolation> errors) {
        return new ErrorResponse(OffsetDateTime.now(ZoneOffset.UTC), status, message, path, errors);
    }
}
