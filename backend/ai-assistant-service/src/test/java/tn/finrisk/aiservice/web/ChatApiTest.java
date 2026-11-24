package tn.finrisk.aiservice.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.finrisk.aiservice.client.exceptions.RemoteResourceNotFoundException;
import tn.finrisk.aiservice.service.ChatService;
import tn.finrisk.aiservice.web.dto.ChatResponse;

/**
 * Contrat HTTP du chat : 200 avec intent/mode, 400 si message ou alertId
 * manquant (validation), 404 si alerte inconnue. Service MOCKÉ.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatApiTest {

    @MockitoBean
    private ChatService chatService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void chatReturnsAnswerWithIntentAndMode() throws Exception {
        when(chatService.chat(new tn.finrisk.aiservice.web.dto.ChatRequest(42L, "pourquoi ce score ?")))
                .thenReturn(new ChatResponse(42L, "WHY_SCORE", "DETERMINISTE",
                        "Le score 87/100 vient de risk-service."));

        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertId\":42,\"message\":\"pourquoi ce score ?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertId").value(42))
                .andExpect(jsonPath("$.intent").value("WHY_SCORE"))
                .andExpect(jsonPath("$.mode").value("DETERMINISTE"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("87")));
    }

    @Test
    void chat400_whenMessageBlank() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertId\":42,\"message\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("message"));
    }

    @Test
    void chat400_whenAlertIdMissing() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"bonjour\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("alertId"));
    }

    @Test
    void chat404_whenAlertUnknown() throws Exception {
        when(chatService.chat(new tn.finrisk.aiservice.web.dto.ChatRequest(999L, "résumé")))
                .thenThrow(new RemoteResourceNotFoundException("alert", 999L));

        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertId\":999,\"message\":\"résumé\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
