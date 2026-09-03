package app.pickple.auth.infra;

import app.pickple.auth.domain.Nickname;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 닉네임 유일성이 <b>스키마</b>에서 지켜지는지 확인한다 (R-21 · R-23).
 *
 * <p>애플리케이션 검사를 지나쳐도 제약이 막아야 한다. 그래서 여기서는 서비스가 아니라
 * 저장소와 SQL 을 직접 본다.
 */
@IntegrationTest
class JpaUserStoreNicknameIT {

    @Autowired
    private UserStore userStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long seed;

    @BeforeEach
    void setUp() {
        seed = System.nanoTime();
    }

    private User newUser(String suffix) {
        return userStore.save(new User(SocialProvider.GOOGLE, suffix + "-" + seed, null, "테스터"));
    }

    private String nickname(String prefix) {
        return prefix + Long.toString(Math.abs(seed) % 1000, 36);
    }

    private User withProfile(User user, String nickname) {
        user.registerProfile(new Nickname(nickname), "https://cdn/x.png");
        return userStore.saveProfileIfNicknameFree(user).orElseThrow();
    }

    @Test
    @DisplayName("active_nickname 은 state 를 따라간다")
    void generatedColumnFollowsState() {
        User user = newUser("gen");
        String nick = nickname("g");
        try {
            withProfile(user, nick);
            assertThat(activeNickname(user.id())).isEqualTo(nick);

            jdbcTemplate.update("UPDATE users SET state = 'INACTIVE' WHERE id = ?", user.id());
            // 생성 컬럼이 deleted_at 을 보던 시절에는 여기가 그대로 nick 이었다 —
            // 그래서 탈퇴해도 닉네임이 잠겼다. state 를 보게 바꾼 것이 V5 다.
            assertThat(activeNickname(user.id())).isNull();

            jdbcTemplate.update("UPDATE users SET state = 'ACTIVE' WHERE id = ?", user.id());
            assertThat(activeNickname(user.id())).isEqualTo(nick);
        } finally {
            delete(user.id());
        }
    }

    @Test
    @DisplayName("활성 회원 둘이 같은 닉네임을 가질 수 없다 (R-23)")
    void uniqueAmongActiveUsers() {
        User first = newUser("a");
        User second = newUser("b");
        String nick = nickname("u");
        try {
            withProfile(first, nick);

            second.registerProfile(new Nickname(nick), null);
            assertThat(userStore.saveProfileIfNicknameFree(second)).isEmpty();
            assertThat(countActive(nick)).isEqualTo(1);
        } finally {
            delete(second.id());
            delete(first.id());
        }
    }

    @Test
    @DisplayName("탈퇴자끼리는 같은 닉네임이어도 충돌하지 않는다 (R-21)")
    void withdrawnUsersDoNotCollide() {
        User first = newUser("w1");
        User second = newUser("w2");
        String nick = nickname("w");
        try {
            withProfile(first, nick);
            jdbcTemplate.update("UPDATE users SET state = 'INACTIVE' WHERE id = ?", first.id());

            withProfile(second, nick);
            jdbcTemplate.update("UPDATE users SET state = 'INACTIVE' WHERE id = ?", second.id());

            // 유니크 키는 NULL 을 서로 다르게 취급하므로 탈퇴자 둘이 공존한다.
            assertThat(countActive(nick)).isZero();
            assertThat(rawNicknameCount(nick)).isEqualTo(2);
        } finally {
            delete(second.id());
            delete(first.id());
        }
    }

    @Test
    @DisplayName("본인이 쓰던 닉네임은 다시 내도 통과한다")
    void ownNicknameIsNotConflict() {
        User user = newUser("self");
        String nick = nickname("s");
        try {
            withProfile(user, nick);

            User reloaded = userStore.findById(user.id()).orElseThrow();
            reloaded.registerProfile(new Nickname(nick), "https://cdn/changed.png");
            Optional<User> saved = userStore.saveProfileIfNicknameFree(reloaded);

            assertThat(saved).isPresent();
            assertThat(saved.get().profileImageUrl()).isEqualTo("https://cdn/changed.png");
        } finally {
            delete(user.id());
        }
    }

    @Test
    @DisplayName("대소문자만 다른 닉네임은 같은 것으로 본다")
    void collationIsCaseInsensitive() {
        // utf8mb4_0900_ai_ci 를 쓰기로 한 결정(ERD 초안 §9.1 #2)을 고정한다.
        // 콜레이션을 바꾸면 이 테스트가 먼저 알려준다.
        User first = newUser("c1");
        User second = newUser("c2");
        try {
            withProfile(first, "Pick");

            second.registerProfile(new Nickname("pick"), null);
            assertThat(userStore.saveProfileIfNicknameFree(second)).isEmpty();
        } finally {
            delete(second.id());
            delete(first.id());
        }
    }

    @Test
    @DisplayName("닉네임 조회는 활성 회원만 센다")
    void existsCountsOnlyActive() {
        User user = newUser("e");
        String nick = nickname("e");
        try {
            withProfile(user, nick);
            assertThat(userStore.existsActiveNickname(nick)).isTrue();

            jdbcTemplate.update("UPDATE users SET state = 'INACTIVE' WHERE id = ?", user.id());
            assertThat(userStore.existsActiveNickname(nick)).isFalse();
        } finally {
            delete(user.id());
        }
    }

    private String activeNickname(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT active_nickname FROM users WHERE id = ?", String.class, id);
    }

    private long countActive(String nickname) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE active_nickname = ?", Long.class, nickname);
    }

    private long rawNicknameCount(String nickname) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE nickname = ?", Long.class, nickname);
    }

    private void delete(Long id) {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
    }
}
