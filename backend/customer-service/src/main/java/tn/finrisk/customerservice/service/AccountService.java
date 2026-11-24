package tn.finrisk.customerservice.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.finrisk.customerservice.repository.AccountRepository;
import tn.finrisk.customerservice.web.dto.AccountResponse;
import tn.finrisk.customerservice.web.mapper.AccountMapper;

/**
 * Lecture des comptes appartenant a un client.
 * Ecriture des comptes : pas encore (M2), cette etape se limite a la lecture.
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomerService customerService;
    private final AccountMapper accountMapper;

    public AccountService(AccountRepository accountRepository,
                          CustomerService customerService,
                          AccountMapper accountMapper) {
        this.accountRepository = accountRepository;
        this.customerService = customerService;
        this.accountMapper = accountMapper;
    }

    /** GET /api/customers/{id}/accounts */
    public List<AccountResponse> getAccountsOf(Long customerId) {
        customerService.getReference(customerId);   // 404 si le client n'existe pas
        return accountRepository.findByCustomerCustomerIdOrderByAccountIdDesc(customerId)
                .stream()
                .map(accountMapper::toResponse)
                .toList();
    }
}
