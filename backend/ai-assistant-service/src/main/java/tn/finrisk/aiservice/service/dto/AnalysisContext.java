package tn.finrisk.aiservice.service.dto;

import java.util.List;
import tn.finrisk.aiservice.client.dto.AccountView;
import tn.finrisk.aiservice.client.dto.AlertGroupView;
import tn.finrisk.aiservice.client.dto.AlertView;
import tn.finrisk.aiservice.client.dto.CustomerView;
import tn.finrisk.aiservice.client.dto.EvaluationView;
import tn.finrisk.aiservice.client.dto.TransactionView;

/**
 * Tout ce que le moteur d'insights doit savoir, ASSEMBLÉ en amont par
 * l'orchestrateur (Étape 3) à partir des 4 clients — le moteur lui-même
 * ne parle jamais réseau : il est pur, donc testable sans aucun service.
 *
 *   history        : transactions des comptes du client (fenêtre glissante, ex. 90j)
 *   globalScan     : échantillon de transactions toutes entreprises confondues,
 *                    sert au graphe (bénéficiaire/device/IP partagés)
 *   globalScanTotal: nombre TOTAL de transactions que le service distant
 *                    déclare sur la fenêtre — compare à globalScan.size() il
 *                    donne l'honnêteté du scan (le graphe est-il tronqué ?).
 *                    Sans ce chiffre, un échantillon de 100 sur 10 000 se
 *                    ferait passer pour une vision complète du réseau.
 *   alertGroup     : groupe d'alertes apparentées auquel appartient l'alerte
 *                    focale (null si hors agrégation ou alert-case-service
 *                    injoignable). Porte le pattern structurant (cumul,
 *                    compteur, sévérité escaladée).
 *
 * Listes jamais nulles (l'orchestrateur normalise avant).
 */
public record AnalysisContext(
        AlertView alert,
        EvaluationView evaluation,
        TransactionView transaction,
        CustomerView customer,
        List<AccountView> accounts,
        List<TransactionView> history,
        List<TransactionView> globalScan,
        long globalScanTotal,
        AlertGroupView alertGroup) {

    /** Signature d'avant alertGroup (alertes hors agrégation M5). */
    public AnalysisContext(AlertView alert, EvaluationView evaluation,
            TransactionView transaction, CustomerView customer,
            List<AccountView> accounts, List<TransactionView> history,
            List<TransactionView> globalScan, long globalScanTotal) {
        this(alert, evaluation, transaction, customer, accounts, history, globalScan,
                globalScanTotal, null);
    }

    /** Signature d'avant globalScanTotal : total = taille de l'échantillon. */
    public AnalysisContext(AlertView alert, EvaluationView evaluation, TransactionView transaction,
            CustomerView customer, List<AccountView> accounts, List<TransactionView> history,
            List<TransactionView> globalScan) {
        this(alert, evaluation, transaction, customer, accounts, history, globalScan,
                globalScan == null ? 0 : globalScan.size(), null);
    }

    /** Vrai si l'échantillon couvre tout ce que le service distant sait. */
    public boolean globalScanComplete() {
        int loaded = globalScan == null ? 0 : globalScan.size();
        return globalScanTotal <= loaded;
    }
}
