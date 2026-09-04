package app.pickple.point.infra;

import app.pickple.common.CursorCodec;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

    /**
     * 숫자이기만 하면 통과시키면 안 되는 이유.
     *
     * <p>{@code Number.intValue()} 는 범위를 벗어난 값을 <b>예외 없이 자른다</b>.
     * 잘린 값은 오류가 아니라 <b>틀린 조각</b>을 만든다 — {@code ranking > 0} 은
     * 모든 행에 걸려 "다음 조각" 요청이 첫 조각을 돌려주고, 클라이언트가
     * {@code nextCursor} 를 계속 따라가면 같은 자리를 무한히 맴돈다.
     *
     * <p>커서는 Base64 로 감쌌을 뿐 암호화가 아니라 누구나 만들어 보낼 수 있다.
     * 아래 값들은 전부 손으로 만든 커서로 실제 재현한 것이다.
     */
    @ParameterizedTest(name = "ranking={0} 은 400 이다")
    @MethodSource("outOfDomainRankings")
    @DisplayName("순위 범위를 벗어난 커서는 조용히 잘리지 않고 400 이다")
    void rejectsOutOfDomainRanking(Object ranking) {
        KeysetScrollPosition tampered = (KeysetScrollPosition)
                ScrollPosition.forward(java.util.Map.of("ranking", ranking));

        assertThatThrownBy(() -> RankingCursor.from(tampered))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ResponseCode.INVALID_REQUEST);
    }

    private static java.util.stream.Stream<Object> outOfDomainRankings() {
        return java.util.stream.Stream.of(
                -1,                 // ranking > -1 은 전체에 걸린다
                0,                  // ROW_NUMBER() 는 1 부터라 0 은 아무도 가리키지 않는다
                4_294_967_296L,     // intValue() 가 조용히 0 으로 자르던 값
                Long.MAX_VALUE,
                3.7d,               // intValue() 가 조용히 3 으로 자르던 값
                "-1",               // 문자열로 와도 같은 판정이어야 한다
                "0");
    }

    @Test
    @DisplayName("1위 커서는 정상이다 — 경계를 너무 좁히지 않았는지 확인")
    void acceptsFirstRanking() {
        KeysetScrollPosition first =
                (KeysetScrollPosition) ScrollPosition.forward(java.util.Map.of("ranking", 1));

        RankingCursor cursor = RankingCursor.from(first);

        assertThat(cursor).isNotNull();
        assertThat(cursor.ranking()).isEqualTo(1);
    }
}
