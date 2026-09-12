package app.pickple.auth.infra;

import app.pickple.auth.service.AccountWithdrawalPersistenceService;
import app.pickple.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #147: 실제 MySQL의 Flyway 적용과 제약을 검증한다. #123 작업 폴더의 추가 검증을 반영했다.
 * 기존 ContainerConfig를 재사용하고, fresh/upgrade/경합만 컨테이너 안의 독립 스키마에서 실행한다.
 * 공용 pickple 스키마는 Spring 테스트 트랜잭션으로 롤백하며 clean/drop 하지 않는다.
 */
@IntegrationTest
@Transactional
class TermsMigrationIT {

    // 정책 시행일이 아니라 테스트의 고정 시각이다.
    private static final LocalDateTime V1_START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime V2_START = V1_START.plusMonths(1);
    private static final String BODY = "# 이용약관\n\n회원의 '동의'를 보존합니다. ✅\n| 항목 | 내용 |\n|---|---|\n| 한글 | 본문 |";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MySQLContainer<?> mysql;
    @Autowired
    private AccountWithdrawalPersistenceService withdrawalPersistenceService;

    @Test
    @DisplayName("T-01·T-12 빈 DB에 V15까지 적용하고 재실행해도 약관·동의를 생성하지 않는다")
    void migratesFreshSchemaWithoutFabricatingTermsOrAgreements() {
        withIsolatedSchema(dataSource -> {
            Flyway migration = migrations(dataSource, "15");
            assertThat(migration.migrate().migrationsExecuted).isPositive();
            JdbcTemplate isolated = new JdbcTemplate(dataSource);

            assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("15");
            assertThat(count(isolated, "terms")).isZero();
            assertThat(count(isolated, "terms_agreement")).isZero();
            assertThat(migration.migrate().migrationsExecuted).isZero();
            migration.validate();
        });
    }

    @Test
    @DisplayName("T-02 V14에서 업그레이드하면 기존 회원과 Flyway 이력을 보존한다")
    void upgradesV14WithoutChangingExistingUsers() {
        withIsolatedSchema(dataSource -> {
            migrations(dataSource, "14").migrate();
            JdbcTemplate isolated = new JdbcTemplate(dataSource);
            long userId = insertUser(isolated);
            var before = isolated.queryForMap("SELECT * FROM users WHERE id = ?", userId);
            isolated.update("""
                    INSERT INTO user_refresh_token (user_id, token_hash, expires_at, created_at)
                    VALUES (?, ?, ?, ?)
                    """, userId, "a".repeat(64), V2_START, V1_START);
            var refreshBefore = isolated.queryForList("SELECT * FROM user_refresh_token");
            String columnsSql = """
                    SELECT table_name, column_name, ordinal_position, column_type, is_nullable,
                           column_default, extra, generation_expression, collation_name
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name NOT IN ('terms', 'terms_agreement', 'flyway_schema_history')
                    ORDER BY table_name, ordinal_position
                    """;
            String indexesSql = """
                    SELECT table_name, index_name, non_unique, seq_in_index, column_name, sub_part
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name NOT IN ('terms', 'terms_agreement', 'flyway_schema_history')
                    ORDER BY table_name, index_name, seq_in_index
                    """;
            var columnsBefore = isolated.queryForList(columnsSql);
            var indexesBefore = isolated.queryForList(indexesSql);
            var history = isolated.queryForList(
                    "SELECT version, checksum FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
            assertThat(isolated.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name IN ('terms', 'terms_agreement')
                    """, Long.class)).isZero();

            Flyway upgrade = migrations(dataSource, "15");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

            assertThat(isolated.queryForMap("SELECT * FROM users WHERE id = ?", userId)).isEqualTo(before);
            assertThat(isolated.queryForList("SELECT * FROM user_refresh_token")).isEqualTo(refreshBefore);
            assertThat(isolated.queryForList(columnsSql)).isEqualTo(columnsBefore);
            assertThat(isolated.queryForList(indexesSql)).isEqualTo(indexesBefore);
            assertThat(isolated.queryForList("""
                    SELECT version, checksum FROM flyway_schema_history
                    WHERE success = 1 AND version <> '15' ORDER BY installed_rank
                    """)).isEqualTo(history);
            assertThat(count(isolated, "terms")).isZero();
            assertThat(count(isolated, "terms_agreement")).isZero();
            upgrade.validate();
        });
    }

    @Test
    @DisplayName("T-03·T-08 본문을 그대로 보존하고 버전별 최초 동의를 각각 연결한다")
    void preservesContentAndAgreementsForEachVersion() {
        String type = uniqueType();
        long first = insertTerms(jdbc, type, "v1", V1_START, BODY);
        long second = insertTerms(jdbc, type, "v2", V2_START, "# 개정 본문\n다른 내용입니다.");
        long user = insertUser(jdbc);
        LocalDateTime acceptedV1 = V1_START.plusDays(1);
        LocalDateTime acceptedV2 = V2_START.plusDays(1);

        agree(jdbc, user, first, acceptedV1);
        agree(jdbc, user, second, acceptedV2);

        assertThat(jdbc.queryForList("""
                SELECT t.content FROM terms_agreement a JOIN terms t ON t.id = a.terms_id
                WHERE a.user_id = ? ORDER BY t.effective_at
                """, String.class, user)).containsExactly(BODY, "# 개정 본문\n다른 내용입니다.");
        assertThat(jdbc.query("""
                SELECT agreed_at FROM terms_agreement WHERE user_id = ? ORDER BY agreed_at
                """, (row, index) -> row.getObject(1, LocalDateTime.class), user))
                .containsExactly(acceptedV1, acceptedV2);
        assertThatThrownBy(() -> agree(jdbc, user, first, acceptedV1.plusDays(2)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_terms_agreement_user_terms");
        assertThat(jdbc.queryForObject(
                "SELECT agreed_at FROM terms_agreement WHERE user_id = ? AND terms_id = ?",
                LocalDateTime.class, user, first)).isEqualTo(acceptedV1);
    }

    @Test
    @DisplayName("T-04 같은 종류의 버전·시행 시각 중복을 거부하고 다른 종류는 허용한다")
    void rejectsAmbiguousVersionsAndEffectiveTimes() {
        String type = uniqueType();
        insertTerms(jdbc, type, "v1", V1_START, BODY);

        assertThatThrownBy(() -> insertTerms(jdbc, type, "v1", V2_START, BODY))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_terms_type_version");
        assertThatThrownBy(() -> insertTerms(jdbc, type, "v2", V1_START, BODY))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_terms_type_effective_at");
        assertThat(insertTerms(jdbc, uniqueType(), "v1", V1_START, BODY)).isPositive();
    }

    @Test
    @DisplayName("64KiB를 넘는 한국어 Markdown과 이모지도 손실 없이 보존한다")
    void preservesContentLargerThanTextCapacity() {
        String largeBody = BODY.repeat(2_000);
        assertThat(largeBody.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(65_535);

        long terms = insertTerms(jdbc, uniqueType(), "v1", V1_START, largeBody);

        assertThat(jdbc.queryForObject("SELECT content FROM terms WHERE id = ?", String.class, terms))
                .isEqualTo(largeBody);
    }

    @Test
    @DisplayName("약관 종류와 버전의 대소문자를 구분한다")
    void distinguishesCaseInTypeAndVersionIdentifiers() {
        String upperType = uniqueType().toUpperCase(Locale.ROOT);
        String lowerType = upperType.toLowerCase(Locale.ROOT);

        long upper = insertTerms(jdbc, upperType, "V1", V1_START, BODY);
        long lowerTypeId = insertTerms(jdbc, lowerType, "V1", V1_START, BODY);
        long lowerVersion = insertTerms(jdbc, upperType, "v1", V2_START, BODY);

        assertThat(List.of(upper, lowerTypeId, lowerVersion)).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT id FROM terms WHERE type = ? AND version = ?",
                Long.class, upperType, "V1")).isEqualTo(upper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "version", "title", "content", "is_required", "effective_at", "created_at"})
    @DisplayName("T-05 약관 필수 데이터의 NULL을 거부한다")
    void rejectsNullTermsFields(String column) {
        long terms = insertTerms(jdbc, uniqueType(), "v1", V1_START, BODY);
        // column은 외부 입력이 아니라 위 ValueSource의 고정된 컬럼명이다.
        assertThatThrownBy(() -> jdbc.update("UPDATE terms SET " + column + " = NULL WHERE id = ?", terms))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "version", "title", "content"})
    @DisplayName("T-05 약관의 빈 문자열과 일반 공백만 있는 값을 거부한다")
    void rejectsBlankTermsFields(String column) {
        long terms = insertTerms(jdbc, uniqueType(), "v1", V1_START, BODY);
        for (String blank : List.of("", "   ")) {
            assertDatabaseError(
                    () -> jdbc.update("UPDATE terms SET " + column + " = ? WHERE id = ?", blank, terms),
                    3819, "ck_terms_" + column + "_not_blank");
        }
    }

    @Test
    @DisplayName("T-05 필수 여부는 명시적인 0/1이어야 한다")
    void rejectsInvalidRequiredFlagAndAllowsOptionalTerms() {
        long terms = insertTerms(jdbc, uniqueType(), "v1", V1_START, BODY);
        assertDatabaseError(() -> jdbc.update("UPDATE terms SET is_required = 2 WHERE id = ?", terms),
                3819, "ck_terms_required_boolean");
        assertThat(jdbc.update("UPDATE terms SET is_required = 0 WHERE id = ?", terms)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_required FROM terms WHERE id = ?", Boolean.class, terms)).isFalse();
    }

    @Test
    @DisplayName("필수 여부를 생략하면 기본값으로 추정하지 않고 거부한다")
    void rejectsOmittedRequiredFlag() {
        assertDatabaseError(() -> jdbc.update("""
                INSERT INTO terms (type, version, title, content, effective_at, created_at)
                VALUES (?, 'v1', '테스트 약관', ?, ?, ?)
                """, uniqueType(), BODY, V1_START, V1_START), 1364, "is_required");
    }

    @Test
    @DisplayName("동의 시각을 생략하면 현재 시각으로 추정하지 않고 거부한다")
    void rejectsOmittedAgreementTime() {
        long user = insertUser(jdbc);
        long terms = insertTerms(jdbc, uniqueType(), "v1", V1_START, BODY);

        assertDatabaseError(() -> jdbc.update(
                "INSERT INTO terms_agreement (user_id, terms_id) VALUES (?, ?)", user, terms),
                1364, "agreed_at");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM terms_agreement WHERE user_id = ?",
                Long.class, user)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "terms_id"})
    @DisplayName("동의의 사용자·약관 참조는 명시적인 NULL도 거부한다")
    void rejectsNullAgreementReferences(String column) {
        long user = insertUser(jdbc);
        long terms = insertTerms(jdbc, uniqueType(), "v1", V1_START, BODY);
        agree(jdbc, user, terms, V1_START);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE terms_agreement SET " + column + " = NULL WHERE user_id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 버전을 여러 사용자가 수락하면 각자의 최초 동의를 보존한다")
    void permitsDifferentUsersToAgreeToTheSameVersion() {
        long firstUser = insertUser(jdbc);
        long secondUser = insertUser(jdbc);
        long terms = insertTerms(jdbc, uniqueType(), "v1", V1_START, BODY);

        agree(jdbc, firstUser, terms, V1_START);
        agree(jdbc, secondUser, terms, V1_START.plusSeconds(1));

        assertThat(jdbc.queryForList(
                "SELECT user_id FROM terms_agreement WHERE terms_id = ? ORDER BY agreed_at",
                Long.class, terms)).containsExactly(firstUser, secondUser);
    }

    @Test
    @DisplayName("T-05·T-06 동의의 필수값과 사용자·약관 FK를 검증한다")
    void rejectsMissingAgreementFieldsAndReferences() {
        long user = insertUser(jdbc);
        long terms = insertTerms(jdbc, uniqueType(), "v1", V1_START, BODY);
        assertThatThrownBy(() -> agree(jdbc, Long.MAX_VALUE, terms, V1_START))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("fk_terms_agreement_user");
        assertThatThrownBy(() -> agree(jdbc, user, Long.MAX_VALUE, V1_START))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("fk_terms_agreement_terms");
        assertThatThrownBy(() -> agree(jdbc, user, terms, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertDatabaseError(() -> jdbc.update(
                "INSERT INTO terms_agreement (terms_id, agreed_at) VALUES (?, ?)", terms, V1_START),
                1364, "user_id");
        assertDatabaseError(() -> jdbc.update(
                "INSERT INTO terms_agreement (user_id, agreed_at) VALUES (?, ?)", user, V1_START),
                1364, "terms_id");
    }

    @Test
    @DisplayName("T-07 별도 연결의 동시 동의 INSERT는 하나만 커밋된다")
    void concurrentDuplicateAgreementHasExactlyOneWinner() {
        withIsolatedSchema(dataSource -> {
            migrations(dataSource, "15").migrate();
            JdbcTemplate isolated = new JdbcTemplate(dataSource);
            long user = insertUser(isolated);
            long terms = insertTerms(isolated, uniqueType(), "v1", V1_START, BODY);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<String> contender = () -> {
                // 실제 연결 두 개를 확보한 뒤 동시에 INSERT한다. 각 연결은 autoCommit이다.
                try (var connection = dataSource.getConnection()) {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("동시 INSERT 시작 신호 시간 초과");
                    }
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO terms_agreement (user_id, terms_id, agreed_at) VALUES (?, ?, ?)")) {
                        statement.setLong(1, user);
                        statement.setLong(2, terms);
                        statement.setObject(3, V1_START);
                        statement.executeUpdate();
                        return "inserted";
                    } catch (SQLException error) {
                        assertThat(error.getErrorCode()).isEqualTo(1062);
                        assertThat(error.getMessage()).contains("uk_terms_agreement_user_terms");
                        return "duplicate";
                    }
                }
            };
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(contender);
                var second = executor.submit(contender);
                try {
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                } finally {
                    start.countDown();
                }
                assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder("inserted", "duplicate");
            } catch (Exception error) {
                throw new AssertionError("별도 연결 경합 검증 실패", error);
            }
            assertThat(count(isolated, "terms_agreement")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("T-09 현재 버전은 등록 시각이나 문자열 버전이 아닌 시행 시각으로 선택한다")
    void selectsCurrentVersionAtTheEffectiveBoundary() {
        String type = uniqueType();
        long first = insertTerms(jdbc, type, "v9", V1_START, BODY);
        long second = insertTerms(jdbc, type, "v10", V2_START, BODY);
        String currentSql = """
                SELECT id FROM terms WHERE type = ? AND effective_at <= ?
                ORDER BY effective_at DESC LIMIT 1
                """;

        assertThat(jdbc.queryForList(currentSql, Long.class, type, V1_START.minusSeconds(1))).isEmpty();
        assertThat(jdbc.queryForObject(currentSql, Long.class, type, V1_START)).isEqualTo(first);
        assertThat(jdbc.queryForObject(currentSql, Long.class, type, V2_START.minusSeconds(1))).isEqualTo(first);
        assertThat(jdbc.queryForObject(currentSql, Long.class, type, V2_START)).isEqualTo(second);
    }

    @Test
    @DisplayName("T-10·T-11 약관 삭제는 거부하고 사용자 물리 삭제만 동의를 정리한다")
    void distinguishesReferencedTermsPhysicalDeletionAndSoftWithdrawal() {
        long user = insertUser(jdbc);
        long terms = insertTerms(jdbc, uniqueType(), "v1", V1_START, BODY);
        agree(jdbc, user, terms, V1_START);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM terms WHERE id = ?", terms))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("fk_terms_agreement_terms");
        jdbc.update("UPDATE users SET state = 'INACTIVE' WHERE id = ?", user);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM terms_agreement WHERE user_id = ?", Long.class, user)).isEqualTo(1);

        jdbc.update("DELETE FROM users WHERE id = ?", user);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM terms_agreement WHERE user_id = ?", Long.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM terms WHERE id = ?", Long.class, terms)).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("실제 탈퇴 서비스는 개인정보를 파기하지만 동의 행을 자동 삭제하지 않는다")
    void withdrawalServiceCommitsPiiErasureWithoutDeletingAgreementHistory() {
        long user = insertUser(jdbc);
        long terms = insertTerms(jdbc, uniqueType(), "v1", V1_START, BODY);
        try {
            agree(jdbc, user, terms, V1_START);

            withdrawalPersistenceService.complete(user);

            var withdrawn = jdbc.queryForMap("SELECT state, name, provider_id FROM users WHERE id = ?", user);
            assertThat(withdrawn.get("state")).isEqualTo("INACTIVE");
            assertThat(withdrawn.get("name")).isNull();
            assertThat(withdrawn.get("provider_id")).isNull();
            assertThat(jdbc.queryForObject("""
                    SELECT agreed_at FROM terms_agreement WHERE user_id = ? AND terms_id = ?
                    """, LocalDateTime.class, user, terms)).isEqualTo(V1_START);
        } finally {
            // 이 테스트가 커밋한 픽스처만 식별자로 정리한다.
            jdbc.update("DELETE FROM users WHERE id = ?", user);
            jdbc.update("DELETE FROM terms WHERE id = ?", terms);
        }
    }

    private long insertTerms(JdbcTemplate target, String type, String version,
                             LocalDateTime effectiveAt, String content) {
        target.update("""
                INSERT INTO terms (type, version, title, content, is_required, effective_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, type, version, "테스트 약관", content, true, effectiveAt, V2_START.plusDays(1));
        return target.queryForObject("SELECT id FROM terms WHERE type = ? AND version = ?", Long.class, type, version);
    }

    private long insertUser(JdbcTemplate target) {
        String subject = "terms-it-" + UUID.randomUUID();
        target.update("""
                INSERT INTO users (provider, provider_id, name, state, created_at, updated_at)
                VALUES ('APPLE', ?, '약관 검증', 'ACTIVE', ?, ?)
                """, subject, V1_START, V1_START);
        return target.queryForObject(
                "SELECT id FROM users WHERE provider = 'APPLE' AND provider_id = ?", Long.class, subject);
    }

    private void agree(JdbcTemplate target, long user, long terms, LocalDateTime agreedAt) {
        target.update("INSERT INTO terms_agreement (user_id, terms_id, agreed_at) VALUES (?, ?, ?)",
                user, terms, agreedAt);
    }

    private long count(JdbcTemplate target, String table) {
        return target.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String uniqueType() {
        return "IT_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void assertDatabaseError(Runnable write, int errorCode, String detail) {
        // CHECK(3819)와 기본값 없는 필드(1364)는 HY000으로 와서 Spring이 Uncategorized로 분류한다.
        // 래퍼 클래스가 아니라 실제 MySQL 오류 코드와 대상 제약/필드를 확인한다.
        assertThatThrownBy(write::run).rootCause().isInstanceOfSatisfying(SQLException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(errorCode);
            assertThat(error.getSQLState()).isEqualTo("HY000");
            assertThat(error.getMessage()).contains(detail);
        });
    }

    private Flyway migrations(DataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(target).load();
    }

    private void withIsolatedSchema(Consumer<DataSource> test) {
        // 기존 Testcontainers MySQL 안에서만 만든다. 업무 DB·공용 pickple 스키마는 삭제하지 않는다.
        String schema = "terms_it_" + UUID.randomUUID().toString().replace("-", "");
        String endpoint = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        DataSource adminSource = new DriverManagerDataSource(
                endpoint + "mysql", "root", mysql.getPassword());
        JdbcTemplate admin = new JdbcTemplate(adminSource);
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            test.accept(new DriverManagerDataSource(endpoint + schema, "root", mysql.getPassword()));
        } finally {
            // 이름은 이 메서드가 생성한 ASCII UUID뿐이며 CREATE가 성공한 뒤에만 정리한다.
            admin.execute("DROP DATABASE " + schema);
        }
    }
}
