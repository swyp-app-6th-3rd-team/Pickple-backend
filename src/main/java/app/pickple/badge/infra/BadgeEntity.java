package app.pickple.badge.infra;

import app.pickple.badge.domain.Badge;
import app.pickple.badge.domain.BadgeConditionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * {@code badge} 한 행 — 뱃지 정의.
 *
 * <p><b>애플리케이션이 이 테이블에 쓰지 않는다.</b> 8행은 V9 마이그레이션이 넣고,
 * 이름이 바뀌면 운영 DB 를 직접 UPDATE 한다. 그래서 정적 팩터리도 수정 메서드도 없다 —
 * 쓰기 경로를 두지 않는 것이 "정의는 코드가 만들지 않는다" 는 설계의 표현이다.
 *
 * <p>{@code threshold} 가 {@code Long} 이 아니라 {@code Integer} 인 것에 주의한다.
 * 컬럼이 {@code INT UNSIGNED} 라 {@code Long} 으로 매핑하면
 * {@code ddl-auto: validate} 가 기동에서 {@code wrong column type} 으로 막는다
 * (ERD 3차 §1.1 — 컴파일도 단위 테스트도 통과하지만 기동이 실패한다).
 * 도메인은 {@code long} 으로 다루고 변환은 여기서 한다.
 */
@Getter
@Entity
@Table(name = "badge")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BadgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 30)
    private String name;

    @Column(name = "description", nullable = false, length = 100)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 20)
    private BadgeConditionType conditionType;

    /** {@code INT UNSIGNED} 라 {@code Integer} 다. {@code Long} 이면 기동이 실패한다. */
    @Column(name = "threshold", nullable = false)
    private Integer threshold;

    /** {@code TINYINT} 라 {@code Byte} 다. {@code Integer} 면 기동이 실패한다 (ERD 3차 §1.1). */
    @Column(name = "display_order", nullable = false)
    private Byte displayOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    Badge toDomain() {
        return new Badge(id, code, name, description, conditionType, threshold, displayOrder);
    }
}
