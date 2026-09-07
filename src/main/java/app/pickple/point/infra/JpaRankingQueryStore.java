package app.pickple.point.infra;

import app.pickple.point.domain.RankingQueryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * 랭킹 조회 결과를 {@link Window} 로 감싼다.
 *
 * <p>{@code Window} 를 직접 만드는 이유는 정렬 키 {@code users.ranking} 이 배치가 채우는
 * 유도 컬럼이라 Spring Data 의 파생 keyset 스크롤을 쓸 수 없기 때문이다. 엔티티에는 읽기
 * 전용으로 매핑돼 있어(ADR-0041) 조회는 되지만, 쓰기 경로가 없으므로 파생 스크롤이 기대하는
 * 정렬 프로퍼티로는 다루지 않는다.
 *
 * <p>다만 <b>타입은 그대로 쓴다</b> — {@code ScrollResponse.of(...)} 와 ArchUnit 규칙이
 * 그 위에 서 있다(ADR-0004). {@link app.pickple.post.infra.JpaPostStore} 와 같은 형태다.
 *
 * <p><b>행 변환 코드가 사라졌다.</b> 조회가 {@code Object[]} 대신 {@link RankingView} 를
 * 직접 돌려주므로 컬럼 인덱스 상수와 드라이버 타입 방어(`toLong`·`toRanking`)가 필요 없다.
 * 그 계약은 이제 {@code RankingQuerydslRepository} 의 프로젝션이 컴파일 시점에 지킨다.
 */
@Component
@RequiredArgsConstructor
public class JpaRankingQueryStore implements RankingQueryStore {

    private final RankingQuerydslRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<RankingView> findTop(int size) {
        return repository.findTop(size);
    }

    @Override
    @Transactional(readOnly = true)
    public Window<RankingView> findSlice(ScrollPosition position, int size) {
        List<RankingView> rows = repository.findSlice(RankingCursor.from(position), size);

        // size + 1 건을 요청했으므로, 넘치면 다음 조각이 있다는 뜻이다.
        boolean hasNext = rows.size() > size;
        List<RankingView> page = hasNext ? rows.subList(0, size) : rows;

        return Window.from(page, positionFunction(page), hasNext);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RankingView> findByUser(Long userId) {
        return repository.findByUser(userId);
    }

    /**
     * 각 행의 커서 위치. {@code ScrollResponse} 는 마지막 행의 것만 쓰지만,
     * {@code Window} 계약상 어느 색인이든 물어볼 수 있으므로 행마다 만든다.
     *
     * <p>목록에 오르는 행은 {@code ranking IS NOT NULL} 로 걸러졌으므로 여기서
     * {@code null} 을 만나지 않는다.
     */
    private static IntFunction<ScrollPosition> positionFunction(List<RankingView> rows) {
        return index -> RankingCursor.toPosition(rows.get(index).ranking());
    }
}
