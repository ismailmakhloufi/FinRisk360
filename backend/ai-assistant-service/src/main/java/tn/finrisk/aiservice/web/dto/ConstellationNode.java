package tn.finrisk.aiservice.web.dto;

import java.util.Map;

/**
 * Nœud du graphe d'investigation — UNE entité réelle de la plateforme,
 * jamais une invention du LLM : chaque nœud est né d'un appel API vérifiable
 * (customer, account, transaction...).
 *
 *   id     : identifiant technique stable, préfixé par le type
 *            (ex. "cust:42", "acc:7", "ben:SARL MedTech", "ben:acc:42")
 *   type   : CUSTOMER | ACCOUNT | TRANSACTION | BENEFICIARY | DEVICE
 *            | IP | COUNTRY | ALERT   (enum stable pour le front)
 *   label  : ce que l'analyste lit sur le graphe (nom, montant, compte)
 *   risk   : HIGH | MEDIUM | LOW — niveau CALCULÉ sur les données disponibles.
 *            Ce n'est PAS le fait d'être le centre du graphe : une transaction
 *            focale à 25/100 reste LOW, elle n'est « que » l'objet de l'alerte.
 *   investigationFocus : vrai si c'est le CENTRE de l'investigation (transaction
 *            focale, son client, son compte). La focale n'est pas une preuve
 *            de risque : c'est une notion de position dans le graphe.
 *   subtitle : ligne secondaire courte (type de compte, devise, pays...)
 *   meta   : détails enrichis affichés au clic (jamais vides de sens)
 */
public record ConstellationNode(
        String id,
        String type,
        String label,
        String risk,
        boolean investigationFocus,
        String subtitle,
        Map<String, Object> meta) {

    /** Fabrique d'un nœud hors centre d'investigation. */
    public ConstellationNode(String id, String type, String label, String risk,
            String subtitle, Map<String, Object> meta) {
        this(id, type, label, risk, false, subtitle, meta);
    }

    /** Fabrique pratique : meta vide par défaut, hors centre. */
    public static ConstellationNode of(String id, String type, String label,
            String risk, String subtitle) {
        return new ConstellationNode(id, type, label, risk, false, subtitle, Map.of());
    }

    /** Fabrique d'un nœud CENTRE d'investigation (focale). */
    public static ConstellationNode focal(String id, String type, String label,
            String risk, String subtitle, Map<String, Object> meta) {
        return new ConstellationNode(id, type, label, risk, true, subtitle, meta);
    }
}
