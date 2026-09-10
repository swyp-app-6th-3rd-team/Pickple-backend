package app.pickple.post.infra;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 검색어 문맥과 {@code (created_at, id)} 경계를 함께 검증하는 검색 전용 커서. */
record PostSearchCursor(String keywordHash, LocalDateTime createdAt, long id) {

    private static final String KIND = "post-search";
    private static final int VERSION = 2;
    private static final String KIND_KEY = "kind";
    private static final String VERSION_KEY = "version";
    private static final String KEYWORD_HASH_KEY = "keywordHash";
    private static final String CREATED_AT_KEY = "createdAt";
    private static final String ID_KEY = "id";
    private static final Set<String> REQUIRED_KEYS = Set.of(
            KIND_KEY, VERSION_KEY, KEYWORD_HASH_KEY, CREATED_AT_KEY, ID_KEY);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    static PostSearchCursor from(ScrollPosition position, String keyword) {
        String expectedHash = hash(keyword);
        if (!(position instanceof KeysetScrollPosition keyset) || keyset.getKeys().isEmpty()) {
            return new PostSearchCursor(expectedHash, null, 0L);
        }

        Map<String, Object> keys = keyset.getKeys();
        if (!keys.keySet().equals(REQUIRED_KEYS)
                || !KIND.equals(keys.get(KIND_KEY))
                || !BigInteger.valueOf(VERSION).equals(toInteger(keys.get(VERSION_KEY)))
                || !(keys.get(KEYWORD_HASH_KEY) instanceof String actualHash)
                || !expectedHash.equals(actualHash)) {
            throw invalidCursor();
        }

        return new PostSearchCursor(
                expectedHash,
                toCreatedAt(keys.get(CREATED_AT_KEY)),
                toPositiveLong(keys.get(ID_KEY)));
    }

    boolean hasBoundary() {
        return createdAt != null;
    }

    static KeysetScrollPosition toPosition(String keyword, LocalDateTime createdAt, long id) {
        if (createdAt == null || createdAt.getNano() != 0 || id < 1) {
            throw new IllegalArgumentException("검색 커서 경계가 올바르지 않습니다.");
        }
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put(KIND_KEY, KIND);
        keys.put(VERSION_KEY, VERSION);
        keys.put(KEYWORD_HASH_KEY, hash(keyword));
        keys.put(CREATED_AT_KEY, createdAt);
        keys.put(ID_KEY, id);
        return (KeysetScrollPosition) ScrollPosition.forward(keys);
    }

    private static LocalDateTime toCreatedAt(Object raw) {
        if (!(raw instanceof LocalDateTime) && !(raw instanceof String)) {
            throw invalidCursor();
        }
        try {
            LocalDateTime value = raw instanceof LocalDateTime dateTime
                    ? dateTime
                    : LocalDateTime.parse((String) raw);
            if (value.getNano() != 0 || value.getYear() < 1000 || value.getYear() > 9999) {
                throw invalidCursor();
            }
            return value;
        } catch (DateTimeParseException exception) {
            throw invalidCursor();
        }
    }

    private static long toPositiveLong(Object raw) {
        BigInteger value = toInteger(raw);
        if (value == null || value.signum() <= 0 || value.compareTo(LONG_MAX) > 0) {
            throw invalidCursor();
        }
        return value.longValueExact();
    }

    private static BigInteger toInteger(Object raw) {
        if (raw instanceof BigInteger value) {
            return value;
        }
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            return BigInteger.valueOf(((Number) raw).longValue());
        }
        return null;
    }

    private static String hash(String keyword) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(keyword.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private static ApiException invalidCursor() {
        return new ApiException(ResponseCode.INVALID_REQUEST, "검색 커서 형식이 올바르지 않습니다.");
    }
}
