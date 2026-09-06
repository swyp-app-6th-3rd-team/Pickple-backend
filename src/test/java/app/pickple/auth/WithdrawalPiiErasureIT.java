package app.pickple.auth;

import app.pickple.auth.domain.Nickname;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.domain.SocialIdentity;
import app.pickple.auth.service.AccountWithdrawalPersistenceService;
import app.pickple.auth.service.AuthService;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 탈퇴 시 개인정보 파기의 완료 판정 (이슈 #111, PRD-023, ADR-0040).
 *
 * <p>개인정보처리방침 제3조가 "회원 탈퇴 시 지체 없이 파기" 를 규정하는데
 * {@code withdraw()} 는 상태만 바꿔 이메일·이름·닉네임·프로필 이미지와 카카오
 * {@code provider_id} 가 무기한 남았다. 여기서 고정하는 것은 그 다섯 컬럼이
 * <b>실제 행에서</b> 비었다는 사실이다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 파기 여부를 인메모리 객체가 아니라
 * {@code JdbcTemplate} 로 <b>커밋된 행</b>을 직접 읽어 판정하기 때문이다. 도메인 객체의
 * 게터가 {@code null} 인 것은 영속화의 증거가 아니다 — {@code UserEntity.applyState} 가
 * 값을 실제로 반영했는지는 행을 봐야 안다. 롤백 대신 실행마다 고유한 픽스처를 만든다.
 */
@IntegrationTest
class WithdrawalPiiErasureIT {

    /** 제1조가 수집 항목으로 명시한 컬럼들. 탈퇴 후 전부 비어야 한다. */
    private static final String PII_COLUMNS =
            "email, name, nickname, profile_image_url, provider_id";

    /** 활성 회원의 닉네임 유일성(R-23)을 피하기 위한 픽스처용 일련번호. */
    private static final java.util.concurrent.atomic.AtomicLong NICKNAME_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    /** 소급 파기 마이그레이션. 사본이 아니라 <b>실제 파일</b>을 읽는다. */
    private static final String V14_PATH =
            "db/migration/V14__erase_withdrawn_user_personal_data.sql";

    @Autowired
    private UserStore userStore;
    @Autowired
    private AccountWithdrawalPersistenceService withdrawalPersistenceService;
    @Autowired
    private AuthService authService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /** 바깥 트랜잭션과 독립적으로 커밋하는 경계. 탈퇴가 먼저 커밋되는 순서를 만든다. */
    private org.springframework.transaction.support.TransactionTemplate newTransaction;

    @org.junit.jupiter.api.BeforeEach
    void setUpNewTransactionTemplate() {
        newTransaction = new org.springframework.transaction.support.TransactionTemplate(
                transactionTemplate.getTransactionManager());
        newTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * C-1 · C-2 — provider 를 가리지 않고 다섯 컬럼이 전부 비어야 한다.
     *
     * <p>ADR-0037 이 Apple 에만 열어둔 문을 전 provider 로 넓힌 것이 이 결정이므로,
     * Apple 하나가 통과하는 것으로는 판정이 되지 않는다. enum 전체를 돈다.
     */
    @ParameterizedTest
    @EnumSource(SocialProvider.class)
    @DisplayName("C-1·C-2 탈퇴하면 provider 무관하게 users 행의 개인정보가 전부 비워진다")
    void withdrawalErasesEveryPiiColumnInTheRow(SocialProvider provider) {
        User user = saveProfiledUser(provider, "erase-" + provider + "-" + System.nanoTime());
        Long userId = user.id();

        // 파기 전에는 값이 있었다는 대조군. 없으면 "원래 비어 있던 것" 과 구분되지 않는다.
        assertThat(nonNullPiiCount(userId))
                .as("탈퇴 전에는 다섯 컬럼이 채워져 있어야 대조가 된다")
                .isEqualTo(5L);

        withdrawalPersistenceService.complete(userId);

        Map<String, Object> row = piiRow(userId);
        assertThat(row).allSatisfy((column, value) ->
                assertThat(value).as("%s 이 파기되지 않았다", column).isNull());
        assertThat(state(userId)).isEqualTo("INACTIVE");
    }

    /**
     * C-3 — 회귀 위험 1순위.
     *
     * <p>{@code User.restore} 가 생성자 가드를 지나므로, 가드를 넓히지 않은 채 파기만
     * 넣으면 마스킹한 행을 읽는 <b>모든 조회</b>가 {@code IllegalArgumentException} 으로
     * 터진다. 쓰기는 성공하고 읽기가 죽는 형태라 탈퇴 순간에는 드러나지 않는다.
     */
    @ParameterizedTest
    @EnumSource(SocialProvider.class)
    @DisplayName("C-3 파기한 행을 다시 조회해도 터지지 않는다")
    void maskedRowIsStillReadable(SocialProvider provider) {
        User user = saveProfiledUser(provider, "reread-" + provider + "-" + System.nanoTime());
        Long userId = user.id();
        withdrawalPersistenceService.complete(userId);

        User reloaded = userStore.findById(userId).orElseThrow();

        assertThat(reloaded.id()).isEqualTo(userId);
        assertThat(reloaded.provider()).isEqualTo(provider);
        assertThat(reloaded.providerId()).isNull();
        assertThat(reloaded.isActive()).isFalse();
        assertThat(reloaded.hasProfile()).isFalse();
    }

    /**
     * C-6 — sentinel 문자열을 쓰지 않는 이유를 고정한다.
     *
     * <p>{@code uk_users_provider (provider, provider_id)} 가 걸려 있어 {@code 'withdrawn'}
     * 같은 고정 값이면 여기서 두 번째 탈퇴가 유니크 위반으로 실패한다.
     * InnoDB 가 유니크 제약에서 {@code NULL} 을 중복으로 세지 않는다는 사실에 기대는 부분이다.
     */
    @Test
    @DisplayName("C-6 같은 provider 회원이 연속으로 탈퇴해도 유니크 위반이 없다")
    void consecutiveWithdrawalsDoNotViolateProviderUnique() {
        long seed = System.nanoTime();
        User first = saveProfiledUser(SocialProvider.KAKAO, "kakao-seq-1-" + seed);
        User second = saveProfiledUser(SocialProvider.KAKAO, "kakao-seq-2-" + seed);

        withdrawalPersistenceService.complete(first.id());
        withdrawalPersistenceService.complete(second.id());

        assertThat(nonNullPiiCount(first.id())).isZero();
        assertThat(nonNullPiiCount(second.id())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id IN (?, ?) AND provider_id IS NULL",
                Long.class, first.id(), second.id()))
                .as("두 행 모두 provider_id 가 NULL 로 공존해야 한다")
                .isEqualTo(2L);
    }

    /**
     * C-7 — 카카오 재로그인이 신규 가입 흐름이 된다.
     *
     * <p>ADR-0037 이 Apple 에 준 계약이 전 provider 로 같아지는 지점이다. 식별자를 비우면
     * {@code (KAKAO, sub)} 조회가 과거 행을 찾지 못해 "탈퇴한 계정입니다" 403 이 아니라
     * 신규 생성 경로로 들어간다.
     */
    @Test
    @DisplayName("C-7 카카오 탈퇴 후 같은 계정으로 로그인하면 새 회원이 만들어진다")
    void kakaoRejoinAfterWithdrawalCreatesNewUser() {
        String sub = "kakao-rejoin-" + System.nanoTime();
        User old = saveProfiledUser(SocialProvider.KAKAO, sub);
        Long oldUserId = old.id();

        withdrawalPersistenceService.complete(oldUserId);

        assertThat(userStore.findByProviderAndProviderId(SocialProvider.KAKAO, sub))
                .as("파기 후에는 소셜 식별자로 과거 행을 찾을 수 없어야 한다")
                .isEmpty();

        User rejoined = authService.loginOrRegister(
                identity(SocialProvider.KAKAO, sub, "new@example.com", "새이름"));

        assertThat(rejoined.id()).isNotEqualTo(oldUserId);
        assertThat(rejoined.state()).isEqualTo(User.State.ACTIVE);
        assertThat(rejoined.providerId()).isEqualTo(sub);
        assertThat(rejoined.hasProfile()).as("과거 프로필을 승계하지 않는다").isFalse();

        // 과거 행은 콘텐츠의 FK 대상이라 그대로 남는다 (R-20).
        assertThat(userStore.findById(oldUserId)).isPresent();
    }

    /**
     * C-11 · C-12 — 소급 파기 마이그레이션(V14)이 탈퇴 회원만 비운다.
     *
     * <p>코드 변경은 <b>이번 이후의 탈퇴</b>만 파기한다. 제3조의 의무는 탈퇴 시점으로
     * 나뉘지 않으므로 기존 탈퇴 행도 함께 비워야 시행일에 준수 상태가 된다.
     * V12 가 Apple {@code provider_id} 에 했던 소급 적용과 같은 종류다.
     *
     * <p><b>실제 V14 파일을 읽어 실행한다.</b> SQL 사본을 테스트에 복사해 두면
     * 마이그레이션에서 {@code WHERE} 가 빠져도 이 테스트는 그대로 통과한다 —
     * 사본의 동작을 검증할 뿐 배포되는 파일을 검증하지 않기 때문이다.
     * 파기는 되돌릴 수 없으므로 그 차이가 실제 사고와 검출의 차이가 된다.
     *
     * <p>마이그레이션은 이미 적용된 뒤라 재실행으로는 효과를 볼 수 없어 문장만 다시 돌린다.
     * <b>활성 회원을 건드리지 않는 것</b>이 이 문장에서 가장 위험한 부분이라 대조군을 둔다.
     */
    @Test
    @DisplayName("C-11·C-12 소급 파기는 탈퇴 회원만 비우고 활성 회원은 그대로 둔다")
    void backfillErasesOnlyWithdrawnRows() {
        long seed = System.nanoTime();
        User withdrawn = saveProfiledUser(SocialProvider.KAKAO, "legacy-inactive-" + seed);
        User active = saveProfiledUser(SocialProvider.KAKAO, "legacy-active-" + seed);

        // 코드 경로를 거치지 않고 "구버전에서 탈퇴한 행" 을 직접 만든다.
        // withdraw() 를 쓰면 이미 파기돼 소급 파기의 효과를 볼 수 없다.
        jdbcTemplate.update("UPDATE users SET state = 'INACTIVE' WHERE id = ?", withdrawn.id());
        assertThat(nonNullPiiCount(withdrawn.id()))
                .as("소급 파기 전에는 탈퇴 행에 개인정보가 남아 있어야 대조가 된다")
                .isEqualTo(5L);

        for (String statement : migrationStatements(V14_PATH)) {
            jdbcTemplate.update(statement);
        }

        assertThat(nonNullPiiCount(withdrawn.id()))
                .as("C-11 기존 탈퇴 행의 잔존 개인정보")
                .isZero();
        assertThat(nonNullPiiCount(active.id()))
                .as("C-12 활성 회원은 한 컬럼도 건드리지 않아야 한다")
                .isEqualTo(5L);
        assertThat(state(active.id())).isEqualTo("ACTIVE");
    }

    /**
     * C-14 — 탈퇴와 겹친 로그인이 파기한 개인정보를 되살리지 못한다.
     *
     * <p>로그인은 회원을 읽고({@code findByProviderAndProviderId}) 활성인지 본 뒤 저장한다.
     * 그 사이에 탈퇴가 커밋되면, 저장이 읽어둔 옛 값을 그대로 써서 <b>파기한 다섯 컬럼과
     * {@code ACTIVE} 상태가 되살아난다.</b> 확인과 쓰기 사이의 틈이다.
     *
     * <p>경합 자체는 이번 변경 이전부터 있었지만 그때 되살아나는 것은 {@code state} 뿐이었고
     * 관문(ADR-0035)이 매 요청 다시 확인해 피해가 제한됐다. 파기를 도입한 뒤로는
     * <b>파기했다고 선언한 개인정보가 되돌아온다</b> — 등급이 다른 문제다.
     *
     * <p>스레드 없이 인터리빙을 만든다. 실제 순서를 재현하는 것이 목적이지 경합을
     * 확률적으로 노리는 것이 목적이 아니다 — 확률에 기대면 통과가 증거가 되지 못한다.
     *
     * <p><b>로그인 쪽을 트랜잭션 안에서 돌리는 것이 핵심이다.</b> {@code loginOrRegister}
     * 가 {@code @Transactional} 이라 엔티티가 영속 상태로 남고 변경 감지가 flush 된다.
     * 트랜잭션 밖에서 부르면 {@code open-in-view: false} 라 엔티티가 준영속이 되어
     * 쓰기가 조용히 사라지고, 결함이 있어도 테스트가 통과해 버린다.
     */
    @Test
    @DisplayName("C-14 탈퇴와 겹친 로그인이 파기한 개인정보를 되살리지 못한다")
    void concurrentLoginCannotResurrectErasedPersonalData() {
        String sub = "race-" + System.nanoTime();
        User user = saveProfiledUser(SocialProvider.KAKAO, sub);
        Long userId = user.id();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            // T1 로그인: 회원을 읽고 활성임을 확인한다 (loginOrRegister 의 앞부분).
            User loginView = userStore.findByProviderAndProviderId(SocialProvider.KAKAO, sub)
                    .orElseThrow();
            assertThat(loginView.isActive()).isTrue();
            loginView.syncProfile("fresh@example.com", "새이름");

            // T2 탈퇴: 별도 트랜잭션에서 먼저 커밋된다.
            newTransaction.executeWithoutResult(inner ->
                    withdrawalPersistenceService.complete(userId));

            // T1 로그인: 읽어둔 옛 상태로 저장하려 한다. 여기서 거부돼야 한다.
            userStore.save(loginView);
        })).hasMessageContaining("활성 사용자를 찾을 수 없습니다");

        assertThat(state(userId))
                .as("탈퇴가 먼저 커밋됐는데 로그인이 계정을 되살리면 안 된다")
                .isEqualTo("INACTIVE");
        assertThat(nonNullPiiCount(userId))
                .as("파기한 개인정보가 되살아나면 방침 제3조 위반이다")
                .isZero();
    }

    // --- 픽스처 -----------------------------------------------------------

    /**
     * 다섯 컬럼이 전부 채워진 회원. 파기 전후를 대조하려면 값이 있어야 한다.
     *
     * <p>닉네임은 매번 다르게 만든다. 이 클래스는 {@code @Transactional} 이 아니라
     * 활성 회원 행이 남고, {@code uk_users_active_nickname} 이 활성 회원의 닉네임
     * 중복을 막기 때문이다(R-23). 5자 제한이 있어 짧은 일련번호를 쓴다.
     */
    private User saveProfiledUser(SocialProvider provider, String providerId) {
        User user = new User(provider, providerId, "user@example.com", "홍길동");
        user.registerProfile(new Nickname(uniqueNickname()), "https://cdn.example.com/p.png");
        return userStore.save(user);
    }

    /**
     * 5자 이내 한글·영문·숫자만 허용된다 ({@link Nickname}). 36진수로 5자 안에 넣는다.
     *
     * <p>컨테이너를 재사용하므로 이전 실행이 남긴 활성 회원과도 겹치면 안 된다.
     * 나노초 시각을 36진수로 접어 접두사로 쓰고 호출마다 증가시킨다.
     */
    private static String uniqueNickname() {
        long value = Math.floorMod(System.nanoTime() + NICKNAME_SEQUENCE.getAndIncrement(),
                60_466_176L); // 36^5 — 5자 안에 들어가는 최대값
        return Long.toString(value, 36);
    }

    /**
     * 재로그인용 신원. Kakao 의 구현체는 {@code KakaoAuthService} 안의 private record 라
     * 여기서 쓸 수 없고, 검증 대상도 카카오 전송 계층이 아니라 {@code AuthService} 의
     * 가입·로그인 분기다.
     */
    private static SocialIdentity identity(
            SocialProvider provider, String providerId, String email, String name) {
        return new SocialIdentity() {
            @Override
            public SocialProvider provider() {
                return provider;
            }

            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public String email() {
                return email;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    // --- 검증 도구 ---------------------------------------------------------

    /**
     * 마이그레이션 파일에서 실행할 문장만 뽑는다. 주석(`--`)과 빈 줄을 걷어내고
     * 세미콜론으로 가른다. Flyway 가 읽는 것과 같은 파일을 읽는 것이 요점이다.
     */
    private static List<String> migrationStatements(String classpathLocation) {
        try (var in = WithdrawalPiiErasureIT.class.getClassLoader()
                .getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalStateException("마이그레이션을 찾을 수 없습니다: " + classpathLocation);
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String stripped = sql.lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            return Arrays.stream(stripped.split(";"))
                    .map(String::trim)
                    .filter(statement -> !statement.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> piiRow(Long userId) {
        return jdbcTemplate.queryForMap(
                "SELECT " + PII_COLUMNS + " FROM users WHERE id = ?", userId);
    }

    private long nonNullPiiCount(Long userId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT (email IS NOT NULL) + (name IS NOT NULL) + (nickname IS NOT NULL)
                     + (profile_image_url IS NOT NULL) + (provider_id IS NOT NULL)
                  FROM users WHERE id = ?
                """, Long.class, userId);
        return value == null ? 0L : value;
    }

    private String state(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM users WHERE id = ?", String.class, userId);
    }
}
