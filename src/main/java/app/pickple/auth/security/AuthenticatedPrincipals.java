package app.pickple.auth.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * {@link Authentication} 에서 우리 신원({@link AuthenticatedPrincipal})을 꺼낸다.
 *
 * <p>인가 관문과 익명 강등 필터가 같은 판정을 해야 하므로 한 곳에 둔다 —
 * 둘이 서로 다른 조건으로 신원을 인정하면 "관문은 막았는데 공개 경로는 통과" 같은
 * 어긋남이 생긴다.
 */
final class AuthenticatedPrincipals {

    private AuthenticatedPrincipals() {
    }

    /** 우리 신원이면 사용자 ID, 익명·미인증·다른 종류의 인증이면 {@code null}. */
    static Long userIdOf(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        if (authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.userId();
        }
        return null;
    }
}
