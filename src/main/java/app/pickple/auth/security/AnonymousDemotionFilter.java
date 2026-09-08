package app.pickple.auth.security;

import app.pickple.auth.domain.UserStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * <b>비활성 계정의 신원을 익명으로 강등한다.</b> 탈퇴 차단의 실제 집행 지점이다.
 *
 * <p><b>규칙은 하나다 — 비활성 신원은 어디서든 익명이 된다.</b>
 * 그 하나가 경로에 따라 두 결과를 낳는다.
 *
 * <table border="1">
 *   <caption>같은 강등이 낳는 두 결과</caption>
 *   <tr><th>경로</th><th>강등 후</th><th>결과</th></tr>
 *   <tr><td>보호 경로</td><td>익명이 {@link ActiveAccountAuthorizationManager} 에 걸린다</td>
 *       <td><b>401</b></td></tr>
 *   <tr><td>{@code permitAll} 경로</td><td>익명이 그대로 통과한다</td>
 *       <td>익명으로 조회</td></tr>
 * </table>
 *
 * <p><b>왜 강등이 401 을 만드는가.</b> 관문이 "인증된 사용자를 거부" 하면 Spring Security 는
 * <b>403</b> 을 낸다 — {@code ExceptionTranslationFilter} 가 익명이 아닌 주체의
 * {@code AccessDeniedException} 을 {@code AccessDeniedHandler} 로 보내기 때문이다.
 * 401 을 얻으려고 프레임워크를 비틀 것이 아니라 <b>의미를 맞춘다</b>:
 * 탈퇴한 계정의 토큰은 더 이상 유효한 신원이 아니다. 익명으로 만들면 401 은 저절로 따라온다.
 * 그래서 이 필터는 {@code AuthorizationFilter} <b>앞</b>에 있어야 한다.
 *
 * <p><b>공개 경로에도 필요한 이유.</b> {@code permitAll} 은 필터를 우회하지 <b>않는다</b>.
 * {@link JwtAuthenticationFilter} 가 토큰이 붙은 모든 요청을 파싱하므로, 강등이 없으면
 * 공개 경로에서 탈퇴자가 여전히 "본인" 으로 식별된다. 댓글 목록이 공개였던 #106 당시의 실측:
 *
 * <pre>
 * GET /posts/{id}/comments
 *   게스트(토큰 없음)  → comments[0].mine = false
 *   탈퇴자 토큰 첨부   → comments[0].mine = true    ← 강등이 없을 때
 * </pre>
 *
 * <p>현재 댓글 목록은 인증이 필요하므로 게스트와 탈퇴자 모두 401을 받는다(#100).
 * 게시글·랭킹 등 공개 목록에서는 익명으로 조회할 수 있다.
 *
 * <p>거부가 아니라 강등인 이유는, 거부하면 토큰을 지우지 않은 탈퇴자가 공개 목록조차 보지
 * 못하기 때문이다. R-20(탈퇴자의 글은 계속 보인다)은 <b>글의 가시성</b>을 말하는 것이지
 * 탈퇴자가 개인화된 신원을 유지해도 된다는 뜻이 아니다 — 강등이 정확히 그 둘을 가른다.
 *
 * <p><b>조회 비용.</b> 토큰이 붙었고 <b>개인화하는 경로</b>인 요청에만 조회가 돈다.
 * 게스트는 신원이 없어 0회이고, 정적·문서 경로와 자격증명 폐기 경로는
 * {@link #shouldNotFilter} 로 빠져 0회다. 판정 결과를 요청 속성에 남겨
 * {@link ActiveAccountAuthorizationManager} 가 재사용하므로 한 요청에 두 번 조회하지 않는다.
 *
 * <p><b>실패 모드.</b> 조회가 실패하면 삼키지 않고 올려보낸다. "확인 못 했으니 일단 익명" 으로
 * 처리하면 DB 가 죽은 동안 로그인 사용자 전체가 조용히 게스트가 되고, 보호 경로는 그 사실을
 * 401 로 알린다 — 우리가 고치려는 바로 그 은폐다.
 * {@link AccountStateUnavailableFilter} 가 503 으로 번역한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousDemotionFilter extends OncePerRequestFilter {

    /**
     * 이 요청의 신원이 활성으로 확인됐다는 표시.
     *
     * <p>{@link ActiveAccountAuthorizationManager} 가 읽어 같은 요청에 조회를 두 번 돌리지
     * 않게 한다. 값이 없다는 것은 "확인하지 않았다" 가 아니라 "신원이 없었다" 는 뜻이다 —
     * 그 경우 관문은 어차피 익명으로 거부한다.
     */
    static final String ACTIVE_CONFIRMED = AnonymousDemotionFilter.class.getName() + ".ACTIVE";

    /**
     * 강등을 건너뛰는 경로 — <b>기준은 "공개인가" 가 아니라 "신원으로 개인화하는가" 다.</b>
     *
     * <p>공개 경로 전부를 빼면 {@code GET /posts/{id}/comments} 의 {@code mine} 이 다시
     * 탈퇴자에게 참이 된다. 반대로 아무것도 빼지 않으면 아래 두 부류가 <b>계정 DB 가용성에
     * 불필요하게 묶인다.</b> 그래서 개인화 유무로 가른다.
     *
     * <ol>
     *   <li><b>자격증명을 버리는 요청.</b> 로그아웃은 익명·탈퇴자도 부를 수 있어야 한다는
     *       것이 결정이었는데(PRD-021 결정 4), 강등이 걸리면 DB 장애 때 503 이 되어
     *       <b>쿠키를 지우지 못한다.</b> 로그인에서 빠져나오려는 사용자를 장애가 가두는 셈이다.
     *       "ACTIVE 를 요구하지 않는다" 는 원칙은 "DB 가용성을 요구하지 않는다" 까지 가야
     *       일관된다. 재발급도 같다 — 액세스 토큰 없이도 부르는 경로이고
     *       계정 상태는 {@code AuthService} 가 자체로 확인하므로 앞단 조회는 중복이다.</li>
     *   <li><b>개인화가 아예 없는 정적·문서 리소스.</b> 신원에 따라 응답이 달라지지 않으므로
     *       강등할 대상 자체가 없다. 토큰을 붙여 부르는 트래픽이 드물어도, 그 때문에
     *       문서 페이지가 DB 장애에 묶일 이유는 없다.</li>
     * </ol>
     *
     * <p><b>이 목록은 낡을 수 있다.</b> 새 정적 경로가 {@code SecurityConfig} 의
     * {@code permitAll} 에만 추가되면 여기 빠진 채 조회가 돈다 — 안전한 방향의 누락이라
     * (보호가 과할 뿐 뚫리지 않는다) 감수한다. 반대 방향, 즉 <b>개인화하는 경로를 여기에
     * 넣는 것은 위험하다</b> — 그쪽이 탈퇴자 신원을 되살린다.
     */
    private static final RequestMatcher SKIP_DEMOTION = skipMatcher();

    private final UserStore userStore;

    private static RequestMatcher skipMatcher() {
        PathPatternRequestMatcher.Builder mvc = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                // 자격증명을 버리거나 다시 받는 경로. 계정 상태는 AuthService 가 본다.
                mvc.matcher(HttpMethod.POST, "/auth/logout"),
                mvc.matcher(HttpMethod.POST, "/auth/refresh"),
                mvc.matcher(HttpMethod.POST, "/auth/mobile/refresh"),
                // 신원에 따라 달라지지 않는 리소스.
                mvc.matcher(HttpMethod.GET, "/favicon.ico"),
                mvc.matcher(HttpMethod.GET, "/error"),
                mvc.matcher(HttpMethod.GET, "/swagger-ui/**"),
                mvc.matcher(HttpMethod.GET, "/swagger-ui.html"),
                mvc.matcher(HttpMethod.GET, "/v3/api-docs/**"),
                mvc.matcher(HttpMethod.GET, "/scalar"),
                mvc.matcher(HttpMethod.GET, "/scalar/**"),
                mvc.matcher(HttpMethod.GET, "/llms.txt"),
                mvc.matcher(HttpMethod.GET, "/llms.md"),
                mvc.matcher(HttpMethod.GET, "/docs/**"),
                mvc.matcher(HttpMethod.GET, "/actuator/health/**"));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SKIP_DEMOTION.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        demoteIfInactive(request);
        chain.doFilter(request, response);
    }

    private void demoteIfInactive(HttpServletRequest request) {
        Long userId = AuthenticatedPrincipals.userIdOf(
                SecurityContextHolder.getContext().getAuthentication());
        if (userId == null) {
            return;   // 게스트. 조회할 것이 없다.
        }
        if (userStore.existsActiveById(userId)) {
            request.setAttribute(ACTIVE_CONFIRMED, Boolean.TRUE);
            return;
        }
        log.debug("비활성 계정의 신원을 익명으로 강등한다: userId={}", userId);
        SecurityContextHolder.clearContext();
    }
}
