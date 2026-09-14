package app.pickple.auth.infra;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.sql.PreparedStatement;
import java.util.Map;

/** V16: 미등록 도메인으로 저장한 기본 프로필만 환경에 맞는 공개 이미지 URL로 복구한다. */
public final class DefaultProfileImageMigration implements JavaMigration {

    private static final String UPDATE = """
            UPDATE users SET profile_image_url = ?
            WHERE state = 'ACTIVE' AND BINARY profile_image_url = BINARY ?
            """;

    private final Map<String, String> replacements;

    public DefaultProfileImageMigration(Map<String, String> replacements) {
        this.replacements = Map.copyOf(replacements);
    }

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("16");
    }

    @Override
    public String getDescription() {
        return "repair default profile image URLs";
    }

    @Override
    public Integer getChecksum() {
        // 환경별 CDN 주소가 달라도 동일한 마이그레이션이다.
        return UPDATE.hashCode();
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws Exception {
        try (PreparedStatement statement = context.getConnection().prepareStatement(UPDATE)) {
            for (Map.Entry<String, String> replacement : replacements.entrySet()) {
                statement.setString(1, replacement.getValue());
                statement.setString(2, replacement.getKey());
                statement.executeUpdate();
            }
        }
    }
}
