package app.pickple.activity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정렬 값의 해석을 고정한다.
 *
 * <p>여기서 지키는 것은 <b>모르는 값이 400 이 아니라 기본값</b>이라는 규칙이다
 * (SPEC §5.2). 마이페이지 진입 화면이 오타 하나로 비지 않게 하는 선택이고,
 * {@code PostSort} 가 같은 규칙을 따른다.
 */
class ActivitySortTest {

    @Test
    @DisplayName("값이 없으면 최신순이다")
    void defaultsToLatest() {
        assertThat(ActivitySort.from(null)).isEqualTo(ActivitySort.LATEST);
        assertThat(ActivitySort.from("")).isEqualTo(ActivitySort.LATEST);
        assertThat(ActivitySort.from("   ")).isEqualTo(ActivitySort.LATEST);
    }

    @ParameterizedTest(name = "\"{0}\" -> LATEST")
    @ValueSource(strings = {"lastest", "LATSET", "최신순", "created_at DESC", "'; DROP TABLE post; --"})
    @DisplayName("모르는 값은 400 이 아니라 기본값으로 되돌린다")
    void unknownFallsBackToDefault(String raw) {
        assertThat(ActivitySort.from(raw)).isEqualTo(ActivitySort.LATEST);
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백을 가리지 않는다")
    void acceptsAnyCaseAndTrims() {
        assertThat(ActivitySort.from("oldest")).isEqualTo(ActivitySort.OLDEST);
        assertThat(ActivitySort.from("  Popular  ")).isEqualTo(ActivitySort.POPULAR);
    }

    @Test
    @DisplayName("오래된순만 오름차순이다 — keyset 부등호와 ORDER BY 가 함께 뒤집힌다")
    void onlyOldestAscends() {
        assertThat(ActivitySort.OLDEST.ascending()).isTrue();
        assertThat(ActivitySort.LATEST.ascending()).isFalse();
        assertThat(ActivitySort.POPULAR.ascending()).isFalse();
    }

    @Test
    @DisplayName("최신순과 오래된순은 같은 커서 키를 쓴다 — 방향만 다르고 키의 의미가 같다")
    void timeSortsShareCursorKey() {
        assertThat(ActivitySort.LATEST.cursorKey()).isEqualTo(ActivitySort.OLDEST.cursorKey());
        assertThat(ActivitySort.POPULAR.cursorKey()).isNotEqualTo(ActivitySort.LATEST.cursorKey());
    }

    @Test
    @DisplayName("인기순만 활동 시각이 아닌 게시글 점수로 정렬한다")
    void onlyPopularSortsByScore() {
        assertThat(ActivitySort.LATEST.byActivityTime()).isTrue();
        assertThat(ActivitySort.OLDEST.byActivityTime()).isTrue();
        assertThat(ActivitySort.POPULAR.byActivityTime()).isFalse();
    }
}
