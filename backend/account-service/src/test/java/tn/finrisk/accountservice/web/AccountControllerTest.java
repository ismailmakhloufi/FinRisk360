package tn.finrisk.accountservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.accountservice.domain.enums.AccountStatus;
import tn.finrisk.accountservice.domain.enums.AccountType;
import tn.finrisk.accountservice.service.AccountService;
import tn.finrisk.accountservice.web.dto.AccountCreateRequest;
import tn.finrisk.accountservice.web.dto.AccountResponse;
import tn.finrisk.accountservice.web.error.AccountNotFoundException;
import tn.finrisk.accountservice.web.error.AccountNumberAlreadyExistsException;
import tn.finrisk.accountservice.web.error.InsufficientBalanceException;

/**
 * Tests HTTP simules : Spring MVC + Jackson + GlobalExceptionHandler,
 * mais AUCUNE logique metier (le service est un mock).
 */
@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    private static final String CREATE_OK = """
            {"customerId":1,"accountType":"CHECKING","currency":"TND","openingBalance":500.00}
            """;

    @Test
    void getAll_retourneLaPage() throws Exception {
        AccountResponse account = response(1L, AccountStatus.ACTIVE);
        when(accountService.getAll(any(PageRequest.class), eq(null)))
                .thenReturn(new PageImpl<>(List.of(account), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].accountId").value(1L))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void create_donneesValides_retourne201AvecLocation() throws Exception {
        AccountResponse created = response(42L, AccountStatus.ACTIVE);
        when(accountService.create(any(AccountCreateRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_OK))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/accounts/42"))
                .andExpect(jsonPath("$.accountId").value(42L))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void create_donneesInvalides_retourne400AvecErreurs() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingBalance\":-50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@.field == 'customerId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'accountType')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'openingBalance')]").exists());
    }

    @Test
    void getById_inexistant_retourne404Formate() throws Exception {
        when(accountService.getById(999L)).thenThrow(new AccountNotFoundException(999L));

        mockMvc.perform(get("/api/accounts/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Compte introuvable : id = 999"))
                .andExpect(jsonPath("$.path").value("/api/accounts/999"));
    }

    @Test
    void create_numeroDoublon_retourne409() throws Exception {
        when(accountService.create(any(AccountCreateRequest.class)))
                .thenThrow(new AccountNumberAlreadyExistsException("TN123"));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_OK))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Ce numero de compte est deja utilise : TN123"));
    }

    @Test
    void getById_identifiantNonNumerique_retourne400() throws Exception {
        mockMvc.perform(get("/api/accounts/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parametre 'id' invalide : abc"));
    }

    @Test
    void operateBalance_retourneNouveauSolde() throws Exception {
        AccountResponse updated = response(2L, AccountStatus.ACTIVE);
        when(accountService.operateBalance(eq(2L), any())).thenReturn(updated);

        mockMvc.perform(patch("/api/accounts/2/balance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"direction\":\"CREDIT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(2L))
                .andExpect(jsonPath("$.balance").value(0.0));
    }

    @Test
    void operateBalance_soldeInsuffisant_retourne409() throws Exception {
        when(accountService.operateBalance(eq(3L), any()))
                .thenThrow(new InsufficientBalanceException("9999"));

        mockMvc.perform(patch("/api/accounts/3/balance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"direction\":\"DEBIT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Solde insuffisant sur le compte 9999"));
    }

    private AccountResponse response(Long id, AccountStatus status) {
        return new AccountResponse(id, 1L, "123456789012", AccountType.CHECKING, "TND",
                new BigDecimal("0.00"), LocalDate.now(), status);
    }
}
