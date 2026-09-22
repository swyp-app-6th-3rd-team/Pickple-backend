package app.pickple.auth.oauth;

import app.pickple.auth.domain.SocialProvider;

import java.util.Map;

/** Google — 평평한 OIDC 표준 응답. */
record GoogleUserInfo(Map<String, Object> attributes) implements OAuth2UserInfo {

    @Override
    public SocialProvider provider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public String providerId() {
        return (String) attributes.get("sub");
    }

    @Override
    public String email() {
        return (String) attributes.get("email");
    }

    @Override
    public String name() {
        return (String) attributes.get("name");
    }
}
