package app.pickple.badge.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.badge.domain.DailyActivityStore;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판정이 <b>투표 전체를 훑지 않는다</b>는 증거 (R-19).
 *
 * <p>이슈 #27 의 완료 판정 중 하나가 "실행 쿼리 확인 — 날짜별 집계 테이블 사용" 이다.
 * 집계 테이블을 만들었다는 사실이나 다른 테스트가 초록색이라는 것은 <b>대리지표</b>일 뿐이라,
 * 판정 경로의 실행 계획을 직접 본다.
 *
 * <p><b>테이블을 충분히 키운 뒤 계획을 읽는다.</b> 행이 수십 개뿐이면 MySQL 이 인덱스
 * 왕복보다 풀스캔이 싸다고 <b>옳게</b> 판단해 {@code key=null} 이 나온다 — 그 상태로는
 * 인덱스 사용 여부를 판정할 수 없다(실측: 100행에서 {@code type=ALL},
 * 4,100행에서 {@code type=ref}). 데이터 크기가 결론을 뒤집으므로 크기를 고정한다.
 */
@IntegrationTest
class BadgeJudgementQueryIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 20);

    /** 옵티마이저가 풀스캔 대신 인덱스를 고르기에 충분한 규모. */
    private static final int OTHER_USERS = 40;
    private static final int DAYS_PER_USER = 100;

    @Autowired
    private DailyActivityStore store;

    @Autowired
    private UserStore userStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = saveUser("badge-plan-target");
        seedActivity(userId);
        for (int i = 0; i < OTHER_USERS; i++) {
            seedActivity(saveUser("badge-plan-other-" + i));
        }
        // 통계가 낡으면 옵티마이저가 옛 카디널리티로 계획을 세운다.
        jdbcTemplate.execute("ANALYZE TABLE user_daily_activity");
    }

    private Long saveUser(String prefix) {
        return userStore.save(
                new User(SocialProvider.GOOGLE, prefix + "-" + System.nanoTime(), null, "투표자")).id();
    }

    /** 한 회원의 {@value #DAYS_PER_USER} 일치 활동을 한 문장으로 넣는다. */
    private void seedActivity(Long id) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO user_daily_activity (user_id, activity_date, vote_count, created_at, updated_at) VALUES ");
        for (int day = 0; day < DAYS_PER_USER; day++) {
            sql.append(day == 0 ? "" : ",")
                    .append("(").append(id).append(", DATE_SUB('").append(TODAY)
                    .append("', INTERVAL ").append(day).append(" DAY), 1, NOW(), NOW())");
        }
        jdbcTemplate.update(sql.toString());
    }

    private Map<String, Object> explain(String sql, Object... args) {
        return jdbcTemplate.queryForMap("EXPLAIN " + sql, args);
    }

    @Test
    @DisplayName("연속 판정이 커버링 인덱스만 읽는다 — 투표 테이블을 훑지 않는다 (R-19)")
    void streakUsesCoveringIndexOnly() {
        Map<String, Object> plan = explain("""
                SELECT activity_date FROM user_daily_activity
                 WHERE user_id = ? AND activity_date <= ?
                 ORDER BY activity_date DESC LIMIT 31
                """, userId, TODAY);

        assertThat(String.valueOf(plan.get("table")))
                .as("연속 판정이 읽는 테이블 — vote 가 아니라 집계 테이블이어야 한다")
                .isEqualTo("user_daily_activity");
        assertThat(String.valueOf(plan.get("key")))
                .as("타는 인덱스")
                .isEqualTo("uk_daily_user_date");
        // Using index = 커버링. 인덱스에서 날짜를 바로 읽어 테이블 접근이 없다.
        // Backward index scan = ORDER BY DESC 를 역방향으로 읽어 정렬 비용이 없다.
        assertThat(String.valueOf(plan.get("Extra")))
                .as("실행 계획의 Extra")
                .contains("Using index")
                .contains("Backward index scan");
        assertThat(Long.parseLong(String.valueOf(plan.get("rows"))))
                .as("후보 행은 그 사용자의 활동 일수를 넘지 않는다 (전체 %d행 중)",
                        (OTHER_USERS + 1) * DAYS_PER_USER)
                .isLessThanOrEqualTo(DAYS_PER_USER);
    }

    @Test
    @DisplayName("누적 합계가 그 회원의 행만 인덱스로 읽는다")
    void totalSumReadsOwnRowsOnly() {
        Map<String, Object> plan =
                explain("SELECT SUM(vote_count) FROM user_daily_activity WHERE user_id = ?", userId);

        assertThat(String.valueOf(plan.get("key")))
                .as("타는 인덱스")
                .isEqualTo("uk_daily_user_date");
        assertThat(String.valueOf(plan.get("type")))
                .as("접근 방식 — ALL(풀스캔)이면 안 된다")
                .isEqualTo("ref");
        // 전역 정렬이 필요했던 랭킹(ADR-0028)과 달리 자기 행만 보는 지역 집계다.
        assertThat(Long.parseLong(String.valueOf(plan.get("rows"))))
                .as("읽는 행은 그 사용자의 활동 일수 언저리다")
                .isLessThanOrEqualTo(DAYS_PER_USER);
    }

    @Test
    @DisplayName("판정에 쓰는 세 수가 집계 테이블만으로 나온다")
    void activityComesFromAggregateAlone() {
        // 판정 경로를 실제로 태운다. vote 행이 하나도 없는데도 값이 나오는 것이
        // 곧 "투표 테이블을 읽지 않는다" 는 증거다.
        Long voteRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vote WHERE user_id = ?", Long.class, userId);
        assertThat(voteRows).as("이 회원은 vote 행이 없다").isZero();

        var activity = store.findActivity(userId, TODAY);

        assertThat(activity.totalVoteCount()).isEqualTo(DAYS_PER_USER);
        assertThat(activity.todayVoteCount()).isEqualTo(1);
        // LIMIT 31 이 상한이라 100일 연속이어도 31 에서 멈춘다 —
        // 최대 임계값이 30 이라 그 위는 판정을 바꾸지 못한다.
        assertThat(activity.streakDays()).isEqualTo(31);
    }

    @Test
    @DisplayName("집계 테이블의 인덱스가 (user_id, activity_date) 순서다")
    void indexColumnOrderIsUserThenDate() {
        List<Map<String, Object>> index = jdbcTemplate.queryForList(
                "SHOW INDEX FROM user_daily_activity WHERE Key_name = 'uk_daily_user_date'");

        // 순서가 뒤집히면 그 사용자의 행을 모으지 못해 연속 판정이 전체를 훑는다.
        assertThat(index).hasSize(2);
        assertThat(index.get(0).get("Column_name")).isEqualTo("user_id");
        assertThat(index.get(1).get("Column_name")).isEqualTo("activity_date");
    }
}
