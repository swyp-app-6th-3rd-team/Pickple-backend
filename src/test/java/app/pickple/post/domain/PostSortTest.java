package app.pickple.post.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PostSortTest {

    @Test
    @DisplayName("값이 없으면 최신순이다")
    void defaultsToLatest() {
        assertThat(PostSort.from(null)).isEqualTo(PostSort.LATEST);
        assertThat(PostSort.from("")).isEqualTo(PostSort.LATEST);
        assertThat(PostSort.from("   ")).isEqualTo(PostSort.LATEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POPULAR", "popular", " Popular "})
    @DisplayName("대소문자와 앞뒤 공백은 무시한다")
    void acceptsCaseInsensitiveValue(String raw) {
        assertThat(PostSort.from(raw)).isEqualTo(PostSort.POPULAR);
    }

    @Test
    @DisplayName("모르는 정렬 값은 기본값으로 되돌린다")
    void unknownValueFallsBackToDefault() {
        // 허용 목록 방식(SPEC §5.2). 400 으로 거부하면 진입 화면이 오타 하나로 비어버린다.
        assertThat(PostSort.from("OLDEST")).isEqualTo(PostSort.LATEST);
        assertThat(PostSort.from("created_at; DROP TABLE post")).isEqualTo(PostSort.LATEST);
    }

    @Test
    @DisplayName("정렬마다 커서 키가 다르다")
    void cursorKeyDiffersPerSort() {
        // 키 이름이 같으면 다른 정렬로 만든 커서가 조용히 통과해 엉뚱한 조건이 선다.
        assertThat(PostSort.LATEST.cursorKey()).isNotEqualTo(PostSort.POPULAR.cursorKey());
    }
}
