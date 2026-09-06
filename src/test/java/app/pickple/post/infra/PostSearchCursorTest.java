package app.pickple.post.infra;

import app.pickple.common.CursorCodec;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostSearchCursorTest {

    private static final String KEYWORD = "에어팟";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 6, 10, 0);

    @Test
    @DisplayName("커서가 없으면 검색 결과의 첫 조각이다")
    void initialCursor() {
        PostSearchCursor cursor = PostSearchCursor.from(ScrollPosition.keyset(), KEYWORD);

        assertThat(cursor.hasBoundary()).isFalse();
    }

    @Test
    @DisplayName("검색 커서는 종류·버전·검색어 해시·작성시각·id를 왕복한다")
    void roundTripsEveryContextKey() {
        KeysetScrollPosition made = PostSearchCursor.toPosition(KEYWORD, CREATED_AT, 42L);

        PostSearchCursor restored = PostSearchCursor.from(
                CursorCodec.decode(CursorCodec.encode(made)), KEYWORD);

        assertThat(restored.hasBoundary()).isTrue();
        assertThat(restored.createdAt()).isEqualTo(CREATED_AT);
        assertThat(restored.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("다른 검색어 또는 다른 종류의 커서는 400이다")
    void rejectsAnotherContext() {
        KeysetScrollPosition cursor = PostSearchCursor.toPosition(KEYWORD, CREATED_AT, 42L);
        assertInvalid(() -> PostSearchCursor.from(cursor, "버즈"));

        Map<String, Object> wrongKind = new LinkedHashMap<>(cursor.getKeys());
        wrongKind.put("kind", "post-list");
        assertInvalid(() -> PostSearchCursor.from(ScrollPosition.forward(wrongKind), KEYWORD));

        Map<String, Object> extraKey = new LinkedHashMap<>(cursor.getKeys());
        extraKey.put("unexpected", "value");
        assertInvalid(() -> PostSearchCursor.from(ScrollPosition.forward(extraKey), KEYWORD));
    }

    @Test
    @DisplayName("id와 작성시각이 정확한 양의 정수·초 정밀도가 아니면 400이다")
    void rejectsMalformedBoundary() {
        assertInvalid(() -> PostSearchCursor.from(position(Map.of(
                "id", 0L,
                "createdAt", CREATED_AT)), KEYWORD));
        assertInvalid(() -> PostSearchCursor.from(position(Map.of(
                "id", 3.5d,
                "createdAt", CREATED_AT)), KEYWORD));
        assertInvalid(() -> PostSearchCursor.from(position(Map.of(
                "id", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                "createdAt", CREATED_AT)), KEYWORD));
        assertInvalid(() -> PostSearchCursor.from(position(Map.of(
                "id", 1L,
                "createdAt", "2026-09-06T10:00:00.001")), KEYWORD));
        assertInvalid(() -> PostSearchCursor.from(position(Map.of(
                "id", 1L,
                "createdAt", "날짜 아님")), KEYWORD));
    }

    private static ScrollPosition position(Map<String, Object> overrides) {
        Map<String, Object> keys = new LinkedHashMap<>(
                PostSearchCursor.toPosition(KEYWORD, CREATED_AT, 42L).getKeys());
        keys.putAll(overrides);
        return ScrollPosition.forward(keys);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ResponseCode.INVALID_REQUEST);
    }
}
