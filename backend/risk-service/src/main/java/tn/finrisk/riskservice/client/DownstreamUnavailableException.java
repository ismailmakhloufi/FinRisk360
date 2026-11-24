package tn.finrisk.riskservice.client;

/**
 * Le service distant est injoignable (refus de connexion) ou repond en erreur.
 * Sera mappee en HTTP 503 par le GlobalExceptionHandler (etape 8) : ce n'est
 * pas une erreur du client, c'est une indisponibilite d'infrastructure.
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
