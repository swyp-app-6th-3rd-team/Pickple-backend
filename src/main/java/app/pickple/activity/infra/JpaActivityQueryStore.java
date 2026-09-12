package app.pickple.activity.infra;

import app.pickple.activity.domain.ActivityQueryStore;
import app.pickple.activity.domain.ActivitySort;
import app.pickple.activity.domain.ActivityType;
import app.pickple.activity.infra.ActivityQuerydslRepository.ActivityRow;
import app.pickple.activity.infra.ActivityQuerydslRepository.ActivitySlice;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.IntFunction;

/**
 * 활동 목록 결과를 {@link Window} 로 감싼다.
 *
 * <p>{@code Window} 를 직접 만드는 이유는 정렬 키가 읽기 전용 생성 컬럼이거나(인기순)
 * 조인 상대 테이블의 컬럼이라(최신·오래된순) Spring Data 의 파생 keyset 스크롤을
 * 쓸 수 없기 때문이다. 다만 <b>타입은 그대로 쓴다</b> —
 * {@code ScrollResponse.of(...)} 와 ArchUnit 규칙이 그 위에 서 있다(ADR-0004).
 *
 * <p><b>두 문장 조회는 REPEATABLE READ 를 명시한다.</b> 저장소가 키 문장과 행 문장을 나눠 내므로
 * (ADR-0043) 둘이 한 스냅샷을 봐야 한다. MySQL 기본값과 같아 실제로 바뀌는 것은 없지만, 전제를
 * 애노테이션에 적어 두면 격리 수준을 낮추는 변경이 이 파일을 지나가게 된다. 바깥 트랜잭션에
 * 참여하면 그쪽 격리 수준을 따른다 — Spring 은 참여 트랜잭션의 격리를 검증하지 않으므로
 * 이 선언은 새 트랜잭션을 여는 경우에만 강제다. 저장소의 {@code requireSnapshot()} 은 트랜잭션의
 * 존재만 확인한다.
 *
 * <p><b>행 변환 코드가 사라졌다.</b> 조회가 {@code Object} 배열 대신 {@link ActivityRow} 를
 * 직접 돌려주므로 컬럼 인덱스 상수와 드라이버 타입 방어({@code toLong}·{@code toLocalDateTime})가
 * 필요 없다. 그 계약은 이제 {@code ActivityQuerydslRepository} 의 프로젝션이 컴파일 시점에 지킨다.
 */
@Component
@RequiredArgsConstructor
public class JpaActivityQueryStore implements ActivityQueryStore {

    private final ActivityQuerydslRepository repository;

    @Override
    @Transactional(readOnly = true)
    public ActivitySummary summarize(Long userId) {
        return repository.summarize(userId);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Window<ActivityPostView> findSlice(
            Long userId, ActivityType type, ActivitySort sort, ScrollPosition position, int size) {

        ActivityListCursor cursor = ActivityListCursor.from(position, type, sort);
        ActivitySlice slice = repository.findSlice(userId, type, sort, cursor, size);

        List<ActivityPostView> content = slice.rows().stream().map(ActivityRow::view).toList();
        return Window.from(content, positionFunction(type, sort, slice.rows()), slice.hasNext());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ActivityPostView> findRecentVotePosts(Long userId, LocalDateTime since, int limit) {
        return repository.findRecentVotePosts(userId, since, limit);
    }

    /**
     * 각 행의 커서 위치. {@code ScrollResponse} 는 마지막 행의 것만 쓰지만,
     * {@code Window} 계약상 어느 색인이든 물어볼 수 있으므로 행마다 만든다.
     *
     * <p>인기 점수는 화면에 나가지 않는 값이라 뷰가 아닌 {@link ActivityRow} 에서 읽는다.
     */
    private static IntFunction<ScrollPosition> positionFunction(
            ActivityType type, ActivitySort sort, List<ActivityRow> rows) {
        return index -> {
            ActivityRow row = rows.get(index);
            Object sortValue = sort.byActivityTime() ? row.view().activityAt() : row.popularityScore();
            return ActivityListCursor.toPosition(type, sort, sortValue, row.view().id());
        };
    }
}
