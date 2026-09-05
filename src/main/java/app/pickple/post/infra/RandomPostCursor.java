package app.pickple.post.infra;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.post.domain.PostType;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.util.LinkedHashMap;
import java.util.Map;

/** 시드 기반 랜덤 순서의 경계인 {@code (randomKey, id)}를 커서로 왕복한다. */
record RandomPostCursor(long seed, PostType type, Long randomKey, Long id) {

    private static final String SEED_KEY = "randomSeed";
    private static final String TYPE_KEY = "postType";
    private static final String RANDOM_KEY = "randomKey";
    private static final String ID_KEY = "id";
    private static final long MAX_UNSIGNED_CRC32 = 0xFFFF_FFFFL;

    /** 첫 조각은 새 시드를 쓰고, 후속 조각은 커서의 시드와 유형을 검증해 복원한다. */
    static RandomPostCursor from(ScrollPosition position, PostType requestedType, long initialSeed) {
        if (!(position instanceof KeysetScrollPosition keyset) || keyset.getKeys().isEmpty()) {
            return new RandomPostCursor(initialSeed, requestedType, null, null);
        }

        Map<String, Object> keys = new LinkedHashMap<>(keyset.getKeys());
        Object rawSeed = keys.get(SEED_KEY);
        Object rawType = keys.get(TYPE_KEY);
        Object rawRandomKey = keys.get(RANDOM_KEY);
        Object rawId = keys.get(ID_KEY);
        if (rawSeed == null || rawType == null || rawRandomKey == null || rawId == null) {
            throw invalidCursor();
        }

        PostType cursorType;
        try {
            cursorType = PostType.valueOf(rawType.toString());
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
        if (cursorType != requestedType) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "커서와 투표 유형이 맞지 않습니다.");
        }

        long randomKey = toLong(rawRandomKey);
        long id = toLong(rawId);
        if (randomKey < 0 || randomKey > MAX_UNSIGNED_CRC32 || id < 1) {
            throw invalidCursor();
        }

        return new RandomPostCursor(toLong(rawSeed), cursorType, randomKey, id);
    }

    boolean hasBoundary() {
        return randomKey != null && id != null;
    }

    /** 현재 카드 뒤에서 같은 시드·유형의 순회를 이어갈 위치를 만든다. */
    static KeysetScrollPosition toPosition(
            long seed, PostType type, long randomKey, long id) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put(SEED_KEY, seed);
        keys.put(TYPE_KEY, type.name());
        keys.put(RANDOM_KEY, randomKey);
        keys.put(ID_KEY, id);
        return ScrollPosition.forward(keys);
    }

    private static long toLong(Object raw) {
        if (raw instanceof Integer || raw instanceof Long) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof java.math.BigInteger value) {
            try {
                return value.longValueExact();
            } catch (ArithmeticException e) {
                throw invalidCursor();
            }
        }
        if (raw instanceof Number) {
            // Base64URL은 서명이 아니다. 소수나 overflow를 조용히 잘라 조회 경계로 쓰지 않는다.
            throw invalidCursor();
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            throw invalidCursor();
        }
    }

    private static ApiException invalidCursor() {
        return new ApiException(ResponseCode.INVALID_REQUEST, "커서 형식이 올바르지 않습니다.");
    }
}
