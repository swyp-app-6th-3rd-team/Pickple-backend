package app.pickple.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("닉네임 값 객체")
class NicknameTest {

    @Nested
    @DisplayName("경계값")
    class Boundary {

        @Test
        @DisplayName("5자는 통과한다")
        void acceptsFiveCharacters() {
            assertThatCode(() -> new Nickname("가나다라마")).doesNotThrowAnyException();
            assertThatCode(() -> new Nickname("abcde")).doesNotThrowAnyException();
            assertThatCode(() -> new Nickname("12345")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("6자는 거부한다")
        void rejectsSixCharacters() {
            assertThatThrownBy(() -> new Nickname("가나다라마바"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Nickname("abcdef"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("1자는 통과하고 빈 문자열은 거부한다")
        void acceptsOneRejectsEmpty() {
            assertThatCode(() -> new Nickname("가")).doesNotThrowAnyException();
            assertThatThrownBy(() -> new Nickname("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Nickname(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("허용 문자")
    class AllowedCharacters {

        @ParameterizedTest(name = "\"{0}\" 은 통과한다")
        @ValueSource(strings = {"가나다", "abc", "ABC", "123", "a1가", "ㄱㄴㄷ", "ㅏㅑㅓ"})
        void accepts(String value) {
            assertThat(Nickname.isValid(value)).isTrue();
        }

        @ParameterizedTest(name = "\"{0}\" 은 거부한다")
        @ValueSource(strings = {
                "가 나",      // 공백
                " 가나",      // 앞 공백
                "가나 ",      // 뒤 공백
                "가\t나",     // 탭
                "가!",        // 특수문자
                "a@b",
                "닉_네임",
                "가.나",
                "한글abcd",   // 6자 — 혼합이어도 길이는 지킨다
        })
        void rejects(String value) {
            assertThat(Nickname.isValid(value)).isFalse();
        }

        @Test
        @DisplayName("이모지를 거부한다")
        void rejectsEmoji() {
            assertThat(Nickname.isValid("😀")).isFalse();
            assertThat(Nickname.isValid("가나😀")).isFalse();
            // 이모지는 BMP 밖이라 char 로 세면 2다. 길이가 아니라 문자 집합으로 걸러야
            // "이모지 2개(=4 char)" 같은 값이 길이 검사만으로 통과하지 않는다.
            assertThat("😀".length()).isEqualTo(2);
        }

        @Test
        @DisplayName("줄바꿈이 붙은 값을 거부한다")
        void rejectsTrailingNewline() {
            // Java 정규식의 `$` 는 마지막 줄 종결자 앞에서도 매칭된다.
            // matches() 는 전체 일치를 요구해 이 경로가 막히지만,
            // 나중에 find() 로 바꾸면 "abc\n" 이 통과하므로 여기서 고정한다.
            assertThat(Nickname.isValid("abc\n")).isFalse();
            assertThat(Nickname.isValid("abc\r\n")).isFalse();
            assertThat(Nickname.isValid("\n")).isFalse();
        }

        @Test
        @DisplayName("조합형(NFD) 한글을 거부한다")
        void rejectsDecomposedHangul() {
            // 완성형 "가"(U+AC00) 는 통과하지만 조합형 ᄀ+ᅡ 는 가-힣 범위 밖이다.
            // 통과시키면 화면에는 같아 보이는 닉네임 둘이 공존해 사칭 여지가 생긴다.
            String decomposed = "가";
            assertThat(decomposed).isNotEqualTo("가");
            assertThat(Nickname.isValid(decomposed)).isFalse();
            assertThat(Nickname.isValid("가")).isTrue();
        }
    }

    @Test
    @DisplayName("같은 값이면 같은 닉네임이다")
    void equality() {
        assertThat(new Nickname("가나다")).isEqualTo(new Nickname("가나다"));
        assertThat(new Nickname("가나다")).hasSameHashCodeAs(new Nickname("가나다"));
        assertThat(new Nickname("가나다")).isNotEqualTo(new Nickname("라마바"));
    }

    @Test
    @DisplayName("isValid 는 예외 없이 판정한다")
    void isValidDoesNotThrow() {
        assertThat(Nickname.isValid(null)).isFalse();
        assertThat(Nickname.isValid("")).isFalse();
        assertThat(Nickname.isValid("정상")).isTrue();
    }
}
