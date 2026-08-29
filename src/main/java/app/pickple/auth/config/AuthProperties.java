package app.pickple.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AuthProperties(Jwt jwt, Auth auth, Cors cors) {

    public record Jwt(
            String secretKey,
            @DefaultValue("PT30M") Duration accessTokenValidity,
            @DefaultValue("P14D") Duration refreshTokenValidity,
            @DefaultValue("pickple") String issuer) {

        public Jwt {
            // HS256 은 최소 256비트(32바이트) 키를 요구한다.
            // 짧은 키로도 서명은 되지만 무차별 대입에 취약하므로 기동 시점에 막는다.
            if (secretKey == null || secretKey.getBytes().length < 32) {
                throw new IllegalStateException(
                        "JWT_SECRET_KEY 는 32바이트 이상이어야 합니다. `openssl rand -base64 48` 로 생성하세요.");
            }
        }

        @Override
        public String toString() {
            return "Jwt[secretKey=redacted, accessTokenValidity=" + accessTokenValidity
                    + ", refreshTokenValidity=" + refreshTokenValidity + ", issuer=" + issuer + "]";
        }
    }

    public record Auth(
            @DefaultValue("http://localhost:3000/oauth/callback") String redirectUri,
            @DefaultValue("localhost") List<String> allowedRedirectHosts,
            @DefaultValue("false") boolean cookieSecure) {
    }

    public record Cors(@DefaultValue("http://localhost:3000") List<String> allowedOrigins) {
    }
}
