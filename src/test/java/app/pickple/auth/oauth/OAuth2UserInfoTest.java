package app.pickple.auth.oauth;

import app.pickple.auth.domain.SocialProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로바이더 3사의 응답 구조가 실제로 다르다는 것을 고정하는 테스트.
 * 어댑터가 그 차이를 흡수하는지 확인한다.
 */
class OAuth2UserInfoTest {

    @Test
    @DisplayName("Google — 평평한 OIDC 표준 응답")
    void parsesGoogle() {
        Map<String, Object> attributes = Map.of(
                "sub", "google-sub-123",
                "email", "user@gmail.com",
                "name", "홍길동");

        OAuth2UserInfo info = OAuth2UserInfo.of("google", attributes);

        assertThat(info.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(info.providerId()).isEqualTo("google-sub-123");
        assertThat(info.email()).isEqualTo("user@gmail.com");
        assertThat(info.name()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("Kakao — 중첩된 kakao_account 를 풀어낸다")
    void parsesKakao() {
        Map<String, Object> attributes = Map.of(
                "id", 1234567890L,          // 숫자로 온다
                "kakao_account", Map.of(
                        "email", "user@kakao.com",
                        "profile", Map.of("nickname", "카카오유저")));

        OAuth2UserInfo info = OAuth2UserInfo.of("kakao", attributes);

        assertThat(info.provider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(info.providerId()).isEqualTo("1234567890");   // 문자열로 변환
        assertThat(info.email()).isEqualTo("user@kakao.com");
        assertThat(info.name()).isEqualTo("카카오유저");
    }

    @Test
    @DisplayName("Kakao — 이메일 동의를 안 받으면 null 이다")
    void handlesKakaoWithoutEmailConsent() {
        Map<String, Object> attributes = Map.of(
                "id", 999L,
                "kakao_account", Map.of("profile", Map.of("nickname", "익명")));

        OAuth2UserInfo info = OAuth2UserInfo.of("kakao", attributes);

        assertThat(info.providerId()).isEqualTo("999");
        assertThat(info.email()).isNull();      // 터지지 않고 null
        assertThat(info.name()).isEqualTo("익명");
    }

    @Test
    @DisplayName("Kakao — kakao_account 자체가 없어도 터지지 않는다")
    void handlesKakaoWithoutAccount() {
        OAuth2UserInfo info = OAuth2UserInfo.of("kakao", Map.of("id", 42L));

        assertThat(info.providerId()).isEqualTo("42");
        assertThat(info.email()).isNull();
        assertThat(info.name()).isNull();
    }

    @Test
    @DisplayName("Naver — response 래퍼를 벗겨낸다")
    void parsesNaver() {
        Map<String, Object> attributes = Map.of(
                "resultcode", "00",
                "message", "success",
                "response", Map.of(
                        "id", "naver-id-abc",
                        "email", "user@naver.com",
                        "name", "네이버유저"));

        OAuth2UserInfo info = OAuth2UserInfo.of("naver", attributes);

        assertThat(info.provider()).isEqualTo(SocialProvider.NAVER);
        assertThat(info.providerId()).isEqualTo("naver-id-abc");
        assertThat(info.email()).isEqualTo("user@naver.com");
        assertThat(info.name()).isEqualTo("네이버유저");
    }

    @Test
    @DisplayName("Naver — response 가 없어도 터지지 않는다")
    void handlesNaverWithoutResponse() {
        OAuth2UserInfo info = OAuth2UserInfo.of("naver", Map.of("resultcode", "24"));

        assertThat(info.providerId()).isNull();
        assertThat(info.email()).isNull();
    }
}
