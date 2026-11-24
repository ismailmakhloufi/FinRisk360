package tn.finrisk.aiservice.client.dto;

import java.util.List;

/**
 * Vue paginée des listes distantes : les APIs renvoient Spring Page (JSON),
 * mais on ne désérialise JAMAIS dans PageImpl (API instable documentée) —
 * on lit juste les champs utiles. Champs inconnus ignorés (Boot le fait déjà).
 */
public record PageView<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int number,
        int size) {

    public List<T> contentOrEmpty() {
        return content == null ? List.of() : content;
    }
}
