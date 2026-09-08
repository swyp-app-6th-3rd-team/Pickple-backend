package app.pickple.vote.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VotePercentageTest {

    @Test
    @DisplayName("투표가 없으면 득표율은 0이다")
    void zeroWhenNobodyVoted() {
        assertThat(VotePercentage.calculate(0, 0)).isZero();
    }

    @Test
    @DisplayName("부동소수점 없이 가장 가까운 정수 퍼센트로 반올림한다")
    void roundsToNearestInteger() {
        assertThat(VotePercentage.calculate(1, 3)).isEqualTo(33);
        assertThat(VotePercentage.calculate(2, 3)).isEqualTo(67);
    }
}
