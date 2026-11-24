package tn.finrisk.transactionservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.transactionservice.domain.enums.MerchantStatus;
import tn.finrisk.transactionservice.service.MerchantService;
import tn.finrisk.transactionservice.web.dto.MerchantResponse;
import tn.finrisk.transactionservice.web.error.MerchantAlreadyExistsException;
import tn.finrisk.transactionservice.web.error.MerchantNotFoundException;

/** Tests HTTP simules des endpoints marchands (service mocke). */
@WebMvcTest(MerchantController.class)
class MerchantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MerchantService merchantService;

    @Test
    void getAll_retourneLaListe() throws Exception {
        when(merchantService.getAll()).thenReturn(List.of(response(1L)));

        mockMvc.perform(get("/api/merchants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].merchantId").value(1L))
                .andExpect(jsonPath("$[0].merchantName").value("Tunis Market"));
    }

    @Test
    void create_donneesValides_retourne201AvecLocation() throws Exception {
        when(merchantService.create(any())).thenReturn(response(9L));

        mockMvc.perform(post("/api/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantName\":\"Tunis Market\",\"country\":\"TN\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/merchants/9"))
                .andExpect(jsonPath("$.merchantId").value(9L))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void create_nomDoublon_retourne409() throws Exception {
        when(merchantService.create(any())).thenThrow(new MerchantAlreadyExistsException("Tunis Market"));

        mockMvc.perform(post("/api/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantName\":\"Tunis Market\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ce marchand existe deja : Tunis Market"));
    }

    @Test
    void create_nomManquant_retourne400() throws Exception {
        mockMvc.perform(post("/api/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'merchantName')]").exists());
    }

    @Test
    void getById_inexistant_retourne404() throws Exception {
        when(merchantService.getById(77L)).thenThrow(new MerchantNotFoundException(77L));

        mockMvc.perform(get("/api/merchants/77"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Marchand introuvable : id = 77"));
    }

    private MerchantResponse response(Long id) {
        return new MerchantResponse(id, "Tunis Market", "RETAIL", "TN", "Tunis",
                MerchantStatus.ACTIVE, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
