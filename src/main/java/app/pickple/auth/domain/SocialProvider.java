package app.pickple.auth.domain;

import java.util.Arrays;

/**
 * 지원하는 소셜 로그인 프로바이더.
 *
 * <p>{@code registrationId} 는 application.yml 의
 * {@code spring.security.oauth2.client.registration.<id>} 와 일치해야 한다.
 */
public enum SocialProvider {

    GOOGLE("google"),
    KAKAO("kakao"),
    NAVER("naver");

    private final String registrationId;

    SocialProvider(String registrationId) {
        this.registrationId = registrationId;
    }

    public String registrationId() {
        return registrationId;
    }

    public static SocialProvider from(String registrationId) {
        return Arrays.stream(values())
                .filter(p -> p.registrationId.equalsIgnoreCase(registrationId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 프로바이더입니다: " + registrationId));
    }
}
