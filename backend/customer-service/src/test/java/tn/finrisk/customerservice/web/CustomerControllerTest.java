package tn.finrisk.customerservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.customerservice.domain.enums.CustomerStatus;
import tn.finrisk.customerservice.domain.enums.CustomerType;
import tn.finrisk.customerservice.service.AccountService;
import tn.finrisk.customerservice.service.CustomerService;
import tn.finrisk.customerservice.web.dto.CustomerRequest;
import tn.finrisk.customerservice.web.dto.CustomerResponse;
import tn.finrisk.customerservice.web.error.CustomerNotFoundException;
import tn.finrisk.customerservice.web.error.EmailAlreadyExistsException;

/**
 * Test du contrôleur seul : MockMvc simule le HTTP, le service est un FAUX.
 * Ni base, ni Tomcat : le test tourne en ~1 seconde.
 */
@WebMvcTest(CustomerController.class)
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    @MockitoBean
    private AccountService accountService;

    @Test
    void getCustomer_existant_retourne200() throws Exception {
        when(customerService.getById(1L)).thenReturn(customer());

        mockMvc.perform(get("/api/customers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(1))
                .andExpect(jsonPath("$.firstName").value("Amine"))
                .andExpect(jsonPath("$.country").value("TN"));
    }

    @Test
    void getCustomer_inexistant_retourne404EtLeFormatDerreur() throws Exception {
        when(customerService.getById(999L)).thenThrow(new CustomerNotFoundException(999L));

        mockMvc.perform(get("/api/customers/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Client introuvable : id = 999"))
                .andExpect(jsonPath("$.path").value("/api/customers/999"));
    }

    @Test
    void create_donneesInvalides_retourne400AvecLeDetailDesChamps() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"\",\"lastName\":\"Ben Ali\",\"email\":\"pas-un-mail\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation echouee"))
                .andExpect(jsonPath("$.errors[0].field").value("firstName"))
                .andExpect(jsonPath("$.errors[0].message").value("le prenom est obligatoire"));
    }

    @Test
    void create_donneesValides_retourne201AvecLocation() throws Exception {
        when(customerService.create(any(CustomerRequest.class))).thenReturn(customer());

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Amine\",\"lastName\":\"Ben Ali\",\"email\":\"amine@mail.tn\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/customers/1"))
                .andExpect(jsonPath("$.customerId").value(1));
    }

    @Test
    void create_emailDejaUtilise_retourne409() throws Exception {
        when(customerService.create(any(CustomerRequest.class)))
                .thenThrow(new EmailAlreadyExistsException("amine@mail.tn"));

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Amine\",\"lastName\":\"Ben Ali\",\"email\":\"amine@mail.tn\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void listerClients_retourneLaPage() throws Exception {
        when(customerService.getAll(any())).thenReturn(new PageImpl<>(List.of(customer())));

        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].firstName").value("Amine"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getCustomer_identifiantNonNumerique_retourne400() throws Exception {
        mockMvc.perform(get("/api/customers/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    private CustomerResponse customer() {
        return new CustomerResponse(1L, "Amine", "Ben Ali", "amine@mail.tn", null, null,
                "TN", null, CustomerType.RETAIL, CustomerStatus.ACTIVE, null);
    }
}
