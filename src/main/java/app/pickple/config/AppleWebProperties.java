package app.pickple.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/** Android 앱이 브라우저에서 시작하는 Apple 웹 로그인 설정. */
@ConfigurationProperties(prefix = "app.oauth.apple.web")
public record AppleWebProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("not-configured") String clientId,
        @DefaultValue("not-configured") String redirectUri,
        @DefaultValue("pickple://auth/callback") String appRedirectUri,
        @DefaultValue("https://appleid.apple.com/auth/authorize") String authorizationUri,
        @DefaultValue("PT10M") Duration attemptValidity,
        @DefaultValue("PT1M") Duration handoffValidity,
        @DefaultValue("P1D") Duration retention) {

    public static final String PICKPLE_CALLBACK = "pickple://auth/callback";

    public static AppleWebProperties disabled() {
        return new AppleWebProperties(false, "not-configured", "not-configured", PICKPLE_CALLBACK,
                "https://appleid.apple.com/auth/authorize", Duration.ofMinutes(10),
                Duration.ofMinutes(1), Duration.ofDays(1));
    }

    public AppleWebProperties {
        requirePositive("attempt-validity", attemptValidity);
        requirePositive("handoff-validity", handoffValidity);
        requirePositive("retention", retention);
        if (enabled) {
            requireConfigured("client-id", clientId);
            requireHttps("redirect-uri", redirectUri);
            requireHttps("authorization-uri", authorizationUri);
            if (!PICKPLE_CALLBACK.equals(appRedirectUri)) {
                throw new IllegalStateException("Apple 웹 로그인 앱 리다이렉트는 pickple://auth/callback 이어야 합니다.");
            }
        }
    }

    public String authorizationOrigin() {
        URI uri = URI.create(authorizationUri);
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
    }

    private static void requireConfigured(String name, String value) {
        if (value == null || value.isBlank()
                || "not-configured".equalsIgnoreCase(value)
                || "CHANGE_ME".equalsIgnoreCase(value)) {
            throw new IllegalStateException("Apple 웹 로그인이 활성화됐지만 " + name + " 설정이 없습니다.");
        }
    }

    private static void requireHttps(String name, String value) {
        requireConfigured(name, value);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Apple 웹 로그인 " + name + " 형식이 올바르지 않습니다.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || "localhost".equalsIgnoreCase(uri.getHost())
                || uri.getHost().matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            throw new IllegalStateException("Apple 웹 로그인 " + name + " 는 공개 HTTPS URI여야 합니다.");
        }
        if ("redirect-uri".equals(name)
                && (uri.getQuery() != null || !"/auth/apple/web/callback".equals(uri.getPath()))) {
            throw new IllegalStateException(
                    "Apple 웹 로그인 redirect-uri 경로는 /auth/apple/web/callback 이어야 합니다.");
        }
    }

    private static void requirePositive(String name, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException("Apple 웹 로그인 " + name + " 은 0보다 커야 합니다.");
        }
    }
}
