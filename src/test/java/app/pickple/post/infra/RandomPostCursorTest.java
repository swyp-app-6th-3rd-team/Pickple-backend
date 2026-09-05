package app.pickple.post.infra;

import app.pickple.common.CursorCodec;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.post.domain.PostType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RandomPostCursorTest {

    @Test
    @DisplayName("커서가 없으면 요청에서 만든 새 시드의 첫 조각이다")
    void firstSliceUsesInitialSeed() {
        RandomPostCursor cursor = RandomPostCursor.from(ScrollPosition.keyset(), PostType.AGREE, 42L);

        assertThat(cursor.seed()).isEqualTo(42L);
        assertThat(cursor.type()).isEqualTo(PostType.AGREE);
        assertThat(cursor.hasBoundary()).isFalse();
    }

    @Test
    @DisplayName("Base64 왕복 후에도 시드와 unsigned CRC32 경계를 복원한다")
    void restoresAllKeysAfterJsonRoundTrip() {
        long unsignedCrc32 = 4_294_967_295L;
        KeysetScrollPosition made = RandomPostCursor.toPosition(
                -77L, PostType.A_B, unsignedCrc32, 123L);

        RandomPostCursor restored = RandomPostCursor.from(
                CursorCodec.decode(CursorCodec.encode(made)), PostType.A_B, 999L);

        assertThat(restored.seed()).isEqualTo(-77L);
        assertThat(restored.type()).isEqualTo(PostType.A_B);
        assertThat(restored.randomKey()).isEqualTo(unsignedCrc32);
        assertThat(restored.id()).isEqualTo(123L);
        assertThat(restored.hasBoundary()).isTrue();
    }

    @Test
    @DisplayName("다른 투표 유형에서 만든 커서를 재사용하면 400이다")
    void rejectsCursorFromAnotherType() {
        KeysetScrollPosition agreeCursor = RandomPostCursor.toPosition(
                1L, PostType.AGREE, 10L, 20L);

        assertInvalid(() -> RandomPostCursor.from(agreeCursor, PostType.A_B, 0L));
    }

    @Test
    @DisplayName("필수 키가 빠졌거나 수치가 깨진 커서는 400이다")
    void rejectsMalformedCursor() {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("randomSeed", 1L);
        assertInvalid(() -> RandomPostCursor.from(
                ScrollPosition.forward(missing), PostType.AGREE, 0L));

        Map<String, Object> malformed = new LinkedHashMap<>();
        malformed.put("randomSeed", "seed");
        malformed.put("postType", "AGREE");
        malformed.put("randomKey", 10L);
        malformed.put("id", 20L);
        assertInvalid(() -> RandomPostCursor.from(
                ScrollPosition.forward(malformed), PostType.AGREE, 0L));

        Map<String, Object> outOfRange = new LinkedHashMap<>();
        outOfRange.put("randomSeed", 1L);
        outOfRange.put("postType", "AGREE");
        outOfRange.put("randomKey", 4_294_967_296L);
        outOfRange.put("id", 0L);
        assertInvalid(() -> RandomPostCursor.from(
                ScrollPosition.forward(outOfRange), PostType.AGREE, 0L));

        outOfRange.put("randomKey", 3.7d);
        outOfRange.put("id", 20L);
        assertInvalid(() -> RandomPostCursor.from(
                ScrollPosition.forward(outOfRange), PostType.AGREE, 0L));

        outOfRange.put("randomKey", 10L);
        outOfRange.put("id", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        assertInvalid(() -> RandomPostCursor.from(
                ScrollPosition.forward(outOfRange), PostType.AGREE, 0L));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.INVALID_REQUEST);
    }
}
