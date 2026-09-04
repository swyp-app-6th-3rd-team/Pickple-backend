package app.pickple.point.infra;

import app.pickple.common.CursorCodec;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 랭킹 커서 복원.
 *
 * <p>{@code PostListCursorTest} 와 같은 함정을 본다 — 커서는 JSON 을 거치므로
 * 타입이 소실된다. 다른 점은 키가 하나뿐이라 동률 처리가 없다는 것이다
 * ({@code users.ranking} 은 {@code ROW_NUMBER()} 가 만든 전순서다).
 */
class RankingCursorTest {

    @Test
    @DisplayName("커서가 없으면 첫 조각이다")
    void nullForFirstSlice() {
        assertThat(RankingCursor.from(ScrollPosition.keyset())).isNull();
        assertThat(RankingCursor.from(CursorCodec.decode(null))).isNull();
        assertThat(RankingCursor.from(CursorCodec.decode(""))).isNull();
    }

    @Test
    @DisplayName("Base64 왕복 후에도 같은 순위로 되돌아온다")
    void survivesJsonRoundTrip() {
        // 같은 프로세스에서 만든 위치는 Integer 지만, 클라이언트가 돌려준 것은
        // JSON 을 거쳐 Integer·Long·문자열 어느 쪽으로도 올 수 있다.
        KeysetScrollPosition made = RankingCursor.toPosition(150_000);

        RankingCursor direct = RankingCursor.from(made);
        RankingCursor roundTripped = RankingCursor.from(CursorCodec.decode(CursorCodec.encode(made)));

        assertThat(direct).isNotNull();
        assertThat(direct.ranking()).isEqualTo(150_000);
        assertThat(roundTripped).isEqualTo(direct);
    }

    @Test
    @DisplayName("다른 목록에서 만든 커서는 400 이다 — 조용히 처음으로 돌아가지 않는다")
    void rejectsForeignCursor() {
        // 게시글 목록의 커서를 그대로 들고 오면 ranking 키가 없다.
        // 조용히 첫 조각을 주면 클라이언트는 무한 스크롤이 되감기는 것을 본다.
        KeysetScrollPosition foreign =
                (KeysetScrollPosition) ScrollPosition.forward(java.util.Map.of("createdAt", "2026-09-04T00:00:00"));

        assertThatThrownBy(() -> RankingCursor.from(foreign))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ResponseCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("숫자가 아닌 커서 값은 400 이다")
    void rejectsNonNumericValue() {
        KeysetScrollPosition tampered =
                (KeysetScrollPosition) ScrollPosition.forward(java.util.Map.of("ranking", "조작됨"));

        assertThatThrownBy(() -> RankingCursor.from(tampered))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ResponseCode.INVALID_REQUEST);
    }
}
