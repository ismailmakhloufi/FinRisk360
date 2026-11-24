package tn.finrisk.riskservice.client;

/**
 * La ressource demandee n'existe pas chez le service distant (404 en amont).
 * Sera mappee en HTTP 404 par le GlobalExceptionHandler (etape 8).
 */
public class RemoteResourceNotFoundException extends RuntimeException {

    private final String resource;
    private final Long id;

    public RemoteResourceNotFoundException(String resource, Long id) {
        super(resource + " introuvable chez le service distant : id = " + id);
        this.resource = resource;
        this.id = id;
    }

    public String getResource() {
        return resource;
    }

    public Long getId() {
        return id;
    }
}
