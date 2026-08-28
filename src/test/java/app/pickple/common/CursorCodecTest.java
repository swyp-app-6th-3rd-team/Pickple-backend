package app.pickple.common;

import app.pickple.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    @Test
    @DisplayName("빈 커서는 첫 조각을 뜻한다")
    void emptyCursorMeansFirstSlice() {
        assertThat(CursorCodec.decode(null)).isEqualTo(ScrollPosition.keyset());
        assertThat(CursorCodec.decode("  ")).isEqualTo(ScrollPosition.keyset());
    }

    @Test
    @DisplayName("JSON 왕복 후 LocalDateTime 은 문자열이 된다")
    void roundTripLosesTypeInformation() {
        // 이 사실이 keyset 조건을 만들 때의 함정이다.
        // 같은 프로세스에서 만든 위치는 LocalDateTime 이지만,
        // 클라이언트가 돌려준 커서를 복원하면 ISO-8601 문자열이다.
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("createdAt", LocalDateTime.of(2026, 8, 1, 9, 0));
        keys.put("id", 42);

        String cursor = CursorCodec.encode((KeysetScrollPosition) ScrollPosition.forward(keys));
        ScrollPosition decoded = CursorCodec.decode(cursor);

        Map<String, Object> restored = ((KeysetScrollPosition) decoded).getKeys();
        assertThat(restored.get("createdAt")).isInstanceOf(String.class);
        assertThat(restored.get("createdAt")).isEqualTo("2026-08-01T09:00:00");
        assertThat(restored.get("id")).isEqualTo(42);
    }

    @Test
    @DisplayName("커서는 원문이 그대로 보이지 않는다")
    void cursorIsOpaque() {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("createdAt", LocalDateTime.of(2026, 8, 1, 9, 0));
        keys.put("id", 42);

        String cursor = CursorCodec.encode((KeysetScrollPosition) ScrollPosition.forward(keys));

        // Base64URL 로 감싸므로 정렬 키가 눈에 띄지 않는다(암호화가 아니라 캡슐화다).
        assertThat(cursor).doesNotContain("createdAt").doesNotContain("2026-08-01");
    }

    @Test
    @DisplayName("조작된 커서는 400 으로 거부한다")
    void rejectsTamperedCursor() {
        assertThatThrownBy(() -> CursorCodec.decode("!!!not-base64!!!"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.INVALID_REQUEST);
    }
}
