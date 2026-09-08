package app.pickple.grade.infra;

import app.pickple.auth.infra.UserEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 등급 판정에 쓰는 세 SQL 을 타입이 있는 시그니처로 묶는다. package-private —
 * 바깥은 {@link app.pickple.grade.domain.GradeStore} 만 본다.
 *
 * <p><b>왜 {@code JpaRepository} 가 아니라 {@code Repository} 인가</b> — 여기서 필요한 것은
 * 아래 세 메서드뿐이다. CRUD 를 상속하면 {@code users} 를 이 패키지에서 저장·삭제할 수 있는
 * 문이 열린다. {@code UserEntity} 는 Spring Data 가 요구하는 관리 타입 자리일 뿐이고,
 * 이 인터페이스는 그 엔티티를 읽거나 쓰지 않는다 — {@code highest_grade} 는 엔티티에
 * 읽기 전용으로만 매핑돼 있다 ({@code JpaGradeStore} javadoc · ADR-0041).
 *
 * <p><b>왜 인터페이스 프로젝션인가</b> — 드라이버가 함수마다 다른 타입을 돌려준다
 * ({@code SUM()} → {@code BigDecimal}, {@code COUNT()} → {@code Long},
 * {@code TINYINT} → 정수 소형 타입). Spring Data 는 인터페이스 프로젝션의 접근자 반환 타입에
 * 맞춰 값을 {@code ConversionService} 로 변환하므로({@code ProjectingMethodInterceptor}),
 * 그 방어가 여기 선언 한 줄로 끝난다. record 로 받으면 Hibernate 가 생성자 인자 타입과
 * 드라이버 타입이 맞는 생성자를 찾지 못해 실패하고, 맞추려면 SQL 에 {@code CAST} 를 넣거나
 * record 가 {@code BigDecimal} 을 들어야 한다 — 둘 다 드라이버 사정이 계약에 새는 것이다.
 * {@code CommentRepository.CommentListRow} 와 같은 방식이다.
 */
interface GradeRepository extends Repository<UserEntity, Long> {

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
     *
     * <p>별칭은 {@link GradeInputRow} 의 접근자 이름과 같아야 한다. 프로젝션이 이름으로
     * 묶이므로 별칭이 어긋나면 컴파일은 통과하고 접근자가 {@code null} 을 돌려준다 —
     * {@code JpaGradeStoreIT} 가 첫 호출에서 잡는다.
     *
     * <p>상수로 둔 이유는 테스트가 <b>같은 문장</b>으로 실행 계획을 확인하기 위해서다.
     * 테스트에 SQL 을 베껴 두면 여기가 바뀌어도 테스트는 옛 문장을 검사한다.
     */
    String READ_INPUTS = """
            SELECT COALESCE((SELECT SUM(ph.amount) FROM point_history ph
                              WHERE ph.user_id = :userId), 0) AS point,
                   (SELECT COUNT(*) FROM vote v WHERE v.user_id = :userId) AS voteCount
            """;

    @Query(value = READ_INPUTS, nativeQuery = true)
    GradeInputRow readInputs(@Param("userId") Long userId);

    /** 저장된 도달 등급. 사용자가 없으면 비어 있다 — 판단은 호출자의 몫이다 (ADR-0019). */
    @Query(value = "SELECT highest_grade FROM users WHERE id = :userId", nativeQuery = true)
    Optional<Integer> readHighestGrade(@Param("userId") Long userId);

    /**
     * 도달 등급을 올린다. 내리지 않는다 (R-16).
     *
     * <p>{@code highest_grade < :level} 조건이 이 메서드의 전부다. 읽고 비교해서 쓰면
     * 그 사이에 끼어든 승급을 되돌린다 — 조건을 SQL 에 두면 DB 가 원자적으로 판정한다.
     * 낮은 값으로 부르면 아무 행도 갱신되지 않고 0 을 돌려준다.
     *
     * <p>{@code clearAutomatically} 를 켜지 않는다. {@code highest_grade} 는 {@code UserEntity} 에
     * 읽기 전용으로 매핑돼 있지만(ADR-0041 · #20), 이 경로는 관리 {@code UserEntity} 를 적재하지 않는다 —
     * 이 인터페이스는 네이티브 스칼라 SQL 만 내보내므로 영속성 컨텍스트에 이 값의 옛 스냅샷이 남을 수 없다.
     * 같은 트랜잭션에서 회원 엔티티를 먼저 읽는 호출자가 생기면 그때 이 판단을 다시 한다.
     */
    @Modifying
    @Query(value = """
            UPDATE users SET highest_grade = :level
             WHERE id = :userId AND highest_grade < :level
            """, nativeQuery = true)
    int raiseHighestGrade(@Param("userId") Long userId, @Param("level") int level);

    /** {@link #READ_INPUTS} 의 한 행. 접근자 이름이 SELECT 별칭과 묶인다. */
    interface GradeInputRow {

        Long getPoint();

        Long getVoteCount();
    }
}
