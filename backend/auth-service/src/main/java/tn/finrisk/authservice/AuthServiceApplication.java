package tn.finrisk.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * M5 — authentification JWT / RBAC (port 8087).
 *
 * Le service est le SEUL a signer les tokens (HMAC-SHA256, secret partage
 * avec le gateway via auth.jwt.secret) et le seul a lire/ ecrire "users".
 * Le gateway VALIDE les tokens de bout en bout ; ici on valide aussi nos
 * propres routes protegees (defense en profondeur : passer le gateway
 * ne suffit pas).
 */
@SpringBootApplication
public class AuthServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthServiceApplication.class, args);
	}
}
