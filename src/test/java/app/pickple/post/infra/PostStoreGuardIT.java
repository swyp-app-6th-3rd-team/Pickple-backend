package app.pickple.post.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostCounters;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리뷰에서 지적된 결함의 회귀 테스트.
 *
 * <p>{@code @Transactional} 을 일부러 붙이지 않는다 — 스토어가 자기 트랜잭션 없이도
 * 실제로 flush 되는지 확인해야 하기 때문이다.
 */
@IntegrationTest
class PostStoreGuardIT {

    @Autowired
    private PostStore postStore;

    @Autowired
    private ItemContainerStore containerStore;

    @Autowired
    private PostCounters counters;

    @Autowired
    private UserStore userStore;

    private Long authorId;

    @BeforeEach
    void setUp() {
        authorId = userStore.save(
                new User(SocialProvider.GOOGLE, "guard-" + System.nanoTime(), null, "작성자")).id();
    }

    private Long container() {
        return containerStore.save(new ItemContainer(authorId, AttachType.PRODUCT)
                .add(new ItemResource(1L, "p.jpg", "s3/" + System.nanoTime(), "https://cdn/x"))).id();
    }

    @Test
    @DisplayName("검증을 부르지 않아도 저장이 불변식을 막는다 (R-02)")
    void saveEnforcesProductCount() {
        // 찬반인데 상품 2개. 예전에는 조용히 저장됐다.
        Post broken = new Post(authorId, PostType.AGREE, PostCategory.ETC, "깨진 글", null)
                .addProduct(new PostProduct(container(), "A", 1000L, null, 1))
                .addProduct(new PostProduct(container(), "B", 1000L, null, 2))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2));

        assertThatThrownBy(() -> postStore.save(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("상품은 1개");
    }

    @Test
    @DisplayName("선택지 없는 투표 게시글은 저장되지 않는다 (R-04)")
    void saveEnforcesOptionCount() {
        Post broken = new Post(authorId, PostType.AGREE, PostCategory.ETC, "선택지 없음", null)
                .addProduct(new PostProduct(container(), "A", 1000L, null, 1));

        assertThatThrownBy(() -> postStore.save(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("선택지는 2개");
    }

    @Test
    @DisplayName("기존 게시글에 추가한 상품이 유실되지 않는다")
    void addedChildrenArePersisted() {
        Post saved = postStore.save(
                new Post(authorId, PostType.GENERAL, PostCategory.ETC, "일반 글", null));

        // 일반 → 상품 0개가 정상이므로, 여기서는 수정 경로가 컬렉션을 반영하는지만 본다.
        Post loaded = postStore.findById(saved.id()).orElseThrow();
        loaded.edit("제목 수정", "설명 추가", null);
        postStore.save(loaded);

        Post reloaded = postStore.findById(saved.id()).orElseThrow();
        assertThat(reloaded.title()).isEqualTo("제목 수정");
        assertThat(reloaded.description()).isEqualTo("설명 추가");
    }

    @Test
    @DisplayName("게시글 수정이 카운터를 되돌리지 않는다")
    void editDoesNotOverwriteCounters() {
        Post saved = postStore.save(
                new Post(authorId, PostType.GENERAL, PostCategory.ETC, "카운터 글", null));

        // 원자적으로 카운터를 올린 뒤, 오래된 스냅샷으로 제목을 수정한다.
        counters.increaseCommentCount(saved.id());
        counters.increaseCommentCount(saved.id());

        Post stale = postStore.findById(saved.id()).orElseThrow();
        counters.increaseCommentCount(saved.id());   // 그 사이 또 늘어남
        stale.edit("나중 수정", null, null);
        postStore.save(stale);

        // 카운터가 읽기 전용이라 3 이 유지된다. 매핑이 쓰기 가능이면 2 로 되돌아간다.
        assertThat(postStore.findById(saved.id()).orElseThrow().commentCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("카운터는 0 아래로 내려가지 않는다")
    void counterDoesNotUnderflow() {
        Post saved = postStore.save(
                new Post(authorId, PostType.GENERAL, PostCategory.ETC, "언더플로", null));

        // INT UNSIGNED 는 0-1 에서 ERROR 1690 을 낸다. GREATEST 가 막는다.
        counters.decreaseCommentCount(saved.id());

        assertThat(postStore.findById(saved.id()).orElseThrow().commentCount()).isZero();
    }
}
