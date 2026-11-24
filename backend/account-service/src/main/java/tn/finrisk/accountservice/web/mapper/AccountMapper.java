package tn.finrisk.accountservice.web.mapper;

import org.springframework.stereotype.Component;
import tn.finrisk.accountservice.domain.Account;
import tn.finrisk.accountservice.web.dto.AccountCreateRequest;
import tn.finrisk.accountservice.web.dto.AccountResponse;

/** Traduction entite <-> DTO. Seul point de passage entre les deux mondes. */
@Component
public class AccountMapper {

    public Account toEntity(AccountCreateRequest request) {
        Account account = new Account();
        account.setCustomerId(request.customerId());
        account.setAccountType(request.accountType());
        if (request.currency() != null && !request.currency().isBlank()) {
            account.setCurrency(request.currency().toUpperCase());
        }
        if (request.openingBalance() != null) {
            account.setBalance(request.openingBalance());
        }
        return account;
    }

    public AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getAccountId(),
                account.getCustomerId(),
                account.getAccountNumber(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getOpeningDate(),
                account.getStatus());
    }
}
