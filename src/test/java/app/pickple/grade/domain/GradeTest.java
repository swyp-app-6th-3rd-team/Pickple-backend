package app.pickple.grade.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 등급 판정 규칙. 원장도 스키마도 없이 순수 함수만 확인한다.
 *
 * <p>이슈 #25 의 완료 판정 중 경계값 항목(1~3·7)이 여기서 끝난다.
 */
class GradeTest {

    @Nested
    @DisplayName("승급 판정 (R-15 AND 조건)")
    class Promotion {

        @Test
        @DisplayName("누적 200P·투표 19회는 LV.2 로 오르지 않는다")
        void pointMetButVoteShortStaysAtLv1() {
            // 포인트만 채운 경우. AND 를 OR 로 잘못 짜면 여기서 LV.2 가 나온다.
            assertThat(Grade.reachedBy(200L, 19L)).isEqualTo(Grade.LV1);
        }

        @Test
        @DisplayName("누적 199P·투표 20회는 LV.2 로 오르지 않는다")
        void voteMetButPointShortStaysAtLv1() {
            // 투표만 채운 경우. 위 테스트와 짝이라 한쪽만 있으면 AND 를 증명하지 못한다.
            assertThat(Grade.reachedBy(199L, 20L)).isEqualTo(Grade.LV1);
        }

        @Test
        @DisplayName("누적 200P·투표 20회는 LV.2 로 오른다")
        void bothMetPromotesToLv2() {
            assertThat(Grade.reachedBy(200L, 20L)).isEqualTo(Grade.LV2);
        }

        @ParameterizedTest(name = "{0}P·{1}회 → {2}")
        @CsvSource({
                // 각 등급의 정확한 경계 (정책 요약표 §2)
                "0,      0,     LV1",
                "199,    19,    LV1",
                "200,    20,    LV2",
                "999,    99,    LV2",
                "1000,   100,   LV3",
                "3499,   299,   LV3",
                "3500,   300,   LV4",
                "9999,   999,   LV4",
                "10000,  1000,  LV5",
                // 조건을 넘어서도 그 등급에 머문다 — 다음 조건을 채워야 오른다
                "99999,  999,   LV4",
                "9999,   99999, LV4",
        })
        @DisplayName("정책 요약표 §2 의 임계값을 그대로 판정한다")
        void thresholdsFollowPolicy(long point, long voteCount, Grade expected) {
            assertThat(Grade.reachedBy(point, voteCount)).isEqualTo(expected);
        }

        @Test
        @DisplayName("한 축만 크게 넘어도 등급은 덜 채운 축을 따른다")
        void thePoorerAxisDecides() {
            // 포인트는 LV.5 조건인데 투표가 LV.2 조건이면 LV.2 다.
            // AND 조건의 실질적 의미이자, 달성률이 min 인 이유이기도 하다.
            assertThat(Grade.reachedBy(10_000L, 20L)).isEqualTo(Grade.LV2);
        }
    }

    @Nested
    @DisplayName("등급 순서")
    class Ordering {

        @Test
        @DisplayName("전체 등급은 낮은 등급부터 다섯 개다")
        void orderedListsFiveFromLowest() {
            assertThat(Grade.ordered())
                    .containsExactly(Grade.LV1, Grade.LV2, Grade.LV3, Grade.LV4, Grade.LV5);
        }

        @Test
        @DisplayName("최고 등급의 다음 등급은 비어 있다")
        void highestHasNoNext() {
            // 자기 자신을 돌려주면 달성률이 0% 가 되어 최고 등급 사용자의 게이지가 빈다.
            assertThat(Grade.LV5.next()).isEmpty();
            assertThat(Grade.LV1.next()).contains(Grade.LV2);
        }

        @Test
        @DisplayName("모르는 레벨은 예외다")
        void unknownLevelThrows() {
            // 조용히 LV.1 로 떨어뜨리면 저장된 등급이 사라져 R-16 이 깨지는데
            // 그 사실이 아무 데도 드러나지 않는다.
            assertThat(Grade.ofLevel(3)).isEqualTo(Grade.LV3);
            assertThatThrownBy(() -> Grade.ofLevel(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Grade.ofLevel(6)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("높은 등급을 고른다 (R-16)")
        void higherOfPicksTheHigher() {
            assertThat(Grade.LV2.higherOf(Grade.LV4)).isEqualTo(Grade.LV4);
            assertThat(Grade.LV4.higherOf(Grade.LV2)).isEqualTo(Grade.LV4);
            assertThat(Grade.LV3.higherOf(Grade.LV3)).isEqualTo(Grade.LV3);
        }
    }

    @Nested
    @DisplayName("다음 등급까지 달성률")
    class Achievement {

        @Test
        @DisplayName("가입 직후는 LV.1 이고 달성률 0% 다")
        void freshUserStartsAtZero() {
            GradeProgress progress = new GradeProgress(Grade.LV1, 0L, 0L);

            assertThat(progress.grade()).isEqualTo(Grade.LV1);
            assertThat(progress.nextGrade()).contains(Grade.LV2);
            assertThat(progress.achievementRate()).isZero();
        }

        @Test
        @DisplayName("최고 등급은 다음이 없고 달성률 100% 다")
        void highestGradeIsComplete() {
            GradeProgress progress = new GradeProgress(Grade.LV5, 10_000L, 1_000L);

            assertThat(progress.nextGrade()).isEmpty();
            assertThat(progress.achievementRate()).isEqualTo(100);
        }

        @Test
        @DisplayName("덜 채운 쪽이 달성률이다 (R-15 가 AND 이므로)")
        void theLesserAxisIsTheRate() {
            // LV.1 → LV.2 구간: 포인트 0→200, 투표 0→20.
            // 포인트는 90% 채웠지만 투표는 10% 뿐이다.
            GradeProgress progress = new GradeProgress(Grade.LV1, 180L, 2L);

            // 평균(50%)이면 곧 오를 것처럼 속인다. 실제로 남은 길은 90% 다.
            assertThat(progress.achievementRate()).isEqualTo(10);
        }

        @Test
        @DisplayName("승급 직후 달성률은 0 에서 시작한다")
        void rateRestartsAfterPromotion() {
            // 기준선이 0 이 아니라 현재 등급의 조건이다. 0 기준으로 재면
            // LV.2 에 갓 오른 사람의 LV.3 진행률이 20% 로 시작한다.
            GradeProgress justPromoted = new GradeProgress(Grade.LV2, 200L, 20L);

            assertThat(justPromoted.achievementRate()).isZero();
        }

        @Test
        @DisplayName("구간의 절반을 채우면 50% 다")
        void halfwayIsFifty() {
            // LV.2(200P·20회) → LV.3(1000P·100회). 구간은 800P·80회.
            GradeProgress progress = new GradeProgress(Grade.LV2, 600L, 60L);

            assertThat(progress.achievementRate()).isEqualTo(50);
        }

        @Test
        @DisplayName("조건을 채우지 못했으면 100% 가 되지 않는다")
        void neverReachesHundredBeforeMeetingTheCondition() {
            // 내림이라야 "다 찼는데 안 오른다" 로 보이지 않는다.
            // LV.2→LV.3 구간에서 999P·99회는 각각 한 걸음 모자라다.
            GradeProgress progress = new GradeProgress(Grade.LV2, 999L, 99L);

            assertThat(progress.achievementRate()).isLessThan(100);
        }

        @Test
        @DisplayName("음수 누적값은 만들 수 없다")
        void negativeInputsRejected() {
            assertThatThrownBy(() -> new GradeProgress(Grade.LV1, -1L, 0L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new GradeProgress(Grade.LV1, 0L, -1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
