package app.pickple.auth.security;

import app.pickple.auth.domain.UserStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
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
 *       <td><b>200</b>, 개인화만 사라짐({@code mine=false})</td></tr>
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
 * 공개 경로에서 탈퇴자가 여전히 "본인" 으로 식별된다. 실측:
 *
 * <pre>
 * GET /posts/{id}/comments
 *   게스트(토큰 없음)  → comments[0].mine = false
 *   탈퇴자 토큰 첨부   → comments[0].mine = true    ← 강등이 없을 때
 * </pre>
 *
 * <p>거부가 아니라 강등인 이유는, 거부하면 토큰을 지우지 않은 탈퇴자가 공개 목록조차 보지
 * 못하기 때문이다. R-20(탈퇴자의 글은 계속 보인다)은 <b>글의 가시성</b>을 말하는 것이지
 * 탈퇴자가 개인화된 신원을 유지해도 된다는 뜻이 아니다 — 강등이 정확히 그 둘을 가른다.
 *
 * <p><b>조회 비용.</b> 토큰이 붙은 요청에만 조회가 돈다. 게스트와 정적 경로는 <b>0회</b>다.
 * 판정 결과를 요청 속성에 남겨 {@link ActiveAccountAuthorizationManager} 가 재사용하므로
 * 한 요청에 두 번 조회하지 않는다.
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

    private final UserStore userStore;

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
