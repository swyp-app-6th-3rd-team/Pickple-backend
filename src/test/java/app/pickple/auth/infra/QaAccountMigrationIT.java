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
class QaAccountMigrationIT {
    @Autowired private MySQLContainer<?> mysql;
    @Autowired private DefaultProfileImageMigration profileMigration;
    @Autowired private Flyway applicationFlyway;

    @Test
    void freshDatabaseRegistersV18AndValidatesJpaSchema() {
        applicationFlyway.validate();
        assertThat(applicationFlyway.info().current().getVersion().getVersion()).isEqualTo("18");
    }

    @Test
    void upgradeFromV17PreservesSocialUsersAndCanBeRepeated() {
        withIsolatedSchema(source -> {
            migrations(source, "17").migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("""
                    INSERT INTO users (provider, provider_id, role, state, nickname, created_at, updated_at)
                    VALUES ('KAKAO', 'migration-social-user', 'ROLE_USER', 'ACTIVE', '기존회원', NOW(), NOW()),
                           ('APPLE', NULL, 'ROLE_USER', 'INACTIVE', NULL, NOW(), NOW())
                    """);
            var before = jdbc.queryForList("SELECT * FROM users ORDER BY id");
            Flyway upgrade = migrations(source, "18");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM qa_account", Integer.class)).isZero();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            upgrade.validate();
        });
    }

    private Flyway migrations(DataSource source, String target) {
        return Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .javaMigrations(profileMigration).target(target).load();
    }

    private void withIsolatedSchema(Consumer<DataSource> test) {
        String schema = "qa_it_" + UUID.randomUUID().toString().replace("-", "");
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
