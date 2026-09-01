package app.pickple.point.infra;

import app.pickple.point.domain.PointHistory;
import app.pickple.point.domain.PointReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * {@code point_history} 한 행.
 *
 * <p>{@code uk_point_idem (comment_pick_id, reason)} 이 멱등키다.
 * 두 컬럼 모두 {@code NOT NULL} 이어야 성립한다 — 유니크 키는 NULL 을
 * 서로 다르게 취급하므로 하나라도 비면 중복 적립이 뚫린다.
 */
@Getter
@Entity
@Table(name = "point_history", uniqueConstraints =
        @UniqueConstraint(name = "uk_point_idem", columnNames = {"comment_pick_id", "reason"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "amount", nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private PointReason reason;

    @Column(name = "comment_pick_id", nullable = false)
    private Long commentPickId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private PointHistoryEntity(PointHistory history, LocalDateTime now) {
        this.id = history.id();
        this.userId = history.userId();
        this.amount = history.amount();
        this.reason = history.reason();
        this.commentPickId = history.onePickId();
        this.createdAt = now;
    }

    static PointHistoryEntity from(PointHistory history, LocalDateTime now) {
        return new PointHistoryEntity(history, now);
    }

    PointHistory toDomain() {
        return PointHistory.restore(id, userId, amount, reason, commentPickId);
    }
}
