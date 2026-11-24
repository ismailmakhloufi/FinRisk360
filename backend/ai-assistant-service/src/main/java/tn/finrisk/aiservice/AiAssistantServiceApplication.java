package tn.finrisk.aiservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * M6 — assistant IA (port 8086).
 *
 * Aucune table : le service ne stocke rien, il AGRÈGE des données lues chez
 * les autres services (règle M0 : par API, jamais leur SQL) et produit :
 *   - Analyze : signaux que l'analyste ne voit pas (statistiques, vélocité,
 *     réseau/graph, séquences temporelles)
 *   - Explain : explication du score de risk-service + raisonnement IA
 *   - Chat : dialogue avec l'analyste sur le contexte d'une alerte
 *
 * L'avis IA est rendu À CÔTÉ du score : il ne décide jamais seul,
 * l'analyste garde la décision finale.
 */
@SpringBootApplication
public class AiAssistantServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiAssistantServiceApplication.class, args);
	}
}
