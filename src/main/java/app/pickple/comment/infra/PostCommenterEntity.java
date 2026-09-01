package app.pickple.comment.infra;

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
 * 게시글에 처음 댓글을 단 사람 (R-25).
 *
 * <p>도메인 객체가 없다. 이 테이블은 "인원을 세기 위한 장치"이지 도메인 개념이 아니다 —
 * 행의 <b>존재 여부</b>만 의미를 갖고 상태도 행위도 없다.
 */
@Getter
@Entity
@Table(name = "post_commenter", uniqueConstraints =
        @UniqueConstraint(name = "uk_commenter_post_user", columnNames = {"post_id", "user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostCommenterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
