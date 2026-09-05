package app.pickple.auth.security;

import app.pickple.auth.domain.UserStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 보호 경로의 관문 — 인증된 신원이 <b>지금도 살아 있는지</b> 확인한다.
 *
 * <p><b>왜 이 관문이 필요한가.</b> 탈퇴한 회원이 탈퇴 전 발급받은 액세스 토큰(TTL 30분)으로
 * 댓글·투표·원픽을 계속 만들 수 있었다. {@link JwtAuthenticationFilter} 는 의도적으로 무상태라
 * 토큰 클레임만 보고(ADR-0006 결정 10), 탈퇴 판정은 각 서비스의 {@code isActive()} 호출에
 * 맡겨져 있었는데 {@code vote}·{@code comment}·{@code point} 패키지에는 한 줄도 없었다.
 * <b>결함은 세 곳의 누락이 아니라 하나의 부재였다</b> — 탈퇴자를 거르는 관문이 없었다.
 *
 * <p>이 저장소에는 같은 문제를 이미 푼 선례가 있다. {@code ActivePostGuard} 가
 * "투표·댓글·원픽이 저마다 확인하면 한 곳만 빠뜨려도 뚫린다" 며 관문을 하나로 모았다.
 * 그 관문이 보는 것은 게시글의 생사이고, 이 관문이 보는 것은 사용자의 생사다.
 *
 * <p><b>왜 필터가 아니라 인가 계층인가.</b> JWT 는 <i>누가</i> 토큰을 냈는지 증명하고(인증),
 * 계정 상태는 <i>지금 행동해도 되는지</i>를 정한다(인가). 둘을 한 필터에 합치면
 * 필터가 {@code ApiException} 을 삼키는 구조 때문에 <b>DB 장애가 "토큰이 유효하지 않다"(401)로
 * 나간다.</b> 전 클라이언트 재로그인 폭주를 부르고 장애를 모니터링에서 감춘다 (ADR-0035).
 *
 * <p><b>차단 자체는 {@link AnonymousDemotionFilter} 가 한다.</b> 그 필터가 비활성 신원을
 * 익명으로 강등한 뒤 이 관문에 도달하므로, 여기서는 "익명인가" 만 판정하면 된다.
 * 강등을 거쳐야 하는 이유는 401 이다 — 인증된 주체를 이 관문이 거부하면 Spring Security 는
 * 403 을 낸다. 자세한 것은 그 필터의 문서에 있다.
 *
 * <p>이 관문이 여전히 필요한 이유는 <b>적용 범위</b>다. {@code .anyRequest()} 에 걸리므로
 * 새 보호 엔드포인트가 생겨도 별도 조치 없이 자동으로 이 판정을 지난다 —
 * D-1 이 세 곳에서 동시에 터진 구조적 원인이 여기서 사라진다.
 *
 * <p><b>세 갈래로 갈린다.</b>
 * <ul>
 *   <li>인증 안 됨 / 토큰 무효 / 강등된 비활성 계정 → 거부 →
 *       {@code RestAuthenticationEntryPoint} 가 <b>401</b></li>
 *   <li>활성 계정 → 통과</li>
 *   <li><b>상태를 확인할 수 없음(DB 장애) → 거부가 아니라 예외 전파</b> →
 *       {@link AccountStateUnavailableFilter} 가 <b>503</b></li>
 * </ul>
 *
 * <p><b>남는 한계 — 과장하지 않는다.</b> 이 관문은 진입 장벽이지 트랜잭션 불변식이 아니다.
 * {@code open-in-view: false} 이므로 인가 시점 조회는 서비스 트랜잭션 밖에서 끝난다.
 * "조회 시점엔 ACTIVE" 는 보장해도 "커밋 시점에도 ACTIVE" 는 보장하지 못한다.
 * 또한 이 시큐리티 체인을 지나는 HTTP 요청만 보호한다 — 배치·내부 호출은 못 막으므로
 * 기존 서비스별 확인을 일괄 제거하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveAccountAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    private final UserStore userStore;

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Long userId = AuthenticatedPrincipals.userIdOf(authentication.get());
        if (userId == null) {
            // 익명이거나, 토큰이 없거나, 강등된 비활성 계정이다. 기존 authenticated() 와 같은 판정.
            return new AuthorizationDecision(false);
        }
        if (Boolean.TRUE.equals(context.getRequest().getAttribute(
                AnonymousDemotionFilter.ACTIVE_CONFIRMED))) {
            return new AuthorizationDecision(true);   // 강등 필터가 이미 확인했다.
        }
        // 여기 오는 것은 강등 필터를 지나지 않은 요청뿐이다(다른 필터체인 등).
        // 조회가 실패하면 예외가 그대로 올라간다 — 여기서 잡아 false 로 바꾸면
        // DB 장애가 "탈퇴한 계정" 으로 위장된다 (ADR-0035 결정 3).
        return new AuthorizationDecision(userStore.existsActiveById(userId));
    }
}
