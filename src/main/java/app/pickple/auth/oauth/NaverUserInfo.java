package app.pickple.auth.oauth;

import app.pickple.auth.domain.SocialProvider;

import java.util.Map;

/**
 * Naver — 응답을 {@code response} 로 한 번 감싼다.
 * <pre>
 * { "resultcode": "00", "message": "success",
 *   "response": { "id": "...", "email": "...", "name": "..." } }
 * </pre>
 * application.yml 의 {@code user-name-attribute: response} 와 짝을 이룬다.
 */
record NaverUserInfo(Map<String, Object> attributes) implements OAuth2UserInfo {

    @Override
    public SocialProvider provider() {
        return SocialProvider.NAVER;
    }

    @Override
    public String providerId() {
        return (String) response().get("id");
    }

    @Override
    public String email() {
        return (String) response().get("email");
    }

    @Override
    public String name() {
        return (String) response().get("name");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response() {
        Object response = attributes.get("response");
        return response instanceof Map ? (Map<String, Object>) response : Map.of();
    }
}
