package app.pickple.activity.infra;

import app.pickple.activity.domain.ActivitySort;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 내 활동 목록 커서에 실린 {@code (정렬키, id)} 쌍을 SQL 파라미터로 되돌린다.
 *
 * <p><b>{@code PostListCursor} 와 같은 규약을 따른다</b> — 새 인코딩을 만들지 않는다.
 * 그쪽이 실측으로 두 번 물린 함정이 여기에도 그대로 있다(ADR-0004 · SPEC §5.2).
 *
 * <ol>
 *   <li><b>타입 소실</b> — 커서는 JSON 을 거치므로 {@code LocalDateTime} 이 문자열로,
 *       {@code Long} 이 {@code Integer} 로 돌아온다({@code CursorCodecTest} 가 이 사실을 고정한다).
 *       같은 프로세스에서 방금 만든 위치는 원래 타입이므로 <b>양쪽을 모두 받아야 한다.</b></li>
 *   <li><b>동률</b> — 정렬 키 하나로 자르면 같은 값을 가진 행이 조각 경계에서 사라진다.
 *       초 단위로 끊는 {@code Clock}(ClockConfig) 때문에 같은 시각은 실제로 생긴다.
 *       그래서 항상 {@code (정렬키, id)} 튜플로 비교한다.</li>
 * </ol>
 *
 * <p><b>여기만 다른 것</b> — {@code id} 는 활동 행이 아니라 <b>게시글</b>의 것이다.
 * 목록 항목이 게시글이라 정렬 튜플의 두 번째 자리도 게시글이어야 한다(ADR-0036).
 * 활동 행의 id 를 실으면 세 유형이 각기 다른 시퀀스를 쓰므로 유형을 바꿀 때
 * 커서가 조용히 엉뚱한 자리를 가리킨다.
 *
 * <p>게시글 식별자는 이미 목록 응답에 그대로 실려 나가므로 커서에 담아도
 * 새로 드러나는 것이 없다({@code CursorCodec} 의 "노출되면 곤란한 값을 정렬 키로
 * 쓰지 않는다" 를 지킨다).
 */
record ActivityListCursor(Object sortValue, long id) {

    private static final String ID_KEY = "id";

    /** 첫 조각이면 {@code null} 을 준다 — 커서 조건 없이 처음부터 읽는다. */
    static ActivityListCursor from(ScrollPosition position, ActivitySort sort) {
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
        return new ActivityListCursor(convertSortValue(rawSortValue, sort), toLong(rawId));
    }

    /**
     * 최신순과 오래된순은 <b>같은 커서 키</b>({@code activityAt})를 쓴다.
     *
     * <p>정렬 방향만 다르고 키의 의미가 같아서다. 그래서 스크롤 도중 방향만 뒤집으면
     * 커서가 형식상 통과한다 — 그때 클라이언트가 보는 것은 <b>왔던 길을 되돌아가는
     * 목록</b>이지 틀린 데이터가 아니다. 방향 전환은 화면이 처음부터 다시 읽는 동작이라
     * 커서를 버리고 보내므로 실제로 일어나지 않는다.
     */
    private static Object convertSortValue(Object raw, ActivitySort sort) {
        return sort.byActivityTime() ? toLocalDateTime(raw) : toLong(raw);
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
    static KeysetScrollPosition toPosition(ActivitySort sort, Object sortValue, long id) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put(sort.cursorKey(), sortValue);
        keys.put(ID_KEY, id);
        return (KeysetScrollPosition) ScrollPosition.forward(keys);
    }
}
