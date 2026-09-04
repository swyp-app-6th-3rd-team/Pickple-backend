package app.pickple.badge.infra;

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
 * {@code user_badge} 한 행 — 누가 무엇을 언제 얻었는가.
 *
 * <p><b>수정 수단을 두지 않는다.</b> 뱃지는 한 번 얻으면 남고 회수 정책이 없다.
 * 상태를 바꾸는 메서드가 없으면 그 규칙을 어길 경로 자체가 생기지 않는다
 * ({@code Post} 의 유형 불변이 같은 방식으로 지켜진다 — ERD 3차 §2).
 *
 * <p>{@code badge} 와 {@code users} 를 연관관계로 매핑하지 않고 식별자로 든다.
 * 획득 판정은 "가진 뱃지 id 집합" 만 있으면 되고, 연관을 걸면 판정 한 번에
 * 뱃지 정의를 다시 읽는 조인이 따라붙는다.
 */
@Getter
@Entity
@Table(name = "user_badge", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_badge", columnNames = {"user_id", "badge_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBadgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "badge_id", nullable = false)
    private Long badgeId;

    @Column(name = "acquired_at", nullable = false)
    private LocalDateTime acquiredAt;

    private UserBadgeEntity(Long userId, Long badgeId, LocalDateTime acquiredAt) {
        this.userId = userId;
        this.badgeId = badgeId;
        this.acquiredAt = acquiredAt;
    }

    static UserBadgeEntity of(Long userId, Long badgeId, LocalDateTime acquiredAt) {
        return new UserBadgeEntity(userId, badgeId, acquiredAt);
    }
}
