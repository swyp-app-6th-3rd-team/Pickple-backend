package app.pickple.auth.infra;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

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
 *
 * <p><b>공유 컨테이너를 쓰지 않는다.</b> 같은 서버에서 행을 대량으로 넣고 지우고 스키마를 버리면,
 * 공유 스키마를 건드리지 않았는데도 {@code PopularPostsIT} 의 실행 계획 단언이 전체 스위트에서
 * 결정적으로 깨졌다(이 클래스만 뺀 스위트는 통과). 파괴적인 마이그레이션 검증은 전용 서버에서 한다.
 * 스프링 컨텍스트도 필요 없다 — V17(Java)은 치환 목록 없이 직접 만든다.
 */
class ReleaseDataResetMigrationIT {
    private static final String HASH = "$2a$10$" + "a".repeat(53);

    /** {@code ContainerConfig} 와 같은 서버 옵션. 재사용하지 않는다. */
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--lower_case_table_names=0",
                    "--default-time-zone=+09:00");

    private final DefaultProfileImageMigration profileMigration = new DefaultProfileImageMigration(Map.of());

    @BeforeAll
    static void start() {
        MYSQL.start();
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @Test
    void emptyDatabaseAppliesV19AndKeepsMasterData() {
        withIsolatedSchema(source -> {
            Flyway flyway = migrations(source, "19");
            flyway.migrate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("19");
            assertThat(count(new JdbcTemplate(source), "badge")).isEqualTo(8);
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

            // 프로필: 지워질 이미지를 정확히 가리키던 URL 만 기본(NULL)으로 돌린다
            assertThat(profileUrl(jdbc, seed.qa.get(1))).isEqualTo(url("profile-images/qa.png"));
            assertThat(profileUrl(jdbc, seed.qa.get(2))).isNull();
            assertThat(profileUrl(jdbc, seed.qa.get(3))).isEqualTo(DEFAULT_URL);
            assertThat(profileUrl(jdbc, seed.qa.get(4))).isEqualTo(url("profile-images/AUTHOR.png"));

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

    /**
     * qa_account 가 비어 있으면 회원 전원이 지워진다. ADR-0053 이 마이그레이션 안에 가드를 두지 않고
     * 런북 사전 점검으로 막기로 한 트레이드오프를 고정한다 — 가드를 넣는다면 이 테스트를 의도적으로 바꾼다.
     */
    @Test
    void deletesEveryUserWhenQaAccountIsEmpty() {
        withIsolatedSchema(source -> {
            migrations(source, "18").migrate();
            var jdbc = new JdbcTemplate(source);
            user(jdbc, "KAKAO", "회원");
            user(jdbc, "QA", "짝없는QA");

            migrations(source, "19").migrate();

            assertThat(count(jdbc, "users")).isZero();
        });
    }

    private static final String DEFAULT_URL = "https://cdn.test/defaults/profile-2.png";

    private record Seed(List<Long> qa, long qaProfileContainer) {
    }

    /** QA 5명 사이에 일반 회원을 끼워 넣고, 모든 도메인 테이블과 FK 경로에 행을 만든다. */
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
        qa.add(user(jdbc, "QA", "QA라"));
        qa.add(user(jdbc, "QA", "QA마"));
        for (int i = 0; i < qa.size(); i++) {
            jdbc.update("""
                    INSERT INTO qa_account (login_id, password_hash, user_id, created_at, updated_at)
                    VALUES (?, ?, ?, NOW(), NOW())
                    """, "qa." + i, HASH, qa.get(i));
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

        // 프로필: QA(1) 본인 이미지 / QA(2) 일반 회원 이미지 / QA(3) 기본 이미지 / QA(4) 대소문자만 다른 URL
        long qaProfile = container(jdbc, qa.get(1), "PROFILE", "profile-images/qa.png");
        container(jdbc, author, "PROFILE", "profile-images/author.png");
        setProfile(jdbc, qa.get(1), url("profile-images/qa.png"));
        setProfile(jdbc, qa.get(2), url("profile-images/author.png"));
        setProfile(jdbc, qa.get(3), DEFAULT_URL);
        setProfile(jdbc, qa.get(4), url("profile-images/AUTHOR.png"));

        // 활동: QA(0) 의 찬반 글에 일반 회원이 투표·이미지 댓글·원픽, QA(0) 도 일반 회원의 A/B 글에 투표
        long qaPost = agreePost(jdbc, qa.get(0));
        long abPost = abPost(jdbc, author);
        vote(jdbc, qaPost, author);
        vote(jdbc, abPost, qa.get(0));
        long commentImage = container(jdbc, picker, "COMMENT", "product-images/comment.png");
        jdbc.update("""
                INSERT INTO comment (post_id, user_id, item_container_id, content, created_at, updated_at)
                VALUES (?, ?, ?, '댓글', NOW(), NOW())
                """, qaPost, picker, commentImage);
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
        return new Seed(qa, qaProfile);
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

    /** 찬반 글. 선택지는 라벨만 갖는다. */
    private long agreePost(JdbcTemplate jdbc, long userId) {
        long id = post(jdbc, userId, "AGREE");
        jdbc.update("INSERT INTO post_option (post_id, label, display_order, created_at) VALUES (?, '사자', 1, NOW())", id);
        return id;
    }

    /** A/B 글. 선택지가 상품을 참조하고(fk_option_product), 상품이 사진 컨테이너를 참조한다. */
    private long abPost(JdbcTemplate jdbc, long userId) {
        long id = post(jdbc, userId, "A_B");
        for (int order = 1; order <= 2; order++) {
            long image = container(jdbc, userId, "PRODUCT", "product-images/" + id + "-" + order + ".png");
            jdbc.update("""
                    INSERT INTO post_product (post_id, item_container_id, name, display_order, created_at, updated_at)
                    VALUES (?, ?, '상품', ?, NOW(), NOW())
                    """, id, image, order);
            jdbc.update("""
                    INSERT INTO post_option (post_id, post_product_id, display_order, created_at)
                    VALUES (?, ?, ?, NOW())
                    """, id, lastId(jdbc, "post_product"), order);
        }
        return id;
    }

    private long post(JdbcTemplate jdbc, long userId, String type) {
        jdbc.update("""
                INSERT INTO post (user_id, type, category, title, created_at, updated_at)
                VALUES (?, ?, 'ETC', '글', NOW(), NOW())
                """, userId, type);
        return lastId(jdbc, "post");
    }

    private void vote(JdbcTemplate jdbc, long postId, long userId) {
        jdbc.update("""
                INSERT INTO vote (post_id, post_option_id, user_id, created_at)
                SELECT ?, MIN(id), ?, NOW() FROM post_option WHERE post_id = ?
                """, postId, userId, postId);
    }

    private static void setProfile(JdbcTemplate jdbc, long userId, String url) {
        jdbc.update("UPDATE users SET profile_image_url = ? WHERE id = ?", url, userId);
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

    private static void withIsolatedSchema(Consumer<DataSource> test) {
        String schema = "reset_it_" + UUID.randomUUID().toString().replace("-", "");
        String endpoint = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/";
        var admin = new JdbcTemplate(new DriverManagerDataSource(endpoint + "mysql", "root", MYSQL.getPassword()));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            test.accept(new DriverManagerDataSource(endpoint + schema, "root", MYSQL.getPassword()));
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }
}
