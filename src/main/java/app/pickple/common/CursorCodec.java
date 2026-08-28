package app.pickple.common;

import app.pickple.error.ApiException;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * keyset 커서를 클라이언트에 불투명한 문자열로 주고받는다.
 *
 * <p>커서에 담긴 키(정렬 컬럼과 그 값)는 서버 구현 세부사항이다.
 * 그대로 노출하면 클라이언트가 값을 조작하거나 내부 스키마에 의존하게 되므로
 * JSON 을 Base64URL 로 감싼다. <b>암호화가 아니라 캡슐화</b>이므로
 * 커서로 노출되면 곤란한 값(타인의 식별자 등)을 정렬 키로 쓰지 않는다.
 *
 * <p>Spring Boot 4 는 Jackson 3({@code tools.jackson}) 를 쓴다.
 * Jackson 2 의 {@code com.fasterxml.jackson.databind} 가 아니다.
 */
public final class CursorCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private CursorCodec() {
    }

    public static String encode(KeysetScrollPosition position) {
        try {
            String json = MAPPER.writeValueAsString(position.getKeys());
            return ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalStateException("커서를 만들지 못했습니다.", e);
        }
    }

    /**
     * 커서 문자열을 ScrollPosition 으로 되돌린다.
     * 값이 없거나 비어 있으면 첫 조각을 뜻하는 {@link ScrollPosition#keyset()} 을 준다.
     */
    public static ScrollPosition decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return ScrollPosition.keyset();
        }
        try {
            String json = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            Map<String, Object> keys = MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return ScrollPosition.forward(keys);
        } catch (RuntimeException e) {
            // 조작되었거나 형식이 깨진 커서. 클라이언트 입력이므로 400 으로 돌려준다.
            throw new ApiException(ResponseCode.INVALID_REQUEST, "커서 형식이 올바르지 않습니다.");
        }
    }
}
