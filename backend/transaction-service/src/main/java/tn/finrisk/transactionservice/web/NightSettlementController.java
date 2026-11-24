package tn.finrisk.transactionservice.web;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.transactionservice.service.TransactionService;
import tn.finrisk.transactionservice.web.dto.TransactionResponse;

/** Endpoint interne, accessible uniquement sur le reseau prive des services. */
@RestController
public class NightSettlementController {
    private final TransactionService transactions;

    public NightSettlementController(TransactionService transactions) {
        this.transactions = transactions;
    }

    @PutMapping("/internal/night-settlements/{transactionId}/{intentId}")
    public TransactionResponse complete(@PathVariable Long transactionId, @PathVariable UUID intentId) {
        return transactions.completeNightIntent(transactionId, intentId);
    }
}
