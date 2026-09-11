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
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색 구현이 실제로 내보낸 QueryDSL 네 문장을 캡처해 MySQL 실행계획을 읽는다.
 *
 * <p>부분 문자열 검색의 count와 key 문장은 일치 후보를 찾기 위해 많은 행을 읽을 수 있다.
 * 여기서 그 비용이 없다고 주장하지 않는다. 고정하는 성질은 검색 조건이 LIMIT 전에 SQL에 있고,
 * key 문장이 최신순 11개까지만 확정하며, row 문장이 확정된 10개 id만 장식한다는 것이다.
 */
@IntegrationTest
@Import(SqlCapture.Config.class)
class PostSearchQueryPlanIT {

    private static final int POSTS = 2_000;
    private static final int PAGE_SIZE = 10;
    private static final String KEYWORD = "plan-needle";
    private static final LocalDateTime CREATED_AT =
            LocalDateTime.of(2026, 9, 10, 12, 0);

    @Autowired
    private PostStore postStore;
    @Autowired
    private UserStore userStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SqlCapture sqlCapture;

    private long authorId;

    @BeforeEach
    void seedForOptimizer() {
        String tag = "search-plan-" + System.nanoTime();
        authorId = userStore.save(new User(
                SocialProvider.GOOGLE, tag, null, "검색계획")).id();

        jdbcTemplate.batchUpdate("""
                INSERT INTO post (
                    user_id, type, category, title, description, created_at, updated_at
                ) VALUES (?, 'GENERAL', 'LIVING', ?, '설명', ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                statement.setLong(1, authorId);
                statement.setString(2, index % 100 == 0 ? KEYWORD : "plan-other");
                statement.setTimestamp(3, Timestamp.valueOf(CREATED_AT));
                statement.setTimestamp(4, Timestamp.valueOf(CREATED_AT));
            }

            @Override
            public int getBatchSize() {
                return POSTS;
            }
        });
        jdbcTemplate.execute("ANALYZE TABLE post, post_product, item_resource");
    }

    @AfterEach
    void deleteSeed() {
        if (authorId == 0L) {
            return;
        }
        jdbcTemplate.update("DELETE FROM post WHERE user_id = ?", authorId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", authorId);
    }

    @Test
    @DisplayName("실제 검색 SQL은 조건을 먼저 적용하고 11개 key와 10개 typed row만 읽는다")
    void explainsActualSearchStatements() {
        AtomicReference<PostStore.PostSearchResult> firstResult = new AtomicReference<>();
        List<String> first = sqlCapture.record(() -> firstResult.set(
                postStore.search(KEYWORD, ScrollPosition.keyset(), PAGE_SIZE)));

        assertThat(firstResult.get().totalCount()).isEqualTo(20L);
        assertThat(firstResult.get().window()).hasSize(PAGE_SIZE);
        assertThat(first).as("count, key, row, thumbnail 네 문장").hasSize(4);

        String countSql = first.get(0);
        String keySql = first.get(1);
        String rowSql = first.get(2);
        String thumbnailSql = first.get(3);
        assertThat(countSql)
                .containsIgnoringCase("count(")
                .containsIgnoringCase("exists(select")
                .contains("title like ? escape '!'")
                .contains("name like ? escape '!'")
                .doesNotContainIgnoringCase(" order by ")
                .doesNotContainIgnoringCase("item_resource");
        assertThat(keySql)
                .containsIgnoringCase(" order by ")
                .containsIgnoringCase("created_at desc")
                .containsIgnoringCase("id desc")
                .containsIgnoringCase(" limit ?")
                .doesNotContainIgnoringCase(" join ");
        assertThat(rowSql)
                .containsIgnoringCase(" in (")
                .doesNotContainIgnoringCase("item_resource")
                .doesNotContainIgnoringCase("min(")
                .doesNotContainIgnoringCase("count(");
        assertThat(thumbnailSql)
                .containsIgnoringCase("post_product")
                .containsIgnoringCase("item_resource")
                .containsIgnoringCase(" join ")
                .containsIgnoringCase(" order by ")
                .doesNotContainIgnoringCase("(select")
                .doesNotContainIgnoringCase("min(");
        assertThat(placeholders(countSql)).isEqualTo(6L);
        assertThat(placeholders(keySql)).isEqualTo(7L);
        assertThat(placeholders(rowSql)).isEqualTo(12L);
        assertThat(placeholders(thumbnailSql)).isEqualTo(PAGE_SIZE);

        String countPlan = explain(countSql, statement -> bindMatch(statement));
        String keyPlan = explain(keySql, statement -> {
            bindMatch(statement);
            statement.setInt(7, PAGE_SIZE + 1);
        });
        List<Long> selectedIds = firstResult.get().window().getContent().stream()
                .map(PostStore.PostSearchView::id)
                .toList();
        String rowPlan = explain(rowSql, statement -> {
            statement.setString(1, PostType.AGREE.name());
            statement.setByte(2, (byte) 1);
            for (int index = 0; index < selectedIds.size(); index++) {
                statement.setLong(index + 3, selectedIds.get(index));
            }
        });

        assertThat(countPlan).contains("Aggregate", "rows=1");
        assertThat(keyPlan)
                .contains("Limit: 11")
                .contains("rows=11")
                .contains("Index range scan on pe1_0 using idx_post_latest_all")
                .doesNotContain("Sort:");
        assertThat(rowPlan)
                .containsPattern("Index range scan on \\w+ using PRIMARY")
                .contains("rows=10")
                .doesNotContain("Table scan on pe1_0");

        PostStore.PostSearchView last = firstResult.get().window().getContent().getLast();
        ScrollPosition cursor =
                PostSearchCursor.toPosition(KEYWORD, last.createdAt(), last.id());
        List<String> second = sqlCapture.record(() ->
                postStore.search(KEYWORD, cursor, PAGE_SIZE));
        assertThat(second).hasSize(4);
        assertThat(second.get(1).replaceAll("\\s+", ""))
                .contains("(pe1_0.created_at,pe1_0.id)<(?,?)");
        assertThat(placeholders(second.get(1))).isEqualTo(9L);
        String secondKeyPlan = explain(second.get(1), statement -> {
            bindMatch(statement);
            statement.setTimestamp(7, Timestamp.valueOf(last.createdAt()));
            statement.setLong(8, last.id());
            statement.setInt(9, PAGE_SIZE + 1);
        });
        assertThat(secondKeyPlan).contains("rows=10");
    }

    private static void bindMatch(PreparedStatement statement) throws SQLException {
        String pattern = PostSearchQuerydslRepository.literalContainsPattern(KEYWORD);
        statement.setString(1, PostType.GENERAL.name());
        statement.setString(2, PostType.A_B.name());
        statement.setString(3, pattern);
        statement.setString(4, PostType.AGREE.name());
        statement.setString(5, PostType.A_B.name());
        statement.setString(6, pattern);
    }

    private String explain(String sql, StatementBinder binder) {
        return String.join("\n", jdbcTemplate.query(
                "EXPLAIN ANALYZE " + sql,
                binder::bind,
                (resultSet, rowNumber) -> resultSet.getString(1)));
    }

    private static long placeholders(String sql) {
        return sql.chars().filter(character -> character == '?').count();
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
