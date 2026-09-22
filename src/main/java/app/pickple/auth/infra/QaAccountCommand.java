package app.pickple.auth.infra;

import app.pickple.auth.domain.Nickname;
import app.pickple.auth.domain.QaAccount;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.UUID;

/** DB 관리 권한과 대화형 터미널이 필요한 계정 생성 도구. HTTP/애플리케이션 시작 시 실행하지 않는다. */
public final class QaAccountCommand {
    private QaAccountCommand() {}

    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("QA 계정 생성: 대화형 터미널에서 실행. DB 접속은 SPRING_DATASOURCE_URL/USERNAME/PASSWORD 사용.");
            return;
        }
        Console console = System.console();
        if (args.length != 0 || console == null) {
            System.err.println("대화형 터미널에서 인자 없이 실행하세요. 비밀번호는 콘솔에 표시되지 않습니다.");
            System.exit(1);
            return;
        }
        char[] password = null;
        char[] confirmation = null;
        int exitCode = 0;
        try {
            String loginId = console.readLine("QA login ID: ");
            Nickname nickname = new Nickname(console.readLine("Nickname (1-5 Korean/English letters or digits): "));
            password = console.readPassword("QA password (12+ characters, max 72 UTF-8 bytes): ");
            confirmation = console.readPassword("Confirm password: ");
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("비밀번호 확인이 일치하지 않습니다.");
            }
            String rawPassword = new String(password);
            validatePassword(rawPassword);
            String hash = new BCryptPasswordEncoder(10).encode(rawPassword);
            // 실제 비밀값은 인자·로그·출력으로 전달하지 않는다.
            var dataSource = new DriverManagerDataSource(
                    requiredEnvironment("SPRING_DATASOURCE_URL"),
                    requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                    requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
            create(dataSource, loginId, hash, nickname, Clock.system(ZoneId.of("Asia/Seoul")));
            console.printf("QA 계정을 생성했습니다. 입력한 로그인 정보는 별도로 안전하게 전달하세요.%n");
        } catch (DataAccessException | TransactionException error) {
            // JDBC 예외 본문에는 중복 아이디/쿼리 값이 포함될 수 있어 출력하지 않는다.
            System.err.println("계정 생성 실패. 변경은 롤백됩니다. DB 연결·마이그레이션·아이디/닉네임 중복을 확인하세요.");
            exitCode = 1;
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            exitCode = 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    static void validatePassword(String password) {
        if (password == null || password.isBlank() || password.codePointCount(0, password.length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("비밀번호는 12자 이상, UTF-8 기준 72바이트 이하여야 합니다.");
        }
    }

    /** 사용자와 자격증명을 한 트랜잭션으로 생성한다. 중복은 덮어쓰지 않고 전체 롤백한다. */
    static long create(DataSource dataSource, String loginId, String passwordHash,
                       Nickname nickname, Clock clock) {
        QaAccount.validateCredentials(loginId, passwordHash);
        if (nickname == null) throw new IllegalArgumentException("닉네임은 필수입니다.");
        var jdbc = JdbcClient.create(dataSource);
        var transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        return transaction.execute(status -> {
            var now = LocalDateTime.now(clock);
            var keys = new GeneratedKeyHolder();
            // QA 로그인은 qa_account.login_id로 조회한다.
            // provider_id의 UUID는 기존 사용자 식별자 제약을 유지하기 위한 내부 식별자다.
            jdbc.sql("""
                    INSERT INTO users (provider, provider_id, role, state, nickname, created_at, updated_at)
                    VALUES ('QA', ?, 'ROLE_USER', 'ACTIVE', ?, ?, ?)
                    """).params(UUID.randomUUID().toString(), nickname.value(), now, now).update(keys, "id");
            Number generatedId = keys.getKey();
            if (generatedId == null) throw new DataRetrievalFailureException("사용자 키를 받지 못했습니다.");
            long userId = generatedId.longValue();
            jdbc.sql("""
                    INSERT INTO qa_account (login_id, password_hash, user_id, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """).params(loginId, passwordHash, userId, now, now).update();
            return userId;
        });
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 설정이 필요합니다.");
        return value;
    }
}
