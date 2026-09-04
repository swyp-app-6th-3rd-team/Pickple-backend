package app.pickple.grade.service;

import app.pickple.grade.domain.Grade;
import app.pickple.grade.domain.GradeProgress;
import app.pickple.grade.domain.GradeStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 등급 조회와 승급 판정 (ADR-0030).
 *
 * <p><b>여기가 서비스인 이유</b> — 승급 판정 자체는 {@link Grade} 하나로 끝난다.
 * 서비스가 하는 일은 그 판정을 <b>원장에서 읽은 입력값</b>과 <b>저장된 도달 등급</b>
 * 사이에서 조율하는 것이다. R-16(등급은 내려가지 않는다)은 계산값과 저장값
 * <b>둘을 비교해야</b> 성립하므로 등급 객체 혼자서는 지킬 수 없다.
 */
@Service
@RequiredArgsConstructor
public class GradeService {

    private final GradeStore gradeStore;

    /**
     * 내 등급 현황 (기능명세 §11.1).
     *
     * <p><b>조회인데 쓰기가 있다.</b> 원장 판정이 저장된 등급보다 높으면 그 자리에서 올린다 —
     * 승급을 알아차릴 다른 지점이 없기 때문이다. 지급 경로에 등급 갱신을 붙이는 대안도
     * 있었지만, 그러면 포인트 지급이 등급·뱃지 판정의 허브가 된다 (ADR-0030 기각 대안).
     *
     * <p>갱신은 <b>오를 때만</b> 일어난다. 정상 상태에서는 읽기뿐이고,
     * 조건부 UPDATE 라 동시 요청이 겹쳐도 낮은 값이 높은 값을 덮지 못한다.
     *
     * <p><b>왜 계산값과 저장값 중 높은 쪽인가</b> (R-16). 오늘은 포인트가 줄어들 경로가
     * 없어 둘이 항상 같다. 회수가 생기면 계산값이 내려가는데, 그때 저장값이
     * 등급을 붙잡는다 — 이 한 줄이 R-16 을 "우연히 참" 이 아니라 명시로 만든다.
     */
    @Transactional
    public GradeProgress readMyGrade(Long userId) {
        GradeStore.GradeInputs inputs = gradeStore.readInputs(userId);
        Grade reached = inputs.reachedGrade();
        Grade stored = gradeStore.readHighestGrade(userId);

        if (reached.level() > stored.level()) {
            gradeStore.raiseHighestGrade(userId, reached);
        }

        return new GradeProgress(reached.higherOf(stored), inputs.point(), inputs.voteCount());
    }

    /**
     * 전체 등급 기준 (기능명세 §11.2).
     *
     * <p>사용자와 무관한 정적 목록이라 저장소를 거치지 않는다 —
     * 임계값의 정본이 코드이기 때문이다 (ADR-0030 결정 2).
     */
    @Transactional(readOnly = true)
    public List<Grade> readAllGrades() {
        return Grade.ordered();
    }
}
