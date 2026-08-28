package app.pickple.auth.oauth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;

import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * OAuth2 인가 요청을 세션이 아니라 쿠키에 담는다.
 *
 * <p>세션 정책이 {@code STATELESS} 라 기본 구현(세션 기반)을 쓸 수 없다.
 * 인가 요청은 리다이렉트 왕복 동안만 살아 있으면 되므로 짧은 만료를 준다.
 */
@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String AUTHORIZATION_REQUEST_COOKIE = "oauth2_auth_request";
    public static final String REDIRECT_URI_COOKIE = "oauth2_redirect_uri";
    private static final int EXPIRE_SECONDS = 180;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return getCookie(request, AUTHORIZATION_REQUEST_COOKIE)
                .map(this::deserialize)
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        if (authorizationRequest == null) {
            removeCookies(request, response);
            return;
        }
        addCookie(response, AUTHORIZATION_REQUEST_COOKIE, serialize(authorizationRequest));

        String redirectUri = request.getParameter("redirect_uri");
        if (redirectUri != null && !redirectUri.isBlank()) {
            addCookie(response, REDIRECT_URI_COOKIE, redirectUri);
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        removeCookies(request, response);
        return authorizationRequest;
    }

    public static Optional<Cookie> getCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .findFirst();
    }

    private void addCookie(HttpServletResponse response, String name, String value) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(EXPIRE_SECONDS);
        response.addCookie(cookie);
    }

    private void removeCookies(HttpServletRequest request, HttpServletResponse response) {
        for (String name : new String[]{AUTHORIZATION_REQUEST_COOKIE, REDIRECT_URI_COOKIE}) {
            getCookie(request, name).ifPresent(cookie -> {
                Cookie expired = new Cookie(name, "");
                expired.setPath("/");
                expired.setHttpOnly(true);
                expired.setMaxAge(0);
                response.addCookie(expired);
            });
        }
    }

    private String serialize(OAuth2AuthorizationRequest request) {
        return Base64.getUrlEncoder().encodeToString(SerializationUtils.serialize(request));
    }

    private OAuth2AuthorizationRequest deserialize(Cookie cookie) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cookie.getValue());
            Object deserialized = SerializationUtils.deserialize(bytes);
            return deserialized instanceof OAuth2AuthorizationRequest req ? req : null;
        } catch (RuntimeException e) {
            // 조작되었거나 형식이 깨진 쿠키. 인가 요청이 없는 것으로 취급한다.
            return null;
        }
    }
}
