package app.pickple.post.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import app.pickple.support.SqlCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 랜덤 카드의 두 문장이 <b>쿼리에서</b> 끝나는지 실행계획으로 본다.
 *
 * <p><b>EXPLAIN 하는 문장은 Hibernate 가 실제로 내보낸 것이다</b>({@link SqlCapture}).
 * QueryDSL 전환(#139) 뒤로 SQL 은 사람이 쓰지 않으므로, 손으로 베껴 둔 문장을 EXPLAIN 하면
 * 저장소가 바뀌어도 테스트가 초록색으로 남는다. 실제 조회 경로를 한 번 태우고 그때 나간
 * 문장에 같은 값을 바인딩해 계획을 읽는다.
 *
 * <p><b>랜덤 키에는 인덱스가 없다.</b> 함수 값이라 어떤 인덱스도 정렬을 맡지 못하고, 유형의 게시글
 * 전체를 정렬하는 것은 옛 네이티브 문장부터 받아들인 비용이다. 그래서 여기서 고정하는 것은 "filesort 가
 * 없다" 가 아니라 <b>정렬이 {@code post} 한 테이블 위에서만 일어나고</b>(선택지를 붙이면 LIMIT 이 선택지
 * 행을 센다), <b>행 문장은 확정된 id 의 기본 키 범위에서 시작하며</b>, <b>세 곳의 랜덤 키 표현식이 글자
 * 하나까지 같다</b>(같은 시드가 같은 순서를 내야 커서가 이어진다)는 세 성질이다.
 *
 * <p>{@code RandomPostsIT} 에 중첩하지 않은 이유 — 그 클래스의 {@code setUp} 은 기존 글을 전부 숨기는
 * {@code UPDATE} 를 시험 트랜잭션 안에서 하는데, 통계를 위한 {@code ANALYZE TABLE} 이 암묵 커밋이라 그 숨김이
 * 재사용 컨테이너에 남는다. 이 클래스는 트랜잭션 없이 심고 별도로 지운다.
 */
@IntegrationTest
@Import(SqlCapture.Config.class)
class RandomPostQueryPlanIT {

    private static final int SLICE = 10;
    private static final int AGREE_POSTS = 300;
    private static final int GENERAL_POSTS = 100;
    private static final long SEED = 314L;

    /** {@code (p.`type` = 'AGREE')} 이 있는 술어 — 유형 필터가 키 문장의 WHERE 에 있다. 예약어라 EXPLAIN 이 역따옴표를 친다. */
    private static final String TYPE_FILTER = "Filter: \\(.*\\w+\\.`type` = ";
    /** 랜덤 키 정렬. 첫 자리가 crc32 여야 한다 — id 만으로 정렬하면 시드가 무의미하다. */
    private static final String RANDOM_SORT = "Sort: cast\\(crc32\\(";
    /** 행 문장의 게시글 접근 — 확정된 id 들의 기본 키 범위. */
    private static final String PRIMARY_KEY_RANGE = "Index range scan on \\w+ using PRIMARY over \\(id = ";
    /** 행 값 비교 {@code (crc32(…), p.id) > (?, ?)} — 풀어쓴 OR 이 아니다. */
    private static final String ROW_VALUE_GREATER_THAN =
            "\\(\\s*cast\\(crc32\\(.*?\\) as signed\\)\\s*,\\s*\\w+\\.id\\s*\\)\\s*>\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)";

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
    private long cursorKey;
    private long cursorPostId;

    /**
     * 행을 먼저 심는다. 빈 테이블에서는 옵티마이저가 통계 없이 아무 접근 방식이나 고르므로 계획이 의미를 갖지
     * 않는다. 선택지·상품·사진·내 투표까지 심어야 행 문장의 조인 넷이 운영과 같은 모양이 된다.
     */
    @BeforeEach
    void seedForOptimizer() {
        String tag = "plan-" + System.nanoTime();
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
            resources.add(new Object[] {containerId, 1024L, "p.jpg", tag + "/" + i + ".jpg",
                    "https://cdn.test/" + tag + "-" + i, now, now});
            products.add(new Object[] {postId, containerId, "상품 " + i, 1, now, now});
            options.add(new Object[] {postId, "사자", 1, now});
            options.add(new Object[] {postId, "말자", 2, now});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO item_resource (item_container_id, size, original_file_name, item_key, access_url, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, resources);
        jdbcTemplate.batchUpdate("""
                INSERT INTO post_product (post_id, item_container_id, name, display_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, products);
        jdbcTemplate.batchUpdate(
                "INSERT INTO post_option (post_id, label, display_order, created_at) VALUES (?, ?, ?, ?)", options);

        // 조회자가 세 장 중 한 장에 투표해 둔다 — 내 투표 조인이 실제로 맞는 행을 갖는다.
        List<Object[]> votes = new ArrayList<>();
        for (int i = 0; i < agreeIds.size(); i += 3) {
            long postId = agreeIds.get(i);
            Long optionId = jdbcTemplate.queryForObject(
                    "SELECT id FROM post_option WHERE post_id = ? AND display_order = 1", Long.class, postId);
            votes.add(new Object[] {postId, optionId, viewerId, now});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO vote (post_id, post_option_id, user_id, created_at) VALUES (?, ?, ?, ?)", votes);

        // 커서는 심은 게시글의 랜덤 순서 한가운데다 — 앞뒤로 행이 남아 튜플 비교가 실제로 일한다.
        Map<String, Object> middle = jdbcTemplate.queryForMap("""
                SELECT CRC32(CONCAT(?, ':', CAST(id AS CHAR))) AS random_key, id FROM post
                 WHERE user_id = ? AND type = 'AGREE' ORDER BY random_key, id LIMIT 1 OFFSET ?
                """, String.valueOf(SEED), authorId, AGREE_POSTS / 2);
        cursorKey = ((Number) middle.get("random_key")).longValue();
        cursorPostId = ((Number) middle.get("id")).longValue();

        jdbcTemplate.execute("ANALYZE TABLE post, post_option, post_product, item_container, item_resource, vote");
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
    @DisplayName("키 문장은 post 한 테이블 위에서 랜덤 키로 정렬하고 커서는 행 값 비교로 걸린다")
    void keysStatementSortsRandomKeyOverPostOnly() {
        Statements statements = secondSlice(null);

        // 계획을 먼저 본다. 문장 형태 검사가 앞서면 위반 주입 때 이 단언이 실제로 물리는지 알 수 없다.
        assertThat(explain(statements.keys(), statements.keysArgs()))
                .as("선택지를 키 문장에 붙이면 Nested loop 가 나타나고 LIMIT 이 선택지 행을 센다")
                .contains("Limit: " + (SLICE + 1) + " row(s)")
                .containsPattern(RANDOM_SORT)
                .containsPattern(TYPE_FILTER)
                .doesNotContain("Nested loop")
                .doesNotContain("join");
        assertThat(statements.keys())
                .as("행 값 비교가 풀어쓴 OR 로 바뀌면 커서 뒤 행을 두 번 훑는다")
                .containsPattern(ROW_VALUE_GREATER_THAN)
                .doesNotContainIgnoringCase(" or ")
                .doesNotContainIgnoringCase(" join ");
    }

    @Test
    @DisplayName("랜덤 키 표현식은 커서 비교·키 정렬·행 정렬 세 곳에서 글자 하나까지 같다")
    void randomKeyExpressionIsIdenticalInAllThreePlaces() {
        Statements statements = secondSlice(null);
        String keys = statements.keys();
        String rows = statements.rows();

        // 같은 시드가 같은 순서를 내야 커서가 이어진다. 저장소가 표현식을 한 객체로 들고 있으므로
        // 세 곳이 갈릴 수 없다 — 그 사실을 문장으로 고정한다. 한 곳을 손으로 다시 쓰면 여기서 깨진다.
        String expression = randomKeyExpression(keys);
        int orderBy = keys.indexOf(" order by ");
        assertThat(orderBy).isPositive();
        assertThat(keys.indexOf(expression))
                .as("커서 비교(WHERE)에 랜덤 키가 있다")
                .isLessThan(orderBy);
        assertThat(keys.indexOf(expression, orderBy))
                .as("키 문장의 ORDER BY 첫 자리가 같은 랜덤 키다")
                .isPositive();
        assertThat(rows.indexOf(expression, rows.indexOf(" order by ")))
                .as("행 문장의 ORDER BY 첫 자리도 같은 랜덤 키다 — 다르면 응답 순서와 커서가 어긋난다")
                .isPositive();
        assertThat(expression)
                .as("옛 네이티브 문장과 같은 문자열 CONCAT(seed, ':', id) 를 해시한다 — 발급된 커서가 그대로 맞는다")
                .containsIgnoringCase("crc32(concat(cast(? as char),':',cast(")
                .as("값은 Long 으로 읽는다 — 방언이 crc32 를 Integer 로 등록해 unsigned 상위 절반이 넘친다")
                .endsWith(" as signed)")
                .doesNotContain("cast(? as signed");
    }

    @Test
    @DisplayName("행 문장은 확정된 id 만 게시글 기본 키로 읽고 선택지·상품·내 투표를 유니크 키로 한 줄씩 붙인다")
    void rowsStatementStartsFromPostPrimaryKey() {
        Statements statements = secondSlice(viewerId);

        // 인덱스 이름이 아니라 접근 방식을 본다 — 선택지에서 시작하는 계획이면 진입점 단언이 뒤집힌다.
        String plan = explain(statements.rows(), statements.rowsArgs());
        assertThat(plan)
                .as("post.id IN (…) 은 기본 키 범위, 선택지는 (post_id, display_order), 내 투표는 (post_id, user_id) 단건")
                .containsPattern(PRIMARY_KEY_RANGE)
                .contains("using uk_option_post_order")
                .contains("using uk_vote_post_user")
                .doesNotContain("Table scan");
        assertThat(plan.indexOf("using PRIMARY over (id = "))
                .as("게시글 기본 키 범위가 조인의 진입점이다 — 선택지가 먼저 나오면 방향이 뒤집힌 것이다")
                .isLessThan(plan.indexOf("using uk_option_post_order"));
    }

    /** 한 조각이 내보낸 두 문장과 각 문장의 바인딩 값. */
    private record Statements(String keys, Object[] keysArgs, String rows, Object[] rowsArgs) {
    }

    /**
     * 실제 조회 경로를 둘째 조각으로 한 번 태우고, 그때 나간 키 문장과 행 문장을 붙잡는다.
     * 첫 조각은 keyset 조건이 없어 행 값 비교가 계획에 어떻게 내려가는지 보여주지 못한다.
     */
    private Statements secondSlice(Long viewer) {
        ScrollPosition cursor = RandomPostCursor.toPosition(SEED, PostType.AGREE, cursorKey, cursorPostId);
        List<Long> ids = new ArrayList<>();
        List<String> statements = sqlCapture.record(() -> transactionTemplate.executeWithoutResult(status ->
                postStore.findRandomSlice(PostType.AGREE, viewer, cursor, SLICE, SEED)
                        .forEach(view -> ids.add(view.id()))));
        assertThat(ids).as("커서 뒤에 카드가 남아 있어야 계획이 의미를 갖는다").hasSize(SLICE);
        assertThat(statements).as("키 문장과 행 문장").hasSize(2);

        // 바인딩 순서는 문장 안의 위치다 — 개수만으로는 같은 개수의 자리바꿈을 못 잡으므로 순서까지 본다.
        String keys = statements.get(0);
        assertThat(keys)
                .as("키 문장: 유형, 커서 비교 랜덤 키의 시드, 커서 튜플, 정렬 랜덤 키의 시드, LIMIT 순")
                .matches("(?s)[^?]*type=\\?[^?]*cast\\(\\? as char\\)[^?]*>\\s*\\(\\?,\\?\\)[^?]*"
                        + "order by cast\\(crc32\\(concat\\(cast\\(\\? as char\\)[^?]*limit \\?");
        Object[] keysArgs = {PostType.AGREE.name(), SEED, cursorKey, cursorPostId, SEED, SLICE + 1};

        // 행 문장: 제목 CASE 의 유형 → 프로젝션 랜덤 키의 시드 → 찬반 상품 조인의 유형과 표시 순서 → 내 투표의 회원
        // → IN 의 id 목록 → 정렬 랜덤 키의 시드. `.*` 사이에 다른 ? 가 끼어들지 못하게 자리마다 [^?]* 로 묶는다.
        String rows = statements.get(1);
        assertThat(rows)
                .as("행 문장의 파라미터 자리가 가정한 순서와 같아야 같은 값을 묶는다")
                .matches("(?s)[^?]*case when \\(\\w+\\.type=\\?\\)[^?]*cast\\(crc32\\(concat\\(cast\\(\\? as char\\)[^?]*"
                        + "\\w+\\.type=\\? and [^?]*display_order=\\?[^?]*user_id=\\?[^?]*in \\(\\?[?,]*\\)[^?]*"
                        + "order by cast\\(crc32\\(concat\\(cast\\(\\? as char\\)[^?]*");
        List<Object> rowsArgs = new ArrayList<>(List.of(PostType.AGREE.name(), SEED, PostType.AGREE.name(), 1,
                viewer == null ? -1L : viewer));
        rowsArgs.addAll(ids);
        rowsArgs.add(SEED);
        return new Statements(keys, keysArgs, rows, rowsArgs.toArray());
    }

    /** 문장에서 첫 {@code cast(crc32(} 부터 짝이 맞는 닫는 괄호까지 — Long 캐스트를 포함한 표현식 전체다. */
    private static String randomKeyExpression(String sql) {
        int start = sql.indexOf("cast(crc32(");
        assertThat(start).as("랜덤 키가 SQL 함수 CRC32 로 내려간다").isNotNegative();
        int depth = 0;
        for (int i = start; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return sql.substring(start, i + 1);
            }
        }
        throw new AssertionError("괄호가 닫히지 않는다: " + sql);
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
