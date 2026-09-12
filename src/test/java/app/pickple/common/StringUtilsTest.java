package app.pickple.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StringUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {" ", "\t\n\r", "\u00a0", "\u2007", "\u202f", "\u3000"})
    @DisplayName("ASCII 공백과 Unicode space character를 양끝에서 제거한다")
    void stripsEdgeWhitespace(String whitespace) {
        assertThat(StringUtils.stripEdgeWhitespace(whitespace + "검색" + whitespace))
                .isEqualTo("검색");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n", "\u00a0\u2007\u202f\u3000"})
    @DisplayName("빈 문자열과 공백만 있는 문자열은 빈 문자열이 된다")
    void returnsEmptyForWhitespaceOnly(String value) {
        assertThat(StringUtils.stripEdgeWhitespace(value)).isEmpty();
    }

    @Test
    @DisplayName("내부 공백과 보조 평면 문자를 그대로 보존한다")
    void preservesInteriorAndSupplementaryCharacters() {
        String value = "😀에어  팟\u00a0프로\u3000😀";
        assertThat(StringUtils.stripEdgeWhitespace("\u00a0" + value + "\u3000"))
                .isEqualTo(value);
    }

    @Test
    @DisplayName("공백이 아닌 zero-width space와 BOM은 임의로 제거하지 않는다")
    void preservesNonWhitespaceCharacters() {
        String value = "\u200b검색\ufeff";
        assertThat(StringUtils.stripEdgeWhitespace(value)).isEqualTo(value);
    }
}
