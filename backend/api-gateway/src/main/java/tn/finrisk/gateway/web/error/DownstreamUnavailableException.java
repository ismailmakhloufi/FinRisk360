package tn.finrisk.gateway.web.error;

/** La cible de la route est arretee ou refuse la connexion -> 503.
 *  distinction importante : 503 = panne d'INFRASTRUCTURE, alors que les
 *  4xx de la metier proviennent du service cible et sont proxyes tels quels. */
public class DownstreamUnavailableException extends RuntimeException {

    private final String target;

    public DownstreamUnavailableException(String target) {
        super("Service cible indisponible : " + target + " (arrete ou en erreur)");
        this.target = target;
    }

    public String getTarget() {
        return target;
    }
}
