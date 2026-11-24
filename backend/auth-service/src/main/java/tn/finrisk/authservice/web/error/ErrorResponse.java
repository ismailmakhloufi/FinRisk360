package tn.finrisk.authservice.web.error;

import java.util.List;

/** Charge utile d'erreur, meme forme que les autres services du projet (M1-M4). */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> errors) {

    public record FieldViolation(String field, String message) {
        public static FieldViolation of(String field, String message) {
            return new FieldViolation(field, message);
        }
    }

    public static ErrorResponse of(int status, String message, String path) {
        return new ErrorResponse(status, reason(status), message, path, List.of());
    }

    public static ErrorResponse of(int status, String message, String path,
                                   List<FieldViolation> violations) {
        return new ErrorResponse(status, reason(status), message, path, violations);
    }

    private static String reason(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Error";
        };
    }
}
