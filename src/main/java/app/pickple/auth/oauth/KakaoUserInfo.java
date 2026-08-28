package app.pickple.auth.oauth;

import app.pickple.auth.domain.SocialProvider;

import java.util.Map;

/**
 * Kakao — 중첩 구조.
 * <pre>
 * { "id": 12345, "kakao_account": { "email": "...", "profile": { "nickname": "..." } } }
 * </pre>
 * {@code id} 가 숫자로 오므로 문자열로 바꿔 쓴다.
 * 이메일은 사용자가 동의하지 않으면 아예 오지 않는다.
 */
record KakaoUserInfo(Map<String, Object> attributes) implements OAuth2UserInfo {

    @Override
    public SocialProvider provider() {
        return SocialProvider.KAKAO;
    }

    @Override
    public String providerId() {
        Object id = attributes.get("id");
        return id == null ? null : String.valueOf(id);
    }

    @Override
    public String email() {
        return (String) account().get("email");
    }

    @Override
    public String name() {
        Object profile = account().get("profile");
        if (profile instanceof Map<?, ?> map) {
            return (String) map.get("nickname");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> account() {
        Object account = attributes.get("kakao_account");
        return account instanceof Map ? (Map<String, Object>) account : Map.of();
    }
}
