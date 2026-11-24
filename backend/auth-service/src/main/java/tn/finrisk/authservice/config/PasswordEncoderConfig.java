package tn.finrisk.authservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Hashage des mots de passe : BCrypt via spring-security-crypto SEULEMENT
 * (pas de starter-security => aucune chaine de filtres Spring Security, le
 * service reste un service REST classique : c'est le gateway qui arme le
 * 401/403, comme le veut l'architecture §5).
 *
 * BCrypt = salage aleatoire inclus : deux comptes avec le meme mot de passe
 * ont deux hashes distincts ; le cout est volontairement lent (facteur 10
 * par defaut) pour freiner le crack hors-ligne si la table fuitait.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
