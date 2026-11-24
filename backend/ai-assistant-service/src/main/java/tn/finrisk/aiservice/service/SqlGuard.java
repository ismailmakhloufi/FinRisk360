package tn.finrisk.aiservice.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Garde-fou du chat SQL : LE SEUL point de passage entre le SQL genere
 * (motifs codes en dur ou LLM) et la base. Defense en profondeur, dans
 * l'ordre :
 *
 *   1. une seule instruction, qui commence par SELECT (jamais de multi-statement
 *      ni de ; qui en decoupe une 2e) ;
 *   2. aucun mot interdit (INSERT/UPDATE/DELETE/DDL/GRANT/COPY, fuites via
 *      pg_read_file / pg_catalog / information_schema, DoS via pg_sleep...) ;
 *   3. toute table citee (FROM/JOIN) est dans la whitelist publique ;
 *   4. LIMIT impose (50 max) : meme une requete legitime ne peut pas vider
 *      la table dans la memoire du chat.
 *
 * Meme si un SQL malveillant passait ici, le user finrisk_ro (droits SELECT
 * seuls, voir 10-add-ro-user.sql) ne pourrait rien ecrire — double verrou.
 */
@Component
public class SqlGuard {

    /** Lignes maximum renvoyees au chat (et donc au LLM). */
    public static final int MAX_ROWS = 50;

    /** Tables interrogeables par le chat. Pas de schema : public uniquement. */
    static final Set<String> ALLOWED_TABLES = Set.of(
            "transactions", "accounts", "customers", "merchants",
            "risk_assessments", "alerts", "alert_groups", "cases");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|"
                    + "copy|call|execute|exec|merge|union|values|into|setrole|"
                    + "pg_read_file|pg_read_binary_file|pg_ls_dir|pg_sleep|"
                    + "pg_terminate_backend|pg_cancel_backend|lo_import|lo_export|"
                    + "dblink|dblink_connect|current_setting|set_config|"
                    + "information_schema|pg_catalog|pg_proc|pg_shadow)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:from|join)\\s+([a-z_][a-z0-9_]*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LIMIT = Pattern.compile(
            "\\blimit\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    /** Resultat d'une validation : SQL final (avec LIMIT) ou message d'erreur. */
    public record Outcome(boolean ok, String sql, String error) {
    }

    /**
     * Valide le SQL et renvoie la version sure (LIMIT force a MAX_ROWS max) ;
     * sinon un message d'erreur lisible destiné à l'analyste.
     */
    public Outcome validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return refuse("requete vide");
        }
        String sql = rawSql.trim();
        // une seule instruction : pas de ; interne (le ; final tolere)
        String body = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        if (body.contains(";")) {
            return refuse("plusieurs instructions refusees");
        }
        if (!body.toLowerCase(Locale.ROOT).startsWith("select")) {
            return refuse("seules les requetes SELECT sont autorisees");
        }
        Matcher forbidden = FORBIDDEN.matcher(body);
        if (forbidden.find()) {
            return refuse("mot interdit dans la requete : " + forbidden.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher refs = TABLE_REF.matcher(body);
        while (refs.find()) {
            String table = refs.group(1).toLowerCase(Locale.ROOT);
            if (!ALLOWED_TABLES.contains(table)) {
                return refuse("table non autorisee : " + table);
            }
        }
        return ok(forceLimit(body));
    }

    private Outcome ok(String sql) {
        return new Outcome(true, sql, null);
    }

    private Outcome refuse(String error) {
        return new Outcome(false, null, error);
    }

    /**
     * Impose un LIMIT : absent -> ajoute MAX_ROWS ; superieur -> plafonne ;
     * sans chiffre (LIMIT :n ?) -> remplace par MAX_ROWS. Les params nommes
     * (:n) ne passent pas ici car nos requetes pre-ecrites sont sans param.
     */
    static String forceLimit(String sql) {
        Matcher m = LIMIT.matcher(sql);
        if (!m.find()) {
            return sql + " LIMIT " + MAX_ROWS;
        }
        long requested;
        try {
            requested = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            requested = MAX_ROWS;
        }
        String capped = String.valueOf(Math.min(requested, MAX_ROWS));
        return sql.substring(0, m.start(1)) + capped + sql.substring(m.end(1));
    }

    /** Tables autorisees, expose pour les tests. */
    public static List<String> allowedTables() {
        return List.copyOf(ALLOWED_TABLES);
    }
}
