package tn.finrisk.transactionservice.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Journal en memoire pour les tests UNITAIRES (aucune base) : memes regles
 * que le vrai journal — cle stable par (tentative, etape), etat PREPARED puis
 * CONFIRMED, abort apres compensation.
 */
class InMemoryFinancialJournal extends FinancialOperationJournal {

    private final Map<String, Entry> rows = new ConcurrentHashMap<>();

    InMemoryFinancialJournal() {
        super(null);
    }

    @Override
    public Entry prepare(UUID attempt, String step, long transactionId, long accountId,
            BigDecimal amount, String direction) {
        return rows.computeIfAbsent(attempt + "|" + step,
                k -> new Entry(UUID.randomUUID(), "PREPARED"));
    }

    @Override
    public void confirm(UUID attempt, String step, UUID operationId) {
        rows.computeIfPresent(attempt + "|" + step,
                (k, v) -> new Entry(v.operationId(), STATE_CONFIRMED));
    }

    @Override
    public boolean hasCompensation(UUID attempt) {
        return rows.keySet().stream().anyMatch(
                k -> k.startsWith(attempt + "|")
                        && (k.endsWith("|COMPENSATE_SOURCE") || k.endsWith("|REVERSE_COMPENSATION")));
    }

    @Override
    public void abort(UUID attempt) {
        rows.keySet().removeIf(k -> k.startsWith(attempt + "|"));
    }

    /** Etat d'une etape pour les assertions (null = jamais preparee). */
    Entry row(UUID attempt, String step) {
        return rows.get(attempt + "|" + step);
    }
}
