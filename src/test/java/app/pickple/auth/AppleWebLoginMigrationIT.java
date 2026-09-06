package app.pickple.auth;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AppleWebLoginMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pickple")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--lower_case_table_names=0",
                    "--default-time-zone=+09:00");

    @Test
    void v14PreservesExistingNativeGrantAndAllowsWebGrantAlongsideIt() throws Exception {
        flyway(MigrationVersion.fromVersion("13")).migrate();

        long userId;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement user = connection.prepareStatement("""
                     INSERT INTO users(provider, provider_id, email, name, role, state, created_at, updated_at)
                     VALUES ('APPLE', 'legacy-sub', 'relay@example.com', 'legacy', 'ROLE_USER', 'ACTIVE', NOW(), NOW())
                     """, Statement.RETURN_GENERATED_KEYS)) {
            user.executeUpdate();
            try (ResultSet keys = user.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                userId = keys.getLong(1);
            }
        }
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement token = connection.prepareStatement("""
                     INSERT INTO apple_provider_token(
                         user_id, encryption_format_version, encrypted_refresh_token,
                         encryption_iv, encryption_key_id, created_at, updated_at)
                     VALUES (?, 1, 'legacy-ciphertext', 'legacy-iv', 'k1', NOW(), NOW())
                     """)) {
            token.setLong(1, userId);
            token.executeUpdate();
        }

        flyway(null).migrate();

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement nativeGrant = connection.prepareStatement("""
                     SELECT client_type, encryption_format_version, encrypted_refresh_token
                       FROM apple_provider_token
                      WHERE user_id = ?
                     """)) {
            nativeGrant.setLong(1, userId);
            try (ResultSet row = nativeGrant.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("client_type")).isEqualTo("NATIVE");
                assertThat(row.getInt("encryption_format_version")).isEqualTo(1);
                assertThat(row.getString("encrypted_refresh_token")).isEqualTo("legacy-ciphertext");
                assertThat(row.next()).isFalse();
            }
        }

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement webGrant = connection.prepareStatement("""
                     INSERT INTO apple_provider_token(
                         user_id, client_type, encryption_format_version, encrypted_refresh_token,
                         encryption_iv, encryption_key_id, created_at, updated_at)
                     VALUES (?, 'WEB', 2, 'web-ciphertext', 'web-iv', 'k1', NOW(), NOW())
                     """)) {
            webGrant.setLong(1, userId);
            assertThat(webGrant.executeUpdate()).isEqualTo(1);
        }

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement count = connection.prepareStatement(
                     "SELECT COUNT(*) FROM apple_provider_token WHERE user_id = ?")) {
            count.setLong(1, userId);
            try (ResultSet row = count.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(2);
            }
        }

        assertThatThrownBy(() -> insertUnsupportedClient(userId))
                .isInstanceOf(SQLException.class);
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void insertUnsupportedClient(long userId) throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement invalid = connection.prepareStatement("""
                     INSERT INTO apple_provider_token(
                         user_id, client_type, encryption_format_version, encrypted_refresh_token,
                         encryption_iv, encryption_key_id, created_at, updated_at)
                     VALUES (?, 'UNKNOWN', 2, 'ciphertext', 'iv', 'k1', NOW(), NOW())
                     """)) {
            invalid.setLong(1, userId);
            invalid.executeUpdate();
        }
    }
}
