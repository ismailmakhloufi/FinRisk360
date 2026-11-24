package tn.finrisk.accountservice.web;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.accountservice.service.IdempotentBalanceService;
import tn.finrisk.accountservice.web.dto.AccountResponse;
import tn.finrisk.accountservice.web.dto.BalanceOperationRequest;

@RestController
@RequestMapping("/api/accounts")
public class IdempotentBalanceController {
    private final IdempotentBalanceService service;

    public IdempotentBalanceController(IdempotentBalanceService service) {
        this.service = service;
    }

    @PutMapping("/{accountId}/balance-operations/{operationId}")
    public AccountResponse apply(@PathVariable Long accountId, @PathVariable UUID operationId,
            @Valid @RequestBody BalanceOperationRequest request) {
        return service.apply(operationId, accountId, request);
    }
}
