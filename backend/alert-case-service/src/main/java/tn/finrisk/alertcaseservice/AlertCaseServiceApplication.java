package tn.finrisk.alertcaseservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * M4 — alertes et investigation (port 8085).
 *
 * Flux métier : une evaluation risque (risk-service) devient une ALERTE,
 * une alerte devient une CASE (enquete), la case avance par COMMENTAIRES
 * et se cloture — chaque mutation laisse une trace dans audit_logs.
 *
 * @EnableScheduling : l'AGENT NOCTURNE (NightAgentService) tourne en
 * background — tick horaire, fenetre 17h00->09h00.
 */
@SpringBootApplication
@EnableScheduling
public class AlertCaseServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AlertCaseServiceApplication.class, args);
	}
}
