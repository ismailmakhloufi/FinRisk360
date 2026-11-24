package tn.finrisk.accountservice.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.accountservice.web.dto.AccountResponse;
import tn.finrisk.accountservice.web.dto.BalanceOperationRequest;
import tn.finrisk.accountservice.web.error.AccountNotOperableException;

/** Marqueur et mouvement sont valides ensemble dans la base des comptes. */
@Service
public class IdempotentBalanceService {
    private final JdbcTemplate jdbc;
    private final AccountService accounts;

    public IdempotentBalanceService(JdbcTemplate jdbc, AccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Transactional
    public AccountResponse apply(UUID operationId, Long accountId, BalanceOperationRequest request) {
        int inserted = jdbc.update("INSERT INTO balance_operation_keys "
                + "(operation_id, account_id, amount, direction) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (operation_id) DO NOTHING", operationId, accountId,
                request.amount(), request.direction().name());
        if (inserted == 1) {
            return accounts.operateBalance(accountId, request);
        }
        var existing = jdbc.queryForMap("SELECT account_id, amount, direction "
                + "FROM balance_operation_keys WHERE operation_id = ?", operationId);
        if (((Number) existing.get("account_id")).longValue() != accountId.longValue()
                || ((BigDecimal) existing.get("amount")).compareTo(request.amount()) != 0
                || !existing.get("direction").equals(request.direction().name())) {
            throw new AccountNotOperableException("Identifiant deja utilise pour un autre mouvement");
        }
        // Pas de nouveau mouvement : le solde rendu est le solde actuel, qui
        // peut avoir change depuis la premiere execution.
        return accounts.getById(accountId);
    }
}
