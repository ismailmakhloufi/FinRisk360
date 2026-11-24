package tn.finrisk.aiservice.client;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Connecteur LLM optionnel, API compatible OpenAI (OpenAI, Mistral, Groq,
 * Ollama sur localhost:11434/v1 avec clé vide...). Activé IFF AI_LLM_KEY
 * definie — sinon isEnabled()=false et le chat reste déterministe.
 *
 * Toute panne (réseau, modèle absent, timeout) retombe silencieusement sur
 * le déterministe : le chat ne casse JAMAIS, la démo non plus.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestClient restClient;
    private final String model;
    private final String key;

    public LlmClient(@Value("${ai.llm.base-url}") String baseUrl,
            @Value("${ai.llm.model}") String model,
            @Value("${ai.llm.key}") String key) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.model = model;
        this.key = key == null ? "" : key;
    }

    public boolean isEnabled() {
        return !key.isBlank();
    }

    /** Renvoie le texte de la réponse, ou null si échec (→ repli déterministe). */
    public String complete(String system, String user) {
        return complete(system, user, 0.2, null);
    }

    public String completeStructured(String system, String user, Map<String, Object> schema) {
        return complete(system, user, 0.0, Map.of("type", "json_schema",
                "json_schema", Map.of("name", "investigation", "strict", true, "schema", schema)));
    }

    private String complete(String system, String user, double temperature, Map<String, Object> format) {
        try {
            LlmResult result = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + key)
                    .body(new LlmRequest(model, List.of(
                            new LlmMessage("system", system),
                            new LlmMessage("user", user)), temperature, "24h", format))
                    .retrieve()
                    .body(LlmResult.class);
            if (result == null || result.choices() == null || result.choices().isEmpty()) {
                return null;
            }
            LlmMessage message = result.choices().getFirst().message();
            return message == null ? null : message.content();
        } catch (Exception e) {
            log.warn("LLM injoignable, repli sur le moteur deterministe : {}", e.getMessage());
            return null;
        }
    }

    /**
     * keep_alive (Ollama) : garde le modele charge 24h — sans cela le modele
     * est decharge apres 5 min et le 1er appel suivant coute ~40s de
     * rechargement le jour de la demo. OpenAI ignore ce champ inconnu.
     */
    record LlmRequest(String model, List<LlmMessage> messages, double temperature,
            @JsonProperty("keep_alive") String keepAlive,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("response_format") Map<String, Object> responseFormat) {
    }

    record LlmMessage(String role, String content) {
    }

    record LlmResult(List<LlmChoice> choices) {
    }

    record LlmChoice(LlmMessage message) {
    }
}
