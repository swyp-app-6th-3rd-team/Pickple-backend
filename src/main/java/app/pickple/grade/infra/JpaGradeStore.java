package app.pickple.grade.infra;

import app.pickple.grade.domain.Grade;
import app.pickple.grade.domain.GradeStore;
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
 * <p><b>왜 QueryDSL 로 옮기지 않았는가</b> (#130 · #132) — 판정 입력값 조회는
 * <b>FROM 절이 없는 스칼라 서브쿼리 SELECT</b> 다. JPQL 은 FROM 없는 SELECT 를 허용하지
 * 않으므로 QueryDSL-JPA 로 표현할 수 없다. 조인으로 바꾸면 두 집계가 곱해지고
 * ({@link GradeRepository#READ_INPUTS} javadoc), 두 쿼리로 나누면 같은 스냅샷이 깨진다
 * (아래 {@code readOnly} javadoc). SQL 은 그대로 두고 <b>반환 타입만</b> 인터페이스
 * 프로젝션으로 바꿨다 — 타입 없는 배열 행과 인덱스 리터럴, 드라이버 타입 방어 함수가
 * 사라진다. 방식 선택의 근거는 {@link GradeRepository} 에 있다.
 *
 * <p><b>{@code UserEntity} 에 {@code highest_grade} 를 매핑하지 않는다.</b>
 * 매핑하면 프로필 저장 같은 평범한 쓰기가 도달 등급을 옛 스냅샷으로 덮어쓴다 —
 * V7 의 {@code ranking} 이 매핑되지 않은 것과 같은 이유다 (ADR-0028).
 * 유도 컬럼은 유도하는 쪽만 만진다.
 */
@Component
@RequiredArgsConstructor
public class JpaGradeStore implements GradeStore {

    private final GradeRepository repository;

    /**
     * ⚠️ 아래 두 조회의 {@code readOnly = true} 는 <b>서비스를 거쳐 오면 적용되지 않는다.</b>
     *
     * <p>기본 전파(`REQUIRED`)에서 이미 열린 트랜잭션에 참여하면 읽기전용 여부는
     * <b>최초로 트랜잭션을 연 지점</b>이 정한다. {@code GradeService.readMyGrade()} 가
     * 쓰기 트랜잭션을 열므로 그 경로에서는 이 플래그가 무시된다.
     *
     * <p>그럼에도 남겨두는 이유는 <b>저장소를 단독으로 부르는 경로</b>(테스트·배치·
     * 앞으로 생길 다른 호출자)에서는 그대로 유효하고, 이 메서드가 쓰기를 하지 않는다는
     * 사실을 애노테이션으로 못박아 두면 나중에 여기에 UPDATE 를 끼워 넣기 어려워지기 때문이다.
     *
     * <p>두 조회를 별도 읽기전용 트랜잭션으로 떼어내는 대안은 택하지 않았다 —
     * 포인트와 투표 횟수는 <b>같은 스냅샷에서 읽어야</b> 한다(R-15 가 AND 라
     * 서로 다른 시점의 값을 묶으면 실제로 존재한 적 없는 조합으로 등급이 정해진다).
     */
    @Override
    @Transactional(readOnly = true)
    public GradeInputs readInputs(Long userId) {
        GradeRepository.GradeInputRow row = repository.readInputs(userId);
        return new GradeInputs(row.getPoint(), row.getVoteCount());
    }

    /**
     * 저장된 도달 등급.
     *
     * <p>사용자가 없으면 {@link GradePersistenceException} 이 올라간다. 여기서 LV.1 로
     * 대신 답하면 없는 사용자의 등급을 지어내는 것이 되고, 그 판단은 저장소의 몫이 아니다
     * (ADR-0019). 존재 여부는 인증이 이미 보장하므로, 행이 없다는 것은 요청이 아니라
     * 영속 상태의 모순이다 — 각 기능 {@code infra} 의 {@code *PersistenceException} 으로
     * 표현하고 500 으로 흘려보낸다 (ADR-0039). 이전 구현이 던지던
     * {@code NoResultException} 도 같은 경로로 500 이었으므로 응답은 바뀌지 않는다.
     */
    @Override
    @Transactional(readOnly = true)
    public Grade readHighestGrade(Long userId) {
        int level = repository.readHighestGrade(userId)
                .orElseThrow(() -> new GradePersistenceException("등급을 읽을 사용자가 없습니다: userId=" + userId));
        return Grade.ofLevel(level);
    }

    @Override
    @Transactional
    public boolean raiseHighestGrade(Long userId, Grade grade) {
        return repository.raiseHighestGrade(userId, grade.level()) > 0;
    }
}
