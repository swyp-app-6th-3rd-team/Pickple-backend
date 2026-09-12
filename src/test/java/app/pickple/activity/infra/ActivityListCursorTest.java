package app.pickple.activity.infra;

import app.pickple.activity.domain.ActivitySort;
import app.pickple.activity.domain.ActivityType;
import app.pickple.common.CursorCodec;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 활동 목록 커서 복원의 함정을 고정한다.
 *
 * <p>{@code PostListCursorTest} 와 같은 것을 본다 — 왕복 후에도 같은 조건이 서는가.
 * 규약을 물려받았으므로 함정도 그대로 물려받는다.
 */
class ActivityListCursorTest {

    @Test
    @DisplayName("커서가 없으면 첫 조각이다")
    void nullForFirstSlice() {
        assertThat(ActivityListCursor.from(ScrollPosition.keyset(), ActivityType.VOTE, ActivitySort.LATEST))
                .isNull();
        assertThat(ActivityListCursor.from(CursorCodec.decode(null), ActivityType.VOTE, ActivitySort.LATEST))
                .isNull();
    }

    @Test
    @DisplayName("Base64 왕복으로 문자열이 되어도 LocalDateTime 으로 되돌린다")
    void restoresLocalDateTimeAfterJsonRoundTrip() {
        // 커서를 만든 직후에는 LocalDateTime 이지만, 클라이언트가 돌려주면 문자열이다.
        // 되돌리지 못하면 SQL 바인딩이 문자열 비교로 떨어져 조각 경계가 어긋난다.
        LocalDateTime activityAt = LocalDateTime.of(2026, 9, 3, 16, 13, 10);
        KeysetScrollPosition made =
                ActivityListCursor.toPosition(ActivityType.VOTE, ActivitySort.LATEST, activityAt, 42L);

        ActivityListCursor restored = ActivityListCursor.from(
                CursorCodec.decode(CursorCodec.encode(made)), ActivityType.VOTE, ActivitySort.LATEST);

        assertThat(restored).isNotNull();
        assertThat(restored.sortValue()).isEqualTo(activityAt);
        assertThat(restored.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("인기 점수는 왕복 후 Integer 로 돌아와도 long 으로 받는다")
    void restoresScoreAfterJsonRoundTrip() {
        KeysetScrollPosition made =
                ActivityListCursor.toPosition(ActivityType.VOTE, ActivitySort.POPULAR, 77L, 42L);

        ActivityListCursor restored = ActivityListCursor.from(
                CursorCodec.decode(CursorCodec.encode(made)), ActivityType.VOTE, ActivitySort.POPULAR);

        assertThat(restored).isNotNull();
        assertThat(restored.sortValue()).isEqualTo(77L);
        assertThat(restored.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("왕복하지 않은 위치도 그대로 받는다 — 같은 프로세스에서 만든 커서는 원래 타입이다")
    void acceptsPositionWithoutRoundTrip() {
        LocalDateTime activityAt = LocalDateTime.of(2026, 9, 3, 16, 13, 10);

        ActivityListCursor restored = ActivityListCursor.from(
                ActivityListCursor.toPosition(ActivityType.VOTE, ActivitySort.OLDEST, activityAt, 7L),
                ActivityType.VOTE, ActivitySort.OLDEST);

        assertThat(restored).isNotNull();
        assertThat(restored.sortValue()).isEqualTo(activityAt);
    }

    @Test
    @DisplayName("최신순으로 만든 커서를 인기순으로 들고 오면 400 이다")
    void rejectsCursorFromAnotherSort() {
        // 조용히 첫 조각을 주면 클라이언트는 무한 스크롤이 되감기는 것을 본다.
        KeysetScrollPosition made = ActivityListCursor.toPosition(
                ActivityType.VOTE, ActivitySort.LATEST, LocalDateTime.now(), 1L);

        assertThatThrownBy(() -> ActivityListCursor.from(made, ActivityType.VOTE, ActivitySort.POPULAR))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ResponseCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("다른 유형으로 만든 커서를 들고 오면 400 이다 — 정렬 가드가 못 잡는 축이다")
    void rejectsCursorFromAnotherType() {
        // 정렬 축 가드는 키 이름으로 판별하는데 세 유형이 같은 키 이름을 쓴다.
        // 유형을 값으로 싣지 않으면 이 커서는 구조상 유효해 보여 그대로 통과한다.
        LocalDateTime activityAt = LocalDateTime.of(2026, 9, 3, 16, 13, 10);
        KeysetScrollPosition madeOnVotes =
                ActivityListCursor.toPosition(ActivityType.VOTE, ActivitySort.LATEST, activityAt, 1L);

        assertThatThrownBy(() ->
                ActivityListCursor.from(madeOnVotes, ActivityType.POST, ActivitySort.LATEST))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ResponseCode.INVALID_REQUEST);

        // 왕복해서 문자열이 되어도 같은 판정이다 — 클라이언트가 실제로 돌려주는 모양이다.
        assertThatThrownBy(() -> ActivityListCursor.from(
                CursorCodec.decode(CursorCodec.encode(madeOnVotes)),
                ActivityType.COMMENT, ActivitySort.LATEST))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ResponseCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("유형이 없는 옛 커서도 400 이다 — 판별자를 떼면 교차 사용이 되살아난다")
    void rejectsCursorWithoutType() {
        // 판별자가 생기기 전에 발급한 커서이거나, 키를 지워 보낸 요청이다.
        // 통과시키면 /votes 커서에서 유형만 떼어 /posts 에 넣는 경로가 그대로 열린다.
        KeysetScrollPosition legacy = (KeysetScrollPosition) ScrollPosition.forward(
                Map.of("activityAt", LocalDateTime.of(2026, 9, 3, 16, 13, 10), "id", 1L));

        assertThatThrownBy(() -> ActivityListCursor.from(legacy, ActivityType.VOTE, ActivitySort.LATEST))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ResponseCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("최신순 커서는 오래된순에 그대로 통한다 — 키가 같고 방향만 다르다")
    void timeSortsShareTheSameCursor() {
        // 되감기는 목록을 보게 되지만 틀린 데이터는 아니다. 방향 전환은 화면이
        // 처음부터 다시 읽는 동작이라 커서를 버리고 보낸다 (ADR-0036).
        // 유형 판별자가 생겨도 이 성질은 그대로다 — 유형이 같으면 통과한다.
        LocalDateTime activityAt = LocalDateTime.of(2026, 9, 3, 16, 13, 10);
        KeysetScrollPosition made =
                ActivityListCursor.toPosition(ActivityType.VOTE, ActivitySort.LATEST, activityAt, 9L);

        ActivityListCursor restored =
                ActivityListCursor.from(made, ActivityType.VOTE, ActivitySort.OLDEST);

        assertThat(restored).isNotNull();
        assertThat(restored.sortValue()).isEqualTo(activityAt);
    }

    @Test
    @DisplayName("형식이 깨진 커서 값은 400 이다")
    void rejectsMalformedValues() {
        KeysetScrollPosition brokenTime =
                ActivityListCursor.toPosition(ActivityType.VOTE, ActivitySort.LATEST, "어제", 1L);
        assertThatThrownBy(() ->
                ActivityListCursor.from(brokenTime, ActivityType.VOTE, ActivitySort.LATEST))
                .isInstanceOf(ApiException.class);

        KeysetScrollPosition brokenScore =
                ActivityListCursor.toPosition(ActivityType.VOTE, ActivitySort.POPULAR, "많음", 1L);
        assertThatThrownBy(() ->
                ActivityListCursor.from(brokenScore, ActivityType.VOTE, ActivitySort.POPULAR))
                .isInstanceOf(ApiException.class);
    }
}
