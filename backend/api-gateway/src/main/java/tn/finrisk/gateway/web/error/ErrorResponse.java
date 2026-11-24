package tn.finrisk.gateway.web.error;

import java.util.List;

/** Charge utile d'erreur — meme forme que les services du projet. */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> errors) {

    public record FieldViolation(String field, String message) {
    }

    public static ErrorResponse of(int status, String message, String path) {
        return new ErrorResponse(status, reason(status), message, path, List.of());
    }

    private static String reason(int status) {
        return switch (status) {
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Error";
        };
    }
}
