package app.pickple.auth.infra;

import app.pickple.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class DefaultProfileImageMigrationIT {

    @Autowired private MySQLContainer<?> mysql;
    @Autowired private DefaultProfileImageMigration migration;
    @Autowired private Flyway applicationFlyway;

    @Test
    void springRegistersV16AndFreshDatabaseValidates() {
        assertThat(applicationFlyway.info().all())
                .extracting(info -> info.getVersion() == null ? null : info.getVersion().getVersion())
                .contains("16");
        withIsolatedSchema(source -> {
            Flyway flyway = migrations(source, "16");
            flyway.migrate();
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        });
    }

    @Test
    void upgradesOnlyExactActiveLegacyImagesAndPreservesEveryOtherUserColumn() {
        withIsolatedSchema(source -> {
            migrations(source, "15").migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            for (int index = 1; index <= 4; index++) {
                insert(jdbc, "legacy-" + index, "ACTIVE",
                        "https://images.pickple.app/defaults/profile-" + index + ".png");
            }
            insert(jdbc, "custom", "ACTIVE", "https://uploads.example.com/my-photo.png");
            insert(jdbc, "null", "ACTIVE", null);
            insert(jdbc, "blank", "ACTIVE", "");
            insert(jdbc, "different-case", "ACTIVE", "https://images.pickple.app/defaults/PROFILE-2.png");
            insert(jdbc, "different-query", "ACTIVE", "https://images.pickple.app/defaults/profile-2.png?x=1");
            insert(jdbc, "withdrawn", "INACTIVE", null);
            insert(jdbc, "inactive-legacy", "INACTIVE", "https://images.pickple.app/defaults/profile-2.png");
            var before = jdbc.queryForList("SELECT * FROM users ORDER BY id");

            Flyway flyway = migrations(source, "16");
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

            var after = jdbc.queryForList("SELECT * FROM users ORDER BY id");
            assertThat(after).hasSameSizeAs(before);
            for (int index = 0; index < before.size(); index++) {
                var original = before.get(index);
                var actual = after.get(index);
                if (index < 4) {
                    // application-test.yml의 두 후보와 SQL 픽스처에 근거한 독립 기대값이다.
                    original.put("profile_image_url", "https://images.local.test/defaults/profile-"
                            + (index % 2 + 1) + ".png");
                }
                assertThat(actual).isEqualTo(original);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            flyway.validate();
        });
    }

    private Flyway migrations(DataSource source, String target) {
        return Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .javaMigrations(migration).target(target).load();
    }

    private void insert(JdbcTemplate jdbc, String subject, String state, String image) {
        jdbc.update("""
                INSERT INTO users (provider, provider_id, name, state, profile_image_url, created_at, updated_at)
                VALUES ('APPLE', ?, '이미지 검증', ?, ?, '2026-09-14 12:00:00', '2026-09-14 12:00:00')
                """, subject, state, image);
    }

    private void withIsolatedSchema(Consumer<DataSource> test) {
        // 공용 테스트 스키마를 변경하지 않고 기존 MySQL 컨테이너만 재사용한다.
        String schema = "profile_it_" + UUID.randomUUID().toString().replace("-", "");
        String endpoint = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                endpoint + "mysql", "root", mysql.getPassword()));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            test.accept(new DriverManagerDataSource(endpoint + schema, "root", mysql.getPassword()));
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }
}
