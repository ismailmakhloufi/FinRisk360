package tn.finrisk.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests du garde-fou SQL : chaque attaque classique (ecriture, multi-statement,
 * fuite de catalogue, DoS, table hors whitelist) doit être refusee, et les
 * requetes legitimes acceptees avec LIMIT force.
 */
class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard();

    // ------------------------------------------------------------ acceptees

    @Test
    void selectSimpleAjouteLimit() {
        SqlGuard.Outcome out = guard.validate("SELECT * FROM transactions");
        assertTrue(out.ok());
        assertEquals("SELECT * FROM transactions LIMIT 50", out.sql());
    }

    @Test
    void selectAvecLimitInferieurEstConserve() {
        SqlGuard.Outcome out = guard.validate(
                "SELECT count(*) AS nb FROM transactions t WHERE t.account_id = 42");
        assertTrue(out.ok());
        assertEquals("SELECT count(*) AS nb FROM transactions t WHERE t.account_id = 42 LIMIT 50",
                out.sql());
    }

    @Test
    void selectJoinDeuxTablesWhitelist() {
        SqlGuard.Outcome out = guard.validate(
                "SELECT t.transaction_id FROM transactions t JOIN accounts a ON a.account_id = t.account_id");
        assertTrue(out.ok());
    }

    @Test
    void limitExcessiveEstPlafonne() {
        SqlGuard.Outcome out = guard.validate("SELECT * FROM transactions LIMIT 100000");
        assertTrue(out.ok());
        assertTrue(out.sql().endsWith("LIMIT 50"), out.sql());
    }

    @Test
    void pointVirguleFinalTolere() {
        SqlGuard.Outcome out = guard.validate("SELECT * FROM accounts;");
        assertTrue(out.ok());
        assertFalse(out.sql().contains(";"));
    }

    // ------------------------------------------------------------ refusees

    @Test
    void refusUpdate() {
        SqlGuard.Outcome out = guard.validate("UPDATE transactions SET amount = 0");
        assertFalse(out.ok());
        assertTrue(out.error().contains("SELECT"));
    }

    @Test
    void refusDelete() {
        assertFalse(guard.validate("DELETE FROM transactions").ok());
    }

    @Test
    void refusDrop() {
        assertFalse(guard.validate("DROP TABLE transactions").ok());
    }

    @Test
    void refusSelectInto() {
        assertFalse(guard.validate("SELECT * INTO copy_tx FROM transactions").ok());
    }

    @Test
    void refusMultiStatementInjection() {
        SqlGuard.Outcome out = guard.validate(
                "SELECT * FROM transactions; DELETE FROM transactions");
        assertFalse(out.ok());
    }

    @Test
    void refusUnionCatalogue() {
        assertFalse(guard.validate(
                "SELECT account_number FROM accounts UNION SELECT rolname FROM pg_roles").ok());
    }

    @Test
    void refusLectureCatalogue() {
        assertFalse(guard.validate("SELECT * FROM pg_catalog.pg_shadow").ok());
        assertFalse(guard.validate("SELECT * FROM information_schema.tables").ok());
    }

    @Test
    void refusDosPgSleep() {
        assertFalse(guard.validate("SELECT pg_sleep(60)").ok());
    }

    @Test
    void refusTableHorsWhitelist() {
        SqlGuard.Outcome out = guard.validate("SELECT * FROM pg_stat_activity");
        assertFalse(out.ok());
        assertTrue(out.error().contains("non autorisee"));
    }

    @Test
    void refusRequeteVide() {
        assertFalse(guard.validate(null).ok());
        assertFalse(guard.validate("   ").ok());
    }

    @Test
    void refusSelectAvecGrant() {
        assertFalse(guard.validate("SELECT 1; GRANT SELECT ON transactions TO hacker").ok());
    }
}
