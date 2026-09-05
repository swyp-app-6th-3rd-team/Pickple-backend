package app.pickple.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Kakao iOS SDK가 발급한 OIDC ID token 검증과 서버 주도 연결 해제 설정. */
@ConfigurationProperties(prefix = "app.oauth.kakao")
public record KakaoProperties(
        @DefaultValue("not-configured") String nativeAppKey,
        @DefaultValue("not-configured") String adminKey,
        @DefaultValue("https://kauth.kakao.com") String issuer,
        @DefaultValue("https://kauth.kakao.com/.well-known/jwks.json") String jwkSetUri,
        @DefaultValue("https://kapi.kakao.com") String apiBaseUrl) {

    public boolean loginConfigured() {
        return isConfigured(nativeAppKey);
    }

    public boolean unlinkConfigured() {
        return isConfigured(adminKey);
    }

    private static boolean isConfigured(String value) {
        return value != null
                && !value.isBlank()
                && !"not-configured".equalsIgnoreCase(value)
                && !"CHANGE_ME".equalsIgnoreCase(value);
    }

    @Override
    public String toString() {
        return "KakaoProperties[redacted]";
    }
}
