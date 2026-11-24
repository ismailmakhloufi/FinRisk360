package tn.finrisk.gateway.web.error;

/** Aucune route ne matche le chemin demande -> 404 emis PAR LE GATEWAY
 *  (pas par le service cible : on ne laisse jamais une requete inconnue
 *  atteindre un backend). */
public class RouteNotFoundException extends RuntimeException {

    public RouteNotFoundException(String uri) {
        super("Route inconnue : " + uri);
    }
}
