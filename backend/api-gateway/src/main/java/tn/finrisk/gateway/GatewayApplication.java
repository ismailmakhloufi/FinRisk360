package tn.finrisk.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import tn.finrisk.gateway.config.GatewayProperties;

/**
 * Point d'entree unique du backend (port 8080) — jalon M5 :
 *   1. verifie le JWT signe par auth-service (MÊME secret, contrat partage) ;
 *   2. applique le RBAC par route (read-roles / write-roles) ;
 *   3. proxye la requete vers le service cible en injectant les identites
 *      de CONFIANCE X-User-Id / X-User-Role / X-Forwarded-For.
 * Critere du jalon : 401 sans (ou avec un faux) token, 403 si le role est
 * valide mais insuffisant pour la route demandee.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
