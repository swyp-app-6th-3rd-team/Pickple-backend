package app.pickple.vote.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoteTest {

    @Test
    @DisplayName("게스트는 투표를 남기지 않는다 (R-11)")
    void guestCannotVote() {
        // 게스트 3회 제한은 클라이언트가 센다. 서버에 행이 생기면 안 된다.
        assertThatThrownBy(() -> new Vote(1L, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("게스트");
    }

    @Test
    @DisplayName("게시글 없이 투표할 수 없다")
    void postIsRequired() {
        assertThatThrownBy(() -> new Vote(null, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("게시글");
    }

    @Test
    @DisplayName("선택지 없이 투표할 수 없다")
    void optionIsRequired() {
        assertThatThrownBy(() -> new Vote(1L, null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("선택지");
    }

    @Test
    @DisplayName("재투표는 선택지를 바꿀 뿐 새 표가 아니다 (R-22)")
    void revoteChangesChoice() {
        // 새 행을 만들면 투표한 사람 수가 부풀어 등급·뱃지가 잘못 나간다.
        Vote vote = Vote.restore(10L, 1L, 100L, 7L);

        vote.changeTo(200L);

        assertThat(vote.id()).isEqualTo(10L);
        assertThat(vote.postOptionId()).isEqualTo(200L);
        assertThat(vote.voterId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("빈 선택지로는 바꿀 수 없다")
    void changeToNullRejected() {
        Vote vote = Vote.restore(10L, 1L, 100L, 7L);

        assertThatThrownBy(() -> vote.changeTo(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("같은 선택지인지 알 수 있다")
    void detectsSameChoice() {
        Vote vote = Vote.restore(10L, 1L, 100L, 7L);

        assertThat(vote.isSameChoice(100L)).isTrue();
        assertThat(vote.isSameChoice(200L)).isFalse();
    }
}
