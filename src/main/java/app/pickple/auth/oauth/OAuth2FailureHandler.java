package app.pickple.auth.oauth;

import app.pickple.auth.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final AuthProperties properties;
    private final HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("소셜 로그인 실패: {}", exception.getMessage());

        // 실패 원인을 그대로 노출하지 않는다. 내부 구조가 드러날 수 있다.
        String targetUrl = UriComponentsBuilder.fromUriString(properties.auth().redirectUri())
                .queryParam("error", "login_failed")
                .build().toUriString();

        authorizationRequestRepository.removeAuthorizationRequest(request, response);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
