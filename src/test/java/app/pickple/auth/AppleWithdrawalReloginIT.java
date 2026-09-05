package app.pickple.auth;

import app.pickple.auth.apple.AppleIdentity;
import app.pickple.auth.domain.Nickname;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AccountWithdrawalPersistenceService;
import app.pickple.auth.service.AuthService;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Apple revoke 성공 뒤 로컬 탈퇴부터 동일 sub 재가입까지를 실제 MySQL 스키마로 검증한다. */
@IntegrationTest
@Transactional
class AppleWithdrawalReloginIT {

    /** V12의 기존 탈퇴 Apple 사용자 소급 분리 문장. */
    private static final String DETACH_WITHDRAWN_APPLE_IDENTITIES = """
            UPDATE users
               SET provider_id = NULL,
                   updated_at = NOW()
             WHERE provider = 'APPLE'
               AND state = 'INACTIVE'
            """;

    @Autowired
    private UserStore userStore;
    @Autowired
    private AccountWithdrawalPersistenceService withdrawalPersistenceService;
    @Autowired
    private AuthService authService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Apple 탈퇴 후 같은 sub로 로그인하면 과거 행이 아니라 새 사용자가 생성된다")
    void sameAppleSubCreatesNewUserAfterWithdrawal() {
        User oldUser = new User(
                SocialProvider.APPLE, "apple-sub-rejoin", "old@example.com", "기존이름");
        oldUser.registerProfile(new Nickname("옛피클"), "https://cdn.example.com/old-profile.png");
        oldUser = userStore.save(oldUser);
        Long oldUserId = oldUser.id();

        withdrawalPersistenceService.complete(oldUserId);

        User withdrawn = userStore.findById(oldUserId).orElseThrow();
        assertThat(withdrawn.state()).isEqualTo(User.State.INACTIVE);
        assertThat(withdrawn.providerId()).isNull();
        assertThat(withdrawn.nickname()).isEqualTo(new Nickname("옛피클"));
        assertThat(withdrawn.profileImageUrl()).isEqualTo("https://cdn.example.com/old-profile.png");
        assertThat(userStore.findByProviderAndProviderId(
                SocialProvider.APPLE, "apple-sub-rejoin")).isEmpty();

        User rejoined = authService.loginOrRegister(
                new AppleIdentity("apple-sub-rejoin", "new@example.com", "새이름"));

        assertThat(rejoined.id()).isNotEqualTo(oldUserId);
        assertThat(rejoined.provider()).isEqualTo(SocialProvider.APPLE);
        assertThat(rejoined.providerId()).isEqualTo("apple-sub-rejoin");
        assertThat(rejoined.state()).isEqualTo(User.State.ACTIVE);
        assertThat(rejoined.email()).isEqualTo("new@example.com");
        assertThat(rejoined.name()).isEqualTo("새이름");
        assertThat(rejoined.hasProfile()).isFalse();
        assertThat(rejoined.nickname()).isNull();
        assertThat(rejoined.profileImageUrl()).isNull();
        assertThat(userStore.findByProviderAndProviderId(
                SocialProvider.APPLE, "apple-sub-rejoin"))
                .get()
                .extracting(User::id)
                .isEqualTo(rejoined.id());
    }

    @Test
    @DisplayName("V12 백필은 기존 탈퇴 Apple 사용자만 소셜 식별자를 분리한다")
    void migrationBackfillDetachesOnlyWithdrawnAppleIdentity() {
        jdbcTemplate.update("""
                INSERT INTO users
                    (provider, provider_id, email, name, role, state, created_at, updated_at)
                VALUES
                    ('APPLE', 'legacy-apple-sub', NULL, '기존 Apple 회원', 'ROLE_USER',
                     'INACTIVE', NOW(), NOW()),
                    ('GOOGLE', 'legacy-google-sub', NULL, '기존 Google 회원', 'ROLE_USER',
                     'INACTIVE', NOW(), NOW())
                """);

        jdbcTemplate.update(DETACH_WITHDRAWN_APPLE_IDENTITIES);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_id FROM users WHERE provider = 'APPLE' AND name = '기존 Apple 회원'",
                String.class)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_id FROM users WHERE provider = 'GOOGLE' AND name = '기존 Google 회원'",
                String.class)).isEqualTo("legacy-google-sub");
    }

    @Test
    @DisplayName("V12 제약은 활성 사용자의 null 소셜 식별자를 거부한다")
    void activeUserCannotHaveNullProviderId() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users
                    (provider, provider_id, email, name, role, state, created_at, updated_at)
                VALUES ('APPLE', NULL, NULL, '잘못된 활성 회원', 'ROLE_USER',
                        'ACTIVE', NOW(), NOW())
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_users_active_provider_id");
    }
}
