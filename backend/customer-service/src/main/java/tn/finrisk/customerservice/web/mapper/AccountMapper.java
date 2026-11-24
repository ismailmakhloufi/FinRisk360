package tn.finrisk.customerservice.web.mapper;

import org.springframework.stereotype.Component;
import tn.finrisk.customerservice.domain.Account;
import tn.finrisk.customerservice.web.dto.AccountResponse;

@Component
public class AccountMapper {

    public AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getAccountId(),
                account.getAccountNumber(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getOpeningDate(),
                account.getStatus());
    }
}
