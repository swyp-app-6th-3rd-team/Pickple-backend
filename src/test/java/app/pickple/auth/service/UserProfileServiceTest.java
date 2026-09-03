package app.pickple.auth.service;

import app.pickple.auth.domain.Nickname;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.config.ProfileProperties;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("프로필 서비스")
class UserProfileServiceTest {

    private static final String IMAGE_A = "https://cdn/default-a.png";
    private static final String IMAGE_B = "https://cdn/default-b.png";

    private UserStore userStore;
    private UserProfileService service;
    private FixedRandom random;

    @BeforeEach
    void setUp() {
        userStore = mock(UserStore.class);
        random = new FixedRandom(1);
        DefaultProfileImages defaults = new DefaultProfileImages(
                new ProfileProperties(List.of(IMAGE_A, IMAGE_B)), random);
        service = new UserProfileService(userStore, defaults);
        given(userStore.save(any(User.class))).willAnswer(call -> call.getArgument(0));
    }

    private User activeUser() {
        return User.restore(1L, SocialProvider.GOOGLE, "sub-1", null, "홍길동",
                app.pickple.auth.domain.Role.ROLE_USER, User.State.ACTIVE, null, null);
    }

    @Nested
    @DisplayName("중복 확인")
    class Availability {

        @Test
        @DisplayName("아무도 안 쓰면 사용 가능하다")
        void availableWhenUnused() {
            given(userStore.existsActiveNickname("피클")).willReturn(false);

            assertThat(service.isNicknameAvailable("피클")).isTrue();
        }

        @Test
        @DisplayName("활성 회원이 쓰고 있으면 사용 불가다")
        void unavailableWhenTaken() {
            given(userStore.existsActiveNickname("피클")).willReturn(true);

            assertThat(service.isNicknameAvailable("피클")).isFalse();
        }

        @Test
        @DisplayName("형식 위반은 조회하지 않고 400 이다")
        void rejectsInvalidFormatWithoutQuery() {
            assertThatThrownBy(() -> service.isNicknameAvailable("여섯글자닉네임"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ResponseCode.INVALID_REQUEST);

            // 형식이 틀린 값으로 DB 를 때리지 않는다.
            verify(userStore, never()).existsActiveNickname(anyString());
        }

        @Test
        @DisplayName("공백·이모지도 조회 전에 걸러진다")
        void rejectsWhitespaceAndEmoji() {
            assertThatThrownBy(() -> service.isNicknameAvailable("가 나"))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.isNicknameAvailable("😀"))
                    .isInstanceOf(ApiException.class);
            verify(userStore, never()).existsActiveNickname(anyString());
        }
    }

    @Nested
    @DisplayName("프로필 저장")
    class SaveProfile {

        @Test
        @DisplayName("이미지를 주지 않으면 기본 프로필이 채워진다")
        void fillsDefaultImage() {
            given(userStore.findById(1L)).willReturn(Optional.of(activeUser()));
            given(userStore.existsActiveNickname("피클")).willReturn(false);

            User saved = service.saveProfile(1L, "피클", null);

            assertThat(saved.profileImageUrl()).isNotNull().isEqualTo(IMAGE_B);
            assertThat(saved.nickname()).isEqualTo(new Nickname("피클"));
        }

        @Test
        @DisplayName("기본 프로필은 후보 중에서 고른다")
        void picksFromCandidates() {
            given(userStore.findById(1L)).willReturn(Optional.of(activeUser()));
            given(userStore.existsActiveNickname(anyString())).willReturn(false);
            random.next = 0;

            assertThat(service.saveProfile(1L, "피클", "").profileImageUrl()).isEqualTo(IMAGE_A);
        }

        @Test
        @DisplayName("이미지를 주면 그대로 쓴다")
        void usesGivenImage() {
            given(userStore.findById(1L)).willReturn(Optional.of(activeUser()));
            given(userStore.existsActiveNickname("피클")).willReturn(false);

            User saved = service.saveProfile(1L, "피클", "https://cdn/mine.png");

            assertThat(saved.profileImageUrl()).isEqualTo("https://cdn/mine.png");
        }

        @Test
        @DisplayName("이미 쓰이는 닉네임은 409 다")
        void rejectsTakenNickname() {
            given(userStore.findById(1L)).willReturn(Optional.of(activeUser()));
            given(userStore.existsActiveNickname("피클")).willReturn(true);

            assertThatThrownBy(() -> service.saveProfile(1L, "피클", null))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ResponseCode.NICKNAME_ALREADY_IN_USE);

            verify(userStore, never()).save(any(User.class));
        }

        @Test
        @DisplayName("본인이 쓰던 닉네임을 그대로 다시 내면 중복이 아니다")
        void ownNicknameIsNotConflict() {
            User user = activeUser();
            user.registerProfile(new Nickname("피클"), "https://cdn/mine.png");
            given(userStore.findById(1L)).willReturn(Optional.of(user));
            // 본인 행이 있으므로 존재 검사는 true 다 — 그대로 409 로 보내면 이미지만 바꿀 수 없다.
            given(userStore.existsActiveNickname("피클")).willReturn(true);

            User saved = service.saveProfile(1L, "피클", "https://cdn/new.png");

            assertThat(saved.profileImageUrl()).isEqualTo("https://cdn/new.png");
        }

        @Test
        @DisplayName("형식 위반은 400 이다")
        void rejectsInvalidFormat() {
            given(userStore.findById(1L)).willReturn(Optional.of(activeUser()));

            assertThatThrownBy(() -> service.saveProfile(1L, "여섯자닉네임", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("탈퇴한 사용자는 401 이다")
        void rejectsWithdrawnUser() {
            User withdrawn = activeUser();
            withdrawn.withdraw();
            given(userStore.findById(1L)).willReturn(Optional.of(withdrawn));

            assertThatThrownBy(() -> service.saveProfile(1L, "피클", null))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ResponseCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("인증 없는 요청은 401 이다")
        void rejectsMissingUserId() {
            assertThatThrownBy(() -> service.saveProfile(null, "피클", null))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ResponseCode.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("기본 프로필 설정")
    class Defaults {

        @Test
        @DisplayName("후보가 비어 있으면 기동에 실패한다")
        void rejectsEmptyCandidates() {
            // 조용히 null 을 넣으면 "이미지가 채워진다" 는 명세가 런타임에 깨진다.
            assertThatThrownBy(() -> new DefaultProfileImages(new ProfileProperties(List.of()), random))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new DefaultProfileImages(new ProfileProperties(null), random))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** 기본 프로필 선택을 고정해 검증 가능하게 만든다. */
    private static final class FixedRandom implements RandomGenerator {

        private int next;

        private FixedRandom(int next) {
            this.next = next;
        }

        @Override
        public int nextInt(int bound) {
            return next % bound;
        }

        @Override
        public long nextLong() {
            return next;
        }
    }
}
