package tn.finrisk.aiservice.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tn.finrisk.aiservice.client.LlmClient;

/**
 * Chat SQL de l'analyste : « le compte 42 a-t-il paye le beneficiaire Y ? »
 *
 * Deux moteurs, meme garde-fou (SqlGuard) et meme base (finrisk_ro,
 * lecture seule) :
 *
 *   1. MOTIFS PRE-ECRITS (SQL_INTENT) — les questions typiques (compte +
 *      beneficiaire, liste de transactions d'un compte, comptage) sont
 *      resolues par du SQL parametre, fiable et immediat ;
 *   2. LLM (SQL_LLM) — a defaut, le LLM genere un SELECT a partir du schema
 *      connu, le garde-fou le valide, puis execution. Si le LLM ou le garde
 *      refusent : message honnête, jamais d'erreur 500.
 *
 * DECLENCHEMENT VOLONTAIREMENT ETROIT : une question SQL doit mentionner un
 * compte (numero, id, ou RIB). Une question sans compte reste au chat
 * d'alerte existant (beneficiaire de CETTE transaction, etc.) — on ne
 * detourne pas le chat contextuel d'une alerte.
 */
@Component
public class DataQueryEngine {

    private static final Logger log = LoggerFactory.getLogger(DataQueryEngine.class);

    /** Lignes affichees dans la reponse (le garde-fou en autorise 50). */
    static final int SHOWN_ROWS = 10;

    private final JdbcTemplate jdbc;
    private final SqlGuard guard;
    private final LlmClient llmClient;

    public DataQueryEngine(JdbcTemplate jdbc, SqlGuard guard, LlmClient llmClient) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.llmClient = llmClient;
    }

    // ------------------------------------------------------------------
    // Detection (statique, testable sans base)
    // ------------------------------------------------------------------

    enum Kind { COUNT_TX, TX_BETWEEN, TX_BY_ACCOUNT }

    /** Parametres extraits de la question en langage naturel. */
    record QueryIntent(Kind kind, Long accountId, String accountNumber,
            String beneficiaryName, String beneficiaryRib) {
    }

    /** SQL + parametres lies (? placeholders — jamais de concat de valeur). */
    record BoundSql(String sql, List<Object> params) {
    }

    private static final Pattern ACCOUNT_ID = Pattern.compile(
            "\\bcompte\\s*(?:n[°o]\\s*|num[ée]ro\\s+)?#?(\\d{1,9})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RIB = Pattern.compile(
            "\\b([A-Z]{2}\\d{2}[A-Z0-9]{10,24})\\b");
    private static final Pattern BENEFICIAIRE_WORD = Pattern.compile(
            "b[éeè]n[éeè]ficiaire", Pattern.CASE_INSENSITIVE);
    private static final Pattern BENEF_RIB = Pattern.compile(
            "b[éeè]n[éeè]ficiaire\\s+(?:nom\\s+|#|n[°o]\\s*)?([A-Z]{2}\\d{2}[A-Z0-9]{10,24})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BENEF_NAME_QUOTED = Pattern.compile(
            "b[éeè]n[éeè]ficiaire\\s+(?:nom\\s+)?[«\"']([^»\"']{2,60})[»\"']",
            Pattern.CASE_INSENSITIVE);
    // Capture jusqu'a 4 mots, en s'arretant aux mots vides (le, de, sur...)
    // : « beneficiaire Societe Generale sur » -> « Societe Generale ».
    private static final String STOP_WORDS =
            "(?!(?:le|la|de|du|des|les|un|une|et|au|aux|en|sur|avec|"
                    + "pour|ce|cet|cette|a|dans|par|que|qui|est|a[ -]?t[ -]?il|"
                    + "compte|rib|solde|client|beneficiaire)\\b)";
    private static final String NAME_WORDS =
            "([\\p{L}][\\w'.-]*(?:\\s+" + STOP_WORDS + "[\\p{L}][\\w'.-]*){0,3})";
    private static final Pattern BENEF_NAME = Pattern.compile(
            "b[éeè]n[éeè]ficiaire\\s+(?:nom\\s+)?" + NAME_WORDS,
            Pattern.CASE_INSENSITIVE);
    // Meme sans le mot « beneficiaire » : « compte 19 ... avec le test
    // moteur », « virements vers ACME ». Le nom ne peut pas commencer par
    // compte/rib/solde... (« vers le compte 18 » = un compte, pas un tiers).
    private static final Pattern NAME_AVEC = Pattern.compile(
            "\\b(?:avec|vers|a\\s+l['’]|au)\\s+(?:le\\s+|la\\s+|l['’]\\s*|les\\s+)?"
                    + NAME_WORDS,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMBIEN = Pattern.compile(
            "\\bcombien\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TX_WORD = Pattern.compile(
            "\\b(transaction\\w*|virement\\w*|paiement\\w*|op[ée]ration\\w*|flux|pay\\w*|vir\\w*)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_WORD = Pattern.compile(
            "\\b(combien|liste|cherche|trouve|montre|solde|historique)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Extrait l'intention SQL de la question, ou null si la question n'est
     * pas une question de donnees sur un compte.
     */
    static QueryIntent detect(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Long accountId = null;
        String accountNumber = null;
        Matcher idMatcher = ACCOUNT_ID.matcher(message);
        if (idMatcher.find()) {
            accountId = Long.parseLong(idMatcher.group(1));
        }
        boolean hasTxWord = TX_WORD.matcher(message).find();
        boolean hasBenefWord = BENEFICIAIRE_WORD.matcher(message).find();

        String benefName = null;
        String benefRib = null;
        // Ordre : guillemets > RIB immediatement apres « beneficiaire » >
        // nom nu apres « beneficiaire » > nom apres « avec/vers ».
        // Un RIB (FR76...) commence par des lettres : sans ce classement, il
        // serait capture comme un « nom ».
        Matcher quoted = BENEF_NAME_QUOTED.matcher(message);
        if (quoted.find()) {
            benefName = quoted.group(1).trim();
        } else if (hasBenefWord) {
            Matcher rib = BENEF_RIB.matcher(message);
            if (rib.find()) {
                benefRib = rib.group(1).toUpperCase(Locale.ROOT);
            } else {
                Matcher bare = BENEF_NAME.matcher(message);
                if (bare.find()) {
                    benefName = bare.group(1).trim();
                }
            }
        }
        if (benefName == null && benefRib == null) {
            Matcher avec = NAME_AVEC.matcher(message);
            if (avec.find()) {
                String name = avec.group(1).trim();
                // « vers le compte 18 » : « compte » a ete capture comme mot
                // de debut — ce n'est pas un nom de tiers.
                String flat = name.toLowerCase(Locale.ROOT)
                        .replaceAll("[éèêë]", "e").replaceAll("[àâä]", "a")
                        .replaceAll("[îï]", "i").replaceAll("[ôö]", "o")
                        .replaceAll("[ùûü]", "u");
                // Frontiere de mot : « vers le compte 18 » rejeté (compte =
                // un compte), mais « beneficiairezzz » reste un nom valide.
                if (!flat.matches("(compte|rib|solde|client|beneficiaire)\\b.*")) {
                    benefName = name;
                }
            }
        }

        // Compte non identifie par "compte N" : peut-etre un RIB/compte number
        if (accountId == null && benefRib == null) {
            Matcher rib = RIB.matcher(message);
            if (rib.find() && hasTxWord) {
                accountNumber = rib.group(1);
            }
        }

        boolean hasAccount = accountId != null || accountNumber != null;
        if (!hasAccount) {
            return null; // pas de compte -> on laisse le chat d'alerte
        }
        boolean hasBenef = benefName != null || benefRib != null;

        if (COMBIEN.matcher(message).find()) {
            return new QueryIntent(Kind.COUNT_TX, accountId, accountNumber, benefName, benefRib);
        }
        if (hasBenef) {
            return new QueryIntent(Kind.TX_BETWEEN, accountId, accountNumber, benefName, benefRib);
        }
        if (hasTxWord) {
            return new QueryIntent(Kind.TX_BY_ACCOUNT, accountId, accountNumber, null, null);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Construction SQL (parametre — aucune valeur concatenee)
    // ------------------------------------------------------------------

    static final String TX_COLUMNS =
            "t.transaction_id, t.amount, t.currency, t.transaction_type, "
                    + "t.transaction_date, t.status, t.beneficiary_name, "
                    + "t.beneficiary_rib, t.country, t.channel";

    static BoundSql buildSql(QueryIntent q) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder();

        if (q.accountId() != null) {
            where.append("t.account_id = ?");
            params.add(q.accountId());
        } else if (q.accountNumber() != null) {
            where.append("t.account_id IN (SELECT account_id FROM accounts WHERE account_number = ?)");
            params.add(q.accountNumber());
        }
        if (q.beneficiaryRib() != null) {
            if (!where.isEmpty()) {
                where.append(" AND ");
            }
            where.append("t.beneficiary_rib = ?");
            params.add(q.beneficiaryRib());
        } else if (q.beneficiaryName() != null) {
            if (!where.isEmpty()) {
                where.append(" AND ");
            }
            where.append("t.beneficiary_name ILIKE ?");
            params.add("%" + q.beneficiaryName() + "%");
        }

        if (q.kind() == Kind.COUNT_TX) {
            String sql = "SELECT count(*) AS nb"
                    + (where.isEmpty() ? "" : " FROM transactions t WHERE " + where);
            return new BoundSql(sql, params);
        }
        String sql = "SELECT " + TX_COLUMNS + " FROM transactions t"
                + (where.isEmpty() ? "" : " WHERE " + where)
                + " ORDER BY t.transaction_date DESC";
        return new BoundSql(sql, params);
    }

    // ------------------------------------------------------------------
    // Execution + reponse
    // ------------------------------------------------------------------

    /** Reponse SQL pour le chat, ou null si ce n'est PAS une question de donnees. */
    public QueryAnswer tryAnswer(String message) {
        QueryIntent intent = detect(message);
        if (intent != null) {
            BoundSql bound = buildSql(intent);
            return execute("SQL_INTENT", bound.sql(), bound.params(), intent);
        }
        return llmFallback(message);
    }

    /**
     * Execution : le SQL reste dans les LOGS (audit) mais n'est JAMAIS
     * montre a l'analyste — la reponse est en langage naturel.
     */
    private QueryAnswer execute(String mode, String sql, List<Object> params, QueryIntent intent) {
        SqlGuard.Outcome outcome = guard.validate(sql);
        if (!outcome.ok()) {
            log.warn("ChatSQL garde-fou refuse : {} | SQL : {}", outcome.error(), sql);
            return new QueryAnswer(mode, "Je ne peux pas repondre a cette question ("
                    + outcome.error() + "). Reformule-la ou demande a un admin.");
        }
        try {
            long start = System.currentTimeMillis();
            List<Map<String, Object>> rows = jdbc.queryForList(outcome.sql(), params.toArray());
            long ms = System.currentTimeMillis() - start;
            log.info("ChatSQL mode={} rows={} ms={} sql={}", mode, rows.size(), ms, outcome.sql());
            String answer = intent != null ? formatIntent(intent, rows) : formatGeneric(rows);
            return new QueryAnswer(mode, answer);
        } catch (Exception e) {
            log.warn("ChatSQL echec execution : {}", e.getMessage());
            return new QueryAnswer(mode, "Base de donnees indisponible pour cette question ("
                    + e.getClass().getSimpleName() + "). Reessaie plus tard.");
        }
    }

    // ------------------------------------------------------------------
    // Formatage : reponse en langage naturel, JAMAIS de SQL affiche
    // ------------------------------------------------------------------

    /** Reponse des motifs pre-ecrits : phrase d'abord, puis lignes propres. */
    static String formatIntent(QueryIntent q, List<Map<String, Object>> rows) {
        String sujet = "compte " + (q.accountId() != null ? q.accountId() : q.accountNumber());
        String vers = vers(q);
        if (q.kind() == Kind.COUNT_TX) {
            long nb = rows.isEmpty() ? 0L : ((Number) rows.get(0).get("nb")).longValue();
            return nb + (nb > 1 ? " transactions" : " transaction") + vers
                    + " sur le " + sujet + ".";
        }
        if (rows.isEmpty()) {
            return "Aucune transaction" + vers + " sur le " + sujet
                    + " dans les donnees de la banque.";
        }
        StringBuilder sb = new StringBuilder();
        if (q.kind() == Kind.TX_BETWEEN) {
            sb.append("Oui — ");
        }
        sb.append(rows.size()).append(rows.size() > 1 ? " transactions" : " transaction")
                .append(vers).append(" sur le ").append(sujet);
        if (rows.size() >= SHOWN_ROWS) {
            sb.append(" (les ").append(SHOWN_ROWS).append(" premieres)");
        }
        sb.append(" :\n");
        int shown = 0;
        for (Map<String, Object> row : rows) {
            if (shown++ >= SHOWN_ROWS) {
                break;
            }
            sb.append(shown).append(". ").append(txLine(row)).append('\n');
        }
        return sb.toString().trim();
    }

    /** Reponse du fallback LLM (colonnes arbitraires) : generique mais propre. */
    static String formatGeneric(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "Aucune ligne ne correspond a cette question dans les donnees de la banque.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(rows.size() >= SHOWN_ROWS
                ? rows.size() + " ligne(s) — les " + SHOWN_ROWS + " premieres :\n"
                : rows.size() + " ligne(s) :\n");
        int shown = 0;
        for (Map<String, Object> row : rows) {
            if (shown++ >= SHOWN_ROWS) {
                break;
            }
            sb.append(shown).append(". ").append(line(row)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String vers(QueryIntent q) {
        if (q.beneficiaryName() != null) {
            return " vers « " + q.beneficiaryName() + " »";
        }
        if (q.beneficiaryRib() != null) {
            return " vers le RIB " + q.beneficiaryRib();
        }
        return "";
    }

    /**
     * Ligne transaction lisible et courte :
     * `#46 — 100 TND · TRANSFER · 2026-10-11 15:03 · COMPLETED · vers test moteur (TN, API)`.
     * Les champs absents sont simplement omis.
     */
    static String txLine(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        Object id = row.get("transaction_id");
        if (id != null) {
            sb.append('#').append(id).append(" — ");
        }
        Object amount = row.get("amount");
        if (amount != null) {
            sb.append(pretty(amount));
            Object currency = row.get("currency");
            if (currency != null) {
                sb.append(' ').append(currency);
            }
        }
        appendIfPresent(sb, " · ", pretty(row.get("transaction_type")));
        appendIfPresent(sb, " · ", pretty(row.get("transaction_date")));
        appendIfPresent(sb, " · ", pretty(row.get("status")));
        appendIfPresent(sb, " · vers ", pretty(row.get("beneficiary_name")));
        appendIfPresent(sb, " · RIB ", pretty(row.get("beneficiary_rib")));
        String country = pretty(row.get("country"));
        String channel = pretty(row.get("channel"));
        if (!"-".equals(country) || !"-".equals(channel)) {
            sb.append(" (");
            if (!"-".equals(country)) {
                sb.append(country);
            }
            if (!"-".equals(channel)) {
                if (!"-".equals(country)) {
                    sb.append(", ");
                }
                sb.append(channel);
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String sep, String value) {
        if (value != null && !value.isEmpty() && !"-".equals(value)) {
            sb.append(sep).append(value);
        }
    }

    /** Ligne generique (fallback LLM) : `col=val | col=val`. */
    static String line(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : new LinkedHashMap<>(row).entrySet()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(e.getKey()).append('=').append(pretty(e.getValue()));
        }
        return sb.toString();
    }

    static String pretty(Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Timestamp ts) {
            return java.time.LocalDateTime.ofInstant(ts.toInstant(), java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        return value.toString();
    }

    // ------------------------------------------------------------------
    // Fallback LLM (generation de SELECT a partir du schema)
    // ------------------------------------------------------------------

    static final String SQL_SYSTEM_PROMPT = """
            Tu generes du SQL PostgreSQL en LECTURE SEULE pour la base d'une banque
            (analyse anti-fraude). Reponds UNIQUEMENT par une requete SELECT valide,
            une seule instruction, sans markdown, sans explication, sans ';'.
            Autorise : tables transactions, accounts, customers, merchants, risk_assessments,
            alerts. Interdit : INSERT/UPDATE/DELETE/DDL/GRANT/UNION/pg_catalog.
            Impose LIMIT 50 maximum. Dates au format 'YYYY-MM-DD'.
            Colonnes utiles :
              transactions(transaction_id, account_id, amount, currency, transaction_type,
                country, transaction_date, channel, ip_address, device_id, beneficiary_name,
                beneficiary_account_id, beneficiary_rib, status, created_at)
              accounts(account_id, customer_id, account_number, account_type, currency,
                balance, opening_date, status)
              customers(customer_id, first_name, last_name, id_number, email, phone,
                country, risk_category, status)
            """;

    /**
     * Si la question contient des mots de donnees ET une reference de compte
     * mais que les motifs pre-ecrites n'ont pas matche : demande au LLM un
     * SELECT, le garde-fou le valide, execution. Sinon null (chat d'alerte).
     */
    private QueryAnswer llmFallback(String message) {
        if (message == null || !llmClient.isEnabled()) {
            return null;
        }
        boolean dataish = TX_WORD.matcher(message).find() || DATA_WORD.matcher(message).find();
        boolean accountish = ACCOUNT_ID.matcher(message).find() || RIB.matcher(message).find();
        if (!dataish || !accountish) {
            return null;
        }
        String raw = llmClient.complete(SQL_SYSTEM_PROMPT,
                "Question de l'analyste (en francais) : " + message
                        + "\nDonne UNIQUEMENT le SELECT PostgreSQL.");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String sql = raw.trim().replace("```sql", "").replace("```", "").trim();
        if (sql.toLowerCase(Locale.ROOT).startsWith("sql")) {
            sql = sql.substring(3).trim();
        }
        return execute("SQL_LLM", sql, List.of(), null);
    }

    /** Reponse du moteur SQL : mode (SQL_INTENT / SQL_LLM) + texte. */
    public record QueryAnswer(String mode, String answer) {
    }
}
