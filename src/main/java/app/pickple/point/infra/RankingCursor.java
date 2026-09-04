package app.pickple.point.infra;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 랭킹 목록의 커서. 실린 값은 마지막으로 내려준 순위 하나다.
 *
 * <p><b>왜 키가 하나인가</b> — {@code users.ranking} 은 {@code ROW_NUMBER()} 가 만든
 * <b>전순서</b>라 활성 회원 사이에 같은 값이 없다 (ADR-0028: 공동 순위가 아니다).
 * 동률이 없으므로 조각 경계에서 행이 새지 않고, {@code PostListCursor} 처럼
 * {@code (정렬키, id)} 튜플로 묶을 이유가 없다.
 *
 * <p>그래서 조건이 {@code ranking > ?} 하나로 끝나고, 행 값 비교가 필요 없다.
 *
 * <p><b>타입 소실은 그대로 물려받는다.</b> 커서는 JSON 을 거치므로 같은 프로세스에서
 * 방금 만든 값은 {@code Integer} 이지만 왕복한 값은 {@code Integer}·{@code Long}·
 * 문자열 어느 쪽으로도 돌아올 수 있다({@code CursorCodecTest} 가 이 사실을 고정한다).
 * 양쪽을 모두 받는다.
 */
record RankingCursor(int ranking) {

    private static final String RANKING_KEY = "ranking";

    /** 첫 조각이면 {@code null} 을 준다 — 커서 조건 없이 처음부터 읽는다. */
    static RankingCursor from(ScrollPosition position) {
        if (!(position instanceof KeysetScrollPosition keyset)) {
            return null;
        }
        Map<String, Object> keys = keyset.getKeys();
        if (keys.isEmpty()) {
            return null;
        }
        Object raw = keys.get(RANKING_KEY);
        if (raw == null) {
            // 다른 목록에서 만든 커서를 그대로 들고 왔다. 조용히 첫 조각을 주면
            // 클라이언트는 무한 스크롤이 되감기는 것을 본다 — 400 으로 알린다.
            throw new ApiException(ResponseCode.INVALID_REQUEST, "커서와 정렬 기준이 맞지 않습니다.");
        }
        return new RankingCursor(toInt(raw));
    }

    private static int toInt(Object raw) {
        if (raw instanceof Number value) {
            return value.intValue();
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "커서 형식이 올바르지 않습니다.");
        }
    }

    /** 이 조각의 마지막 행이 다음 요청에 실어 보낼 커서. */
    static KeysetScrollPosition toPosition(int ranking) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put(RANKING_KEY, ranking);
        return (KeysetScrollPosition) ScrollPosition.forward(keys);
    }
}
