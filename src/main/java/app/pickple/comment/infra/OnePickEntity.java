package app.pickple.comment.infra;

import app.pickple.comment.domain.OnePick;
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
 * 원픽 한 행. 테이블 이름은 {@code comment_pick} 이지만 도메인 이름은 {@code OnePick} 이다 —
 * 화면과 기획이 "원픽"이라 부르므로 코드가 그 말을 따른다 (ADR-0018).
 *
 * <p>{@code post_id} 는 비정규화다. 복합 FK {@code (comment_id, post_id)} 가
 * 댓글의 게시글과 어긋난 값을 막는다.
 */
@Getter
@Entity
@Table(name = "comment_pick", uniqueConstraints =
        @UniqueConstraint(name = "uk_pick_user_comment", columnNames = {"user_id", "comment_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OnePickEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private OnePickEntity(OnePick pick, LocalDateTime now) {
        this.postId = pick.postId();
        this.commentId = pick.commentId();
        this.userId = pick.pickerId();
        this.createdAt = now;
    }

    static OnePickEntity from(OnePick pick, LocalDateTime now) {
        return new OnePickEntity(pick, now);
    }
}
