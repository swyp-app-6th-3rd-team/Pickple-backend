package app.pickple.auth.infra;

import app.pickple.auth.domain.Nickname;
import app.pickple.auth.domain.QaAccountStore;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.UserStore;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@IntegrationTest
class QaAccountCommandIT {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneId.of("Asia/Seoul"));
    @Autowired private DataSource dataSource;
    @Autowired private UserStore users;
    @Autowired private QaAccountStore accounts;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void createsIndependentUserAtomicallyAndSurvivesNewConnection() {
        String loginId = "qa-" + UUID.randomUUID();
        String hash = new BCryptPasswordEncoder(10).encode("qa-test-password");
        String nickname = UUID.randomUUID().toString().substring(0, 5);
        long id = QaAccountCommand.create(dataSource, loginId, hash, new Nickname(nickname), CLOCK);
        try {
            var user = users.findById(id).orElseThrow();
            assertThat(user.provider()).isEqualTo(SocialProvider.QA);
            assertThat(user.hasProfile()).isTrue();
            assertThat(user.email()).isNull();
            assertThat(accounts.findByLoginId(loginId).orElseThrow().userId()).isEqualTo(id);
            assertThat(accounts.findByLoginId(loginId.toUpperCase())).isEmpty();
            assertThat(jdbc.queryForObject("SELECT created_at FROM users WHERE id = ?", LocalDateTime.class, id))
                    .isEqualTo(LocalDateTime.of(2026, 9, 21, 21, 0));
            assertThat(jdbc.queryForObject("SELECT created_at FROM qa_account WHERE user_id = ?", LocalDateTime.class, id))
                    .isEqualTo(LocalDateTime.of(2026, 9, 21, 21, 0));

            int count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
            assertThatThrownBy(() -> QaAccountCommand.create(dataSource, loginId, hash,
                    new Nickname(UUID.randomUUID().toString().substring(0, 5)), CLOCK))
                    .isInstanceOf(DataIntegrityViolationException.class);
            String anotherLoginId = "qa-" + UUID.randomUUID();
            assertThatThrownBy(() -> QaAccountCommand.create(dataSource, anotherLoginId, hash,
                    new Nickname(nickname), CLOCK)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(accounts.findByLoginId(anotherLoginId)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(count);
            assertThat(accounts.findByLoginId(loginId).orElseThrow().passwordHash()).isEqualTo(hash);
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }
}
