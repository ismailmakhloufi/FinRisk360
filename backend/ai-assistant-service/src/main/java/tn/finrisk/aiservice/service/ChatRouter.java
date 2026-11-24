package tn.finrisk.aiservice.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tn.finrisk.aiservice.client.dto.CustomerView;
import tn.finrisk.aiservice.client.dto.FactorView;
import tn.finrisk.aiservice.client.dto.TransactionView;
import tn.finrisk.aiservice.service.AiAnalysisService.FullAnalysis;
import tn.finrisk.aiservice.service.ConstellationGraphService.Graph;
import tn.finrisk.aiservice.web.dto.ChatIntent;
import tn.finrisk.aiservice.web.dto.Insight;
import tn.finrisk.aiservice.web.dto.InsightCode;
import tn.finrisk.aiservice.web.dto.InsightSeverity;

/**
 * Routeur déterministe du chat : « répondre à toute question » signifie
 * que TOUTE question contenant un concept connu (montant, bénéficiaire,
 * pays, signaux, confiance...) est reconnue et répondue à partir du
 * contexte réel de l'alerte. Les synonymes sont accumulés parce qu'un
 * analyste ne formule jamais deux fois de la même façon.
 *
 * Trois lignes de défense :
 *   1. intention dédiée (synonymes multiples, priorisée)
 *   2. repli HELP (on montre ce qu'on sait faire)
 *   3. repli FALLBACK honnête — JAMAIS de réponse inventée
 *
 * PUR : ni client, ni LLM — testable ligne à ligne.
 */
@Component
public class ChatRouter {

    record RoutedReply(ChatIntent intent, String answer) {
    }

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'");

    public RoutedReply route(FullAnalysis fa, String message) {
        return route(fa, null, message);
    }

    /**
     * Variante avec graphe de constellation (peut être null) : les intentions
     * CONSTELLATION et EXPLAIN_ALERT l'utilisent pour donner des chiffres
     * RÉELS (entités, relations, preuves partagées) et le lien visuel.
     */
    public RoutedReply route(FullAnalysis fa, Graph graph, String message) {
        String n = normalize(message);
        if (n.isBlank()) {
            return help(fa);
        }

        // Intentions « visuel / pédagogie » AVANT tout : elles orientent
        // l'analyste vers la constellation et expliquent l'alerte de bout en bout.
        if (has(n, "constellation", "graphe", "reseau", "rezo", "entites liees",
                "liens entre", "qui est relie", "voisinage", "en visuel")) {
            return constellation(fa, graph);
        }
        if (has(n, "explique l alerte", "expliquer l alerte", "explique moi l alerte",
                "explique cette alerte", "explique moi", "je ne comprends pas",
                "comprends pas", "pas clair", "clarifie", "aide moi a comprendre")) {
            return explainAlert(fa);
        }

        if (has(n, "resume", "synthese", "en bref", "situe", "situee", "en une phrase")) {
            return summary(fa);
        }
        if (has(n, "signa", "preuve", "evidence", "detecte", "detection",
                "qu as tu trouve", "trouve", "sur quoi travailles")) {
            return signals(fa);
        }
        if (has(n, "action", "recommand", "conseil", "que faire", "etape",
                "procedure", "suite a donner", "prochaine etape")) {
            return actions(fa);
        }
        if (has(n, "confiance", "certitude", "fiable", "assure")) {
            return confidence(fa);
        }
        if (has(n, "comment ca marche", "comment tu marche", "comment fonctionne")) {
            return help(fa);
        }
        // Concepts métier AVANT « pourquoi » : « pourquoi ce montant » -> montant
        if (has(n, "beneficiaire", "destinataire", "vers qui", "a qui", "qui recoit")) {
            return beneficiary(fa);
        }
        if (has(n, "montant", "somme", "combien", "euro", "valeur", "chiffre")) {
            return amount(fa);
        }
        if (has(n, "pays", "etranger", "geograph", "localisation", "depuis quel")) {
            return geo(fa);
        }
        if (has(n, "device", "appareil", "adresse ip", " trace", "telephone", "ordinateur")) {
            return device(fa);
        }
        if (has(n, "velocite", "vitesse", "rapide", "dans quel delai",
                "nombre d operation", "en peu de temps", "burst")) {
            return velocity(fa);
        }
        if (has(n, "heure", "date", "quand", "nuit", "moment", "journee")) {
            return time(fa);
        }
        if (has(n, "client", "profil", "customer", "particulier", "identite")) {
            return customer(fa);
        }
        if (has(n, "compte", "solde", "balance")) {
            return accounts(fa);
        }
        if (has(n, "transaction", "operation", "virement", "carte", "canal")) {
            return transaction(fa);
        }
        // « Pourquoi / explique » GÉNÉRAL -> score et raisonnement
        if (has(n, "pourquoi", "raison", "explique", "expliquer", "justifie",
                "d ou vient", "origine", "base sur quoi", "fond sur quoi", "comment")) {
            return whyScore(fa);
        }
        if (has(n, "decision", "hold", "bloque", "allow", "review", "attente", "verdict du moteur")) {
            return decision(fa);
        }
        if (has(n, "risque", "danger", "menace", "fraude", "suspect", "verdict", "avis",
                "opinion", "que penses", "grave", "serieux", "etonnant", "normal")) {
            return verdict(fa);
        }
        if (has(n, "facteur", "regle")) {
            return factors(fa);
        }
        if (has(n, "score", "note", "cote", "pourcent", "sur 100")) {
            return score(fa);
        }
        if (isGreeting(n)) {
            return greeting(fa);
        }
        if (has(n, "aide", "help", "que sais", "capacite", "commande", "peux tu", "que peux")) {
            return help(fa);
        }
        return fallback(fa);
    }

    // ------------------------------------------------------------ intentions

    /**
     * Constellation : résumé chiffré du réseau (entités, relations, preuves
     * partagées) + lien vers la vue visuelle /constellation/{id}.
     */
    private RoutedReply constellation(FullAnalysis fa, Graph graph) {
        var alert = fa.context().alert();
        var grp = fa.context().alertGroup();
        String groupPart = grp != null && grp.groupId() != null
                ? " Groupe #" + grp.groupId() + " : " + grp.relatedCount()
                        + " alerte(s) apparentée(s), cumul " + grp.totalAmount()
                        + " TND en " + grp.windowHours() + "h."
                : " Aucun groupe d'alertes apparentées.";
        String link = " Ouvrez la constellation (/constellation/" + alert.alertId()
                + " ou le bouton « ◇ constellation ») pour la voir en visuel.";
        if (graph == null) {
            return new RoutedReply(ChatIntent.CONSTELLATION,
                    "Constellation de l'alerte " + nullSafe(alert.alertCode()) + "."
                            + groupPart + link);
        }
        long shared = graph.nodes().stream()
                .filter(n -> n.meta().get("sharedAccounts") instanceof java.util.Set<?> s
                        && !s.isEmpty())
                .count();
        return new RoutedReply(ChatIntent.CONSTELLATION,
                "Constellation : " + graph.nodes().size() + " entités reliées par "
                        + graph.edges().size() + " relations vérifiables ; " + shared
                        + " entité(s) partagée(s) avec d'autres comptes "
                        + "(bénéficiaire, appareil ou IP commun — préuve à examiner)."
                        + groupPart + link);
    }

    /**
     * Explication de bout en bout de l'alerte pour un analyste qui ne la
     * comprend pas : fait, score + facteurs avec POIDS, signaux, groupe,
     * avis IA (avis, pas décision) et lien constellation.
     */
    private RoutedReply explainAlert(FullAnalysis fa) {
        var ctx = fa.context();
        var alert = ctx.alert();
        var r = fa.response();
        var tx = ctx.transaction();
        StringBuilder s = new StringBuilder();
        s.append("Explication de l'alerte ").append(nullSafe(alert.alertCode()))
                .append(" (").append(nullSafe(alert.alertType())).append(", sévérité ")
                .append(nullSafe(alert.severity())).append(") : ")
                .append(plain(tx.amount())).append(" ").append(nullSafe(tx.currency()))
                .append(" vers '").append(nullSafe(tx.beneficiaryName())).append("' le ")
                .append(fmtDate(tx.transactionDate())).append(". ");
        List<FactorView> top = ctx.evaluation().factorsOrEmpty().stream()
                .filter(f -> f.factorScore() != null && f.factorScore().signum() > 0)
                .sorted((a, b) -> b.factorScore().compareTo(a.factorScore()))
                .limit(3).toList();
        if (top.isEmpty()) {
            s.append("Le score ").append(plain(r.riskScore())).append("/100 (")
                    .append(nullSafe(r.decision()))
                    .append(") ne vient d'aucune règle positive détaillée. ");
        } else {
            s.append("Le moteur a noté ").append(plain(r.riskScore())).append("/100 → ")
                    .append(nullSafe(r.decision())).append(" principalement via ")
                    .append(String.join(", ", top.stream()
                            .map(f -> f.ruleName() + " (+" + plain(f.factorScore())
                                    + (f.weight() != null ? ", poids " + plain(f.weight()) : "")
                                    + ")")
                            .toList()))
                    .append(". ");
        }
        long signals = fa.insights().stream()
                .filter(i -> i.code() != InsightCode.INSUFFICIENT_DATA).count();
        s.append(signals).append(" signal(aux) IA complémentaire(s)"
                + " (« quels signaux ? » pour les preuves). ");
        var grp = ctx.alertGroup();
        if (grp != null && grp.groupId() != null) {
            s.append("Rattachée au groupe #").append(grp.groupId()).append(" (")
                    .append(grp.relatedCount()).append(" alerte(s) apparentée(s), cumul ")
                    .append(grp.totalAmount()).append(" TND en ").append(grp.windowHours())
                    .append("h). ");
        }
        s.append("Avis IA : ").append(r.verdict()).append(" (confiance ")
                .append(r.confidence()).append(") — c'est un AVIS, l'arbitrage vous appartient. ");
        s.append("Pour le réseau complet (entités partagées, preuves), ouvrez la constellation :")
                .append(" /constellation/").append(alert.alertId()).append(".");
        return new RoutedReply(ChatIntent.EXPLAIN_ALERT, s.toString());
    }

    private RoutedReply summary(FullAnalysis fa) {
        TransactionView tx = fa.context().transaction();
        var alert = fa.context().alert();
        var r = fa.response();
        return new RoutedReply(ChatIntent.SUMMARY, "Alerte " + nullSafe(alert.alertCode())
                + " (" + nullSafe(alert.alertType()) + ", sévérité " + nullSafe(alert.severity()) + ")"
                + ", créée le " + fmtDate(alert.createdAt()) + " : "
                + plain(tx.amount()) + " " + nullSafe(tx.currency())
                + " vers '" + nullSafe(tx.beneficiaryName()) + "' le " + fmtDate(tx.transactionDate())
                + ". Score moteur " + plain(r.riskScore()) + "/100 → " + nullSafe(r.decision())
                + ". Avis IA : " + r.verdict() + " (confiance " + r.confidence()
                + ", " + r.insights().size() + " signal(aux)).");
    }

    private RoutedReply signals(FullAnalysis fa) {
        List<Insight> insights = fa.insights();
        List<String> real = insights.stream()
                .filter(i -> i.code() != InsightCode.INSUFFICIENT_DATA)
                .map(i -> "• [" + i.severity() + "] " + i.message() + " (" + i.evidence() + ")")
                .toList();
        if (real.isEmpty()) {
            return new RoutedReply(ChatIntent.SIGNALS,
                    "Aucun signal complémentaire : rien au-delà des règles du moteur. "
                            + "L'absence de signal est aussi une information.");
        }
        String banner = real.size() + " signal(aux), preuves à l'appui : ";
        return new RoutedReply(ChatIntent.SIGNALS, banner + String.join(" ", real));
    }

    private RoutedReply actions(FullAnalysis fa) {
        TransactionView tx = fa.context().transaction();
        return new RoutedReply(ChatIntent.ACTIONS,
                "Je ne décide pas (règle du projet : l'analyste garde la décision). Étapes suggérées : "
                        + "1) vérifier le bénéficiaire '" + nullSafe(tx.beneficiaryName()) + "', "
                        + "2) consulter les signaux (« quels signaux ? »), "
                        + "3) si besoin contacter le client pour confirmer l'opération, "
                        + "4) statuer sur l'alerte. Le score est " + plain(fa.response().riskScore())
                        + "/100, mon avis est " + fa.response().verdict() + " — l'arbitrage vous appartient.");
    }

    private RoutedReply confidence(FullAnalysis fa) {
        var r = fa.response();
        boolean insufficient = fa.insights().stream()
                .anyMatch(i -> i.code() == InsightCode.INSUFFICIENT_DATA);
        return new RoutedReply(ChatIntent.CONFIDENCE,
                "Confiance " + r.confidence() + " sur 1 : base 0.4, +0.2 par signal fort, "
                        + "+0.1 par signal moyen, plafond 0.95"
                        + (insufficient ? " — bornée à 0.5 car l'historique est trop court" : "")
                        + ". Elle porte sur MON analyse, pas sur le score moteur (" + plain(r.riskScore())
                        + "/100, déterministe lui aussi).");
    }

    private RoutedReply decision(FullAnalysis fa) {
        var r = fa.response();
        String meaning = switch (nullSafe(r.decision())) {
            case "HOLD" -> "retenue, en attente d'une revue humaine";
            case "REVIEW" -> "à examiner manuellement";
            case "ALLOW" -> "laisse passer (règles non déclenchées)";
            case "BLOCK" -> "bloquée par le moteur";
            default -> "rendue par le moteur";
        };
        return new RoutedReply(ChatIntent.DECISION,
                "Décision du moteur : " + r.decision() + " — " + meaning
                        + ". Score " + plain(r.riskScore()) + "/100. À côté, mon avis IA : "
                        + r.verdict() + " (confiance " + r.confidence() + ").");
    }

    private RoutedReply verdict(FullAnalysis fa) {
        var r = fa.response();
        long high = count(fa, InsightSeverity.HIGH);
        long medium = count(fa, InsightSeverity.MEDIUM);
        String why = high >= 1
                ? high + " signal(aux) fort(s)"
                : (medium >= 2 ? medium + " signaux moyens" : "rien de significatif au-delà des règles");
        return new RoutedReply(ChatIntent.VERDICT,
                "Avis IA : " + r.verdict() + " — parce que " + why + ". "
                        + "Score moteur " + plain(r.riskScore()) + "/100 (" + nullSafe(r.decision())
                        + ") : je m'appuie dessus, je ne le remplace pas. Confiance : " + r.confidence() + ".");
    }

    private RoutedReply factors(FullAnalysis fa) {
        List<FactorView> factors = fa.context().evaluation().factorsOrEmpty().stream()
                .filter(f -> f.factorScore() != null && f.factorScore().signum() > 0)
                .sorted((a, b) -> b.factorScore().compareTo(a.factorScore()))
                .toList();
        if (factors.isEmpty()) {
            return new RoutedReply(ChatIntent.FACTORS,
                    "Aucun facteur positif : les règles n'ont rien ajouté au score "
                            + "(" + plain(fa.response().riskScore()) + "/100 vient d'ailleurs).");
        }
        List<String> parts = new ArrayList<>();
        factors.forEach(f -> parts.add(f.ruleName() + " +" + plain(f.factorScore())
                + (f.reason() == null || f.reason().isBlank() ? "" : " (" + f.reason() + ")")));
        return new RoutedReply(ChatIntent.FACTORS,
                factors.size() + " facteur(s) expliquent le " + plain(fa.response().riskScore())
                        + "/100 : " + String.join(" ; ", parts) + ".");
    }

    private RoutedReply whyScore(FullAnalysis fa) {
        var r = fa.response();
        List<FactorView> top = fa.context().evaluation().factorsOrEmpty().stream()
                .filter(f -> f.factorScore() != null && f.factorScore().signum() > 0)
                .sorted((a, b) -> b.factorScore().compareTo(a.factorScore()))
                .limit(3).toList();
        String detail = top.isEmpty()
                ? "aucune règle n'a joué de facteur positif"
                : "principalement " + String.join(", ",
                        top.stream().map(f -> f.ruleName() + " (+" + plain(f.factorScore()) + ")").toList());
        return new RoutedReply(ChatIntent.WHY_SCORE,
                "Le score " + plain(r.riskScore()) + "/100 vient de risk-service (moteur de règles) : "
                        + detail + ", décision " + nullSafe(r.decision()) + ". "
                        + "Mon analyse est SEPARÉE : elle s'ajoute à côté (" + r.insights().size()
                        + " signaux, voir « quels signaux ? »).");
    }

    private RoutedReply score(FullAnalysis fa) {
        var r = fa.response();
        return new RoutedReply(ChatIntent.SCORE,
                "Score " + plain(r.riskScore()) + "/100 → " + nullSafe(r.decision())
                        + " (niveau " + nullSafe(r.riskLevel()) + "), calculé par risk-service. "
                        + "Mon avis IA à côté : " + r.verdict() + ".");
    }

    private RoutedReply beneficiary(FullAnalysis fa) {
        TransactionView tx = fa.context().transaction();
        String name = tx.beneficiaryName();
        if (name == null || name.isBlank()) {
            return new RoutedReply(ChatIntent.BENEFICIAIRE,
                    "Aucun bénéficiaire renseigné sur cette opération.");
        }
        long seen = fa.context().history().stream()
                .filter(t -> !java.util.Objects.equals(t.transactionId(), tx.transactionId()))
                .filter(t -> InsightEngine.matches(name, t.beneficiaryName(), true))
                .count();
        String network = fa.insights().stream()
                .filter(i -> i.code() == InsightCode.BENEFICIARY_NETWORK)
                .map(i -> " Réseau : " + i.message() + " " + i.evidence())
                .findFirst().orElse("");
        return new RoutedReply(ChatIntent.BENEFICIAIRE,
                "Bénéficiaire : '" + name.trim() + "' — " + seen + " occurrence(s) dans l'historique ("
                        + fa.context().history().size() + " opérations)." + network);
    }

    private RoutedReply amount(FullAnalysis fa) {
        TransactionView tx = fa.context().transaction();
        List<BigDecimal> amounts = fa.context().history().stream()
                .map(TransactionView::amount)
                .filter(java.util.Objects::nonNull)
                .filter(a -> a.signum() > 0)
                .sorted().toList();
        if (amounts.isEmpty()) {
            return new RoutedReply(ChatIntent.AMOUNT,
                    "Montant : " + plain(tx.amount()) + " " + nullSafe(tx.currency())
                            + " (pas d'historique pour comparer).");
        }
        BigDecimal p95 = InsightEngine.percentile(amounts, 95);
        double ratio = p95.signum() > 0 ? tx.amount().doubleValue() / p95.doubleValue() : 0;
        String deviation = fa.insights().stream()
                .filter(i -> i.code() == InsightCode.AMOUNT_DEVIATION)
                .map(i -> " " + i.message())
                .findFirst().orElse(" écart jugé acceptable.");
        return new RoutedReply(ChatIntent.AMOUNT,
                "Montant " + plain(tx.amount()) + " " + nullSafe(tx.currency()) + " vs P95 de l'historique "
                        + plain(p95) + " (" + amounts.size() + " opérations, ratio " + InsightEngine.fmt1(ratio)
                        + "×)." + deviation);
    }

    private RoutedReply geo(FullAnalysis fa) {
        TransactionView tx = fa.context().transaction();
        CustomerView customer = fa.context().customer();
        String txCountry = nullSafe(tx.country());
        String home = customer == null ? null : customer.country();
        boolean coherent = home != null && !home.isBlank()
                && home.trim().equalsIgnoreCase(txCountry.trim());
        String signal = fa.insights().stream()
                .filter(i -> i.code() == InsightCode.GEO_ANOMALY)
                .map(i -> " " + i.message())
                .findFirst().orElse("");
        return new RoutedReply(ChatIntent.GEO,
                "Transaction émise depuis '" + txCountry + "', profil déclaré '"
                        + nullSafe(home) + "' → " + (coherent ? "cohérent." : "incohérent.") + signal);
    }

    private RoutedReply device(FullAnalysis fa) {
        TransactionView tx = fa.context().transaction();
        String shared = fa.insights().stream()
                .filter(i -> i.code() == InsightCode.DEVICE_SHARED || i.code() == InsightCode.IP_SHARED)
                .map(i -> " " + i.message() + " " + i.evidence())
                .findFirst().orElse(" Aucun partage détecté avec d'autres comptes.");
        return new RoutedReply(ChatIntent.DEVICE,
                "Empreinte numérique : device '" + nullSafe(tx.deviceId()) + "', IP '"
                        + nullSafe(tx.ipAddress()) + "'." + shared);
    }

    private RoutedReply velocity(FullAnalysis fa) {
        TransactionView tx = fa.context().transaction();
        String velocity = fa.insights().stream()
                .filter(i -> i.code() == InsightCode.VELOCITY_SPIKE)
                .map(i -> i.message() + " " + i.evidence())
                .findFirst().orElse(null);
        if (velocity == null) {
            velocity = "Aucun burst : rythme normal sur la fenêtre de 60 min.";
        }
        return new RoutedReply(ChatIntent.VELOCITY, velocity);
    }

    private RoutedReply time(FullAnalysis fa) {
        TransactionView tx = fa.context().transaction();
        if (tx.transactionDate() == null) {
            return new RoutedReply(ChatIntent.TIME, "Date d'opération inconnue.");
        }
        int hour = tx.transactionDate().getHour();
        String slot = (hour >= 22 || hour < 6) ? "créneau de nuit" : "créneau de jour";
        return new RoutedReply(ChatIntent.TIME,
                "Opération du " + fmtDate(tx.transactionDate()) + " — " + slot + " (heure " + hour + "h).");
    }

    private RoutedReply customer(FullAnalysis fa) {
        CustomerView c = fa.context().customer();
        if (c == null) {
            return new RoutedReply(ChatIntent.CUSTOMER, "Profil client indisponible.");
        }
        return new RoutedReply(ChatIntent.CUSTOMER,
                nullSafe(c.firstName()) + " " + nullSafe(c.lastName()) + " (" + nullSafe(c.customerType())
                        + ", statut " + nullSafe(c.status()) + "), déclaré à " + nullSafe(c.city())
                        + " (" + nullSafe(c.country()) + "), inscrit le " + fmtDate(c.registrationDate())
                        + ". " + fa.context().accounts().size() + " compte(s) connu(s).");
    }

    private RoutedReply accounts(FullAnalysis fa) {
        var accounts = fa.context().accounts();
        if (accounts.isEmpty()) {
            return new RoutedReply(ChatIntent.ACCOUNTS, "Aucun compte connu pour ce client.");
        }
        List<String> parts = accounts.stream()
                .map(a -> "#" + a.accountId() + " " + nullSafe(a.accountType()) + " (solde "
                        + plain(a.balance()) + " " + nullSafe(a.currency()) + ")")
                .toList();
        return new RoutedReply(ChatIntent.ACCOUNTS,
                accounts.size() + " compte(s) : " + String.join(", ", parts) + ".");
    }

    private RoutedReply transaction(FullAnalysis fa) {
        TransactionView tx = fa.context().transaction();
        return new RoutedReply(ChatIntent.TRANSACTION,
                "Transaction #" + tx.transactionId() + " : " + plain(tx.amount()) + " "
                        + nullSafe(tx.currency()) + " (" + nullSafe(tx.transactionType()) + "), "
                        + nullSafe(tx.channel()) + ", " + nullSafe(tx.country()) + ", statut "
                        + nullSafe(tx.status()) + ", le " + fmtDate(tx.transactionDate())
                        + " vers '" + nullSafe(tx.beneficiaryName()) + "'.");
    }

    private RoutedReply greeting(FullAnalysis fa) {
        return new RoutedReply(ChatIntent.GREETING,
                "Bonjour — assistant IA FinRisk360 sur l'alerte "
                        + nullSafe(fa.context().alert().alertCode()) + ". Demandez-moi : résumé, score, "
                        + "signaux, bénéficiaire, montant, actions... (« aide » pour la liste).");
    }

    private RoutedReply help(FullAnalysis fa) {
        return new RoutedReply(ChatIntent.HELP,
                "Je réponds sur CETTE alerte uniquement, à partir des données réelles : "
                        + "« résume l'alerte », « explique-moi l'alerte », « constellation », "
                        + "« pourquoi ce score ? », « quels signaux ? », "
                        + "« bénéficiaire ? », « montant vs habitude ? », « pays ? », « device/IP ? », "
                        + "« confiance ? », « que faire ? ». Toute autre question reçoit un repli honnête — "
                        + "je n'invente jamais de réponse.");
    }

    private RoutedReply fallback(FullAnalysis fa) {
        return new RoutedReply(ChatIntent.FALLBACK,
                "Je n'ai pas reconnu cette formulation. Essayez : « résume l'alerte », "
                        + "« pourquoi ce score ? », « quels signaux ? », « bénéficiaire ? », "
                        + "« montant ? », « pays ? », « confiance ? », « actions ? » — "
                        + "ou « aide ». Je ne réponds que sur des données réelles de l'alerte "
                        + nullSafe(fa.context().alert().alertCode()) + ".");
    }

    // --------------------------------------------------------------- outils

    /** Minuscules, sans accents, apostrophes et tirets en espaces, espaces réduits. */
    static String normalize(String s) {
        String base = s == null ? "" : s;
        String noAccents = Normalizer.normalize(base, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return noAccents.toLowerCase(Locale.ROOT)
                .replaceAll("'", " ")
                .replaceAll("\u2019", " ")
                .replaceAll("-", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static boolean has(String normalized, String... keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGreeting(String normalized) {
        return has(normalized, "bonjour", "salut", "hello", "bonsoir", "coucou")
                && normalized.split(" ").length <= 5;
    }

    private long count(FullAnalysis fa, InsightSeverity severity) {
        return fa.insights().stream().filter(i -> i.severity() == severity).count();
    }

    static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    static String nullSafe(String s) {
        return s == null || s.isBlank() ? "n/c" : s;
    }

    static String fmtDate(java.time.OffsetDateTime d) {
        return d == null ? "n/c" : DATE.format(d);
    }
}
