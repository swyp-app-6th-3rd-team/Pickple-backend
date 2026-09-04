package app.pickple.post.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.post.domain.ItemContainerAlreadyAttachedException;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

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

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

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

        Post saved = postStore.saveIfContainerFree(post);

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
        Post post = new Post(authorId, PostType.A_B, PostCategory.BEAUTY, "A vs B", null)
                .addProduct(new PostProduct(c1, "A 상품", 10_000L, null, 1))
                .addProduct(new PostProduct(c2, "B 상품", 20_000L, null, 2))
                .addOption(PostOption.ofProductDisplayOrder(1, 1))
                .addOption(PostOption.ofProductDisplayOrder(2, 2));

        Post saved = postStore.saveIfContainerFree(post);

        Post found = postStore.findById(saved.id()).orElseThrow();
        assertThat(found.products()).hasSize(2);
        assertThat(found.options()).hasSize(2);
        assertThat(found.options()).extracting(PostOption::postProductId)
                .containsExactly(found.products().get(0).id(), found.products().get(1).id());
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

        assertThatThrownBy(() -> postStore.saveIfContainerFree(post))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 컨테이너를 두 게시글에 붙이면 실제 유니크 키 위반을 도메인 예외로 변환한다")
    void duplicateContainerHitsUniqueKey() {
        Long containerId = newProductContainer();
        postStore.saveIfContainerFree(agreePost(containerId, "첫 게시글"));

        assertThatThrownBy(() -> postStore.saveIfContainerFree(agreePost(containerId, "두 번째 게시글")))
                .isInstanceOfSatisfying(ItemContainerAlreadyAttachedException.class,
                        exception -> assertThat(exception.getCause())
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .hasMessageContaining("uk_product_container"));
    }

    @Test
    @DisplayName("여러 컨테이너의 부착 여부를 한 번의 쿼리로 조회한다")
    void findsAttachedContainerIdsInBatch() {
        Long firstAttached = newProductContainer();
        Long secondAttached = newProductContainer();
        Long free = newProductContainer();
        postStore.saveIfContainerFree(agreePost(firstAttached, "첫 번째 게시글"));
        postStore.saveIfContainerFree(agreePost(secondAttached, "둘째 게시글"));
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        Set<Long> attached = postStore.findAttachedItemContainerIds(
                Set.of(firstAttached, secondAttached, free));

        assertThat(attached).containsExactlyInAnyOrder(firstAttached, secondAttached);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
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

    private Post agreePost(Long containerId, String title) {
        return new Post(authorId, PostType.AGREE, PostCategory.ETC, title, null)
                .addProduct(new PostProduct(containerId, "상품", 1000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2));
    }
}
