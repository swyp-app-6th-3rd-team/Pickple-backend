package app.pickple.post.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.post.domain.PostStore;
import app.pickple.support.IntegrationTest;
import app.pickple.support.SqlCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인기 카드의 실제 상품 SQL이 Top 10에 해당하는 상품·사진만 인덱스로 읽는지 확인한다.
 * ANALYZE TABLE은 암묵 커밋이므로 테스트 트랜잭션 없이 준비하고 소유한 데이터만 정리한다.
 */
@IntegrationTest
@Import(SqlCapture.Config.class)
class PopularPostQueryPlanIT {

    private static final int POST_COUNT = 300;
    private static final int TOP_SIZE = 10;
    private static final int PHOTOS_PER_PRODUCT = 3;

    @Autowired
    private PostStore postStore;
    @Autowired
    private UserStore userStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SqlCapture sqlCapture;

    private long authorId;
    private List<Long> topIds;

    @BeforeEach
    void seedForOptimizer() {
        String tag = "popular-plan-" + System.nanoTime();
        authorId = userStore.save(new User(SocialProvider.GOOGLE, tag, null, "계획")).id();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<Object[]> posts = new ArrayList<>();
        for (int i = 0; i < POST_COUNT; i++) {
            // 다른 테스트의 잔여 게시글을 변경하지 않고 이 픽스처를 인기순 앞에 둔다.
            // 집계 정합성은 HTTP 테스트가 검증하며 여기서는 조회 실행계획만 측정한다.
            posts.add(new Object[] {authorId, "계획 " + i, 1_000_000 + i, now, now});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO post (user_id, type, category, title, vote_count, created_at, updated_at)
                VALUES (?, 'AGREE', 'LIVING', ?, ?, ?, ?)
                """, posts);
        List<Long> postIds = jdbcTemplate.queryForList(
                "SELECT id FROM post WHERE user_id = ? ORDER BY id", Long.class, authorId);
        topIds = postIds.reversed().subList(0, TOP_SIZE);

        List<Object[]> containers = new ArrayList<>();
        postIds.forEach(id -> containers.add(new Object[] {authorId, now, now}));
        jdbcTemplate.batchUpdate("""
                INSERT INTO item_container (user_id, attach_type, created_at, updated_at)
                VALUES (?, 'PRODUCT', ?, ?)
                """, containers);
        List<Long> containerIds = jdbcTemplate.queryForList(
                "SELECT id FROM item_container WHERE user_id = ? ORDER BY id", Long.class, authorId);

        List<Object[]> products = new ArrayList<>();
        List<Object[]> resources = new ArrayList<>();
        for (int i = 0; i < POST_COUNT; i++) {
            products.add(new Object[] {postIds.get(i), containerIds.get(i), "상품 " + i, now, now});
            for (int photo = 1; photo <= PHOTOS_PER_PRODUCT; photo++) {
                resources.add(new Object[] {containerIds.get(i), "p" + photo + ".jpg",
                        tag + "/" + i + "-" + photo, "https://cdn.test/" + tag + "/" + i + "-" + photo,
                        now, now});
            }
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO post_product (post_id, item_container_id, name, display_order, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                """, products);
        jdbcTemplate.batchUpdate("""
                INSERT INTO item_resource (item_container_id, size, original_file_name, item_key, access_url, created_at, updated_at)
                VALUES (?, 1024, ?, ?, ?, ?, ?)
                """, resources);
        jdbcTemplate.execute("ANALYZE TABLE post, post_product, item_container, item_resource, users");
    }

    @AfterEach
    void deleteSeed() {
        jdbcTemplate.update("DELETE FROM post WHERE user_id = ?", authorId);
        jdbcTemplate.update("DELETE FROM item_container WHERE user_id = ?", authorId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", authorId);
    }

    @Test
    @DisplayName("인기 상품 SQL은 Top 10 상품과 사진을 인덱스로 읽고 서브쿼리가 없다")
    void productsUsePostAndContainerIndexes() {
        List<String> statements = sqlCapture.record(() -> {
            List<PostStore.PopularPostView> rows = postStore.findPopularTop(TOP_SIZE);
            assertThat(rows).extracting(row -> row.post().id()).containsExactlyElementsOf(topIds);
            assertThat(rows).allSatisfy(row -> assertThat(row.products()).hasSize(1));
        });
        assertThat(statements).hasSize(3);
        assertThat(statements).allSatisfy(sql ->
                assertThat(sql).doesNotContainPattern("(?i)\\(\\s*select\\b"));
        String products = statements.get(2);
        assertThat(products).contains("post_product", "left join item_resource", "post_id in");
        assertThat(products.chars().filter(c -> c == '?').count()).isEqualTo(TOP_SIZE);

        // 실제 키 조회가 확정한 id와 같은 순서·값을 상품 SQL에 바인딩한다.
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN FORMAT=TREE " + products, String.class, topIds.toArray()));
        assertThat(plan)
                .as("상품은 게시글 범위로, 사진은 컨테이너별로 읽는다: %s", plan)
                .contains("using uk_product_post_order", "using idx_resource_container")
                .doesNotContain("Table scan");
        // 사진 후보 최대 30행의 정렬은 허용한다. 전체 상품·사진 스캔과 구분한다.
        System.out.println("Popular product query plan:\n" + plan);
    }
}
