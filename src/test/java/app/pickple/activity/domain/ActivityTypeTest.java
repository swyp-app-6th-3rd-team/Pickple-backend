package app.pickple.activity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 활동 유형의 해석을 고정한다.
 *
 * <p>{@code null} 을 "전체" 로 풀지 <b>않는</b>다는 것이 여기서 지키는 계약이다.
 * 명세 §9.1 상 칩은 항상 하나가 활성이라(들어온 경로가 기본 칩을 정한다)
 * 서버가 유형 없는 상태를 표현할 필요가 없다(ADR-0036).
 */
class ActivityTypeTest {

    @Test
    @DisplayName("값이 없으면 전체가 아니라 첫 칩(투표)이다")
    void defaultsToVoteNotAll() {
        assertThat(ActivityType.from(null)).isEqualTo(ActivityType.VOTE);
        assertThat(ActivityType.from("")).isEqualTo(ActivityType.VOTE);
        assertThat(ActivityType.from("  ")).isEqualTo(ActivityType.VOTE);
    }

    @ParameterizedTest(name = "\"{0}\" -> VOTE")
    @ValueSource(strings = {"ALL", "전체", "votes", "COMMENTS", "post_commenter"})
    @DisplayName("모르는 값은 400 이 아니라 기본값으로 되돌린다")
    void unknownFallsBackToDefault(String raw) {
        assertThat(ActivityType.from(raw)).isEqualTo(ActivityType.VOTE);
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백을 가리지 않는다")
    void acceptsAnyCaseAndTrims() {
        assertThat(ActivityType.from("comment")).isEqualTo(ActivityType.COMMENT);
        assertThat(ActivityType.from("  Post  ")).isEqualTo(ActivityType.POST);
    }

    @Test
    @DisplayName("유형은 셋뿐이다 — '전체' 를 값으로 두지 않는다")
    void hasExactlyThreeChips() {
        // 값이 늘면 커서·인덱스·SQL 분기가 함께 늘어난다.
        // 특히 "전체" 를 더하면 세 테이블 UNION 이 되살아나 ADR-0036 이 무너진다.
        assertThat(ActivityType.values())
                .containsExactly(ActivityType.VOTE, ActivityType.COMMENT, ActivityType.POST);
    }
}
