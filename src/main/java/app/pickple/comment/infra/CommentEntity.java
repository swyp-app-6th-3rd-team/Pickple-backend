package app.pickple.comment.infra;

import app.pickple.comment.domain.Comment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * {@code comment} 한 행.
 *
 * <p>{@code container_type} 은 DB 생성 컬럼이라 매핑하지 않는다. 값이 있을 때만
 * {@code 'COMMENT'} 가 되고, 복합 FK {@code (item_container_id, container_type)} 이
 * 상품용 컨테이너의 부착을 막는다.
 *
 * <p>{@code uk_comment_id_post (id, post_id)} 는 원픽이 "같은 게시글의 댓글"만
 * 참조하도록 하는 복합 FK 의 대상이다.
 */
@Getter
@Entity
@Table(name = "comment", uniqueConstraints =
        @UniqueConstraint(name = "uk_comment_id_post", columnNames = {"id", "post_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_container_id")
    private Long itemContainerId;

    @Column(name = "content", nullable = false, length = 300)
    private String content;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private CommentEntity(Comment comment, LocalDateTime now) {
        this.id = comment.id();
        this.postId = comment.postId();
        this.userId = comment.authorId();
        this.itemContainerId = comment.itemContainerId();
        this.content = comment.content();
        this.deletedAt = comment.isDeleted() ? now : null;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static CommentEntity from(Comment comment, LocalDateTime now) {
        return new CommentEntity(comment, now);
    }

    void applyState(Comment comment, LocalDateTime now) {
        this.content = comment.content();
        if (comment.isDeleted() && this.deletedAt == null) {
            this.deletedAt = now;
        }
        this.updatedAt = now;
    }

    Comment toDomain() {
        return Comment.restore(id, postId, userId, content, itemContainerId, deletedAt != null);
    }
}
