package tn.finrisk.alertcaseservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import tn.finrisk.alertcaseservice.domain.enums.CommentType;

/**
 * Entite mappee sur "case_comments" : le fil de discussion d'une enquete.
 * NOTE = commentaire libre, EVIDENCE = piece jointe/element de preuve,
 * DECISION = arbitrage de l'analyste (celui qui cloture generalement).
 *
 * user_id NOT NULL (FK RESTRICT) : tout commentaire est ATTRIBUE a un user.
 * En M4 auth-service n'existe pas encore => on ne fait JAMAIS SELECT users,
 * on laisse la FK PostgreSQL agir et on traduit sa violation en 404.
 */
@Entity
@Table(name = "case_comments")
public class CaseComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "content", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "comment_type", nullable = false, length = 20)
    private CommentType commentType = CommentType.NOTE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public CaseComment() {
    }

    /** Equivalent des DEFAULT des colonnes SQL cote Java. */
    @PrePersist
    void prePersist() {
        if (commentType == null) {
            commentType = CommentType.NOTE;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getCommentId() {
        return commentId;
    }

    public void setCommentId(Long commentId) {
        this.commentId = commentId;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public CommentType getCommentType() {
        return commentType;
    }

    public void setCommentType(CommentType commentType) {
        this.commentType = commentType;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
