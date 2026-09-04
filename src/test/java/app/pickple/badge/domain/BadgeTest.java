package app.pickple.badge.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 뱃지 조건 판정 (R-18).
 *
 * <p>경계값을 도메인 단위 테스트로 못박는다 — 통합 테스트는 느려서 8종 × 경계를
 * 전부 돌리기 어렵고, 여기서 갈리는 것은 DB 가 아니라 비교 연산이다.
 */
class BadgeTest {

    private static Badge badge(BadgeConditionType type, long threshold) {
        return new Badge(1L, type.name() + "_" + threshold, "이름", "조건 문구", type, threshold, 1);
    }

    @Nested
    @DisplayName("획득 판정")
    class Achievement {

        @ParameterizedTest(name = "누적 {0}회면 10회 뱃지는 {1}")
        @CsvSource({"9, false", "10, true", "11, true"})
        @DisplayName("누적은 임계값에 도달하면 획득이다 — 초과가 아니다")
        void totalVoteBoundary(long votes, boolean achieved) {
            Badge dreamer = badge(BadgeConditionType.TOTAL_VOTE, 10);

            assertThat(dreamer.isAchievedBy(new VoteActivity(votes, 0, 0))).isEqualTo(achieved);
        }

        @ParameterizedTest(name = "하루 {0}개면 20개 뱃지는 {1}")
        @CsvSource({"19, false", "20, true", "21, true"})
        @DisplayName("일일도 이상 조건이다 — 정책 문구가 \"20개 이상\" 이다")
        void dailyVoteBoundary(long votes, boolean achieved) {
            Badge hunter = badge(BadgeConditionType.DAILY_VOTE, 20);

            assertThat(hunter.isAchievedBy(new VoteActivity(votes, votes, 0))).isEqualTo(achieved);
        }

        @ParameterizedTest(name = "{0}일 연속이면 7일 뱃지는 {1}")
        @CsvSource({"6, false", "7, true", "8, true"})
        @DisplayName("연속도 도달하면 획득이다")
        void streakBoundary(long days, boolean achieved) {
            Badge diligent = badge(BadgeConditionType.STREAK_VOTE, 7);

            assertThat(diligent.isAchievedBy(new VoteActivity(days, 1, days))).isEqualTo(achieved);
        }

        @Test
        @DisplayName("유형마다 보는 수가 다르다 — 누적이 많아도 일일 조건은 채워지지 않는다")
        void eachTypeReadsItsOwnCount() {
            // 누적 1,000회지만 오늘은 한 번만 투표했다.
            VoteActivity activity = new VoteActivity(1000, 1, 1);

            assertThat(badge(BadgeConditionType.TOTAL_VOTE, 1000).isAchievedBy(activity)).isTrue();
            assertThat(badge(BadgeConditionType.DAILY_VOTE, 20).isAchievedBy(activity)).isFalse();
            assertThat(badge(BadgeConditionType.STREAK_VOTE, 7).isAchievedBy(activity)).isFalse();
        }
    }

    @Nested
    @DisplayName("미션 진행률")
    class Progress {

        @Test
        @DisplayName("현재값과 목표값을 두 수로 준다 — 퍼센트로 접지 않는다")
        void keepsBothNumbers() {
            BadgeProgress progress =
                    badge(BadgeConditionType.TOTAL_VOTE, 1000).progressOf(new VoteActivity(3, 0, 0));

            // 퍼센트로 환산하면 0% 가 되어 "3/1000" 을 복원할 수 없다.
            assertThat(progress.current()).isEqualTo(3);
            assertThat(progress.goal()).isEqualTo(1000);
        }

        @Test
        @DisplayName("현재값이 목표를 넘어도 목표에서 자른다")
        void clampsToGoal() {
            BadgeProgress progress =
                    badge(BadgeConditionType.TOTAL_VOTE, 10).progressOf(new VoteActivity(1500, 0, 0));

            // 게이지 폭이 100% 를 넘지 않게 한다.
            assertThat(progress.current()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("불변식")
    class Invariants {

        @Test
        @DisplayName("임계값이 0 이면 만들 수 없다 — 가입만으로 획득되는 조건은 조건이 아니다")
        void rejectsZeroThreshold() {
            assertThatThrownBy(() -> badge(BadgeConditionType.TOTAL_VOTE, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("코드 없이는 만들 수 없다 — 표시명이 아니라 코드가 식별자다")
        void rejectsBlankCode() {
            assertThatThrownBy(() ->
                    new Badge(1L, " ", "이름", "문구", BadgeConditionType.TOTAL_VOTE, 10, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("활동 수는 음수일 수 없다")
        void rejectsNegativeActivity() {
            assertThatThrownBy(() -> new VoteActivity(-1, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
