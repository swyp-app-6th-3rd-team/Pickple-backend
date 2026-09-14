package app.pickple.item.infra;

import app.pickple.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class ProfileImageMigrationIT {
    @Autowired private MySQLContainer<?> mysql;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshAndUpgradePreserveDataAndConstraints(boolean upgrade) {
        String schema = "profile_it_" + UUID.randomUUID().toString().replace("-", "");
        String endpoint = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                endpoint + "mysql", "root", mysql.getPassword()));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            DataSource source = new DriverManagerDataSource(endpoint + schema, "root", mysql.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(source);
            if (upgrade) {
                migrate(source, "15");
                fixture(jdbc);
            }
            migrate(source, "16");
            if (!upgrade) {
                fixture(jdbc);
            }
            assertThat(jdbc.queryForObject("SELECT profile_image_url FROM users WHERE id = 1", String.class))
                    .isEqualTo("https://legacy.example/old.png");
            assertThat(jdbc.queryForList("SELECT attach_type FROM item_container ORDER BY id", String.class))
                    .containsExactly("PRODUCT", "COMMENT");
            insertContainer(jdbc, "PROFILE");
            assertThatThrownBy(() -> insertContainer(jdbc, "UNKNOWN"))
                    .hasStackTraceContaining("ck_container_type");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = 'item_resource'
                      AND index_name = 'idx_resource_access_url' AND column_name = 'access_url'
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.referential_constraints
                    WHERE constraint_schema = DATABASE() AND referenced_table_name = 'item_container'
                    """, Integer.class)).isEqualTo(3);
            assertThat(Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .target("16").load().migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16' AND success",
                    Integer.class)).isEqualTo(1);
        } finally {
            // 기존 컨테이너 안에서 이번 테스트가 만든 UUID 스키마만 정리한다.
            admin.execute("DROP DATABASE " + schema);
        }
    }

    private void migrate(DataSource source, String version) {
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target(version).load().migrate();
    }

    private void fixture(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO users (id, provider, provider_id, name, state, profile_image_url, created_at, updated_at)
                VALUES (1, 'GOOGLE', 'migration-test', '기존회원', 'ACTIVE', 'https://legacy.example/old.png', NOW(), NOW())
                """);
        insertContainer(jdbc, "PRODUCT");
        insertContainer(jdbc, "COMMENT");
    }

    private void insertContainer(JdbcTemplate jdbc, String type) {
        jdbc.update("""
                INSERT INTO item_container (user_id, attach_type, created_at, updated_at)
                VALUES (1, ?, NOW(), NOW())
                """, type);
    }
}
