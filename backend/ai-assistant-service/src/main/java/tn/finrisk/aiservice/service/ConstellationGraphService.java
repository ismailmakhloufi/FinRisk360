package tn.finrisk.aiservice.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tn.finrisk.aiservice.client.dto.AccountView;
import tn.finrisk.aiservice.client.dto.AlertGroupView;
import tn.finrisk.aiservice.client.dto.CustomerView;
import tn.finrisk.aiservice.client.dto.EvaluationView;
import tn.finrisk.aiservice.client.dto.TransactionView;
import tn.finrisk.aiservice.service.AiAnalysisService.FullAnalysis;
import tn.finrisk.aiservice.web.dto.ConstellationEdge;
import tn.finrisk.aiservice.web.dto.ConstellationNode;

/**
 * Constructeur DÉTERMINISTE du graphe d'investigation — la fondation de la
 * constellation. À partir du contexte déjà assemblé par AiAnalysisService,
 * on déplie le réseau réel autour de l'alerte :
 *
 *   CLIENT ──possède──> COMPTE ──émet──> TRANSACTION ──vers──> BÉNÉFICIAIRE
 *                         │                 ├──utilise──> DEVICE
 *                         │                 ├──depuis──> IP
 *                         │                 └──dans──> PAYS
 *                         └──partage──(device/IP/bénéf.)──> AUTRES COMPTES
 *
 * Deux notions distinctes, qu'il ne faut JAMAIS confondre :
 *   - investigationFocus : le nœud est le CENTRE de l'investigation.
 *   - risk                : les données disponibles étayent ce niveau.
 * Une transaction à 25/100 reste le centre du graphe sans être HIGH.
 * De même, un appareil utilisé par plusieurs clients est un SIGNAL à
 * examiner, jamais une preuve de fraude : device et IP ne dépassent
 * donc jamais MEDIUM.
 *
 * AUCUNE invention : chaque nœud naît d'une donnée API réelle, chaque arête
 * porte sa preuve (transaction source + date). Les bornages (MAX_*) évitent
 * l'explosion combinatoire — et sont DÉCLARÉS (Graph.truncated) plutôt que
 * dissimulés.
 *
 * Classe PURA : ni RestClient, ni repository — testable hors contexte.
 */
@Service
public class ConstellationGraphService {

    private static final Logger log = LoggerFactory.getLogger(ConstellationGraphService.class);

    /** Bornes de lisibilité : au-delà, le graphe devient un plat de nœuds. */
    static final int MAX_SHARED_ACCOUNTS = 6;
    static final int MAX_SHARED_ENTITIES = 5;
    static final int MAX_RECENT_TX = 4;
    /** Nombre max de preuves détaillées retenues dans une arête. */
    static final int MAX_EVIDENCE = 3;

    /** Risque selon le type d'entité. */
    static final String RISK_HIGH = "HIGH";
    static final String RISK_MEDIUM = "MEDIUM";
    static final String RISK_LOW = "LOW";

    /** Qualité des données du narratif (ConstellationNarrative.dataQuality). */
    static final String QUALITY_COMPLETE = "COMPLETE";
    static final String QUALITY_PARTIAL = "PARTIAL";
    static final String QUALITY_DEGRADED = "DEGRADED";

    /**
     * Bandes de score — miroir de risk-service/DecisionEngine
     * (risk.decisions.* : approved-max 29, monitoring-max 59, review-max 84).
     * Au-delà de 84 risk-service dit CRITICAL : le vocabulaire du graphe
     * ne compte que trois niveaux, CRITICAL est donc dit HIGH.
     */
    static final int SCORE_LOW_MAX = 29;
    static final int SCORE_MEDIUM_MAX = 59;

    /**
     * Au-delà de cette fenêtre, un partage n'est plus un lien récent et ne
     * doit pas colorer le graphe d'aujourd'hui.
     */
    static final int SHARING_WINDOW_DAYS = 90;

    /** Autres comptes touchés pour parler de réseau (miroir InsightEngine). */
    static final int NETWORK_HIGH = 2;
    static final int NETWORK_MEDIUM = 1;

    /**
     * Poids des preuves de partage. Un bénéficiaire payé par plusieurs comptes
     * pèse plus qu'une IP partagée (NAT d'agence, VPN, café) : le score
     * pondéré décide du niveau de risque d'un compte relié.
     */
    static final int WEIGHT_BENEFICIARY = 2;
    static final int WEIGHT_DEVICE = 2;
    static final int WEIGHT_IP = 1;
    /** Score pondéré au-delà duquel un compte relié est HIGH. */
    static final int SHARED_RISK_HIGH = 3;

    /** Longueurs minimales d'un libellé acceptable pour créer un lien. */
    static final int MIN_BENEFICIARY_LENGTH = 4;
    static final int MIN_ENTITY_LENGTH = 3;

    /**
     * Valeurs génériques / mal normalisées : une « unknown-device » ou une IP
     * de boucle commune à tout le monde ne crée JAMAIS une relation — ce
     * serait fabriquer du lien artificiel.
     */
    private static final Set<String> GENERIC_VALUES = Set.of(
            "unknown", "unknow", "inconnu", "inconnue", "n/a", "na", "none", "null",
            "test", "-", "web", "android", "ios", "desktop", "mobile", "tablet",
            "0.0.0.0", "127.0.0.1", "localhost");

    /**
     * Construit le graphe complet à partir d'une analyse déjà chargée.
     * Retourne les nœuds (insertion-ordered) et les arêtes prêts pour le front.
     */
    public Graph build(FullAnalysis fa) {
        var ctx = fa.context();
        TransactionView tx = ctx.transaction();
        CustomerView customer = ctx.customer();
        List<AccountView> accounts = ctx.accounts() == null ? List.of() : ctx.accounts();
        List<TransactionView> scan = ctx.globalScan() == null ? List.of() : ctx.globalScan();

        Map<String, ConstellationNode> nodes = new LinkedHashMap<>();
        List<ConstellationEdge> edges = new ArrayList<>();

        // Le risque du CENTRE vient du moteur : pas du fait d'être le centre.
        String engineRisk = engineRisk(ctx.evaluation());

        // ── 1. Nœud ALERTE (point d'entrée de l'investigation) ───────────
        String alertId = "alert:" + ctx.alert().alertId();
        nodes.put(alertId, new ConstellationNode(alertId, "ALERT",
                ctx.alert().alertCode(), ctx.alert().severity(),
                ctx.alert().alertType(),
                Map.of("status", ctx.alert().status(),
                        "riskScore", ctx.evaluation().riskScore() == null
                                ? BigDecimal.ZERO : ctx.evaluation().riskScore())));

        // ── 1b. Nœud GROUP (agrégation M5) ──────────────────────────────
        // Quand l'alerte appartient à un groupe ouvert (ex. 10 × 100 TND
        // → structuring), le groupe apparaît comme nœud : l'analyste voit
        // le PATTERN, pas seulement l'alerte isolée. Le risque du nœud est
        // la sévérité AGRÉGÉE du groupe (escaladée par le compteur).
        AlertGroupView group = ctx.alertGroup();
        if (group != null && group.groupId() != null) {
            String grpId = "group:" + group.groupId();
            String grpRisk = group.severity() == null ? RISK_LOW
                    : switch (group.severity().trim().toUpperCase(Locale.ROOT)) {
                        case "CRITICAL", "HIGH" -> RISK_HIGH;
                        case "MEDIUM" -> RISK_MEDIUM;
                        default -> RISK_LOW;
                    };
            Map<String, Object> grpMeta = new LinkedHashMap<>();
            grpMeta.put("groupId", group.groupId());
            grpMeta.put("relatedCount", group.relatedCount());
            grpMeta.put("totalAmount", group.totalAmount());
            grpMeta.put("scopeType", group.scopeType());
            grpMeta.put("scopeValue", group.scopeValue());
            grpMeta.put("windowHours", group.windowHours());
            grpMeta.put("status", group.status());
            nodes.put(grpId, new ConstellationNode(grpId, "GROUP",
                    "Groupe " + group.relatedCount() + " alertes", grpRisk,
                    "pattern agrégé — cumul " + formatAmount(group.totalAmount(), "TND")
                            + " en " + group.windowHours() + "h",
                    grpMeta));
            edges.add(edge(alertId, grpId, "BELONGS_TO", "appartient au groupe",
                    group.relatedCount() + " alertes apparentées, cumul "
                            + formatAmount(group.totalAmount(), "TND"),
                    Math.min(group.relatedCount(), 5)));
        }

        if (tx == null) {
            return new Graph(List.copyOf(nodes.values()), edges, 0, 0);
        }

        // ── 2. Nœud TRANSACTION focale ────────────────────────────────────
        String txId = "tx:" + tx.transactionId();
        Map<String, Object> txMeta = new LinkedHashMap<>();
        txMeta.put("transactionId", tx.transactionId());
        txMeta.put("date", tx.transactionDate() == null ? "" : tx.transactionDate().toString());
        txMeta.put("channel", orUnknown(tx.channel()));
        txMeta.put("transactionType", orUnknown(tx.transactionType()));
        txMeta.put("status", orUnknown(tx.status()));
        if (ctx.evaluation().riskScore() != null) {
            txMeta.put("riskScore", ctx.evaluation().riskScore());
        }
        if (notBlank(tx.merchantName())) {
            txMeta.put("merchantName", tx.merchantName());
        }
        nodes.put(txId, ConstellationNode.focal(txId, "TRANSACTION",
                formatAmount(tx.amount(), tx.currency()), engineRisk,
                notBlank(tx.merchantName())
                        ? "transaction à l'origine de l'alerte — marchand " + tx.merchantName()
                        : "transaction à l'origine de l'alerte", txMeta));
        edges.add(edge(alertId, txId, "CONCERNE", "concerne",
                "Alerte " + ctx.alert().alertCode() + " liée à cette transaction", 5));

        // ── 3. Nœud CUSTOMER (client focal) ───────────────────────────────
        String custId = null;
        if (customer != null) {
            custId = "cust:" + customer.customerId();
            nodes.put(custId, ConstellationNode.focal(custId, "CUSTOMER",
                    fullName(customer), engineRisk,
                    customer.city() + " · " + customer.country(),
                    Map.of("customerId", customer.customerId(),
                            "country", orUnknown(customer.country()),
                            "city", orUnknown(customer.city()),
                            "customerType", orUnknown(customer.customerType()),
                            "email", orUnknown(customer.email()))));
        }

        // ── 4. Comptes du client focal + arêtes OWNS ──────────────────────
        AccountView focalAccount = null;
        for (AccountView acc : accounts) {
            String accId = "acc:" + acc.accountId();
            boolean isFocal = tx.accountId() != null && tx.accountId().equals(acc.accountId());
            if (isFocal) {
                focalAccount = acc;
            }
            Map<String, Object> accMeta = new LinkedHashMap<>();
            accMeta.put("accountId", acc.accountId());
            accMeta.put("balance", acc.balance() == null ? BigDecimal.ZERO : acc.balance());
            accMeta.put("status", orUnknown(acc.status()));
            accMeta.put("focal", isFocal);
            // Seul le compte FOCAL hérite du score moteur : les autres comptes
            // du client ne sont risqués par AUCUNE donnée disponible ici.
            nodes.put(accId, new ConstellationNode(accId, "ACCOUNT",
                    orUnknown(acc.accountNumber()), isFocal ? engineRisk : RISK_LOW, isFocal,
                    isFocal ? "compte d'où part la transaction" : "autre compte du même client",
                    accMeta));
            if (custId != null) {
                edges.add(edge(custId, accId, "OWNS", "possède",
                        "Compte rattaché au client " + fullName(customer),
                        isFocal ? 5 : 3));
            }
        }

        // Arête COMPTE -> TRANSACTION (le compte émet la transaction)
        if (focalAccount != null) {
            String accId = "acc:" + focalAccount.accountId();
            edges.add(edge(accId, txId, "SENT", "émet",
                    "Transaction émise depuis le compte " + orUnknown(focalAccount.accountNumber()),
                    5));
        }

        // ── 5. Entités de la transaction : bénéficiaire, device, IP, pays ─
        Map<Long, List<Share>> benShares = Map.of();
        Map<Long, List<Share>> devShares = Map.of();
        Map<Long, List<Share>> ipShares = Map.of();
        String benId = null;
        String devId = null;
        String ipId = null;
        String benLabel = null;
        String devLabel = null;
        String ipLabel = null;

        // Bénéficiaire — identité par beneficiaryAccountId quand il existe
        // (stable), sinon par libellé normalisé. Le nom seul reste un
        // identifiant fragile : on le sait, on le documente.
        if (notBlank(tx.beneficiaryName()) || tx.beneficiaryAccountId() != null) {
            benId = tx.beneficiaryAccountId() != null
                    ? "ben:acc:" + tx.beneficiaryAccountId()
                    : "ben:" + normalizeKey(tx.beneficiaryName());
            benLabel = orUnknown(tx.beneficiaryName());
            benShares = groupByAccount(findShares(scan, tx, TransactionView::beneficiaryName,
                    TransactionView::beneficiaryAccountId, MIN_BENEFICIARY_LENGTH, true));
            int shareCount = benShares.size();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("occurrences", countOccurrences(scan, tx, TransactionView::beneficiaryName,
                    TransactionView::beneficiaryAccountId));
            meta.put("sharedAccounts", new LinkedHashSet<>(benShares.keySet()));
            meta.put("sharedAccountCount", shareCount);
            if (tx.beneficiaryAccountId() != null) {
                meta.put("beneficiaryAccountId", tx.beneficiaryAccountId());
            }
            // Un bénéficiaire payé par plusieurs comptes EST un signal réseau :
            // c'est la même détection que BENEFICIARY_NETWORK côté InsightEngine.
            nodes.put(benId, new ConstellationNode(benId, "BENEFICIARY", benLabel,
                    shareCount >= NETWORK_HIGH ? RISK_HIGH
                            : (shareCount >= NETWORK_MEDIUM ? RISK_MEDIUM : RISK_LOW),
                    shareCount > 0
                            ? "payé par " + (shareCount + 1) + " comptes — signal réseau"
                            : "bénéficiaire de cette transaction seule",
                    meta));
            edges.add(edge(txId, benId, "TO", "bénéficiaire",
                    "Virement vers " + benLabel, 4));
        }

        // Device — SIGNAL, jamais preuve : plafonné à MEDIUM.
        if (notBlank(tx.deviceId())) {
            devId = "dev:" + normalizeKey(tx.deviceId());
            devLabel = normalizeKey(tx.deviceId());
            devShares = groupByAccount(findShares(scan, tx, TransactionView::deviceId, null,
                    MIN_ENTITY_LENGTH, false));
            int shareCount = devShares.size();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sharedAccounts", new LinkedHashSet<>(devShares.keySet()));
            meta.put("sharedAccountCount", shareCount);
            nodes.put(devId, new ConstellationNode(devId, "DEVICE", devLabel,
                    shareCount >= NETWORK_HIGH ? RISK_MEDIUM : RISK_LOW,
                    shareCount > 0
                            ? "signal partagé — " + (shareCount + 1) + " comptes, pas une preuve"
                            : "appareil de cette transaction seule",
                    meta));
            edges.add(edge(txId, devId, "USED_DEVICE", "utilise",
                    "Appareil " + devLabel + " utilisé pour cette transaction", 3));
        }

        // IP — SIGNAL, jamais preuve : plafonné à MEDIUM.
        if (notBlank(tx.ipAddress())) {
            ipId = "ip:" + normalizeKey(tx.ipAddress());
            ipLabel = normalizeKey(tx.ipAddress());
            ipShares = groupByAccount(findShares(scan, tx, TransactionView::ipAddress, null,
                    MIN_ENTITY_LENGTH, false));
            int shareCount = ipShares.size();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sharedAccounts", new LinkedHashSet<>(ipShares.keySet()));
            meta.put("sharedAccountCount", shareCount);
            nodes.put(ipId, new ConstellationNode(ipId, "IP", ipLabel,
                    shareCount >= NETWORK_HIGH ? RISK_MEDIUM : RISK_LOW,
                    shareCount > 0
                            ? "signal partagé — " + (shareCount + 1) + " comptes, pas une preuve"
                            : "adresse de cette transaction seule",
                    meta));
            edges.add(edge(txId, ipId, "FROM_IP", "depuis",
                    "Adresse IP " + ipLabel + " d'origine", 2));
        }

        // Pays
        if (notBlank(tx.country())) {
            String ctryId = "ctry:" + normalizeKey(tx.country()).toUpperCase(Locale.ROOT);
            boolean geoAnomaly = customer != null && notBlank(customer.country())
                    && !customer.country().trim().equalsIgnoreCase(tx.country().trim());
            nodes.put(ctryId, new ConstellationNode(ctryId, "COUNTRY",
                    normalizeKey(tx.country()), geoAnomaly ? RISK_HIGH : RISK_LOW,
                    geoAnomaly ? "pays incohérent avec le profil déclaré" : "pays cohérent",
                    Map.of("geoAnomaly", geoAnomaly)));
            edges.add(edge(txId, ctryId, "IN_COUNTRY", "dans",
                    "Transaction réalisée depuis " + normalizeKey(tx.country()), 2));
        }

        // ── 6. Expansion réseau : autres comptes reliés par entités partagées
        Map<Long, Integer> linkCount = new LinkedHashMap<>();
        Map<Long, Integer> scoreByAccount = new LinkedHashMap<>();
        Map<Long, List<String>> sourcesByAccount = new LinkedHashMap<>();

        collectShares(benShares, WEIGHT_BENEFICIARY, "bénéficiaire", benLabel,
                linkCount, scoreByAccount, sourcesByAccount);
        collectShares(devShares, WEIGHT_DEVICE, "appareil", devLabel,
                linkCount, scoreByAccount, sourcesByAccount);
        collectShares(ipShares, WEIGHT_IP, "IP", ipLabel,
                linkCount, scoreByAccount, sourcesByAccount);

        // Le compte focal ne se relie pas à lui-même.
        if (tx.accountId() != null) {
            linkCount.remove(tx.accountId());
            scoreByAccount.remove(tx.accountId());
            sourcesByAccount.remove(tx.accountId());
        }

        int candidates = linkCount.size();
        int shown = 0;
        int hidden = 0;

        // Tri DÉTERMINISTE : le plus de preuves d'abord, puis l'id — sinon
        // l'ordre d'un HashSet rendrait le graphe différent à chaque appel.
        List<Long> ranked = scoreByAccount.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();

        for (Long otherAccId : ranked) {
            if (shown >= MAX_SHARED_ACCOUNTS) {
                hidden++;
                continue;
            }
            int score = scoreByAccount.getOrDefault(otherAccId, 0);
            int links = linkCount.getOrDefault(otherAccId, 0);
            List<String> sources = sourcesByAccount.getOrDefault(otherAccId, List.of());
            List<String> proofs = shareProofs(otherAccId, benShares, benLabel, "bénéficiaire",
                    devShares, devLabel, "appareil", ipShares, ipLabel, "IP");

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("accountId", otherAccId);
            meta.put("sharedLinkCount", links);
            meta.put("sharedScore", score);
            meta.put("sharedSources", sources);
            meta.put("evidenceCount", proofs.size());
            nodes.putIfAbsent("acc:" + otherAccId, new ConstellationNode("acc:" + otherAccId,
                    "ACCOUNT", "Compte " + otherAccId,
                    score >= SHARED_RISK_HIGH ? RISK_HIGH : RISK_MEDIUM, false,
                    "relié par " + links + " entité(s) partagée(s), score de preuve " + score,
                    meta));

            if (focalAccount != null) {
                String focalAccId = "acc:" + focalAccount.accountId();
                edges.add(edge(focalAccId, "acc:" + otherAccId, "SHARED_WITH", "partage",
                        String.join(" · ", proofs.size() > MAX_EVIDENCE
                                ? proofs.subList(0, MAX_EVIDENCE) : proofs),
                        Math.min(score + 2, 5)));
            }
            String linked = "acc:" + otherAccId;
            addSharedEvidence(edges, benId, linked, "SHARED_BENEFICIARY", "bénéficiaire commun",
                    benShares.get(otherAccId));
            addSharedEvidence(edges, devId, linked, "SHARED_DEVICE", "appareil commun",
                    devShares.get(otherAccId));
            addSharedEvidence(edges, ipId, linked, "SHARED_IP", "adresse IP commune", ipShares.get(otherAccId));
            shown++;
        }

        log.info("Constellation alerte {} : {} nœuds, {} arêtes, {} compte(s) partagé(s)"
                        + " dont {} masqué(s) par bornage",
                ctx.alert().alertId(), nodes.size(), edges.size(), candidates, hidden);

        return new Graph(List.copyOf(nodes.values()), List.copyOf(edges), candidates, shown);
    }

    private static void addSharedEvidence(List<ConstellationEdge> edges, String clue, String account,
            String type, String label, List<Share> shares) {
        if (clue == null || shares == null || shares.isEmpty()) {
            return;
        }
        var transactions = shares.stream()
                .filter(share -> share.transactionId() != null)
                .sorted(Comparator.comparing(Share::date, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Share::transactionId))
                .map(share -> new ConstellationEdge.ProofTransaction(share.transactionId(), share.date()))
                .distinct()
                .limit(MAX_EVIDENCE)
                .toList();
        long total = shares.stream().map(Share::transactionId).filter(Objects::nonNull).distinct().count();
        String evidence = total == 0
                ? "Correspondance observée dans l’échantillon de transactions."
                : total + " transaction(s) liée(s) dans l’échantillon. "
                        + transactions.size() + " référence(s) récente(s) affichée(s).";
        if ("SHARED_BENEFICIARY".equals(type)) {
            evidence += " Recoupement par compte bénéficiaire lorsque les deux identifiants sont connus,"
                    + " sinon par libellé.";
        }
        edges.add(new ConstellationEdge(ConstellationEdge.edgeId(clue, account, type),
                clue, account, type, label, evidence, 2, transactions));
    }

    /**
     * Résultat de la construction : nœuds + arêtes prêts pour le narratif et
     * le front, plus l'honnêteté sur le bornage (MAX_SHARED_ACCOUNTS).
     */
    public record Graph(List<ConstellationNode> nodes, List<ConstellationEdge> edges,
            int sharedAccountCandidates, int sharedAccountsShown) {

        /** Signature d'avant les compteurs de troncature. */
        public Graph(List<ConstellationNode> nodes, List<ConstellationEdge> edges) {
            this(nodes, edges, 0, 0);
        }

        /** Vrai si des comptes partagés ont été écartés pour rester lisible. */
        public boolean truncated() {
            return sharedAccountsShown < sharedAccountCandidates;
        }

        public int hiddenSharedAccounts() {
            return Math.max(sharedAccountCandidates - sharedAccountsShown, 0);
        }
    }

    // ---------------------------------------------------------------- interne

    /** Un lien de partage : l'autre compte + la transaction qui le PROUVE. */
    private record Share(Long otherAccountId, Long transactionId, OffsetDateTime date) {

        String proof() {
            String d = date == null ? "date inconnue" : date.toLocalDate().toString();
            return transactionId == null ? "?" : ("tx #" + transactionId + " du " + d);
        }
    }

    /**
     * Cherche dans le scan les transactions qui partagent l'entité avec la
     * transaction focale. Exclusions assumées :
     *   - la transaction focale elle-même
     *   - les transactions sans compte (aucune identité à relier)
     *   - les valeurs absentes, génériques ou trop courtes (qualité des données)
     *   - les transactions plus vieilles que SHARING_WINDOW_DAYS
     */
    private List<Share> findShares(List<TransactionView> scan, TransactionView focal,
            Function<TransactionView, String> valueExtractor,
            Function<TransactionView, Long> idExtractor,
            int minLength, boolean caseInsensitive) {
        List<Share> out = new ArrayList<>();
        if (focal == null || focal.accountId() == null) {
            return out;
        }
        String focalValue = valueExtractor.apply(focal);
        Long focalId = idExtractor == null ? null : idExtractor.apply(focal);
        if (focalId == null && !linkable(focalValue, minLength)) {
            return out;
        }
        OffsetDateTime horizon = focal.transactionDate() == null ? null
                : focal.transactionDate().minusDays(SHARING_WINDOW_DAYS);
        for (TransactionView t : scan) {
            if (Objects.equals(t.transactionId(), focal.transactionId())) {
                continue;
            }
            if (t.accountId() == null || t.accountId().equals(focal.accountId())) {
                continue;
            }
            if (!withinWindow(t.transactionDate(), horizon)) {
                continue;
            }
            if (!sameEntity(focalValue, focalId, valueExtractor.apply(t),
                    idExtractor == null ? null : idExtractor.apply(t),
                    minLength, caseInsensitive)) {
                continue;
            }
            out.add(new Share(t.accountId(), t.transactionId(), t.transactionDate()));
        }
        return out;
    }

    /**
     * Même entité des deux côtés ? L'identifiant stable prime sur le libellé
     * quand il existe des DEUX côtés ; sinon on retombe sur une comparaison
     * de libellé normalisé — mais seulement si les DEUX libellés sont
     * exploitables (un « unknown » d'un côté et un nom de l'autre ne prouvent
     * rien du tout).
     */
    private static boolean sameEntity(String focalValue, Long focalId,
            String otherValue, Long otherId, int minLength, boolean caseInsensitive) {
        if (focalId != null && otherId != null) {
            return focalId.equals(otherId);
        }
        if (!linkable(focalValue, minLength) || !linkable(otherValue, minLength)) {
            return false;
        }
        String a = normalizeKey(focalValue);
        String b = normalizeKey(otherValue);
        return caseInsensitive ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    /** La valeur est-elle exploitable pour créer un lien de partage ? */
    static boolean linkable(String raw, int minLength) {
        if (!notBlank(raw)) {
            return false;
        }
        String v = normalizeKey(raw).toLowerCase(Locale.ROOT);
        if (GENERIC_VALUES.contains(v)) {
            return false;
        }
        return v.length() >= minLength;
    }

    /** Normalisation d'une valeur avant comparaison : trim + espaces réduits. */
    static String normalizeKey(String v) {
        return v == null ? "" : v.trim().replaceAll("\\s+", " ");
    }

    /** Une transaction est-elle dans la fenêtre de partage ? */
    private static boolean withinWindow(OffsetDateTime date, OffsetDateTime horizon) {
        if (horizon == null) {
            return true; // focale sans date : on ne peut pas jauger
        }
        return date != null && !date.isBefore(horizon);
    }

    /** Regroupe les liens par autre compte. */
    private static Map<Long, List<Share>> groupByAccount(List<Share> shares) {
        Map<Long, List<Share>> byAccount = new LinkedHashMap<>();
        for (Share s : shares) {
            byAccount.computeIfAbsent(s.otherAccountId(), k -> new ArrayList<>()).add(s);
        }
        return byAccount;
    }

    /**
     * Risque du nœud focal : le SCORE DU MOTEUR, pas le fait d'être focal.
     * Bandes miroir de risk-service/DecisionEngine (risk.decisions.*).
     */
    static String engineRisk(EvaluationView evaluation) {
        if (evaluation == null) {
            return RISK_MEDIUM; // pas d'évaluation : on n'affirme rien
        }
        if (evaluation.riskScore() != null) {
            double s = evaluation.riskScore().doubleValue();
            if (s <= SCORE_LOW_MAX) {
                return RISK_LOW;
            }
            if (s <= SCORE_MEDIUM_MAX) {
                return RISK_MEDIUM;
            }
            return RISK_HIGH;
        }
        String level = evaluation.riskLevel() == null
                ? "" : evaluation.riskLevel().trim().toUpperCase(Locale.ROOT);
        return switch (level) {
            case "LOW", "VERY_LOW" -> RISK_LOW;
            case "HIGH", "CRITICAL" -> RISK_HIGH;
            default -> RISK_MEDIUM;
        };
    }

    /** Accumule, pour chaque autre compte, le nombre de liens et le score. */
    private static void collectShares(Map<Long, List<Share>> byAccount, int weight,
            String entityLabel, String entityValue, Map<Long, Integer> linkCount,
            Map<Long, Integer> scoreByAccount, Map<Long, List<String>> sourcesByAccount) {
        if (byAccount.isEmpty() || entityValue == null) {
            return;
        }
        String source = entityLabel + " « " + entityValue + " »";
        for (Map.Entry<Long, List<Share>> e : byAccount.entrySet()) {
            linkCount.merge(e.getKey(), 1, Integer::sum);
            scoreByAccount.merge(e.getKey(), weight, Integer::sum);
            sourcesByAccount.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(source);
        }
    }

    /** Preuves détaillées (transaction + date) pour un autre compte. */
    private static List<String> shareProofs(Long otherAccId,
            Map<Long, List<Share>> benShares, String benLabel, String benEntity,
            Map<Long, List<Share>> devShares, String devLabel, String devEntity,
            Map<Long, List<Share>> ipShares, String ipLabel, String ipEntity) {
        List<String> proofs = new ArrayList<>();
        appendProofs(proofs, benShares.get(otherAccId), benEntity, benLabel);
        appendProofs(proofs, devShares.get(otherAccId), devEntity, devLabel);
        appendProofs(proofs, ipShares.get(otherAccId), ipEntity, ipLabel);
        return proofs;
    }

    private static void appendProofs(List<String> out, List<Share> shares,
            String entityLabel, String entityValue) {
        if (shares == null || shares.isEmpty()) {
            return;
        }
        List<String> detail = shares.stream()
                .sorted(Comparator.comparing(Share::transactionId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_EVIDENCE)
                .map(Share::proof)
                .toList();
        String more = shares.size() > MAX_EVIDENCE
                ? " (+" + (shares.size() - MAX_EVIDENCE) + " autre(s))"
                : "";
        out.add(entityLabel + " « " + entityValue + " » — preuve : "
                + String.join(", ", detail) + more);
    }

    /**
     * Occurrences d'une entité dans le scan (y compris la transaction focale),
     * avec la même règle d'identité que les liens.
     */
    private static long countOccurrences(List<TransactionView> scan, TransactionView focal,
            Function<TransactionView, String> valueExtractor,
            Function<TransactionView, Long> idExtractor) {
        String focalValue = valueExtractor.apply(focal);
        Long focalId = idExtractor == null ? null : idExtractor.apply(focal);
        if (focalId == null && !linkable(focalValue, MIN_BENEFICIARY_LENGTH)) {
            return 0;
        }
        return scan.stream()
                .filter(t -> sameEntity(focalValue, focalId, valueExtractor.apply(t),
                        idExtractor == null ? null : idExtractor.apply(t),
                        MIN_BENEFICIARY_LENGTH, true))
                .count();
    }

    private ConstellationEdge edge(String source, String target, String type,
            String label, String evidence, int weight) {
        return new ConstellationEdge(ConstellationEdge.edgeId(source, target, type),
                source, target, type, label, evidence, weight);
    }

    static String fullName(CustomerView c) {
        if (c == null) {
            return "Inconnu";
        }
        String first = c.firstName() == null ? "" : c.firstName().trim();
        String last = c.lastName() == null ? "" : c.lastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? "Client " + c.customerId() : full;
    }

    static String formatAmount(BigDecimal amount, String currency) {
        if (amount == null) {
            return "Montant inconnu";
        }
        return amount.stripTrailingZeros().toPlainString() + " " + orUnknown(currency);
    }

    static String orUnknown(String v) {
        return v == null || v.isBlank() || "null".equals(v) ? "—" : v;
    }

    static boolean notBlank(String v) {
        return v != null && !v.isBlank() && !"null".equals(v);
    }
}
