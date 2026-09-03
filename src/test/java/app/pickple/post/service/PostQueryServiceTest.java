package app.pickple.post.service;

import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostQueryStore;
import app.pickple.post.domain.PostSort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostQueryServiceTest {

    private final RecordingStore store = new RecordingStore();
    private final PostQueryService service = new PostQueryService(store);

    @Test
    @DisplayName("정렬·크기를 넘기지 않으면 최신순 10개다")
    void appliesDefaults() {
        service.findSlice(null, null, null, null);

        assertThat(store.sort).isEqualTo(PostSort.LATEST);
        assertThat(store.size).isEqualTo(PostQueryService.DEFAULT_SIZE);
        assertThat(store.category).isNull();
    }

    @Test
    @DisplayName("카테고리를 그대로 저장소에 넘긴다")
    void passesCategoryDown() {
        // 서비스가 걸러내면 조각이 10건보다 적어지고 커서 위치가 어긋난다.
        // 필터는 저장소(=쿼리)까지 내려가야 한다.
        service.findSlice(PostCategory.BEAUTY, "POPULAR", null, null);

        assertThat(store.category).isEqualTo(PostCategory.BEAUTY);
        assertThat(store.sort).isEqualTo(PostSort.POPULAR);
    }

    @Test
    @DisplayName("조각 크기는 1 미만이면 기본값, 50 을 넘으면 50 으로 자른다")
    void clampsSliceSize() {
        service.findSlice(null, null, null, 0);
        assertThat(store.size).isEqualTo(PostQueryService.DEFAULT_SIZE);

        service.findSlice(null, null, null, -5);
        assertThat(store.size).isEqualTo(PostQueryService.DEFAULT_SIZE);

        service.findSlice(null, null, null, 100_000);
        assertThat(store.size).isEqualTo(50);

        service.findSlice(null, null, null, 25);
        assertThat(store.size).isEqualTo(25);
    }

    @Test
    @DisplayName("커서가 없으면 첫 조각 위치를 넘긴다")
    void decodesAbsentCursorAsFirstSlice() {
        service.findSlice(null, null, null, null);

        assertThat(store.position).isEqualTo(ScrollPosition.keyset());
    }

    private static final class RecordingStore implements PostQueryStore {

        private PostCategory category;
        private PostSort sort;
        private ScrollPosition position;
        private int size;

        @Override
        public Window<PostListView> findSlice(
                PostCategory category, PostSort sort, ScrollPosition position, int size) {
            this.category = category;
            this.sort = sort;
            this.position = position;
            this.size = size;
            return Window.from(List.of(), index -> ScrollPosition.keyset(), false);
        }
    }
}
