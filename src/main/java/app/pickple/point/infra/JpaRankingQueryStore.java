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

import static app.pickple.point.infra.RankingListRepository.Column;

/**
 * 랭킹 조회 결과를 {@link Window} 로 감싼다.
 *
 * <p>{@code Window} 를 직접 만드는 이유는 정렬 키 {@code users.ranking} 이
 * {@code UserEntity} 에 매핑돼 있지 않아 Spring Data 의 파생 keyset 스크롤을 쓸 수
 * 없기 때문이다 — 그리고 매핑하지 않는 것이 의도다. 유도 컬럼을 엔티티가 들고 있으면
 * 프로필 저장 같은 평범한 쓰기가 배치가 계산한 값을 덮어쓴다({@link JpaRankingStore} 참조).
 *
 * <p>다만 <b>타입은 그대로 쓴다</b> — {@code ScrollResponse.of(...)} 와 ArchUnit 규칙이
 * 그 위에 서 있다(ADR-0004). {@link JpaPostQueryStore} 와 같은 형태다.
 */
@Component
@RequiredArgsConstructor
public class JpaRankingQueryStore implements RankingQueryStore {

    private final RankingListRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<RankingView> findTop(int size) {
        return repository.findTop(size).stream().map(JpaRankingQueryStore::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Window<RankingView> findSlice(ScrollPosition position, int size) {
        List<Object[]> rows = repository.findSlice(RankingCursor.from(position), size);

        // size + 1 건을 요청했으므로, 넘치면 다음 조각이 있다는 뜻이다.
        boolean hasNext = rows.size() > size;
        List<Object[]> page = hasNext ? rows.subList(0, size) : rows;

        return Window.from(page.stream().map(JpaRankingQueryStore::toView).toList(),
                positionFunction(page), hasNext);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RankingView> findByUser(Long userId) {
        return repository.findByUser(userId).stream().findFirst().map(JpaRankingQueryStore::toView);
    }

    /**
     * 각 행의 커서 위치. {@code ScrollResponse} 는 마지막 행의 것만 쓰지만,
     * {@code Window} 계약상 어느 색인이든 물어볼 수 있으므로 행마다 만든다.
     *
     * <p>목록에 오르는 행은 {@code ranking IS NOT NULL} 로 걸러졌으므로 여기서
     * {@code null} 을 만나지 않는다.
     */
    private static IntFunction<ScrollPosition> positionFunction(List<Object[]> rows) {
        return index -> RankingCursor.toPosition(
                ((Number) rows.get(index)[Column.RANKING]).intValue());
    }

    private static RankingView toView(Object[] row) {
        return new RankingView(
                toLong(row[Column.ID]),
                (String) row[Column.NICKNAME],
                (String) row[Column.PROFILE_IMAGE_URL],
                toRanking(row[Column.RANKING]),
                toLong(row[Column.POINT]),
                toLong(row[Column.VOTE_COUNT]));
    }

    /**
     * 순위는 <b>없을 수 있다</b> — 가입 직후 다음 배치까지가 그렇다 (§7.3 의 본인 조회).
     *
     * <p>{@link #toLong} 을 쓰지 않는 이유가 여기 있다. 그쪽은 null 을 0 으로 접는데,
     * 순위 0 은 존재하지 않는 값이라 "아직 모른다" 를 "0위" 라는 거짓으로 바꾼다.
     * 미산정은 {@code null} 로 그대로 올려보내고 화면이 비운다 (ADR-0028).
     */
    private static Integer toRanking(Object raw) {
        return raw == null ? null : ((Number) raw).intValue();
    }

    /** 네이티브 결과의 수치 컬럼은 드라이버가 정하는 타입으로 온다(BigInteger·Integer·Long). */
    private static long toLong(Object raw) {
        return raw == null ? 0L : ((Number) raw).longValue();
    }
}
