package tn.finrisk.alertcaseservice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tn.finrisk.alertcaseservice.domain.enums.CommentType;

/**
 * Charge utile de POST /api/cases/{id}/comments.
 * L'auteur ne vient PAS d'ici : header X-User-Id (case_comments.user_id NOT
 * NULL) — rejet en 400 s'il manque. Pas de pseudo-anonymat.
 */
public record CommentCreateRequest(
        @NotBlank(message = "content est obligatoire")
        @Size(max = 2000, message = "content : 2000 caracteres maximum")
        String content,
        CommentType commentType) {
}
