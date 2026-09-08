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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게시글 상세의 세 문장이 <b>쿼리에서</b> 끝나는지 실행계획으로 본다.
 *
 * <p><b>EXPLAIN 하는 문장은 Hibernate 가 실제로 내보낸 것이다</b>({@link SqlCapture}).
 * QueryDSL 전환(#20 · #130) 뒤로 SQL 은 사람이 쓰지 않으므로, 손으로 베껴 둔 문장을 EXPLAIN 하면
 * 저장소가 바뀌어도 테스트가 초록색으로 남는다. 실제 조회 경로를 한 번 태우고 그때 나간
 * 문장에 같은 값을 바인딩해 계획을 읽는다.
 *
 * <p>여기서 고정하는 성질은 셋이다 — <b>본문 문장은 기본 키·유니크 키 단건 조회로 접히고</b>
 * (삭제 필터와 내 투표 {@code LEFT JOIN} 이 그 문장 안에 있다), <b>상품 문장은 {@code uk_product_post_order}
 * 로 읽고 대표 사진은 {@code idx_resource_container} 를 타는 스칼라 서브쿼리다</b>(조인이 아니라 사진 수만큼
 * 행이 불어나지 않는다), <b>선택지 문장은 {@code uk_option_post_order} 하나로 끝난다</b>. 문장 수가 데이터 양과
 * 무관하다는 성질은 {@code PostDetailIT} 가 따로 잰다.
 *
 * <p>{@code PostDetailIT} 에 중첩하지 않은 이유 — 통계를 위한 {@code ANALYZE TABLE} 이 암묵 커밋이라 시험
 * 트랜잭션과 섞이지 않는다. 이 클래스는 트랜잭션 없이 심고 별도로 지운다.
 */
@IntegrationTest
@Import(SqlCapture.Config.class)
class PostDetailQueryPlanIT {

    private static final int AGREE_POSTS = 300;
    private static final int GENERAL_POSTS = 100;
    /** 찬반 상품은 사진을 최대 3장 갖는다 (R-03) — 서브쿼리가 실제로 고를 것이 있어야 한다. */
    // 정렬 부재는 "filesort" 가 아니라 TREE 형식의 `Sort:` 노드로 본다 — TREE 는 filesort 라는 낱말을 쓰지 않으므로
    // 그 문자열을 찾는 단언은 아무것도 지키지 않는다(이종 리뷰 지적). 표시 순서 ORDER BY 는 유니크 키
    // (post_id, display_order) 가 맡아 Sort 노드가 없고, 정렬 컬럼을 label 로 바꾸면 Sort 가 나타나 여기서 깨진다.
    private static final int PHOTOS_PER_PRODUCT = 3;
    private static final String UNKNOWN_AUTHOR = "알 수 없음";

    @Autowired
    private PostStore postStore;
    @Autowired
    private UserStore userStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SqlCapture sqlCapture;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private long authorId;
    private long viewerId;
    private long targetPostId;

    /**
     * 행을 먼저 심는다. 빈 테이블에서는 옵티마이저가 통계 없이 아무 접근 방식이나 고르므로 계획이 의미를
     * 갖지 않는다. 선택지·상품·사진 세 장·내 투표까지 심어야 세 문장이 운영과 같은 모양이 된다.
     */
    @BeforeEach
    void seedForOptimizer() {
        String tag = "detail-plan-" + System.nanoTime();
        authorId = userStore.save(new User(SocialProvider.GOOGLE, tag + "-author", null, "계획")).id();
        viewerId = userStore.save(new User(SocialProvider.GOOGLE, tag + "-viewer", null, "조회")).id();
        LocalDateTime now = LocalDateTime.now().withNano(0);

        List<Object[]> posts = new ArrayList<>();
        for (int i = 0; i < AGREE_POSTS + GENERAL_POSTS; i++) {
            posts.add(new Object[] {authorId, i < AGREE_POSTS ? "AGREE" : "GENERAL", "LIVING",
                    "계획 " + i, "설명", now.minusMinutes(i), now});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO post (user_id, type, category, title, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, posts);
        List<Long> agreeIds = jdbcTemplate.queryForList(
                "SELECT id FROM post WHERE user_id = ? AND type = 'AGREE' ORDER BY id", Long.class, authorId);

        List<Object[]> containers = new ArrayList<>();
        agreeIds.forEach(id -> containers.add(new Object[] {authorId, "PRODUCT", now, now}));
        jdbcTemplate.batchUpdate(
                "INSERT INTO item_container (user_id, attach_type, created_at, updated_at) VALUES (?, ?, ?, ?)",
                containers);
        List<Long> containerIds = jdbcTemplate.queryForList(
                "SELECT id FROM item_container WHERE user_id = ? ORDER BY id", Long.class, authorId);

        List<Object[]> resources = new ArrayList<>();
        List<Object[]> products = new ArrayList<>();
        List<Object[]> options = new ArrayList<>();
        for (int i = 0; i < agreeIds.size(); i++) {
            long postId = agreeIds.get(i);
            long containerId = containerIds.get(i);
            for (int photo = 1; photo <= PHOTOS_PER_PRODUCT; photo++) {
                resources.add(new Object[] {containerId, 1024L, "p" + photo + ".jpg",
                        tag + "/" + i + "-" + photo + ".jpg", "https://cdn.test/" + tag + "-" + i + "-" + photo,
                        now, now});
            }
            products.add(new Object[] {postId, containerId, "상품 " + i, 10_000, 1, now, now});
            options.add(new Object[] {postId, "사자", 1, now});
            options.add(new Object[] {postId, "말자", 2, now});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO item_resource (item_container_id, size, original_file_name, item_key, access_url, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, resources);
        jdbcTemplate.batchUpdate("""
                INSERT INTO post_product (post_id, item_container_id, name, price, display_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, products);
        jdbcTemplate.batchUpdate(
                "INSERT INTO post_option (post_id, label, display_order, created_at) VALUES (?, ?, ?, ?)", options);

        // 조회자가 세 장 중 한 장에 투표해 둔다 — 내 투표 조인이 실제로 맞는 행을 갖는다. 대상 글은 그중 하나다.
        List<Object[]> votes = new ArrayList<>();
        for (int i = 0; i < agreeIds.size(); i += 3) {
            long postId = agreeIds.get(i);
            Long optionId = jdbcTemplate.queryForObject(
                    "SELECT id FROM post_option WHERE post_id = ? AND display_order = 1", Long.class, postId);
            votes.add(new Object[] {postId, optionId, viewerId, now});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO vote (post_id, post_option_id, user_id, created_at) VALUES (?, ?, ?, ?)", votes);
        targetPostId = agreeIds.get(AGREE_POSTS / 2 / 3 * 3);

        jdbcTemplate.execute("ANALYZE TABLE post, post_option, post_product, item_container, item_resource, vote, users");
    }

    /** {@code ANALYZE TABLE} 이 암묵 커밋이라 심은 행은 롤백되지 않는다. {@code fk_vote_option} 은 CASCADE 가 없어 투표부터 지운다. */
    @AfterEach
    void deleteSeed() {
        jdbcTemplate.update("DELETE FROM vote WHERE user_id = ?", viewerId);
        jdbcTemplate.update("DELETE FROM post WHERE user_id = ?", authorId);
        jdbcTemplate.update("DELETE FROM item_container WHERE user_id = ?", authorId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", authorId, viewerId);
    }

    @Test
    @DisplayName("본문 문장은 게시글·작성자·내 투표를 상수 키로 읽고, 삭제 필터와 내 투표 LEFT JOIN 을 품는다")
    void detailStatementFoldsToConstantKeyLookups() {
        Statements statements = capture(viewerId);

        // 게시글 기본 키 = 상수, 작성자 기본 키 = 그 행의 값, 내 투표 = (post_id, user_id) 유니크 키. 세 접근이 모두
        // 상수로 접혀 옵티마이저가 실행 전에 행을 확정한다 — 어느 하나라도 범위·스캔이면 계획에 그 이름이 남는다.
        String plan = explain(statements.detail(), statements.detailArgs());
        assertThat(plan)
                .as("본문 문장이 인덱스를 잃으면 Table scan 이나 범위 스캔이 나타난다")
                .doesNotContain("Table scan")
                .doesNotContain("Index range scan")
                .doesNotContain("Sort:");
        // 실측 문구 그대로다 — 세 접근이 전부 상수로 접히면 MySQL 8.4 는 계획 전체를 이 한 줄로 낸다.
        // "using PRIMARY" 같은 느슨한 대안을 OR 로 두지 않는다. 느슨하게 두면 어느 한 조인이 범위 스캔으로
        // 물러나도 다른 줄의 문구가 초록색을 지켜준다.
        assertThat(plan)
                .as("게시글·작성자·내 투표가 모두 상수 키 조회여야 실행 전에 행이 확정된다: %s", plan)
                .contains("Rows fetched before execution");

        // 삭제된 글을 거르는 것도, 내 투표를 잃지 않는 것도 이 한 문장의 일이다.
        assertThat(statements.detail())
                .as("소프트 삭제 필터가 본문 문장의 WHERE 에 있다 — 없으면 지운 글이 그대로 보인다")
                .contains("deleted_at is null")
                .as("내 투표는 LEFT JOIN 이다 — INNER 면 미투표자와 게스트에게 글이 사라진다")
                .containsIgnoringCase("left join")
                .as("작성자 표시명 폴백이 문장 안에 있다 — 탈퇴로 파기된 작성자를 '알 수 없음' 으로 접는다")
                .contains("coalesce(nullif(");
    }

    @Test
    @DisplayName("상품 문장은 (post_id, display_order) 유니크 키로 읽고 대표 사진은 컨테이너 인덱스를 타는 스칼라 서브쿼리다")
    void productsStatementUsesUniqueKeyAndScalarPhotoSubquery() {
        Statements statements = capture(null);

        String plan = explain(statements.products(), statements.productsArgs());
        assertThat(plan)
                .as("상품은 유니크 키 (post_id, display_order) 의 앞 컬럼으로 읽는다")
                .contains("using uk_product_post_order")
                .as("대표 사진은 컨테이너 인덱스로 최소 id 를 고른다 — 사진 3장이 상품 행을 불리지 않는다")
                .contains("using idx_resource_container")
                .doesNotContain("Table scan")
                .doesNotContain("Sort:");

        // 사진은 조인이 아니라 서브쿼리다. 조인으로 바꾸면 찬반 상품 한 줄이 사진 수(최대 3)만큼 늘어난다.
        assertThat(statements.products())
                .containsIgnoringCase("min(")
                .contains("item_resource")
                .doesNotContainIgnoringCase("join item_resource");
    }

    @Test
    @DisplayName("선택지 문장은 (post_id, display_order) 유니크 키 하나로 끝나고 조인이 없다")
    void optionsStatementUsesUniqueKeyOnly() {
        Statements statements = capture(null);

        String plan = explain(statements.options(), statements.optionsArgs());
        assertThat(plan)
                .contains("using uk_option_post_order")
                .doesNotContain("Table scan")
                .doesNotContain("Nested loop")
                .doesNotContain("Sort:");
        assertThat(statements.options())
                .as("선택지에 상품을 붙이면 A/B 와 찬반이 갈리고 한 줄이 두 줄이 된다 — 상품은 별도 문장이다")
                .doesNotContainIgnoringCase(" join ");
    }

    /** 한 조회가 내보낸 세 문장과 각 문장의 바인딩 값. */
    private record Statements(
            String detail, Object[] detailArgs,
            String products, Object[] productsArgs,
            String options, Object[] optionsArgs) {
    }

    /**
     * 실제 조회 경로를 한 번 태우고 그때 나간 세 문장을 붙잡는다. 트랜잭션 안에서 부른다 —
     * 저장소가 스냅샷 전제를 단언하므로 밖에서 부르면 첫 문장도 나가기 전에 깨진다.
     */
    private Statements capture(Long viewer) {
        List<String> statements = sqlCapture.record(() -> transactionTemplate.executeWithoutResult(status -> {
            PostStore.PostDetailView view = postStore.findDetail(targetPostId, viewer).orElseThrow();
            assertThat(view.products()).as("찬반은 상품 하나 (R-02)").hasSize(1);
            assertThat(view.options()).as("선택지는 정확히 둘 (R-04)").hasSize(2);
            assertThat(view.voted()).as("조회자가 투표한 글이어야 내 투표 조인이 실제로 맞는다").isEqualTo(viewer != null);
        }));
        assertThat(statements).as("본문 · 상품 · 선택지").hasSize(3);

        // 바인딩 순서는 문장 안의 위치다 — 개수만으로는 같은 개수의 자리바꿈을 못 잡으므로 순서까지 본다.
        // 본문: 표시명 폴백의 빈 문자열 둘과 '알 수 없음'(SELECT 절) → 내 투표 조인의 회원(ON) → 게시글 id(WHERE).
        String detail = statements.get(0);
        assertThat(detail)
                .as("본문 문장의 파라미터 자리가 가정한 순서와 같아야 같은 값을 묶는다")
                .matches("(?s)[^?]*nickname[^?]*\\?[^?]*name[^?]*\\?[^?]*\\?[^?]*user_id=\\?[^?]*\\.id=\\?[^?]*");
        Object[] detailArgs = {"", "", UNKNOWN_AUTHOR, viewer == null ? -1L : viewer, targetPostId};

        // 상품·선택지: 게시글 id 하나뿐이다. 사진 서브쿼리는 상관 조건이라 파라미터가 없다.
        String products = statements.get(1);
        String options = statements.get(2);
        assertThat(products).contains("post_product").matches("(?s)[^?]*post_id=\\?[^?]*");
        assertThat(options).contains("post_option").matches("(?s)[^?]*post_id=\\?[^?]*");
        return new Statements(detail, detailArgs,
                products, new Object[] {targetPostId},
                options, new Object[] {targetPostId});
    }

    /**
     * Hibernate 가 실제로 내보낸 문장에 같은 값을 바인딩해 {@code EXPLAIN FORMAT=TREE} 한다.
     * 개수만 검사한다 — 값이 다른 자리에 묶여도 인덱스 선택은 술어 모양이 정하므로 계획은 같다.
     */
    private String explain(String sql, Object... args) {
        assertThat(sql.chars().filter(c -> c == '?').count())
                .as("바인딩 값의 수가 문장의 ? 와 같아야 같은 계획을 본다")
                .isEqualTo(args.length);
        return String.join(" ", jdbcTemplate.queryForList("EXPLAIN FORMAT=TREE " + sql, String.class, args));
    }
}
