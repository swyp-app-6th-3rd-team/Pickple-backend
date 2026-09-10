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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostSearchCursorTest {

    private static final String KEYWORD = "에어팟";
    private static final LocalDateTime CREATED_AT =
            LocalDateTime.of(2026, 9, 10, 10, 0);

    @Test
    @DisplayName("커서가 없으면 검색 결과의 첫 조각이다")
    void initialCursor() {
        PostSearchCursor cursor =
                PostSearchCursor.from(ScrollPosition.keyset(), KEYWORD);

        assertThat(cursor.hasBoundary()).isFalse();
    }

    @Test
    @DisplayName("검색 커서는 종류·버전2·검색어 해시·작성시각·id를 왕복한다")
    void roundTripsEveryContextKey() {
        KeysetScrollPosition made =
                PostSearchCursor.toPosition(KEYWORD, CREATED_AT, 42L);

        assertThat(made.getKeys())
                .containsEntry("kind", "post-search")
                .containsEntry("version", 2)
                .containsKeys("keywordHash", "createdAt", "id");

        PostSearchCursor restored = PostSearchCursor.from(
                CursorCodec.decode(CursorCodec.encode(made)), KEYWORD);

        assertThat(restored.createdAt()).isEqualTo(CREATED_AT);
        assertThat(restored.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("다른 검색어·종류·버전과 누락·추가 키는 400이다")
    void rejectsAnotherContext() {
        KeysetScrollPosition cursor =
                PostSearchCursor.toPosition(KEYWORD, CREATED_AT, 42L);
        assertInvalid(() -> PostSearchCursor.from(cursor, "버즈"));

        Map<String, Object> wrongKind = keys(cursor);
        wrongKind.put("kind", "post-list");
        assertInvalid(() -> PostSearchCursor.from(
                ScrollPosition.forward(wrongKind), KEYWORD));

        Map<String, Object> oldVersion = keys(cursor);
        oldVersion.put("version", 1);
        assertInvalid(() -> PostSearchCursor.from(
                ScrollPosition.forward(oldVersion), KEYWORD));

        Map<String, Object> missing = keys(cursor);
        missing.remove("createdAt");
        assertInvalid(() -> PostSearchCursor.from(
                ScrollPosition.forward(missing), KEYWORD));

        Map<String, Object> extra = keys(cursor);
        extra.put("unexpected", "value");
        assertInvalid(() -> PostSearchCursor.from(
                ScrollPosition.forward(extra), KEYWORD));
    }

    @Test
    @DisplayName("id와 작성시각이 정확한 양의 정수·초 정밀도가 아니면 400이다")
    void rejectsMalformedBoundary() {
        assertInvalid(() -> PostSearchCursor.from(position(Map.of(
                "id", 0L, "createdAt", CREATED_AT)), KEYWORD));
        assertInvalid(() -> PostSearchCursor.from(position(Map.of(
                "id", 3.5d, "createdAt", CREATED_AT)), KEYWORD));
        assertInvalid(() -> PostSearchCursor.from(position(Map.of(
                "id", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                "createdAt", CREATED_AT)), KEYWORD));
        assertInvalid(() -> PostSearchCursor.from(position(Map.of(
                "id", 1L, "createdAt", "2026-09-10T10:00:00.001")), KEYWORD));
        assertInvalid(() -> PostSearchCursor.from(position(Map.of(
                "id", 1L, "createdAt", "날짜 아님")), KEYWORD));
    }

    @Test
    @DisplayName("서버가 만드는 경계도 양의 id와 초 정밀도를 지켜야 한다")
    void rejectsInvalidGeneratedBoundary() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> PostSearchCursor.toPosition(KEYWORD, CREATED_AT, 0L));
        assertThatIllegalArgumentException().isThrownBy(
                () -> PostSearchCursor.toPosition(
                        KEYWORD, CREATED_AT.plusNanos(1), 1L));
    }

    private static Map<String, Object> keys(KeysetScrollPosition position) {
        return new LinkedHashMap<>(position.getKeys());
    }

    private static ScrollPosition position(Map<String, Object> overrides) {
        Map<String, Object> keys = keys(
                PostSearchCursor.toPosition(KEYWORD, CREATED_AT, 42L));
        keys.putAll(overrides);
        return ScrollPosition.forward(keys);
    }

    private static void assertInvalid(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo(ResponseCode.INVALID_REQUEST);
    }
}
