package app.pickple.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 활성화된 dev QA 로그인에서만 바인딩한다. 비밀번호 원문은 받지 않는다. */
@ConfigurationProperties(prefix = "app.auth.qa-login")
public record QaLoginProperties(String loginId, String passwordHash, Long userId) {

    private static final Pattern LOGIN_ID = Pattern.compile("^[A-Za-z0-9._@+-]{1,100}$");
    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$");

    public QaLoginProperties {
        if (loginId == null || !LOGIN_ID.matcher(loginId).matches()) {
            throw new IllegalStateException("QA_LOGIN_ID는 영문·숫자와 ._@+-만 사용해 1~100자로 설정해야 합니다.");
        }
        Matcher matcher = passwordHash == null ? null : BCRYPT.matcher(passwordHash);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalStateException("QA_LOGIN_PASSWORD_HASH는 BCrypt 해시여야 합니다.");
        }
        int cost = Integer.parseInt(matcher.group(1));
        if (cost < 10 || cost > 14) {
            throw new IllegalStateException("QA_LOGIN_PASSWORD_HASH의 BCrypt cost는 10~14여야 합니다.");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalStateException("QA_LOGIN_USER_ID는 양수여야 합니다.");
        }
    }

    @Override
    public String toString() {
        return "QaLoginProperties[redacted]";
    }
}
