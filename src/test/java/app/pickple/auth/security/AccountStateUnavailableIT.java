package app.pickple.auth.security;

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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import jakarta.servlet.Filter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>DB 장애가 401 로 오분류되지 않는다</b> (PRD-021 C-9).
 *
 * <p>이 판정이 이 변경에서 가장 위험한 부분이다. 상태 조회를 매 요청 돌리기로 한 이상,
 * 그 조회가 실패했을 때 무엇으로 나가는지가 장애 시의 서비스 성격을 정한다.
 *
 * <p><b>401 로 나가면 무슨 일이 벌어지나.</b> DB 가 흔들리는 순간 모든 클라이언트가
 * "토큰이 만료됐다" 로 읽고 일제히 재로그인·재발급을 시도한다. 이미 힘든 DB 에
 * 인증 트래픽이 얹힌다. 게다가 장애가 <b>인증 실패로 위장</b>돼 모니터링에서 감춰진다 —
 * 5xx 알람이 울리지 않는다. ADR-0035 결정 3 이 이것을 금지한 이유다.
 *
 * <p>그래서 여기서는 "거부되는가" 가 아니라 <b>"어떤 코드로 거부되는가"</b> 를 본다.
 * 401 이면 실패다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("계정 상태 조회 실패 (#106 C-9)")
class AccountStateUnavailableIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private JwtService jwtService;

    /**
     * 실제 빈을 감싼 스파이. 조회만 실패시키고 나머지 경로는 그대로 둔다.
     *
     * <p>컨테이너를 정말 죽이지 않는 이유는 <b>변수를 하나만 바꾸기</b> 위해서다.
     * MySQL 을 내리면 Flyway·커넥션 풀·헬스체크가 함께 무너져 무엇이 이 응답을 만들었는지
     * 가려낼 수 없다. 여기서 검증하려는 것은 "조회 실패가 어떤 코드로 번역되는가" 하나다.
     */
    @MockitoSpyBean
    private UserStore userStore;

    private MockMvc mockMvc;
    private String token;
    private Long userId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        User user = userStore.save(new User(
                SocialProvider.GOOGLE, "state-unavailable-" + System.nanoTime(), null, "테스터"));
        userId = user.id();
        token = jwtService.createAccessToken(user);
    }

    @Test
    @DisplayName("보호 경로: 조회가 실패하면 401 이 아니라 503 이다")
    void protectedPathReturnsServiceUnavailable() throws Exception {
        willThrow(new QueryTimeoutException("커넥션 풀 고갈"))
                .given(userStore).existsActiveById(anyLong());

        mockMvc.perform(post("/posts/{postId}/comments", 1L)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"장애 중 요청\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_STATE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("공개 경로: 조회가 실패하면 조용히 익명이 되지 않고 503 이다")
    void publicPathDoesNotSilentlyDegrade() throws Exception {
        willThrow(new QueryTimeoutException("커넥션 풀 고갈"))
                .given(userStore).existsActiveById(anyLong());

        // "확인 못 했으니 일단 익명" 으로 처리하면 장애가 개인화 소실로만 나타나고
        // 아무 데도 기록되지 않는다. 같은 은폐의 다른 얼굴이다.
        mockMvc.perform(get("/posts/{postId}/comments", 1L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_STATE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("토큰 없는 게스트는 장애 중에도 공개 경로를 본다 — 조회 자체가 없다")
    void guestIsUnaffectedByStateLookupFailure() throws Exception {
        willThrow(new QueryTimeoutException("커넥션 풀 고갈"))
                .given(userStore).existsActiveById(anyLong());

        // 신원이 없으면 조회하지 않으므로 장애의 영향권 밖이다.
        // 이것이 "매 요청 조회" 의 실제 범위를 보여준다 — 게스트 트래픽은 0회다.
        mockMvc.perform(get("/posts")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("DB 장애 중에도 로그아웃은 성공한다 — 자격증명을 버리는 데 DB 가 필요하지 않다")
    void logoutSurvivesStateLookupFailure() throws Exception {
        willThrow(new QueryTimeoutException("커넥션 풀 고갈"))
                .given(userStore).existsActiveById(anyLong());

        // PRD-021 결정 4: 로그아웃은 익명·탈퇴자도 부를 수 있어야 한다.
        // 그 원칙은 "ACTIVE 를 요구하지 않는다" 에 그치지 않고
        // <b>DB 가용성을 요구하지 않는다</b> 까지 가야 일관된다 —
        // 로그인에서 빠져나오려는 사용자를 장애가 가둬서는 안 된다.
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("필터 순서가 실제 체인에서 확정돼 있다 — 503 필터가 강등 필터를 감싼다")
    void filterOrderIsPinnedInTheRealChain() {
        // 순서가 뒤집히면 강등 필터가 던진 DataAccessException 이 503 필터 바깥으로 새어
        // 컨테이너 기본 500(HTML)이 된다. 위 두 테스트가 그 회귀를 잡아주긴 하지만,
        // 원인이 "필터 순서" 라는 것까지 말해주지는 못한다. 여기서 그 전제를 직접 고정한다.
        //
        // 순서를 주석이 아니라 실행으로 확인하는 이유: 두 필터를 같은 기준점 앞에 걸면
        // 상대 순서가 등록 순서에 기대게 되는데 그것은 프레임워크 계약이 아니다.
        java.util.List<Filter> filters = serviceChainFilters();
        int unavailable = indexOf(filters, AccountStateUnavailableFilter.class);
        int demotion = indexOf(filters, AnonymousDemotionFilter.class);
        int authorization = indexOf(filters, AuthorizationFilter.class);

        org.assertj.core.api.Assertions.assertThat(unavailable)
                .as("503 필터는 강등 필터보다 바깥이어야 예외를 잡는다")
                .isLessThan(demotion);
        org.assertj.core.api.Assertions.assertThat(demotion)
                .as("강등은 인가 판정보다 먼저여야 401 이 된다")
                .isLessThan(authorization);
    }

    /** 서비스 포트 체인(@Order(2)). 관리 포트 체인은 이 필터들을 달지 않는다. */
    private java.util.List<Filter> serviceChainFilters() {
        for (SecurityFilterChain chain : springSecurityFilterChain.getFilterChains()) {
            java.util.List<Filter> filters = chain.getFilters();
            if (filters.stream().anyMatch(AnonymousDemotionFilter.class::isInstance)) {
                return filters;
            }
        }
        throw new IllegalStateException("강등 필터가 어느 체인에도 없다 — 배선이 빠졌다");
    }

    private static int indexOf(java.util.List<Filter> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        throw new IllegalStateException("체인에 없다: " + type.getSimpleName());
    }

    @Test
    @DisplayName("조회가 정상이면 평소대로 동작한다 — 스파이가 경로를 바꾸지 않았다")
    void behavesNormallyWithoutInjectedFailure() throws Exception {
        // 대조군. 이게 없으면 위 세 개가 "스파이 때문에 깨진 것" 과 구분되지 않는다.
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.userId").value(userId));
    }
}
