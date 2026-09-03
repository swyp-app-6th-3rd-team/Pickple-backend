package app.pickple.auth;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 닉네임 중복확인·프로필 등록 API 통합 테스트 (이슈 #16 완료 판정).
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 테스트 트랜잭션 안에서는 커밋이 없어
 * 유니크 제약이 실제로 판정하지 않고, 동시성 검증이 성립하지 않는다.
 * 대신 픽스처를 매번 고유한 값으로 만들고 쓴 행을 직접 지운다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class UserProfileControllerIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserStore userStore;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private User user;
    private String token;
    private long seed;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        seed = System.nanoTime();
        user = saveUser("profile-user-" + seed);
        token = jwtService.createAccessToken(user);
    }

    private User saveUser(String providerId) {
        return userStore.save(new User(SocialProvider.GOOGLE, providerId, null, "가입자"));
    }

    private String bearer(String value) {
        return "Bearer " + value;
    }

    /** 5자 제한 안에서 테스트마다 겹치지 않는 닉네임을 만든다. */
    private String uniqueNickname() {
        return "n" + Long.toString(Math.abs(seed) % 10000, 36);
    }

    @Test
    @DisplayName("아무도 안 쓰는 닉네임은 사용 가능으로 답한다")
    void availabilityReportsUnused() throws Exception {
        mockMvc.perform(get("/api/users/nickname/availability").param("value", uniqueNickname()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.returnObject.available").value(true))
                .andExpect(jsonPath("$.returnObject.message").value("사용 가능한 닉네임"));
    }

    @Test
    @DisplayName("중복 확인은 로그인 없이도 부를 수 있다")
    void availabilityIsPublic() throws Exception {
        // 가입 화면에서 로그인 전에 부르므로 401 이면 기능 자체가 성립하지 않는다.
        mockMvc.perform(get("/api/users/nickname/availability").param("value", "피클"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("6자 이상·특수문자·이모지·공백은 400 이다")
    void availabilityRejectsInvalidFormat() throws Exception {
        for (String invalid : new String[]{"여섯글자닉네임", "가나다라마바", "가 나", "가!", "😀", "닉_네임", "  "}) {
            mockMvc.perform(get("/api/users/nickname/availability").param("value", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    @DisplayName("value 파라미터가 없으면 400 이다")
    void availabilityRequiresParameter() throws Exception {
        mockMvc.perform(get("/api/users/nickname/availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("경계값 5자는 통과하고 6자는 거부한다")
    void availabilityBoundary() throws Exception {
        mockMvc.perform(get("/api/users/nickname/availability").param("value", "가나다라마"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/nickname/availability").param("value", "가나다라마바"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이미지 없이 등록하면 기본 프로필이 채워진다")
    void registersWithDefaultImage() throws Exception {
        String nickname = uniqueNickname();
        try {
            mockMvc.perform(post("/api/users/profile")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"" + nickname + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("CREATED"))
                    .andExpect(jsonPath("$.returnObject.nickname").value(nickname))
                    .andExpect(jsonPath("$.returnObject.profileImageUrl").isNotEmpty());
        } finally {
            deleteUser(user.id());
        }
    }

    @Test
    @DisplayName("등록한 닉네임은 이후 중복 확인에서 사용 불가로 답한다")
    void registeredNicknameBecomesUnavailable() throws Exception {
        String nickname = uniqueNickname();
        try {
            registerProfile(token, nickname);

            mockMvc.perform(get("/api/users/nickname/availability").param("value", nickname))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.available").value(false))
                    .andExpect(jsonPath("$.returnObject.message").value("이미 사용 중인 닉네임"));
        } finally {
            deleteUser(user.id());
        }
    }

    @Test
    @DisplayName("남이 쓰는 닉네임으로 등록하면 409 다")
    void duplicateRegistrationRejected() throws Exception {
        User other = saveUser("other-" + seed);
        String nickname = uniqueNickname();
        try {
            registerProfile(token, nickname);

            mockMvc.perform(post("/api/users/profile")
                            .header("Authorization", bearer(jwtService.createAccessToken(other)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"" + nickname + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NICKNAME_ALREADY_IN_USE"));
        } finally {
            deleteUser(other.id());
            deleteUser(user.id());
        }
    }

    @Test
    @DisplayName("탈퇴자가 쓰던 닉네임은 다시 쓸 수 있다 (R-21)")
    void withdrawnUserReleasesNickname() throws Exception {
        User newcomer = saveUser("newcomer-" + seed);
        String nickname = uniqueNickname();
        try {
            registerProfile(token, nickname);
            assertThat(activeNicknameCount(nickname)).isEqualTo(1);

            // 탈퇴 = state 를 INACTIVE 로. 생성 컬럼이 state 를 보므로 여기서 닉네임이 풀린다.
            jdbcTemplate.update("UPDATE users SET state = 'INACTIVE' WHERE id = ?", user.id());

            assertThat(activeNicknameCount(nickname)).isZero();
            mockMvc.perform(get("/api/users/nickname/availability").param("value", nickname))
                    .andExpect(jsonPath("$.returnObject.available").value(true));

            // 실제로 등록까지 성공해야 한다 — 조회만 통과하고 유니크 제약이 막으면 소용없다.
            registerProfile(jwtService.createAccessToken(newcomer), nickname);
            assertThat(activeNicknameCount(nickname)).isEqualTo(1);
        } finally {
            deleteUser(newcomer.id());
            deleteUser(user.id());
        }
    }

    @Test
    @DisplayName("동시 요청에서 같은 닉네임이 둘 등록되지 않는다 (R-23)")
    void concurrentRegistrationKeepsUniqueness() throws Exception {
        int threads = 8;
        String nickname = uniqueNickname();
        java.util.List<User> contenders = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            contenders.add(saveUser("racer-" + i + "-" + seed));
        }
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
            java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger conflicted = new java.util.concurrent.atomic.AtomicInteger();
            java.util.List<Integer> unexpected =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());

            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
                for (User contender : contenders) {
                    String contenderToken = jwtService.createAccessToken(contender);
                    pool.submit(() -> {
                        try {
                            start.await();
                            int code = mockMvc.perform(post("/api/users/profile")
                                            .header("Authorization", bearer(contenderToken))
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"nickname\":\"" + nickname + "\"}"))
                                    .andReturn().getResponse().getStatus();
                            if (code == 201) {
                                created.incrementAndGet();
                            } else if (code == 409) {
                                conflicted.incrementAndGet();
                            } else {
                                unexpected.add(code);
                            }
                        } catch (Exception e) {
                            unexpected.add(-1);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }

            // 완료 판정: 동시 삽입 시도 후 SELECT COUNT(*) = 1
            assertThat(activeNicknameCount(nickname)).isEqualTo(1);
            assertThat(created.get()).isEqualTo(1);
            // 나머지는 500 이 아니라 409 로 되돌아와야 한다 — 경합이 사용자에게
            // 서버 오류로 보이면 재시도 판단을 할 수 없다.
            assertThat(unexpected).isEmpty();
            assertThat(conflicted.get()).isEqualTo(threads - 1);
        } finally {
            for (User contender : contenders) {
                deleteUser(contender.id());
            }
            deleteUser(user.id());
        }
    }

    @Test
    @DisplayName("내 프로필을 조회한다")
    void readsOwnProfile() throws Exception {
        String nickname = uniqueNickname();
        try {
            registerProfile(token, nickname);

            mockMvc.perform(get("/api/users/me").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.userId").value(user.id()))
                    .andExpect(jsonPath("$.returnObject.nickname").value(nickname))
                    .andExpect(jsonPath("$.returnObject.profileImageUrl").isNotEmpty());
        } finally {
            deleteUser(user.id());
        }
    }

    @Test
    @DisplayName("미인증 프로필 조회·등록은 401 이다")
    void guestCannotReadOrWriteProfile() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/users/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"피클\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("프로필을 수정하면 닉네임과 이미지가 바뀐다")
    void editsProfile() throws Exception {
        String nickname = uniqueNickname();
        String changed = "z" + nickname.substring(1);
        try {
            registerProfile(token, nickname);

            mockMvc.perform(patch("/api/users/profile")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"" + changed + "\",\"profileImageUrl\":\"https://cdn/new.png\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.nickname").value(changed))
                    .andExpect(jsonPath("$.returnObject.profileImageUrl").value("https://cdn/new.png"));

            // 놓아준 닉네임은 다시 사용 가능해야 한다.
            assertThat(activeNicknameCount(nickname)).isZero();
        } finally {
            deleteUser(user.id());
        }
    }

    @Test
    @DisplayName("이미지를 주지 않은 수정은 쓰던 이미지를 유지한다")
    void editKeepsImageWhenOmitted() throws Exception {
        String nickname = uniqueNickname();
        String changed = "y" + nickname.substring(1);
        try {
            registerProfile(token, nickname);
            String original = jdbcTemplate.queryForObject(
                    "SELECT profile_image_url FROM users WHERE id = ?", String.class, user.id());

            mockMvc.perform(patch("/api/users/profile")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"" + changed + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.profileImageUrl").value(original));
        } finally {
            deleteUser(user.id());
        }
    }

    @Test
    @DisplayName("형식 위반 등록은 400 이고 아무것도 저장하지 않는다")
    void registerRejectsInvalidFormat() throws Exception {
        try {
            for (String invalid : new String[]{"가나다라마바", "가 나", "가!", "😀", ""}) {
                mockMvc.perform(post("/api/users/profile")
                                .header("Authorization", bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nickname\":\"" + invalid + "\"}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
            }
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE id = ? AND nickname IS NOT NULL",
                    Long.class, user.id())).isZero();
        } finally {
            deleteUser(user.id());
        }
    }

    private void registerProfile(String accessToken, String nickname) throws Exception {
        mockMvc.perform(post("/api/users/profile")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isCreated());
    }

    private long activeNicknameCount(String nickname) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE active_nickname = ?", Long.class, nickname);
    }

    private void deleteUser(Long id) {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
    }
}
