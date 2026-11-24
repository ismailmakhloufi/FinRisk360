package tn.finrisk.alertcaseservice.client.exceptions;

/**
 * Service distant arrete, inaccessible ou en erreur (5xx) : mappee -> HTTP 503
 * (« dependance indisponible ») par GlobalExceptionHandler. La cause est
 * conservee pour le log serveur, jamais exposee au client.
 */
public class DownstreamUnavailableException extends RuntimeException {

    private final String serviceName;

    public DownstreamUnavailableException(String serviceName, Throwable cause) {
        super("Service distant indisponible : " + serviceName, cause);
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
