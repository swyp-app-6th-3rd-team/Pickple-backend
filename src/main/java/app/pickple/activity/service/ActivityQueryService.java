package app.pickple.activity.service;

import app.pickple.activity.domain.ActivityQueryStore;
import app.pickple.activity.domain.ActivitySort;
import app.pickple.activity.domain.ActivityType;
import app.pickple.common.CursorCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이페이지 "내 활동" 을 화면용 읽기 모델로 조립한다 (§7.2 · §7.4 · §9.1 · §9.2).
 *
 * <p>정렬·필터를 여기서 하지 않는다 — 전부 쿼리의 {@code WHERE}·{@code ORDER BY} 로 내린다.
 * 애플리케이션에서 거르면 조각 크기만큼만 읽어온 뒤 걸러내므로 한 조각이 10건보다
 * 적어지고, 커서가 가리키는 위치와 실제로 돌려준 마지막 행이 어긋난다.
 */
@Service
@RequiredArgsConstructor
public class ActivityQueryService {

    /** 무한 스크롤 조각 크기 (§9.2 "10개 단위"). 클라이언트가 더 크게 요청해도 이 값으로 자른다. */
    public static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;

    /**
     * 최근 투표의 기간 (§7.4 "7일 이내").
     *
     * <p><b>기준 시각은 요청 시각이다.</b> 자정이나 날짜 경계가 아니다 —
     * 날짜로 끊으면 같은 게시글이 자정을 지나며 목록에서 사라져, 사용자가
     * "방금 봤는데 없어졌다" 를 겪는다. 요청 시각 기준이면 7일이 매끄럽게 흐른다.
     *
     * <p>경계는 <b>반열린 구간</b> {@code (now - 7일, now]} 이다.
     * 정확히 7일 전에 올린 글은 <b>들어오지 않는다</b> — "7일 이내" 를
     * "지난 7일 동안" 으로 읽었고, 7일이 지난 순간이 곧 만료다.
     * 초 단위로 끊는 {@code Clock} 덕에 이 경계는 실제로 판정 가능한 값이다.
     */
    private static final int RECENT_DAYS = 7;

    /** 가로 스크롤 캐러셀에 얹는 최대 개수 (§7.4). 무한 스크롤이 아니다. */
    private static final int RECENT_LIMIT = 10;

    private final ActivityQueryStore activityQueryStore;
    private final Clock clock;

    /** 활동 갯수 요약 (§7.2). */
    @Transactional(readOnly = true)
    public ActivityQueryStore.ActivitySummary summarize(Long userId) {
        return activityQueryStore.summarize(userId);
    }

    /**
     * 활동 목록 한 조각 (§9.1 · §9.2).
     *
     * @param type   없거나 모르는 값이면 투표 (§9.1 — 칩은 항상 하나가 활성이다)
     * @param sort   없거나 모르는 값이면 최신순
     * @param cursor 없으면 첫 조각
     */
    @Transactional(readOnly = true)
    public Window<ActivityQueryStore.ActivityPostView> findSlice(
            Long userId, String type, String sort, String cursor, Integer size) {

        ScrollPosition position = CursorCodec.decode(cursor);
        return activityQueryStore.findSlice(
                userId,
                ActivityType.from(type),
                ActivitySort.from(sort),
                position,
                sliceSize(size));
    }

    /**
     * 7일 이내에 올린 투표 게시글 (§7.4).
     *
     * <p>기준 시각을 여기서 정해 저장소에 넘긴다. SQL 의 {@code NOW()} 를 쓰면
     * DB 세션 타임존이 시각을 정해 애플리케이션이 보는 "지금" 과 갈리고,
     * 무엇보다 <b>테스트가 경계를 고정할 수 없다</b> — {@code Clock} 을 갈아끼워
     * 7일과 8일을 판정하는 것이 이 설계의 목적이다(SPEC §5.1).
     */
    @Transactional(readOnly = true)
    public List<ActivityQueryStore.ActivityPostView> findRecentVotePosts(Long userId) {
        LocalDateTime since = LocalDateTime.now(clock).minusDays(RECENT_DAYS);
        return activityQueryStore.findRecentVotePosts(userId, since, RECENT_LIMIT);
    }

    /**
     * 조각 크기를 제한한다. 상한이 없으면 {@code size=100000} 한 번으로 목록 전체가
     * 나가 무한 스크롤이 무의미해진다.
     */
    private static int sliceSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
