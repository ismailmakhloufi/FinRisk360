package tn.finrisk.gateway.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.AntPathMatcher;

/**
 * Table de routes statiques lue dans application.yml (M0 : pas de
 * discovery, les cibles sont des URLs en dur — le meme principe que
 * RestClient des autres services).
 *
 * Le RBAC est MATRICIEL (§18) : chaque route porte ses propres listes de
 * roles en lecture et en ecriture ; il n'y a PAS de hierarchie de roles
 * (un ANALYST n'heritera pas des droits d'un MANAGER, ni l'inverse).
 *
 * Une route reference un UPSTREAM NOMME (service: customer) plutot que
 * d'embarquer l'URL : les ports vivent tous au meme endroit (upstreams),
 * et les tests peuvent surcharger une seule cle scalaire sans toucher a
 * l'ordre de la liste de routes.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(List<Route> routes, List<String> publicPaths,
                                Map<String, String> upstreams) {

    public record Route(String path, String service,
                        List<String> readRoles, List<String> writeRoles) {
    }

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    public static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    public GatewayProperties {
        routes = routes == null ? List.of() : routes;
        publicPaths = publicPaths == null ? List.of() : publicPaths;
        upstreams = upstreams == null ? Map.of() : upstreams;
    }

    /** Premiere route qui matche le chemin (l'ordre du yml compte). */
    public Route match(String uri) {
        for (Route route : routes) {
            if (MATCHER.match(route.path(), uri)) {
                return route;
            }
        }
        return null;
    }

    /** URL absolue du service cible. Configuration invalide -> echec
     *  BRUYANT plutot que de proxyer vers nulle part. */
    public String target(Route route) {
        String url = upstreams.get(route.service());
        if (url == null) {
            throw new IllegalStateException(
                    "Upstream inconnu pour la route " + route.path() + " : " + route.service());
        }
        return url;
    }

    /** Chemin ouvert : le gateway ne verifie pas le token (le service
     *  cible, lui, se protege — defense en profondeur). */
    public boolean isPublic(String uri) {
        for (String pattern : publicPaths) {
            if (MATCHER.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /** Roles exiges selon la nature de l'operation : lecture (methodes
     *  sure) vs ecriture (toute le reste). */
    public List<String> requiredRoles(Route route, String httpMethod) {
        return SAFE_METHODS.contains(httpMethod.toUpperCase())
                ? route.readRoles()
                : route.writeRoles();
    }
}
