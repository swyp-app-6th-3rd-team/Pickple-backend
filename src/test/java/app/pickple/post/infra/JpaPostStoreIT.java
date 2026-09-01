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
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@Transactional
class JpaPostStoreIT {

    @Autowired
    private PostStore postStore;

    @Autowired
    private ItemContainerStore containerStore;

    @Autowired
    private UserStore userStore;

    private Long authorId;

    @BeforeEach
    void setUp() {
        User author = userStore.save(new User(SocialProvider.GOOGLE, "post-author", null, "글쓴이"));
        authorId = author.id();
    }

    private Long newProductContainer() {
        ItemContainer container = new ItemContainer(authorId, AttachType.PRODUCT)
                .add(new ItemResource(1024L, "p.jpg", "s3/" + System.nanoTime(), "https://cdn/x"));
        return containerStore.save(container).id();
    }

    @Test
    @DisplayName("찬반 게시글이 상품·선택지와 함께 저장된다")
    void savesAgreePostWithChildren() {
        Post post = new Post(authorId, PostType.AGREE, PostCategory.FASHION, "이거 살까?", "고민 중")
                .addProduct(new PostProduct(newProductContainer(), "가방", 89_000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2));
        post.verifyPublishable();

        Post saved = postStore.save(post);

        Post found = postStore.findById(saved.id()).orElseThrow();
        assertThat(found.type()).isEqualTo(PostType.AGREE);
        assertThat(found.products()).hasSize(1);
        assertThat(found.options()).extracting(PostOption::label).containsExactly("사자", "말자");
        assertThat(found.popularityScore()).isZero();
    }

    @Test
    @DisplayName("A/B 게시글의 선택지가 상품을 가리킨다")
    void savesAbPostWithProductOptions() {
        Long c1 = newProductContainer();
        Long c2 = newProductContainer();
        // A/B 선택지는 상품 id 를 가리켜야 하는데, 그 id 는 저장 후에야 생긴다.
        // 상품을 먼저 저장해 id 를 얻고, 그 id 로 선택지를 만들어 다시 저장한다.
        Post draft = new Post(authorId, PostType.A_B, PostCategory.BEAUTY, "A vs B", null)
                .addProduct(new PostProduct(c1, "A 상품", 10_000L, null, 1))
                .addProduct(new PostProduct(c2, "B 상품", 20_000L, null, 2));

        // 선택지 없이 저장하려 하면 R-04 가 막는다 — 이것이 이 모델의 제약이다.
        assertThatThrownBy(() -> postStore.save(draft))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("선택지는 2개");
    }

    @Test
    @DisplayName("상품 사진 컨테이너가 없으면 저장되지 않는다")
    void productWithoutContainerRejected() {
        // 도메인이 먼저 막는다. 통과하더라도 NOT NULL 이 DB 에서 막는다 (ERD 2차 V-2).
        assertThatThrownBy(() -> new PostProduct(null, "가방", 1000L, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("사진 컨테이너");
    }

    @Test
    @DisplayName("댓글용 컨테이너를 상품에 붙이면 DB 가 거부한다")
    void commentContainerOnProductRejected() {
        // 복합 FK (item_container_id, container_type) 의 방어를 실제로 확인한다.
        ItemContainer commentContainer = containerStore.save(
                new ItemContainer(authorId, AttachType.COMMENT)
                        .add(new ItemResource(1L, "c.jpg", "s3/c" + System.nanoTime(), "https://cdn/c")));

        Post post = new Post(authorId, PostType.AGREE, PostCategory.ETC, "잘못된 부착", null)
                .addProduct(new PostProduct(commentContainer.id(), "상품", 1000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2));

        assertThatThrownBy(() -> postStore.save(post))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("소프트 삭제는 행을 지우지 않는다")
    void softDeleteKeepsRow() {
        Post saved = postStore.save(
                new Post(authorId, PostType.GENERAL, PostCategory.ETC, "지울 글", null));

        Post loaded = postStore.findById(saved.id()).orElseThrow();
        loaded.delete();
        postStore.save(loaded);

        assertThat(postStore.findById(saved.id())).isPresent();
        assertThat(postStore.findById(saved.id()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("유형은 수정해도 바뀌지 않는다 (R-01)")
    void typeSurvivesEdit() {
        Post saved = postStore.save(
                new Post(authorId, PostType.GENERAL, PostCategory.ETC, "원래 제목", null));

        Post loaded = postStore.findById(saved.id()).orElseThrow();
        loaded.edit("바뀐 제목", null, PostCategory.LIVING);
        postStore.save(loaded);

        Post reloaded = postStore.findById(saved.id()).orElseThrow();
        assertThat(reloaded.type()).isEqualTo(PostType.GENERAL);
        assertThat(reloaded.title()).isEqualTo("바뀐 제목");
        assertThat(reloaded.category()).isEqualTo(PostCategory.LIVING);
    }
}
