package app.pickple.badge.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9 의 소급 지급 SQL 을 확인한다.
 *
 * <p><b>왜 별도 테스트가 필요한가</b> — 통합 테스트는 매번 빈 스키마에 마이그레이션을 적용하므로
 * 그때 {@code vote} 와 {@code user_daily_activity} 가 비어 있다. 곧 백필 SQL 은
 * <b>0행을 옮기는 것만</b> 검증된 채 배포된다. 실제 데이터가 있는 develop 에 적용될 때가
 * 첫 실행이 되는 셈이라, 같은 SQL 을 데이터를 넣고 다시 돌려 결과를 본다.
 *
 * <p>마이그레이션 파일의 문장을 그대로 옮겨 쓴다. 복제라 어긋날 수 있지만, 대안(마이그레이션을
 * 다시 실행)은 Flyway 가 허용하지 않는다. 문장이 갈라지면 이 테스트가 통과해도 배포가
 * 틀릴 수 있으므로 <b>V9 를 고치면 여기도 함께 고친다</b>.
 */
@IntegrationTest
class BadgeBackfillIT {

    /** V9 의 누적 소급 지급 문장. */
    private static final String BACKFILL_TOTAL = """
            INSERT INTO user_badge (user_id, badge_id, acquired_at)
            SELECT t.user_id, b.id, NOW()
              FROM (SELECT user_id, SUM(vote_count) AS total
                      FROM user_daily_activity
                     GROUP BY user_id) t
              JOIN badge b ON b.condition_type = 'TOTAL_VOTE' AND t.total >= b.threshold
             WHERE NOT EXISTS (SELECT 1 FROM user_badge ub
                                WHERE ub.user_id = t.user_id AND ub.badge_id = b.id)
            """;

    /** V9 의 일일 소급 지급 문장. */
    private static final String BACKFILL_DAILY = """
            INSERT INTO user_badge (user_id, badge_id, acquired_at)
            SELECT t.user_id, b.id, NOW()
              FROM (SELECT user_id, MAX(vote_count) AS best
                      FROM user_daily_activity
                     GROUP BY user_id) t
              JOIN badge b ON b.condition_type = 'DAILY_VOTE' AND t.best >= b.threshold
             WHERE NOT EXISTS (SELECT 1 FROM user_badge ub
                                WHERE ub.user_id = t.user_id AND ub.badge_id = b.id)
            """;

    /** V9 의 연속 소급 지급 문장 (gaps and islands). */
    private static final String BACKFILL_STREAK = """
            INSERT INTO user_badge (user_id, badge_id, acquired_at)
            SELECT t.user_id, b.id, NOW()
              FROM (SELECT user_id, MAX(run_length) AS longest
                      FROM (SELECT user_id, COUNT(*) AS run_length
                              FROM (SELECT user_id, activity_date,
                                           DATE_SUB(activity_date,
                                                    INTERVAL ROW_NUMBER() OVER (PARTITION BY user_id
                                                                                ORDER BY activity_date) DAY) AS grp
                                      FROM user_daily_activity) marked
                             GROUP BY user_id, grp) runs
                     GROUP BY user_id) t
              JOIN badge b ON b.condition_type = 'STREAK_VOTE' AND t.longest >= b.threshold
             WHERE NOT EXISTS (SELECT 1 FROM user_badge ub
                                WHERE ub.user_id = t.user_id AND ub.badge_id = b.id)
            """;

    private static final LocalDate BASE = LocalDate.of(2026, 4, 1);

    @Autowired
    private UserStore userStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userStore.save(
                new User(SocialProvider.GOOGLE, "backfill-" + System.nanoTime(), null, "기존회원")).id();
    }

    /** 마이그레이션 이전에 쌓여 있던 활동인 것처럼 집계 행을 만든다. */
    private void activityOn(LocalDate date, int voteCount) {
        jdbcTemplate.update("""
                INSERT INTO user_daily_activity (user_id, activity_date, vote_count, created_at, updated_at)
                VALUES (?, ?, ?, NOW(), NOW())
                """, userId, date, voteCount);
    }

    private void runBackfill() {
        jdbcTemplate.update(BACKFILL_TOTAL);
        jdbcTemplate.update(BACKFILL_DAILY);
        jdbcTemplate.update(BACKFILL_STREAK);
    }

    private List<String> grantedCodes() {
        return jdbcTemplate.queryForList("""
                SELECT b.code FROM user_badge ub JOIN badge b ON b.id = ub.badge_id
                 WHERE ub.user_id = ? ORDER BY b.display_order
                """, String.class, userId);
    }

    @Test
    @DisplayName("이미 누적 조건을 넘긴 회원에게 소급 지급한다 — 넘긴 것 전부")
    void grantsAllPassedTotalBadges() {
        // 120일 동안 매일 1회 = 누적 120. 10회와 100회를 이미 넘겼다.
        for (int day = 0; day < 120; day++) {
            activityOn(BASE.plusDays(day), 1);
        }

        runBackfill();

        // 10·100 은 받고 500 은 못 받는다. 하나만 주는 게 아니라 넘긴 것을 전부 준다.
        assertThat(grantedCodes()).contains("TOTAL_VOTE_10", "TOTAL_VOTE_100")
                .doesNotContain("TOTAL_VOTE_500", "TOTAL_VOTE_1000");
    }

    @Test
    @DisplayName("하루라도 임계값을 채운 날이 있으면 일일 뱃지를 소급 지급한다")
    void grantsDailyBadgeFromBestDay() {
        activityOn(BASE, 3);
        activityOn(BASE.plusDays(1), 25);   // 이날 20개를 넘겼다
        activityOn(BASE.plusDays(2), 4);

        runBackfill();

        assertThat(grantedCodes()).contains("DAILY_VOTE_20").doesNotContain("DAILY_VOTE_30");
    }

    @Test
    @DisplayName("과거의 가장 긴 연속 구간으로 연속 뱃지를 판정한다")
    void grantsStreakBadgeFromLongestRun() {
        // 8일 연속 → 공백 → 3일 연속. 최장 구간은 8 이라 7일 뱃지를 받는다.
        for (int day = 0; day < 8; day++) {
            activityOn(BASE.plusDays(day), 1);
        }
        for (int day = 10; day < 13; day++) {
            activityOn(BASE.plusDays(day), 1);
        }

        runBackfill();

        assertThat(grantedCodes()).contains("STREAK_VOTE_7").doesNotContain("STREAK_VOTE_30");
    }

    @Test
    @DisplayName("연속이 끊긴 구간을 이어 세지 않는다")
    void doesNotJoinBrokenRuns() {
        // 4일 + 공백 + 4일 = 각 구간이 4 다. 이어 세면 8 이 되어 7일 뱃지를 잘못 준다.
        for (int day = 0; day < 4; day++) {
            activityOn(BASE.plusDays(day), 1);
        }
        for (int day = 5; day < 9; day++) {
            activityOn(BASE.plusDays(day), 1);
        }

        runBackfill();

        assertThat(grantedCodes()).doesNotContain("STREAK_VOTE_7");
    }

    @Test
    @DisplayName("두 번 돌려도 보유 행이 늘지 않는다 — 재실행이 안전하다")
    void isIdempotent() {
        for (int day = 0; day < 30; day++) {
            activityOn(BASE.plusDays(day), 1);
        }

        runBackfill();
        List<String> first = grantedCodes();
        runBackfill();

        // NOT EXISTS 가 이미 준 것을 거른다. UNIQUE 위반으로 실패하지도 않는다.
        assertThat(grantedCodes()).isEqualTo(first);
    }

    @Test
    @DisplayName("여러 회원이 섞여 있어도 각자의 최장 구간으로 판정한다")
    void separatesRunsPerUser() {
        // gaps and islands 는 PARTITION BY user_id 로 회원을 가른다.
        // 파티션이 빠지면 다른 사람의 날짜가 이어져 연속이 부풀어 오른다.
        Long other = userStore.save(
                new User(SocialProvider.GOOGLE, "backfill-other-" + System.nanoTime(), null, "옆사람")).id();

        // 대상: 3일만 (연속 뱃지 없음)
        for (int day = 0; day < 3; day++) {
            activityOn(BASE.plusDays(day), 1);
        }
        // 옆 사람: 같은 기간에 이어지는 날짜로 10일 (7일 뱃지 대상)
        for (int day = 0; day < 10; day++) {
            jdbcTemplate.update("""
                    INSERT INTO user_daily_activity (user_id, activity_date, vote_count, created_at, updated_at)
                    VALUES (?, ?, 1, NOW(), NOW())
                    """, other, BASE.plusDays(day));
        }

        runBackfill();

        // 대상은 3일뿐이라 연속 뱃지가 없어야 한다 — 옆 사람 날짜가 섞이면 7일이 된다.
        assertThat(grantedCodes()).doesNotContain("STREAK_VOTE_7");

        List<String> otherCodes = jdbcTemplate.queryForList("""
                SELECT b.code FROM user_badge ub JOIN badge b ON b.id = ub.badge_id
                 WHERE ub.user_id = ? ORDER BY b.display_order
                """, String.class, other);
        assertThat(otherCodes).as("옆 사람은 10일 연속이라 받는다").contains("STREAK_VOTE_7");

        jdbcTemplate.update("DELETE FROM user_badge WHERE user_id = ?", other);
        jdbcTemplate.update("DELETE FROM user_daily_activity WHERE user_id = ?", other);
    }

    @Test
    @DisplayName("30일 연속을 채우면 두 연속 뱃지를 모두 받는다")
    void grantsBothStreakBadgesAtThirty() {
        for (int day = 0; day < 30; day++) {
            activityOn(BASE.plusDays(day), 1);
        }

        runBackfill();

        assertThat(grantedCodes()).contains("STREAK_VOTE_7", "STREAK_VOTE_30");
    }

    @Test
    @DisplayName("조건을 넘지 못한 회원에게는 아무것도 주지 않는다")
    void grantsNothingBelowThreshold() {
        activityOn(BASE, 5);
        activityOn(BASE.plusDays(1), 4);

        runBackfill();

        assertThat(grantedCodes()).isEmpty();
    }
}
