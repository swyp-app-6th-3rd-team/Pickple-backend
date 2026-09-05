package app.pickple.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 활성화된 dev 로그인에서만 바인딩한다. 비활성 환경에는 이 빈도 없다. */
@ConfigurationProperties(prefix = "app.auth.dev-login")
public record DevLoginProperties(String key, Set<Long> allowedUserIds) {

    public DevLoginProperties {
        if (key == null || key.isBlank()
                || key.getBytes(StandardCharsets.UTF_8).length < 32 || key.length() > 512) {
            throw new IllegalStateException("DEV_LOGIN_KEY는 32바이트 이상, 512자 이하로 설정해야 합니다.");
        }
        if (allowedUserIds == null || allowedUserIds.isEmpty()
                || allowedUserIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalStateException("DEV_LOGIN_ALLOWED_USER_IDS에 양수인 QA 사용자 ID가 필요합니다.");
        }
        allowedUserIds = Set.copyOf(allowedUserIds);
    }

    @Override
    public String toString() {
        return "DevLoginProperties[redacted]";
    }
}
