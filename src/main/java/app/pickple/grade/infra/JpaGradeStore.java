package app.pickple.grade.infra;

import app.pickple.grade.domain.Grade;
import app.pickple.grade.domain.GradeStore;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승급 판정 입력값을 원장에서 읽고 도달 등급을 올린다 (ADR-0030).
 *
 * <p><b>왜 네이티브 SQL 인가</b> — 세 도메인({@code point_history} · {@code vote} ·
 * {@code users})에 걸친 조회라 어느 한 엔티티의 리포지토리에 얹을 자리가 없다.
 * 필터와 집계를 소스에서 끝내는 형태이기도 하다.
 *
 * <p><b>{@code UserEntity} 에 {@code highest_grade} 를 매핑하지 않는다.</b>
 * 매핑하면 프로필 저장 같은 평범한 쓰기가 도달 등급을 옛 스냅샷으로 덮어쓴다 —
 * V7 의 {@code ranking} 이 매핑되지 않은 것과 같은 이유다 (ADR-0028).
 * 유도 컬럼은 유도하는 쪽만 만진다.
 */
@Component
@RequiredArgsConstructor
public class JpaGradeStore implements GradeStore {

    /**
     * 판정 입력값 두 개를 한 번에 읽는다.
     *
     * <p>둘을 스칼라 서브쿼리로 묶는다. 조인하면 {@code point_history} 와 {@code vote} 가
     * 곱해져 같은 행을 여러 번 세게 된다 — 포인트 이력 3건과 투표 5건이 있는 사용자의
     * 합계가 3배, 횟수가 5배로 부푼다.
     *
     * <p>각 서브쿼리는 {@code user_id} 를 선행 컬럼으로 갖는 인덱스로 좁혀진다
     * ({@code idx_point_user_created} · {@code idx_vote_user_created}, 둘 다 V3).
     *
     * <p>{@code vote} 의 {@code COUNT(*)} 가 곧 사람 단위 횟수다 (R-22).
     * {@code UNIQUE (post_id, user_id)} 라 한 사람이 한 게시글에 가질 수 있는 행이
     * 최대 1개이고, 선택 변경은 UPDATE 라 행을 늘리지 않는다 — 재투표를 빼는
     * 별도 조건이 필요 없다.
     */
    private static final String READ_INPUTS = """
            SELECT COALESCE((SELECT SUM(ph.amount) FROM point_history ph
                              WHERE ph.user_id = :userId), 0) AS point,
                   (SELECT COUNT(*) FROM vote v WHERE v.user_id = :userId) AS vote_count
            """;

    private static final String READ_HIGHEST_GRADE = """
            SELECT highest_grade FROM users WHERE id = :userId
            """;

    /**
     * 도달 등급을 올린다. 내리지 않는다 (R-16).
     *
     * <p>{@code highest_grade < :level} 조건이 이 메서드의 전부다. 읽고 비교해서 쓰면
     * 그 사이에 끼어든 승급을 되돌린다 — 조건을 SQL 에 두면 DB 가 원자적으로 판정한다.
     * 낮은 값으로 부르면 아무 행도 갱신되지 않고 0 을 돌려준다.
     */
    private static final String RAISE_HIGHEST_GRADE = """
            UPDATE users SET highest_grade = :level
             WHERE id = :userId AND highest_grade < :level
            """;

    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public GradeInputs readInputs(Long userId) {
        Object[] row = (Object[]) entityManager.createNativeQuery(READ_INPUTS)
                .setParameter("userId", userId)
                .getSingleResult();
        return new GradeInputs(toLong(row[0]), toLong(row[1]));
    }

    /**
     * 저장된 도달 등급.
     *
     * <p>사용자가 없으면 {@code NoResultException} 이 그대로 올라간다. 여기서 LV.1 로
     * 대신 답하면 없는 사용자의 등급을 지어내는 것이 되고, 그 판단은 저장소의 몫이 아니다
     * (ADR-0019). 존재 여부는 인증이 이미 보장한다.
     */
    @Override
    @Transactional(readOnly = true)
    public Grade readHighestGrade(Long userId) {
        Object level = entityManager.createNativeQuery(READ_HIGHEST_GRADE)
                .setParameter("userId", userId)
                .getSingleResult();
        return Grade.ofLevel((int) toLong(level));
    }

    @Override
    @Transactional
    public boolean raiseHighestGrade(Long userId, Grade grade) {
        return entityManager.createNativeQuery(RAISE_HIGHEST_GRADE)
                .setParameter("userId", userId)
                .setParameter("level", grade.level())
                .executeUpdate() > 0;
    }

    /**
     * 네이티브 조회의 수치 타입을 맞춘다.
     *
     * <p>드라이버가 컬럼마다 다른 타입을 돌려준다 — {@code SUM()} 은 {@code BigDecimal},
     * {@code COUNT()} 는 {@code Long}, {@code TINYINT} 는 {@code Integer} 다.
     * 캐스트로 받으면 셋 중 하나에서 {@code ClassCastException} 이 난다.
     */
    private static long toLong(Object value) {
        return ((Number) value).longValue();
    }
}
