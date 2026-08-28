package app.pickple.auth.oauth;

import app.pickple.auth.domain.User;
import app.pickple.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로바이더에서 받은 사용자 정보로 우리 사용자를 찾거나 만든다.
 *
 * <p>반환하는 {@link OAuth2User} 의 attributes 에 우리 {@code userId} 를 심어두고,
 * 성공 핸들러가 그것으로 토큰을 발급한다.
 */
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String ATTRIBUTE_USER_ID = "__userId";

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final AuthService authService;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = delegate.loadUser(request);
        String registrationId = request.getClientRegistration().getRegistrationId();

        OAuth2UserInfo userInfo = OAuth2UserInfo.of(registrationId, oAuth2User.getAttributes());

        User user;
        try {
            user = authService.loginOrRegister(userInfo);
        } catch (RuntimeException e) {
            // OAuth2 예외로 감싸야 실패 핸들러가 받는다.
            throw new OAuth2AuthenticationException(new OAuth2Error("login_failed", e.getMessage(), null), e);
        }

        Map<String, Object> attributes = new HashMap<>(oAuth2User.getAttributes());
        attributes.put(ATTRIBUTE_USER_ID, user.id());

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority(user.role().authority())),
                attributes,
                ATTRIBUTE_USER_ID);
    }
}
