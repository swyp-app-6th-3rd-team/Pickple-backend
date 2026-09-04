package app.pickple.point.service;

import app.pickple.common.CursorCodec;
import app.pickple.point.domain.RankingQueryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 피커 랭킹 조회 (§2.5 · §3.1 · §7.3).
 *
 * <p>정렬·페이징을 여기서 하지 않는다 — 전부 쿼리의 {@code WHERE}·{@code ORDER BY} 로
 * 내린다. 애플리케이션에서 거르면 조각 크기만큼만 읽어온 뒤 걸러내므로 한 조각이
 * 10건보다 적어지고, 커서가 가리키는 위치와 실제로 돌려준 마지막 행이 어긋난다.
 *
 * <p>순위를 계산하지도 않는다. 배치가 매겨둔 값을 읽을 뿐이다 (ADR-0028).
 */
@Service
@RequiredArgsConstructor
public class RankingQueryService {

    /** 상위 피커 노출 인원 (§2.5 "상위 피커 목록을 5명만 노출"). */
    public static final int TOP_SIZE = 5;

    /** 무한 스크롤 조각 크기 (§3.1 "10개 단위"). */
    public static final int DEFAULT_SLICE_SIZE = 10;

    private static final int MAX_SLICE_SIZE = 50;

    private final RankingQueryStore rankingQueryStore;

    /**
     * 상위 피커 (§2.5).
     *
     * <p>포인트를 가진 회원이 없으면 <b>빈 목록</b>이다. "아직 TOP 피커가 존재하지
     * 않아요" 는 화면 문구이므로 서버가 만들지 않는다 — 빈 배열이 그 상태를 말한다.
     *
     * @param size 없거나 범위를 벗어나면 {@link #TOP_SIZE}
     */
    @Transactional(readOnly = true)
    public List<RankingQueryStore.RankingView> findTop(Integer size) {
        return rankingQueryStore.findTop(topSize(size));
    }

    /**
     * 전체 랭킹 한 조각 (§3.1).
     *
     * @param cursor 없으면 첫 조각
     * @param size   없거나 범위를 벗어나면 {@link #DEFAULT_SLICE_SIZE}
     */
    @Transactional(readOnly = true)
    public Window<RankingQueryStore.RankingView> findSlice(String cursor, Integer size) {
        ScrollPosition position = CursorCodec.decode(cursor);
        return rankingQueryStore.findSlice(position, sliceSize(size));
    }

    /**
     * 본인의 포인트와 순위 (§7.3).
     *
     * <p>순위가 아직 없어도 값은 돌아온다 — 비는 것은 순위 하나뿐이다.
     *
     * @return 활성 회원이 아니면 빈 값
     */
    @Transactional(readOnly = true)
    public Optional<RankingQueryStore.RankingView> findMine(Long userId) {
        return rankingQueryStore.findByUser(userId);
    }

    /**
     * 상위 피커 인원을 제한한다.
     *
     * <p>정책이 5명이지만 파라미터를 받는 이유는 화면이 미리보기 개수를 바꿀 수 있기
     * 때문이다. 상한은 조각 크기와 같게 둔다 — 이 경로로 목록 전체를 뽑아
     * 무한 스크롤을 우회하지 못하게 한다.
     */
    private static int topSize(Integer size) {
        if (size == null || size < 1) {
            return TOP_SIZE;
        }
        return Math.min(size, MAX_SLICE_SIZE);
    }

    /**
     * 조각 크기를 제한한다. 상한이 없으면 {@code size=100000} 한 번으로 목록 전체가
     * 나가 무한 스크롤이 무의미해진다.
     */
    private static int sliceSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SLICE_SIZE;
        }
        return Math.min(size, MAX_SLICE_SIZE);
    }
}
