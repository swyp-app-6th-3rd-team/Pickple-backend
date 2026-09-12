package app.pickple.common;

/** Unicode 공백을 포함한 문자열 처리 유틸리티. */
public final class StringUtils {

    private StringUtils() {
    }

    /**
     * 양끝의 Unicode 공백만 제거하고 내부 공백과 나머지 문자는 그대로 보존한다.
     * {@link String#strip()}이 남기는 NBSP 같은 space character도 제거한다.
     *
     * @param value null이 아닌 원본 문자열
     */
    public static String stripEdgeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isEdgeWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isEdgeWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isEdgeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

}
