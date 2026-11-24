package tn.finrisk.aiservice.client.exceptions;

/**
 * 404 distant traduit : la ressource demandee n'existe pas chez le service
 * appele (alerte, evaluation...). Mappee -> HTTP 404 par GlobalExceptionHandler.
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
