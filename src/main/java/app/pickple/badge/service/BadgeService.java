package app.pickple.badge.service;

import app.pickple.badge.domain.Badge;
import app.pickple.badge.domain.BadgeConditionType;
import app.pickple.badge.domain.BadgeProgress;
import app.pickple.badge.domain.BadgeStore;
import app.pickple.badge.domain.DailyActivityStore;
import app.pickple.badge.domain.UserBadgeStore;
import app.pickple.badge.domain.VoteActivity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 뱃지 획득 판정과 조회.
 *
 * <p><b>여기가 서비스인 이유</b> — "어떤 뱃지를 줄 것인가" 는 뱃지 정의 전체와 회원의
 * 활동, 이미 가진 것 세 가지를 함께 봐야 한다. 애그리거트 하나로 판정되지 않는다.
 * 반면 "이 임계값을 넘었는가" 는 뱃지 하나와 활동 요약이면 되므로
 * {@link Badge#isAchievedBy} 가 갖는다 (계층 책임: 판정에 필요한 정보의 범위로 가른다).
 *
 * <p>날짜는 {@code Clock} 에서 얻어 저장소로 넘긴다. SQL 의 {@code CURRENT_DATE} 를 쓰면
 * DB 세션 타임존이 하루를 정하게 되어 자정 근처에서 사용자가 보는 하루와 어긋난다.
 */
@Service
@RequiredArgsConstructor
public class BadgeService {

    /**
     * 미션 슬롯의 계열 순서 (기능명세 §2.3).
     *
     * <p>명세의 {@code <조회 데이터>} 가 미션1 을 누적 계열
     * ({@code 누적 투표 10회 달성 (0/10)} …), 미션2 를 일일 계열
     * ({@code 하루에 투표 20개 이상 (0/20)}) 로 규정한다.
     *
     * <p><b>연속 계열은 미션 슬롯에 나가지 않는다.</b> 명세가 슬롯을 둘로 못박았고
     * 조회 데이터에도 연속 항목이 없다. 뱃지 8종에 연속이 있다는 이유로 세 번째 슬롯을
     * 지어내면 화면이 그릴 수 없는 데이터를 내려주는 셈이다 —
     * 연속 뱃지는 전체 목록({@code /badges})에서 진행 상황이 드러난다.
     */
    private static final List<BadgeConditionType> MISSION_SERIES =
            List.of(BadgeConditionType.TOTAL_VOTE, BadgeConditionType.DAILY_VOTE);

    private final BadgeStore badgeStore;
    private final UserBadgeStore userBadgeStore;
    private final DailyActivityStore dailyActivityStore;
    private final Clock clock;

    /**
     * 투표 직후 조건을 다시 판정하고, 새로 넘긴 뱃지를 지급한다.
     *
     * <p><b>재투표에서는 부르지 않는다 (R-22).</b> 선택 변경은 활동을 늘리지 않으므로
     * 판정 결과가 달라질 수 없다. 부르면 조건이 바뀌지 않았는데도 매번 8행을 읽는다.
     *
     * <p>이미 가진 뱃지는 후보에서 뺀다. 하지만 그 확인이 방어선은 아니다 —
     * 동시 투표 둘이 같은 임계값을 함께 넘기면 <b>둘 다 "없다" 를 본다.</b>
     * 실제 방어선은 {@code UNIQUE(user_id, badge_id)} 이고 (R-17),
     * 지급이 원자적 삽입이라 중복이 예외가 아니라 무시로 끝난다.
     *
     * <p>그래서 반환값은 <b>"이 트랜잭션에서 지급을 시도한 뱃지"</b>이지
     * "내가 삽입에 성공한 뱃지" 가 아니다. 동시 투표 둘이 같은 뱃지를 함께 넘기면
     * 양쪽 다 목록에 담을 수 있다. 지금 이 값을 쓰는 곳이 없어 문제되지 않지만,
     * 획득 모달(§12.3)처럼 "새로 얻었다" 를 사용자에게 보이는 경로가 생기면
     * 그때는 알림이 두 번 갈 수 있으므로 판정 방식을 다시 정해야 한다.
     *
     * @return 이번 판정에서 조건을 넘긴 미보유 뱃지
     */
    @Transactional
    public List<Badge> evaluate(Long userId) {
        VoteActivity activity = dailyActivityStore.findActivity(userId, today());
        Set<Long> owned = userBadgeStore.findOwnedBadgeIds(userId);

        List<Badge> acquired = new ArrayList<>();
        for (Badge badge : badgeStore.findAllOrdered()) {
            if (owned.contains(badge.id()) || !badge.isAchievedBy(activity)) {
                continue;
            }
            userBadgeStore.grantIfAbsent(userId, badge.id());
            acquired.add(badge);
        }
        return acquired;
    }

    /**
     * 내 뱃지 현황 — 획득·미획득 전체와 수집 개수 (기능명세 §12.1·§12.2).
     *
     * <p>미획득 뱃지도 이름과 함께 내려간다. 화면이 3X3 목록에서 일러스트만 가리고
     * 이름은 보여주기 때문이다(§12.2). 서버가 미획득을 빼면 화면이 빈 칸을 그릴 수 없다.
     */
    @Transactional(readOnly = true)
    public BadgeCollection getCollection(Long userId) {
        Set<Long> owned = userBadgeStore.findOwnedBadgeIds(userId);
        List<OwnedBadge> badges = badgeStore.findAllOrdered().stream()
                .map(badge -> new OwnedBadge(badge, owned.contains(badge.id())))
                .toList();
        return new BadgeCollection(badges, owned.size());
    }

    /**
     * 미해제 미션 — 계열마다 아직 달성하지 못한 가장 낮은 임계값 하나씩 (기능명세 §2.3).
     *
     * <p>"하위 미션 먼저 표시" 는 곧 <b>못 넘은 것 중 가장 낮은 것</b>을 고르라는 뜻이다.
     * 누적 10회를 못 넘은 사람에게 1,000회 미션을 보여주면 게이지가 늘 0 에 붙어 있다.
     *
     * <p>계열을 다 채웠으면 그 슬롯은 빠진다. 8종을 모두 얻으면 빈 목록이다 —
     * 남지 않은 미션을 지어내지 않는다(화면은 미션창을 비우면 된다).
     */
    @Transactional(readOnly = true)
    public List<BadgeProgress> getOpenMissions(Long userId) {
        VoteActivity activity = dailyActivityStore.findActivity(userId, today());
        Set<Long> owned = userBadgeStore.findOwnedBadgeIds(userId);
        List<Badge> all = badgeStore.findAllOrdered();

        return MISSION_SERIES.stream()
                .map(series -> lowestUnachieved(all, series, activity, owned))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 이 계열에서 아직 달성하지 못한 가장 낮은 임계값.
     *
     * <p>보유 여부와 달성 여부를 <b>둘 다</b> 본다. 보유만 보면 방금 넘겨 지급이 끝난
     * 뱃지가 다음 조회까지 미션에 남고, 달성만 보면 판정이 아직 안 돈 사이에
     * 이미 가진 뱃지가 미션으로 다시 나온다.
     */
    private Optional<BadgeProgress> lowestUnachieved(
            List<Badge> all, BadgeConditionType series, VoteActivity activity, Set<Long> owned) {
        return all.stream()
                .filter(badge -> badge.conditionType() == series)
                .filter(badge -> !owned.contains(badge.id()) && !badge.isAchievedBy(activity))
                .min(Comparator.comparingLong(Badge::threshold))
                .map(badge -> badge.progressOf(activity));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * 내 뱃지 현황.
     *
     * @param badges        전체 뱃지. 미획득도 포함한다(§12.2 는 이름만 보여준다)
     * @param collectedCount 수집한 뱃지 개수 (§12.1 의 유일한 조회 데이터)
     */
    public record BadgeCollection(List<OwnedBadge> badges, int collectedCount) {
    }

    /** 뱃지 하나와 그 획득 여부. 화면이 일러스트를 보일지 가릴지 가르는 값이다. */
    public record OwnedBadge(Badge badge, boolean acquired) {
    }
}
