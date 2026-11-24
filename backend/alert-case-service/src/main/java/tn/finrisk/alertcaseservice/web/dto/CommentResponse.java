package tn.finrisk.alertcaseservice.web.dto;

import tn.finrisk.alertcaseservice.domain.CaseComment;
import tn.finrisk.alertcaseservice.domain.enums.CommentType;
import java.time.OffsetDateTime;

/** Reponse rendue pour un commentaire du fil d'enquete. */
public record CommentResponse(
        Long commentId,
        Long caseId,
        Long userId,
        CommentType commentType,
        String content,
        OffsetDateTime createdAt) {

    public static CommentResponse from(CaseComment comment) {
        return new CommentResponse(
                comment.getCommentId(),
                comment.getCaseId(),
                comment.getUserId(),
                comment.getCommentType(),
                comment.getContent(),
                comment.getCreatedAt());
    }
}
