package tn.finrisk.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tn.finrisk.aiservice.service.DataQueryEngine.BoundSql;
import tn.finrisk.aiservice.service.DataQueryEngine.Kind;
import tn.finrisk.aiservice.service.DataQueryEngine.QueryIntent;

/**
 * Tests PURS du moteur SQL du chat : detection des questions en langage
 * naturel, construction SQL parametree, formatage des reponses. Aucune base
 * (l'execution est couverte par l'E2E sur le service vivant).
 */
class DataQueryEngineTest {

    // ------------------------------------------------------------ detection

    @Test
    void detectCompteEtBeneficiaire() {
        QueryIntent q = DataQueryEngine.detect(
                "Est-ce que le compte 42 a fait une transaction avec le bénéficiaire ACME ?");
        assertEquals(Kind.TX_BETWEEN, q.kind());
        assertEquals(42L, q.accountId());
        assertTrue(q.beneficiaryName().startsWith("ACME"), q.beneficiaryName());
    }

    @Test
    void detectBeneficiaireSansAccentNiMajuscule() {
        QueryIntent q = DataQueryEngine.detect(
                "le compte 7 a paye le beneficiaire acme");
        assertEquals(Kind.TX_BETWEEN, q.kind());
        assertTrue(q.beneficiaryName().toLowerCase().contains("acme"), q.beneficiaryName());
    }

    @Test
    void detectBeneficiaireEntreGuillemets() {
        QueryIntent q = DataQueryEngine.detect(
                "Compte 9 et bénéficiaire « Société Générale » ?");
        assertEquals(Kind.TX_BETWEEN, q.kind());
        assertEquals("Société Générale", q.beneficiaryName());
    }

    @Test
    void detectBeneficiaireNomPlusieursMotsSansVider() {
        QueryIntent q = DataQueryEngine.detect(
                "Compte 3 transaction bénéficiaire Societe Generale sur Paris");
        // « sur » est un mot vide : le nom s'arrete avant
        assertEquals("Societe Generale", q.beneficiaryName());
    }

    @Test
    void detectBeneficiaireParRib() {
        QueryIntent q = DataQueryEngine.detect(
                "Compte 5 transaction bénéficiaire FR7630006000011234567890189");
        assertEquals(Kind.TX_BETWEEN, q.kind());
        assertEquals("FR7630006000011234567890189", q.beneficiaryRib());
        assertNull(q.beneficiaryName());
    }

    @Test
    void detectCompteSeulAvecTransaction() {
        QueryIntent q = DataQueryEngine.detect("Liste les virements du compte 12");
        assertEquals(Kind.TX_BY_ACCOUNT, q.kind());
        assertEquals(12L, q.accountId());
        assertNull(q.beneficiaryName());
    }

    @Test
    void detectCompteAvecNumeroLeterre() {
        QueryIntent q = DataQueryEngine.detect(
                "transactions du compte FR7611315000011234567890138");
        assertEquals(Kind.TX_BY_ACCOUNT, q.kind());
        assertEquals("FR7611315000011234567890138", q.accountNumber());
    }

    @Test
    void detectComptage() {
        QueryIntent q = DataQueryEngine.detect("Combien de transactions du compte 5 ?");
        assertEquals(Kind.COUNT_TX, q.kind());
        assertEquals(5L, q.accountId());
    }

    @Test
    void detectComptageAvecBeneficiaire() {
        QueryIntent q = DataQueryEngine.detect(
                "Combien de paiements le compte 5 a fait au bénéficiaire ACME ?");
        assertEquals(Kind.COUNT_TX, q.kind());
        assertTrue(q.beneficiaryName().contains("ACME"));
    }

    @Test
    void detectCompteAvecNomApresAvec() {
        // Question naturelle sans le mot « beneficiaire » : le nom suit « avec »
        QueryIntent q = DataQueryEngine.detect(
                "quand le compte 19 fait une transactions avec le test moteur");
        assertEquals(Kind.TX_BETWEEN, q.kind());
        assertEquals(19L, q.accountId());
        assertEquals("test moteur", q.beneficiaryName());
    }

    @Test
    void detectVersCompteNestPasUnBeneficiaire() {
        // « vers le compte 18 » = une destination de compte, pas un tiers
        QueryIntent q = DataQueryEngine.detect("virements vers le compte 18");
        assertEquals(Kind.TX_BY_ACCOUNT, q.kind());
        assertEquals(18L, q.accountId());
        assertNull(q.beneficiaryName());
    }

    @Test
    void ignoreQuestionDAlerteSansCompte() {
        // Chat d'alerte existant : pas de compte mentionne -> pas de SQL
        assertNull(DataQueryEngine.detect("Quel est le bénéficiaire de cette transaction ?"));
        assertNull(DataQueryEngine.detect("Pourquoi ce score ?"));
        assertNull(DataQueryEngine.detect("Raconte-moi la constellation"));
    }

    @Test
    void ignoreQuestionSansTransactionNiDonnee() {
        assertNull(DataQueryEngine.detect("Le compte 42 est-il gelé ?"));
    }

    // ------------------------------------------------------------ SQL parametre

    @Test
    void sqlEntreCompteEtBeneficiaire() {
        QueryIntent q = DataQueryEngine.detect(
                "compte 42 transaction bénéficiaire ACME");
        BoundSql bound = DataQueryEngine.buildSql(q);
        assertTrue(bound.sql().contains("t.account_id = ?"), bound.sql());
        assertTrue(bound.sql().contains("beneficiary_name ILIKE ?"), bound.sql());
        assertEquals(List.of(42L, "%ACME%"), bound.params());
        assertFalse(bound.sql().contains("42"), "aucune valeur concatenee");
    }

    @Test
    void sqlComptage() {
        BoundSql bound = DataQueryEngine.buildSql(DataQueryEngine.detect(
                "combien de transactions du compte 8"));
        assertTrue(bound.sql().startsWith("SELECT count(*) AS nb"), bound.sql());
        assertEquals(List.of(8L), bound.params());
    }

    @Test
    void sqlParRibBeneficiaire() {
        BoundSql bound = DataQueryEngine.buildSql(DataQueryEngine.detect(
                "compte 1 transaction bénéficiaire TN5900000000123456789012"));
        assertTrue(bound.sql().contains("t.beneficiary_rib = ?"), bound.sql());
        assertEquals("TN5900000000123456789012", bound.params().get(1));
    }

    // ------------------------------------------------------------ formatage

    @Test
    void formatVideSansSql() {
        QueryIntent q = DataQueryEngine.detect("compte 42 transaction bénéficiaire ACME");
        String text = DataQueryEngine.formatIntent(q, List.of());
        assertTrue(text.contains("Aucune transaction"), text);
        assertTrue(text.contains("ACME"), text);
        assertFalse(text.contains("SELECT"), "le SQL ne doit jamais etre affiche");
    }

    @Test
    void formatComptage() {
        QueryIntent q = DataQueryEngine.detect("combien de transactions du compte 8");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nb", 3L);
        String text = DataQueryEngine.formatIntent(q, List.of(row));
        assertEquals("3 transactions sur le compte 8.", text);
    }

    @Test
    void formatComptageAvecBeneficiaire() {
        QueryIntent q = DataQueryEngine.detect(
                "combien de transactions du compte 5 vers ACME");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nb", 7L);
        String text = DataQueryEngine.formatIntent(q, List.of(row));
        assertTrue(text.contains("7 transactions vers « ACME » sur le compte 5"), text);
    }

    @Test
    void formatLignePropreSansSqlEtSansNull() {
        QueryIntent q = DataQueryEngine.detect("compte 19 transaction avec test moteur");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("transaction_id", 46L);
        row.put("amount", new java.math.BigDecimal("100.00"));
        row.put("currency", "TND");
        row.put("transaction_type", "TRANSFER");
        row.put("transaction_date", java.sql.Timestamp.valueOf("2026-10-11 15:03:14"));
        row.put("status", "COMPLETED");
        row.put("beneficiary_name", "test moteur");
        row.put("beneficiary_rib", null);
        row.put("country", "TN");
        row.put("channel", "API");
        String text = DataQueryEngine.formatIntent(q, List.of(row));
        assertTrue(text.startsWith("Oui — "), text);
        assertTrue(text.contains("#46 — 100 TND · TRANSFER · 2026-10-11 15:03 · COMPLETED"
                + " · vers test moteur (TN, API)"), text);
        assertFalse(text.contains("SELECT"), text);
        assertFalse(text.contains("beneficiary_rib"), "champs vides omis");
    }

    @Test
    void formatTronqueALignesAffichees() {
        QueryIntent q = DataQueryEngine.detect("liste les virements du compte 5");
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("transaction_id", (long) i);
            rows.add(row);
        }
        String text = DataQueryEngine.formatIntent(q, rows);
        assertTrue(text.contains("(les 10 premieres)"), text);
        assertFalse(text.contains("#11 "), text);
    }

    @Test
    void formatGeneriqueReponseVide() {
        String text = DataQueryEngine.formatGeneric(List.of());
        assertTrue(text.contains("Aucune ligne"));
        assertFalse(text.contains("SELECT"));
    }

    @Test
    void prettyChiffresEtNull() {
        assertEquals("1200.5", DataQueryEngine.pretty(new java.math.BigDecimal("1200.50")));
        assertEquals("-", DataQueryEngine.pretty(null));
    }
}
