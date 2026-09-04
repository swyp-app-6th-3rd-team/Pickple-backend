package app.pickple.badge.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.badge.domain.DailyActivityStore;
import app.pickple.badge.domain.VoteActivity;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일별 활동 집계 (R-19).
 *
 * <p>연속 판정의 경계를 날짜를 직접 넘겨 확인한다 — {@code CURRENT_DATE} 대신
 * 날짜를 인자로 받는 설계라 시계를 조작하지 않고도 "6일 연속" 과 "7일 연속" 을 가를 수 있다.
 */
@IntegrationTest
class JpaDailyActivityStoreIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @Autowired
    private DailyActivityStore store;

    @Autowired
    private UserStore userStore;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userStore.save(
                new User(SocialProvider.GOOGLE, "badge-daily-" + System.nanoTime(), null, "투표자")).id();
    }

    /** 지정한 날짜에 지정한 횟수만큼 투표한 것으로 만든다. */
    private void voteOn(LocalDate date, int times) {
        for (int i = 0; i < times; i++) {
            store.increaseVoteCount(userId, date);
        }
    }

    @Nested
    @DisplayName("일별 누적")
    class DailyAccumulation {

        @Test
        @DisplayName("같은 날 여러 번 투표하면 한 행에 누적된다")
        void accumulatesIntoOneRow() {
            voteOn(TODAY, 3);

            assertThat(store.findActivity(userId, TODAY).todayVoteCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("누적 투표 수는 모든 날짜의 합계다")
        void totalIsSumOfAllDays() {
            voteOn(TODAY.minusDays(2), 4);
            voteOn(TODAY.minusDays(1), 5);
            voteOn(TODAY, 2);

            assertThat(store.findActivity(userId, TODAY).totalVoteCount()).isEqualTo(11);
        }

        @Test
        @DisplayName("투표한 적 없으면 세 수가 모두 0 이다")
        void noActivityIsAllZero() {
            assertThat(store.findActivity(userId, TODAY)).isEqualTo(VoteActivity.none());
        }

        @Test
        @DisplayName("오늘 투표하지 않았으면 오늘 수는 0 이지만 누적은 남는다")
        void todayIsZeroButTotalRemains() {
            voteOn(TODAY.minusDays(1), 7);

            VoteActivity activity = store.findActivity(userId, TODAY);

            assertThat(activity.todayVoteCount()).isZero();
            assertThat(activity.totalVoteCount()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("연속 참여 판정")
    class Streak {

        @Test
        @DisplayName("6일 연속에서는 6, 7일 연속에서 7 이 된다 (경계값)")
        void countsConsecutiveDays() {
            for (int back = 5; back >= 0; back--) {
                voteOn(TODAY.minusDays(back), 1);
            }
            assertThat(store.findActivity(userId, TODAY).streakDays()).isEqualTo(6);

            // 하루를 더 채운 날에서 다시 센다.
            LocalDate nextDay = TODAY.plusDays(1);
            voteOn(nextDay, 1);
            assertThat(store.findActivity(userId, nextDay).streakDays()).isEqualTo(7);
        }

        @Test
        @DisplayName("중간에 하루가 비면 그 뒤부터 다시 센다")
        void gapResetsStreak() {
            // 10~8일 전 3일 연속, 7일 전은 공백, 6~0일 전 7일 연속.
            for (int back = 10; back >= 8; back--) {
                voteOn(TODAY.minusDays(back), 1);
            }
            for (int back = 6; back >= 0; back--) {
                voteOn(TODAY.minusDays(back), 1);
            }

            // 공백 이전의 3일은 세지 않는다.
            assertThat(store.findActivity(userId, TODAY).streakDays()).isEqualTo(7);
        }

        @Test
        @DisplayName("오늘 아직 투표하지 않아도 어제까지의 연속은 유지된다")
        void yesterdayAnchoredWhenTodayEmpty() {
            // 어제까지 6일 연속. 오늘은 아직 투표 전이다.
            for (int back = 6; back >= 1; back--) {
                voteOn(TODAY.minusDays(back), 1);
            }

            // 0 이 되면 사용자는 연속이 끊긴 줄 안다 — 아직 오늘이 남아 있다.
            assertThat(store.findActivity(userId, TODAY).streakDays()).isEqualTo(6);
        }

        @Test
        @DisplayName("어제도 오늘도 투표하지 않았으면 연속은 끊긴 것이다")
        void streakBreaksWhenYesterdayMissing() {
            for (int back = 8; back >= 2; back--) {
                voteOn(TODAY.minusDays(back), 1);
            }

            assertThat(store.findActivity(userId, TODAY).streakDays()).isZero();
        }

        @Test
        @DisplayName("하루에 여러 번 투표해도 연속은 하루로 센다")
        void multipleVotesInADayCountAsOneDay() {
            voteOn(TODAY.minusDays(1), 5);
            voteOn(TODAY, 9);

            assertThat(store.findActivity(userId, TODAY).streakDays()).isEqualTo(2);
        }
    }
}
