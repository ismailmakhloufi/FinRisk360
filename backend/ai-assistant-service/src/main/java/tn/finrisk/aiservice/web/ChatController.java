package tn.finrisk.aiservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.finrisk.aiservice.service.ChatService;
import tn.finrisk.aiservice.web.dto.ChatRequest;
import tn.finrisk.aiservice.web.dto.ChatResponse;

/**
 * Boîte de dialogue de l'analyste sur UNE alerte : questions libres,
 * réponses branchées sur les données réelles (déterministe par défaut,
 * LLM si AI_LLM_KEY definie — mêmes endpoints dans les deux cas).
 */
@Tag(name = "Chat IA", description = "M6 : dialogue analyste sur le contexte d'une alerte")
@RestController
@RequestMapping("/api/ai")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** POST /api/ai/chat { "alertId": 42, "message": "pourquoi ce score ?" } */
    @Operation(summary = "Dialoguer sur une alerte",
            description = "Intentions reconnues : résumé, score, facteurs, signaux, verdict, "
                    + "confiance, actions, montant, bénéficiaire, pays, device/IP, vélocité, "
                    + "heure, client, comptes, transaction. Repli honnête sinon — jamais de "
                    + "réponse inventée. mode = DETERMINISTE ou LLM.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Réponse du chat"),
            @ApiResponse(responseCode = "400", description = "alertId ou message manquant"),
            @ApiResponse(responseCode = "404", description = "Alerte inconnue"),
            @ApiResponse(responseCode = "503", description = "Un des services lus est injoignable")})
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }
}
