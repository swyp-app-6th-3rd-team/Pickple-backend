package app.pickple.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class UserTest {

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("신규 사용자는 ACTIVE · ROLE_USER 로 시작한다")
        void newUserDefaults() {
            User user = new User(SocialProvider.GOOGLE, "google-123", "a@example.com", "홍길동");

            assertThat(user.isActive()).isTrue();
            assertThat(user.role()).isEqualTo(Role.ROLE_USER);
            assertThat(user.id()).isNull();
        }

        @Test
        @DisplayName("providerId 가 없으면 만들 수 없다")
        void rejectsBlankProviderId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new User(SocialProvider.KAKAO, "  ", null, null))
                    .withMessageContaining("providerId");
        }

        @Test
        @DisplayName("provider 가 없으면 만들 수 없다")
        void rejectsNullProvider() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new User(null, "id", null, null))
                    .withMessageContaining("provider");
        }

        @Test
        @DisplayName("활성 Apple 사용자는 providerId 없이 복원할 수 없다")
        void activeAppleUserRequiresProviderId() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> User.restore(
                            1L, SocialProvider.APPLE, null, null, null,
                            Role.ROLE_USER, User.State.ACTIVE, null, null))
                    .withMessageContaining("providerId");
        }
    }

    @Nested
    @DisplayName("프로필 동기화")
    class ProfileSync {

        @Test
        @DisplayName("프로바이더가 준 새 값으로 갱신한다")
        void updatesProfile() {
            User user = new User(SocialProvider.NAVER, "naver-1", "old@example.com", "옛이름");

            user.syncProfile("new@example.com", "새이름");

            assertThat(user.email()).isEqualTo("new@example.com");
            assertThat(user.name()).isEqualTo("새이름");
        }

        @Test
        @DisplayName("빈 값이 오면 기존 값을 지우지 않는다")
        void keepsExistingWhenBlank() {
            // 프로바이더가 이메일 동의를 못 받으면 null 을 준다.
            // 그때 기존 값을 날려버리면 사용자 정보가 사라진다.
            User user = new User(SocialProvider.KAKAO, "kakao-1", "keep@example.com", "유지");

            user.syncProfile(null, "  ");

            assertThat(user.email()).isEqualTo("keep@example.com");
            assertThat(user.name()).isEqualTo("유지");
        }
    }

    @Nested
    @DisplayName("탈퇴")
    class Withdrawal {

        @Test
        @DisplayName("비 Apple 사용자는 탈퇴해도 소셜 식별자를 유지한다")
        void nonAppleWithdrawalKeepsProviderId() {
            User user = new User(SocialProvider.GOOGLE, "g-1", null, null);

            user.withdraw();

            assertThat(user.isActive()).isFalse();
            assertThat(user.state()).isEqualTo(User.State.INACTIVE);
            assertThat(user.providerId()).isEqualTo("g-1");
        }

        @Test
        @DisplayName("Apple 사용자는 탈퇴하면 소셜 식별자를 놓는다")
        void appleWithdrawalReleasesProviderId() {
            User user = new User(SocialProvider.APPLE, "apple-sub", null, null);

            user.withdraw();

            assertThat(user.isActive()).isFalse();
            assertThat(user.state()).isEqualTo(User.State.INACTIVE);
            assertThat(user.providerId()).isNull();
        }

        @Test
        @DisplayName("이미 탈퇴한 사용자는 다시 탈퇴할 수 없다")
        void rejectsDoubleWithdrawal() {
            User user = new User(SocialProvider.GOOGLE, "g-1", null, null);
            user.withdraw();

            assertThatIllegalStateException()
                    .isThrownBy(user::withdraw)
                    .withMessageContaining("이미 탈퇴");
        }
    }

    @Nested
    @DisplayName("프로바이더 매핑")
    class ProviderMapping {

        @Test
        @DisplayName("registrationId 로 프로바이더를 찾는다")
        void resolvesFromRegistrationId() {
            assertThat(SocialProvider.from("google")).isEqualTo(SocialProvider.GOOGLE);
            assertThat(SocialProvider.from("KAKAO")).isEqualTo(SocialProvider.KAKAO);
        }

        @Test
        @DisplayName("모르는 프로바이더는 거부한다")
        void rejectsUnknown() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SocialProvider.from("facebook"))
                    .withMessageContaining("지원하지 않는");
        }
    }

    @Nested
    @DisplayName("프로필 등록")
    class ProfileRegistration {

        @Test
        @DisplayName("가입 직후에는 프로필이 비어 있다")
        void startsWithoutProfile() {
            User user = new User(SocialProvider.GOOGLE, "google-123", null, "홍길동");

            assertThat(user.hasProfile()).isFalse();
            assertThat(user.nickname()).isNull();
            assertThat(user.profileImageUrl()).isNull();
        }

        @Test
        @DisplayName("닉네임과 이미지를 등록한다")
        void registersProfile() {
            User user = new User(SocialProvider.GOOGLE, "google-123", null, "홍길동");

            user.registerProfile(new Nickname("피클"), "https://cdn/p.png");

            assertThat(user.hasProfile()).isTrue();
            assertThat(user.nickname()).isEqualTo(new Nickname("피클"));
            assertThat(user.profileImageUrl()).isEqualTo("https://cdn/p.png");
        }

        @Test
        @DisplayName("닉네임 없이는 등록할 수 없다")
        void requiresNickname() {
            User user = new User(SocialProvider.GOOGLE, "google-123", null, "홍길동");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> user.registerProfile(null, "https://cdn/p.png"))
                    .withMessageContaining("닉네임");
        }

        @Test
        @DisplayName("이미지를 주지 않으면 쓰던 이미지를 유지한다")
        void keepsExistingImageWhenNotGiven() {
            User user = new User(SocialProvider.GOOGLE, "google-123", null, "홍길동");
            user.registerProfile(new Nickname("피클"), "https://cdn/first.png");

            user.registerProfile(new Nickname("피클2"), null);
            assertThat(user.profileImageUrl()).isEqualTo("https://cdn/first.png");

            user.registerProfile(new Nickname("피클3"), "   ");
            assertThat(user.profileImageUrl()).isEqualTo("https://cdn/first.png");
        }

        @Test
        @DisplayName("탈퇴한 사용자는 프로필을 등록할 수 없다")
        void withdrawnUserCannotRegister() {
            User user = new User(SocialProvider.GOOGLE, "google-123", null, "홍길동");
            user.withdraw();

            assertThatIllegalStateException()
                    .isThrownBy(() -> user.registerProfile(new Nickname("피클"), null))
                    .withMessageContaining("탈퇴");
        }

        @Test
        @DisplayName("탈퇴해도 닉네임 값 자체는 남는다")
        void withdrawKeepsNicknameValue() {
            // 반납은 값을 지워서가 아니라 스키마의 생성 컬럼이 state 를 보기 때문에 일어난다.
            // 도메인이 값을 지우면 "누가 쓰던 닉네임인지" 를 잃는다.
            User user = new User(SocialProvider.GOOGLE, "google-123", null, "홍길동");
            user.registerProfile(new Nickname("피클"), null);

            user.withdraw();

            assertThat(user.nickname()).isEqualTo(new Nickname("피클"));
            assertThat(user.isActive()).isFalse();
        }
    }
}
