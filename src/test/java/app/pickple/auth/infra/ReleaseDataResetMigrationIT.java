package app.pickple.auth.infra;

import app.pickple.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V19 출시 데이터 초기화를 확인한다 (#187, ADR-0053).
 *
 * <p>통합 테스트는 빈 스키마에 마이그레이션을 적용하므로 평소에는 V19 가 0행을 지우는 것만 검증된다.
 * 그래서 격리 스키마를 V18 까지 올리고 데이터를 넣은 뒤 <b>실제 V19 파일</b>을 적용한다 —
 * {@code BadgeBackfillIT} 처럼 문장을 복제하지 않으므로 파일과 테스트가 갈라질 수 없다.
 *
 * <p>보존 대상은 ID 가 아니라 "qa_account 가 가리키는 users" 다. 이를 증명하려고 QA 사이에
 * 일반 회원을 끼워 넣어 ID 가 연속하지 않게 만든다.
 */
@IntegrationTest
class ReleaseDataResetMigrationIT {
    private static final String HASH = "$2a$10$" + "a".repeat(53);

    @Autowired private MySQLContainer<?> mysql;
    @Autowired private DefaultProfileImageMigration profileMigration;

    @Test
    void emptyDatabaseAppliesV19AndKeepsMasterData() {
        withIsolatedSchema(source -> {
            Flyway flyway = migrations(source, "19");
            flyway.migrate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("19");
            var jdbc = new JdbcTemplate(source);
            assertThat(count(jdbc, "badge")).isEqualTo(8);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            flyway.validate();
        });
    }

    @Test
    void keepsOnlyUsersReferencedByQaAccountAndTheirCredentials() {
        withIsolatedSchema(source -> {
            migrations(source, "18").migrate();
            var jdbc = new JdbcTemplate(source);
            Seed seed = seed(jdbc);
            List<Long> qaIdsBefore = ids(jdbc, "SELECT user_id FROM qa_account ORDER BY user_id");
            var qaAccountsBefore = jdbc.queryForList("SELECT * FROM qa_account ORDER BY login_id");
            var identityBefore = qaIdentity(jdbc);
            long badges = count(jdbc, "badge");

            assertThat(migrations(source, "19").migrate().migrationsExecuted).isEqualTo(1);

            // 보존: qa_account 가 가리키는 users 와 그 계정·인증 정보
            assertThat(ids(jdbc, "SELECT id FROM users ORDER BY id")).isEqualTo(qaIdsBefore);
            assertThat(jdbc.queryForList("SELECT * FROM qa_account ORDER BY login_id")).isEqualTo(qaAccountsBefore);
            assertThat(qaIdentity(jdbc)).isEqualTo(identityBefore);
            assertThat(ids(jdbc, "SELECT user_id FROM user_refresh_token")).containsExactly(seed.qa.get(0));
            assertThat(ids(jdbc, "SELECT user_id FROM terms_agreement")).containsExactly(seed.qa.get(0));
            assertThat(ids(jdbc, "SELECT user_id FROM apple_provider_token")).containsExactly(seed.qa.get(1));
            assertThat(ids(jdbc, "SELECT id FROM item_container")).containsExactly(seed.qaProfileContainer);
            assertThat(count(jdbc, "item_resource")).isEqualTo(1);
            assertThat(count(jdbc, "terms")).isEqualTo(1);
            assertThat(count(jdbc, "badge")).isEqualTo(badges);

            // 프로필: 본인 이미지는 유지, 지워진 타인 이미지를 가리키던 URL 은 기본(NULL)으로
            assertThat(profileUrl(jdbc, seed.qa.get(1))).isEqualTo(seed.qaProfileUrl);
            assertThat(profileUrl(jdbc, seed.qa.get(2))).isNull();

            // 삭제: 활동 전체(QA 활동 포함)
            for (String table : List.of("post", "post_product", "post_option", "vote", "comment", "comment_pick",
                    "post_commenter", "point_history", "user_badge", "user_daily_activity")) {
                assertThat(count(jdbc, table)).as(table).isZero();
            }
            // QA 파생 컬럼 초기화
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM users
                     WHERE point <> 0 OR vote_count <> 0 OR ranking IS NOT NULL OR highest_grade <> 1
                    """, Long.class)).isZero();

            assertThat(migrations(source, "19").migrate().migrationsExecuted).isZero();
            migrations(source, "19").validate();
        });
    }

    private record Seed(List<Long> qa, long qaProfileContainer, String qaProfileUrl) {
    }

    /** QA 3명 사이에 일반 회원을 끼워 넣고, 모든 도메인 테이블에 행을 만든다. */
    private Seed seed(JdbcTemplate jdbc) {
        List<Long> qa = new ArrayList<>();
        qa.add(user(jdbc, "QA", "QA가"));
        long author = user(jdbc, "KAKAO", "작성자");
        qa.add(user(jdbc, "QA", "QA나"));
        long picker = user(jdbc, "APPLE", "픽커");
        qa.add(user(jdbc, "QA", "QA다"));
        jdbc.update("""
                INSERT INTO users (provider, provider_id, role, state, created_at, updated_at)
                VALUES ('KAKAO', NULL, 'ROLE_USER', 'INACTIVE', NOW(), NOW())
                """);
        for (int i = 0; i < qa.size(); i++) {
            jdbc.update("INSERT INTO qa_account VALUES (?, ?, ?, NOW(), NOW())", "qa." + i, HASH, qa.get(i));
        }
        jdbc.update("UPDATE users SET point = 30, vote_count = 4, ranking = 2, highest_grade = 3");

        // 인증 부속: QA(0) refresh·약관 동의, QA(1) apple 토큰, 일반 회원도 각각 하나씩
        jdbc.update("""
                INSERT INTO terms (type, version, title, content, is_required, effective_at, created_at)
                VALUES ('SERVICE', '1.0', '이용약관', '본문', TRUE, NOW(), NOW())
                """);
        long terms = lastId(jdbc, "terms");
        for (long userId : List.of(qa.get(0), author)) {
            jdbc.update("INSERT INTO user_refresh_token (user_id, token_hash, expires_at, created_at) VALUES (?, ?, NOW(), NOW())",
                    userId, UUID.randomUUID().toString().replace("-", "") + "0".repeat(32));
            jdbc.update("INSERT INTO terms_agreement (user_id, terms_id, agreed_at) VALUES (?, ?, NOW())", userId, terms);
        }
        for (long userId : List.of(qa.get(1), picker)) {
            jdbc.update("""
                    INSERT INTO apple_provider_token (user_id, encryption_format_version, encrypted_refresh_token,
                                                      encryption_iv, encryption_key_id, created_at, updated_at)
                    VALUES (?, 1, 'cipher', 'AAAAAAAAAAAAAAAA', 'key', NOW(), NOW())
                    """, userId);
        }

        // 이미지: QA(1) 본인 PROFILE 은 보존, QA(2) 는 일반 회원의 PROFILE 을 가리킨다
        long qaProfile = container(jdbc, qa.get(1), "PROFILE", "profile-images/qa.png");
        long authorProfile = container(jdbc, author, "PROFILE", "profile-images/author.png");
        jdbc.update("UPDATE users SET profile_image_url = ? WHERE id = ?", url("profile-images/qa.png"), qa.get(1));
        jdbc.update("UPDATE users SET profile_image_url = ? WHERE id = ?", url("profile-images/author.png"), qa.get(2));
        assertThat(authorProfile).isPositive();

        // 활동: QA(0) 의 글에 일반 회원이 투표·댓글·원픽, QA(0) 도 일반 회원 글에 투표
        long qaPost = post(jdbc, qa.get(0));
        long authorPost = post(jdbc, author);
        vote(jdbc, qaPost, author);
        vote(jdbc, authorPost, qa.get(0));
        long productContainer = container(jdbc, author, "PRODUCT", "product-images/a.png");
        jdbc.update("""
                INSERT INTO post_product (post_id, item_container_id, name, display_order, created_at, updated_at)
                VALUES (?, ?, '상품', 1, NOW(), NOW())
                """, authorPost, productContainer);
        jdbc.update("INSERT INTO comment (post_id, user_id, content, created_at, updated_at) VALUES (?, ?, '댓글', NOW(), NOW())",
                qaPost, picker);
        long comment = lastId(jdbc, "comment");
        jdbc.update("INSERT INTO post_commenter (post_id, user_id, created_at) VALUES (?, ?, NOW())", qaPost, picker);
        jdbc.update("INSERT INTO comment_pick (post_id, comment_id, user_id, created_at) VALUES (?, ?, ?, NOW())",
                qaPost, comment, qa.get(0));
        long pick = lastId(jdbc, "comment_pick");
        jdbc.update("""
                INSERT INTO point_history (user_id, amount, reason, comment_pick_id, created_at)
                VALUES (?, 10, 'PICKED', ?, NOW()), (?, 5, 'PICKING', ?, NOW())
                """, picker, pick, qa.get(0), pick);
        jdbc.update("INSERT INTO user_badge (user_id, badge_id, acquired_at) SELECT ?, MIN(id), NOW() FROM badge", qa.get(0));
        jdbc.update("""
                INSERT INTO user_daily_activity (user_id, activity_date, vote_count, created_at, updated_at)
                VALUES (?, CURDATE(), 1, NOW(), NOW()), (?, CURDATE(), 1, NOW(), NOW())
                """, qa.get(0), author);
        return new Seed(qa, qaProfile, url("profile-images/qa.png"));
    }

    private long user(JdbcTemplate jdbc, String provider, String nickname) {
        jdbc.update("""
                INSERT INTO users (provider, provider_id, role, state, nickname, created_at, updated_at)
                VALUES (?, ?, 'ROLE_USER', 'ACTIVE', ?, NOW(), NOW())
                """, provider, UUID.randomUUID().toString(), nickname);
        return lastId(jdbc, "users");
    }

    private long container(JdbcTemplate jdbc, long userId, String type, String key) {
        jdbc.update("INSERT INTO item_container (user_id, attach_type, created_at, updated_at) VALUES (?, ?, NOW(), NOW())",
                userId, type);
        long id = lastId(jdbc, "item_container");
        jdbc.update("""
                INSERT INTO item_resource (item_container_id, size, original_file_name, item_key, access_url, created_at, updated_at)
                VALUES (?, 1, 'f.png', ?, ?, NOW(), NOW())
                """, id, key, url(key));
        return id;
    }

    private long post(JdbcTemplate jdbc, long userId) {
        jdbc.update("""
                INSERT INTO post (user_id, type, category, title, created_at, updated_at)
                VALUES (?, 'AGREE', 'ETC', '글', NOW(), NOW())
                """, userId);
        long id = lastId(jdbc, "post");
        jdbc.update("INSERT INTO post_option (post_id, label, display_order, created_at) VALUES (?, '사자', 1, NOW())", id);
        return id;
    }

    private void vote(JdbcTemplate jdbc, long postId, long userId) {
        jdbc.update("""
                INSERT INTO vote (post_id, post_option_id, user_id, created_at)
                SELECT ?, id, ?, NOW() FROM post_option WHERE post_id = ?
                """, postId, userId, postId);
    }

    private static String url(String key) {
        return "https://cdn.test/" + key;
    }

    /** DriverManagerDataSource 는 호출마다 커넥션을 새로 열어 LAST_INSERT_ID() 가 0 이 된다. 단일 스레드라 MAX 로 충분하다. */
    private static long lastId(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT MAX(id) FROM " + table, Long.class);
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static List<Long> ids(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForList(sql, Long.class);
    }

    private static String profileUrl(JdbcTemplate jdbc, long userId) {
        return jdbc.queryForObject("SELECT profile_image_url FROM users WHERE id = ?", String.class, userId);
    }

    /** 초기화 대상인 파생 컬럼·updated_at·profile_image_url 을 뺀 QA 신원 정보. */
    private static List<Map<String, Object>> qaIdentity(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT u.id, u.provider, u.provider_id, u.role, u.state, u.nickname, u.created_at
                  FROM users u JOIN qa_account q ON q.user_id = u.id ORDER BY u.id
                """);
    }

    private Flyway migrations(DataSource source, String target) {
        return Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .javaMigrations(profileMigration).target(target).load();
    }

    private void withIsolatedSchema(Consumer<DataSource> test) {
        String schema = "reset_it_" + UUID.randomUUID().toString().replace("-", "");
        String endpoint = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        var admin = new JdbcTemplate(new DriverManagerDataSource(endpoint + "mysql", "root", mysql.getPassword()));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            test.accept(new DriverManagerDataSource(endpoint + schema, "root", mysql.getPassword()));
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }
}
