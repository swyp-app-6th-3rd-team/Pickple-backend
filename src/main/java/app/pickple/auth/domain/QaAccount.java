package app.pickple.auth.domain;

import java.util.regex.Pattern;

/** QA 자격증명. 비밀번호 원문은 보관하지 않는다. */
public record QaAccount(String loginId, String passwordHash, Long userId) {
    public static final String LOGIN_ID_PATTERN = "^[A-Za-z0-9._@+-]{1,100}$";
    private static final Pattern LOGIN_ID = Pattern.compile(LOGIN_ID_PATTERN);
    private static final Pattern HASH = Pattern.compile("^\\$2[aby]\\$(10|11|12|13|14)\\$[./A-Za-z0-9]{53}$");

    public QaAccount {
        validateCredentials(loginId, passwordHash);
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
    }

    public static boolean isValidLoginId(String loginId) {
        return loginId != null && LOGIN_ID.matcher(loginId).matches();
    }

    public static void validateCredentials(String loginId, String passwordHash) {
        if (!isValidLoginId(loginId)) {
            throw new IllegalArgumentException("QA 아이디는 영문·숫자와 ._@+-로 1~100자여야 합니다.");
        }
        if (passwordHash == null || !HASH.matcher(passwordHash).matches()) {
            throw new IllegalArgumentException("BCrypt cost 10~14 해시가 필요합니다.");
        }
    }

    @Override
    public String toString() {
        return "QaAccount[redacted]";
    }
}
