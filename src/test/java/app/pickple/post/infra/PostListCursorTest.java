package app.pickple.post.infra;

import app.pickple.common.CursorCodec;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.post.domain.PostSort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 커서 복원의 함정을 고정한다.
 *
 * <p>여기서 검증하는 것은 "왕복 후에도 같은 조건이 서는가" 다.
 * {@code CursorCodecTest} 가 타입이 소실된다는 <b>사실</b>을 고정한다면,
 * 이 테스트는 그 사실 위에서 조회 코드가 <b>버티는지</b>를 본다.
 */
class PostListCursorTest {

    @Test
    @DisplayName("커서가 없으면 첫 조각이다")
    void nullForFirstSlice() {
        assertThat(PostListCursor.from(ScrollPosition.keyset(), PostSort.LATEST)).isNull();
        assertThat(PostListCursor.from(CursorCodec.decode(null), PostSort.LATEST)).isNull();
    }

    @Test
    @DisplayName("Base64 왕복으로 문자열이 되어도 LocalDateTime 으로 되돌린다")
    void restoresLocalDateTimeAfterJsonRoundTrip() {
        // 커서를 만든 직후에는 LocalDateTime 이지만, 클라이언트가 돌려주면 문자열이다.
        // 되돌리지 못하면 SQL 바인딩이 문자열 비교로 떨어져 조각 경계가 어긋난다.
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 3, 16, 13, 10);
        KeysetScrollPosition made = PostListCursor.toPosition(PostSort.LATEST, createdAt, 42L);

        PostListCursor restored =
                PostListCursor.from(CursorCodec.decode(CursorCodec.encode(made)), PostSort.LATEST);

        assertThat(restored).isNotNull();
        assertThat(restored.sortValue()).isEqualTo(createdAt);
        assertThat(restored.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("왕복하지 않은 위치도 그대로 받는다")
    void acceptsUnserializedPosition() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 3, 16, 13, 10);

        PostListCursor cursor =
                PostListCursor.from(PostListCursor.toPosition(PostSort.LATEST, createdAt, 7L), PostSort.LATEST);

        assertThat(cursor.sortValue()).isEqualTo(createdAt);
        assertThat(cursor.id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("인기순 커서는 왕복 후에도 컬럼과 같은 Integer 로 되돌린다")
    void restoresPopularityScore() {
        // 인기 점수 컬럼은 Integer 매핑이다. 튜플 비교의 파라미터는 좌변 타입으로 강제되므로
        // 같은 프로세스의 Integer 든 JSON 왕복 뒤의 값이든 Integer 하나로 모은다.
        KeysetScrollPosition made = PostListCursor.toPosition(PostSort.POPULAR, 15, 3L);

        PostListCursor restored =
                PostListCursor.from(CursorCodec.decode(CursorCodec.encode(made)), PostSort.POPULAR);

        assertThat(restored.sortValue()).isEqualTo(15);
        assertThat(restored.id()).isEqualTo(3L);
    }

    @Test
    @DisplayName("인기 점수가 Integer 범위를 벗어난 커서는 400 이다")
    void rejectsOutOfRangePopularityScore() {
        // INT UNSIGNED 상한(4,294,967,295)은 Java Integer 를 넘는다. 쿼리 안에서 강제 변환이
        // 터지면 500 이 되므로, 조작된 커서로 보고 여기서 거른다. 음수도 점수가 아니다.
        for (Object score : new Object[] {4_294_967_295L, (long) Integer.MAX_VALUE + 1, -1}) {
            KeysetScrollPosition tampered = PostListCursor.toPosition(PostSort.POPULAR, score, 1L);

            assertThatThrownBy(() -> PostListCursor.from(tampered, PostSort.POPULAR))
                    .as("score=%s", score)
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ResponseCode.INVALID_REQUEST);
        }
    }

    @Test
    @DisplayName("정렬을 바꾼 채 이전 커서를 보내면 400 이다")
    void rejectsCursorFromAnotherSort() {
        // 조용히 첫 조각으로 되돌리면 클라이언트는 무한 스크롤이 되감기는 것을 본다.
        KeysetScrollPosition latestCursor =
                PostListCursor.toPosition(PostSort.LATEST, LocalDateTime.now(), 1L);

        assertThatThrownBy(() -> PostListCursor.from(latestCursor, PostSort.POPULAR))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("정렬 키 값이 날짜가 아니면 400 이다")
    void rejectsMalformedSortValue() {
        KeysetScrollPosition tampered =
                PostListCursor.toPosition(PostSort.LATEST, "어제쯤", 1L);

        assertThatThrownBy(() -> PostListCursor.from(tampered, PostSort.LATEST))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.INVALID_REQUEST);
    }
}
