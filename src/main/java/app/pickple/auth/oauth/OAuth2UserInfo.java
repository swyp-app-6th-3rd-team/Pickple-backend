package app.pickple.auth.oauth;

import app.pickple.auth.domain.SocialIdentity;
import app.pickple.auth.domain.SocialProvider;

import java.util.Map;

/**
 * 프로바이더마다 제각각인 사용자 정보 응답을 하나의 모양으로 맞춘다.
 *
 * <p>세 프로바이더의 응답 구조가 실제로 다르다.
 * <ul>
 *   <li>Google — 평평한 OIDC 표준: {@code {sub, email, name}}</li>
 *   <li>Kakao  — 중첩: {@code {id, kakao_account: {email, profile: {nickname}}}}</li>
 *   <li>Naver  — 래퍼: {@code {response: {id, email, name}}}</li>
 * </ul>
 * 이 차이를 어댑터가 흡수하므로 상위 계층은 프로바이더를 구분하지 않는다.
 */
public interface OAuth2UserInfo extends SocialIdentity {

    SocialProvider provider();

    /** 프로바이더가 발급한 고유 식별자. */
    String providerId();

    String email();

    String name();

    static OAuth2UserInfo of(String registrationId, Map<String, Object> attributes) {
        SocialProvider provider = SocialProvider.from(registrationId);
        return switch (provider) {
            case GOOGLE -> new GoogleUserInfo(attributes);
            case KAKAO -> new KakaoUserInfo(attributes);
            case NAVER -> new NaverUserInfo(attributes);
            case APPLE -> throw new IllegalArgumentException("Apple 로그인은 네이티브 API를 사용합니다.");
        };
    }
}
