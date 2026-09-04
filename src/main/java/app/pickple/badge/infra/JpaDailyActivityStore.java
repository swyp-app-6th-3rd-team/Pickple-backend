package app.pickple.badge.infra;

import app.pickple.badge.domain.DailyActivityStore;
import app.pickple.badge.domain.VoteActivity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JpaDailyActivityStore implements DailyActivityStore {

    /**
     * 연속 판정이 읽는 최대 행 수.
     *
     * <p>연속 뱃지의 최대 임계값이 30일(투표 중독자)이라 31번째 행은 판정을 바꾸지 못한다.
     * 미션 목록도 <b>미해제</b> 뱃지만 보여주므로 30 을 넘는 실제 연속일수를 표시할 일이 없다.
     * 상한이 없으면 3년 매일 투표한 사용자의 판정이 1,000행을 읽는다.
     *
     * <p>기준일이 어제일 수 있어(오늘 아직 투표 전) 하루를 더 읽는다 —
     * 그래야 어제부터 30일을 채운 경우를 놓치지 않는다.
     */
    private static final int STREAK_SCAN_LIMIT = 31;

    private final UserDailyActivityRepository repository;

    @Override
    @Transactional
    public void increaseVoteCount(Long userId, LocalDate date) {
        repository.increaseVoteCount(userId, date);
    }

    @Override
    @Transactional(readOnly = true)
    public VoteActivity findActivity(Long userId, LocalDate today) {
        long total = repository.sumVoteCountByUser(userId);
        Integer todayCount = repository.findVoteCountOn(userId, today);
        long streak = countStreak(userId, today);
        return new VoteActivity(total, todayCount == null ? 0 : todayCount, streak);
    }

    /**
     * 하루도 빠지지 않은 날 수를 센다.
     *
     * <p><b>기준일은 오늘 행의 유무가 정한다.</b> 오늘 투표했으면 오늘부터, 아직 안 했으면
     * 어제부터 거슬러 센다. 어제까지 6일을 채운 사람이 오늘 아침 미션을 열었을 때
     * {@code 0/7} 이 뜨면 연속이 끊긴 줄 알기 때문이다 — 아직 오늘이 남아 있다.
     *
     * <p>이 규칙이 획득 판정을 느슨하게 만들지는 않는다. 판정은 투표 직후에만 도는데
     * 그 시점엔 오늘 행이 반드시 있어 기준일이 언제나 오늘이다.
     * "어제부터" 는 조회 경로에서만 관측된다.
     *
     * <p>그제까지밖에 없으면 0 이다 — 어제를 걸렀으므로 이미 끊겼다.
     * 끊긴 뒤에는 처음부터 다시 센다(완료 판정 항목).
     */
    private long countStreak(Long userId, LocalDate today) {
        List<LocalDate> dates = repository.findRecentActivityDates(userId, today, STREAK_SCAN_LIMIT);
        if (dates.isEmpty()) {
            return 0;
        }

        LocalDate mostRecent = dates.getFirst();
        // 오늘도 어제도 아니면 연속은 이미 끊겼다.
        if (mostRecent.isBefore(today.minusDays(1))) {
            return 0;
        }

        long streak = 0;
        LocalDate expected = mostRecent;
        for (LocalDate date : dates) {
            if (!date.equals(expected)) {
                break;
            }
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }
}
