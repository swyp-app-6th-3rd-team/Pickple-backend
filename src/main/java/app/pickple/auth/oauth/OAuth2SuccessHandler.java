package app.pickple.auth.oauth;

import app.pickple.config.AuthProperties;
import app.pickple.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;

/**
 * 소셜 로그인 성공 처리.
 *
 * <p><b>리프레시 토큰은 URL 에 싣지 않는다.</b> URL 은 브라우저 히스토리·리퍼러 헤더·
 * 서버 접근 로그에 그대로 남는다. 리프레시 토큰은 수명이 길어 유출 피해가 크므로
 * HttpOnly 쿠키로만 보낸다.
 * 액세스 토큰은 수명이 짧고 프런트가 즉시 읽어야 하므로 쿼리파라미터로 전달한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private final AuthService authService;
    private final AuthProperties properties;
    private final HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (response.isCommitted()) {
            log.debug("응답이 이미 커밋되어 리다이렉트할 수 없습니다.");
            return;
        }

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        Long userId = ((Number) oAuth2User.getAttributes().get(CustomOAuth2UserService.ATTRIBUTE_USER_ID)).longValue();

        AuthService.TokenPair tokens = authService.issueTokens(authService.getById(userId));

        addRefreshTokenCookie(response, tokens.refreshToken());

        String targetUrl = UriComponentsBuilder.fromUriString(resolveRedirectUri(request))
                .queryParam("accessToken", tokens.accessToken())
                .build().toUriString();

        authorizationRequestRepository.removeAuthorizationRequest(request, response);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private void addRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE, refreshToken);
        cookie.setHttpOnly(true);              // JS 가 읽을 수 없다 (XSS 방어)
        cookie.setSecure(properties.auth().cookieSecure());
        cookie.setPath("/");
        cookie.setMaxAge((int) properties.jwt().refreshTokenValidity().toSeconds());
        cookie.setAttribute("SameSite", "Lax"); // CSRF 완화
        response.addCookie(cookie);
    }

    /**
     * 리다이렉트 대상 검증.
     *
     * <p>쿠키에 담긴 값을 그대로 쓰면 <b>오픈 리다이렉트</b>가 된다.
     * 공격자가 {@code ?redirect_uri=https://evil.com} 으로 로그인을 유도하면
     * 액세스 토큰이 공격자 사이트로 넘어간다. 호스트 화이트리스트로 막는다.
     */
    private String resolveRedirectUri(HttpServletRequest request) {
        String requested = HttpCookieOAuth2AuthorizationRequestRepository
                .getCookie(request, HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_COOKIE)
                .map(Cookie::getValue)
                .orElse(null);

        if (requested == null || requested.isBlank()) {
            return properties.auth().redirectUri();
        }
        try {
            String host = URI.create(requested).getHost();
            if (host != null && properties.auth().allowedRedirectHosts().contains(host)) {
                return requested;
            }
            log.warn("허용되지 않은 리다이렉트 대상이라 기본값을 사용합니다: {}", requested);
        } catch (IllegalArgumentException e) {
            log.warn("리다이렉트 URI 형식이 올바르지 않습니다: {}", requested);
        }
        return properties.auth().redirectUri();
    }
}
