package app.pickple.vote.infra;

import app.pickple.vote.domain.Vote;
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
 * {@code vote} 한 행.
 *
 * <p>선택지 참조는 복합 FK {@code (post_option_id, post_id)} 라 연관관계로 매핑하지 않는다.
 * 단순 FK 였다면 게시글 A 의 투표가 게시글 B 의 선택지를 가리켜도 통과했다 (ERD 2차 2.3).
 */
@Getter
@Entity
@Table(name = "vote", uniqueConstraints =
        @UniqueConstraint(name = "uk_vote_post_user", columnNames = {"post_id", "user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "post_option_id", nullable = false)
    private Long postOptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private VoteEntity(Vote vote, LocalDateTime now) {
        this.id = vote.id();
        this.postId = vote.postId();
        this.postOptionId = vote.postOptionId();
        this.userId = vote.voterId();
        this.createdAt = now;
    }

    static VoteEntity from(Vote vote, LocalDateTime now) {
        return new VoteEntity(vote, now);
    }

    /** 선택지만 바꾼다. 투표 시각은 최초 투표 시점을 유지한다 (R-22). */
    void applyChoice(Vote vote) {
        this.postOptionId = vote.postOptionId();
    }

    Vote toDomain() {
        return Vote.restore(id, postId, postOptionId, userId);
    }
}
