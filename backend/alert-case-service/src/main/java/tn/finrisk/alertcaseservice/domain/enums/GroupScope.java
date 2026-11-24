package tn.finrisk.alertcaseservice.domain.enums;

/**
 * Perimetre de regroupement d'alertes : CE QUI RATTACHE deux alertes.
 * ACCOUNT aujourd'hui (le compte emetteur — c'est la ou un fractionnement
 * se produit). Les deux autres valeurs sont prevues pour l'extension sans
 * migration (check SQL ck_ag_scope les accepte deja).
 */
public enum GroupScope {
    /** Meme compte emetteur. */
    ACCOUNT,
    /** Meme client (tous ses comptes). */
    CUSTOMER,
    /** Meme beneficiaire (compte ou nom normalise). */
    BENEFICIARY
}
