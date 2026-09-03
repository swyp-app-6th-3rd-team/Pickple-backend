package app.pickple.post.infra;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.post.domain.PostSort;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 커서에 실린 {@code (정렬키, id)} 쌍을 SQL 파라미터로 되돌린다.
 *
 * <p><b>왜 별도 타입인가</b> — 커서 복원에는 이 코드베이스가 실측으로 두 번 물린 함정이
 * 모여 있다(ADR-0004 · SPEC §5.2). 조회 코드 안에 섞어두면 다음 목록 API 가
 * 같은 함정을 다시 밟는다.
 *
 * <ol>
 *   <li><b>타입 소실</b> — 커서는 JSON 을 거치므로 {@code LocalDateTime} 이 문자열로,
 *       {@code Long} 이 {@code Integer} 로 돌아온다({@code CursorCodecTest} 가 이 사실을 고정한다).
 *       같은 프로세스에서 방금 만든 위치는 원래 타입이므로 <b>양쪽을 모두 받아야 한다.</b></li>
 *   <li><b>동률</b> — 정렬 키 하나로 자르면 같은 값을 가진 행이 조각 경계에서 사라진다.
 *       초 단위로 끊는 {@code Clock}(ClockConfig) 때문에 같은 {@code created_at} 은
 *       이론이 아니라 실제로 생긴다. 그래서 항상 {@code (정렬키, id)} 튜플로 비교한다.</li>
 * </ol>
 */
record PostListCursor(Object sortValue, long id) {

    private static final String ID_KEY = "id";

    /** 첫 조각이면 {@code null} 을 준다 — 커서 조건 없이 처음부터 읽는다. */
    static PostListCursor from(ScrollPosition position, PostSort sort) {
        if (!(position instanceof KeysetScrollPosition keyset)) {
            return null;
        }
        Map<String, Object> keys = new LinkedHashMap<>(keyset.getKeys());
        if (keys.isEmpty()) {
            return null;
        }
        Object rawSortValue = keys.get(sort.cursorKey());
        Object rawId = keys.get(ID_KEY);
        if (rawSortValue == null || rawId == null) {
            // 다른 정렬로 만든 커서를 그대로 들고 왔다. 키 이름이 맞지 않으면
            // 조건을 세울 수 없으므로 조용히 첫 조각을 주는 대신 400 으로 알린다 —
            // 조용히 처음으로 돌아가면 클라이언트는 무한 스크롤이 되감기는 것을 본다.
            throw new ApiException(ResponseCode.INVALID_REQUEST, "커서와 정렬 기준이 맞지 않습니다.");
        }
        return new PostListCursor(convertSortValue(rawSortValue, sort), toLong(rawId));
    }

    private static Object convertSortValue(Object raw, PostSort sort) {
        return switch (sort) {
            case LATEST -> toLocalDateTime(raw);
            case POPULAR -> toLong(raw);
        };
    }

    private static LocalDateTime toLocalDateTime(Object raw) {
        if (raw instanceof LocalDateTime value) {
            return value;
        }
        try {
            return LocalDateTime.parse(raw.toString());
        } catch (DateTimeParseException e) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "커서 형식이 올바르지 않습니다.");
        }
    }

    private static long toLong(Object raw) {
        if (raw instanceof Number value) {
            return value.longValue();
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "커서 형식이 올바르지 않습니다.");
        }
    }

    /** 이 조각의 마지막 행이 다음 요청에 실어 보낼 커서. */
    static KeysetScrollPosition toPosition(PostSort sort, Object sortValue, long id) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put(sort.cursorKey(), sortValue);
        keys.put(ID_KEY, id);
        return (KeysetScrollPosition) ScrollPosition.forward(keys);
    }
}
