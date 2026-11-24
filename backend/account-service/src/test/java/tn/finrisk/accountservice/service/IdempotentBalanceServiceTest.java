package tn.finrisk.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tn.finrisk.accountservice.domain.enums.BalanceDirection;
import tn.finrisk.accountservice.web.dto.BalanceOperationRequest;
import tn.finrisk.accountservice.web.error.AccountNotOperableException;

class IdempotentBalanceServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccountService accounts = mock(AccountService.class);
    private final IdempotentBalanceService service = new IdempotentBalanceService(jdbc, accounts);
    private final UUID key = UUID.randomUUID();
    private final BalanceOperationRequest request =
            new BalanceOperationRequest(new BigDecimal("10.00"), BalanceDirection.DEBIT);

    @Test
    void premierAppelAppliqueMouvement() {
        when(jdbc.update(anyString(), eq(key), eq(1L), eq(request.amount()), eq("DEBIT"))).thenReturn(1);
        service.apply(key, 1L, request);
        verify(accounts).operateBalance(1L, request);
    }

    @Test
    void rejeuIdentiqueNeReappliquePasMouvement() {
        when(jdbc.update(anyString(), eq(key), eq(1L), eq(request.amount()), eq("DEBIT"))).thenReturn(0);
        when(jdbc.queryForMap(anyString(), eq(key))).thenReturn(
                Map.of("account_id", 1L, "amount", new BigDecimal("10.0"), "direction", "DEBIT"));
        service.apply(key, 1L, request);
        verify(accounts, never()).operateBalance(any(), any());
        verify(accounts).getById(1L);
    }

    @Test
    void reutilisationPourAutreMouvementEstRefusee() {
        when(jdbc.update(anyString(), eq(key), eq(1L), eq(request.amount()), eq("DEBIT"))).thenReturn(0);
        when(jdbc.queryForMap(anyString(), eq(key))).thenReturn(
                Map.of("account_id", 1L, "amount", new BigDecimal("11"), "direction", "DEBIT"));
        assertThatThrownBy(() -> service.apply(key, 1L, request))
                .isInstanceOf(AccountNotOperableException.class);
        verifyNoInteractions(accounts);
    }
}
