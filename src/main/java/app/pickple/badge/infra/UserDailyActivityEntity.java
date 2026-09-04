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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code user_daily_activity} 한 행 — 어느 회원이 어느 날 몇 번 투표했는가 (R-19).
 *
 * <p><b>{@code voteCount} 를 읽기 전용으로 매핑한다.</b> 이 값은 UPSERT 가
 * {@code vote_count = vote_count + 1} 로 원자적으로 올린다. 보통 컬럼으로 매핑하면
 * 하이버네이트의 일반 UPDATE 가 SET 절에 이 컬럼을 포함하고, 그 값은 트랜잭션이
 * 시작할 때 읽은 오래된 스냅샷이라 <b>그 사이 증가한 값을 되돌린다.</b>
 * 게시글 카운터에서 실제로 물렸던 결함이다 (ERD 3차 §1.4).
 *
 * <p>타입도 주의한다 — 컬럼이 {@code INT UNSIGNED} 라 {@code Integer} 여야 하고
 * {@code Long} 이면 {@code ddl-auto: validate} 가 기동에서 막는다 (ERD 3차 §1.1).
 *
 * <p>이 엔티티로 쓰지 않는다. 삽입도 증가도 {@link UserDailyActivityRepository} 의
 * UPSERT 한 문장이 한다 — 읽기 전용 뷰로만 존재한다.
 */
@Getter
@Entity
@Table(name = "user_daily_activity", uniqueConstraints =
        @UniqueConstraint(name = "uk_daily_user_date", columnNames = {"user_id", "activity_date"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDailyActivityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    /** 원자 UPDATE 로만 움직인다. 일반 UPDATE 가 덮어쓰지 못하게 읽기 전용이다. */
    @Column(name = "vote_count", nullable = false, insertable = false, updatable = false)
    private Integer voteCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
