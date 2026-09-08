package app.pickple.support;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Hibernate 가 실제로 내보내는 SQL 을 붙잡는다.
 *
 * <p><b>왜 손으로 옮겨 적은 SQL 을 EXPLAIN 하지 않는가.</b> QueryDSL 이 만든 SQL 은 사람이
 * 읽지 않으므로, 테스트에 베껴 둔 문장과 살아 있는 코드가 조용히 갈라진다 — 저장소가 정렬
 * 컬럼을 바꿔도 베낀 문장은 그대로라 실행계획 테스트가 초록색으로 남는다. 통과만으로는
 * 보호의 증거가 아니다. 그래서 실제 조회 경로를 한 번 태우고 <b>그때 나간 문장</b>을 EXPLAIN 한다.
 *
 * <p>{@link StatementInspector} 는 Hibernate 가 JDBC 로 넘기기 직전의 SQL 을 보여준다.
 * 파라미터는 {@code ?} 로 남아 있으므로, EXPLAIN 할 때 호출자가 같은 값을 순서대로 바인딩한다.
 *
 * <p>{@code @Import(SqlCapture.Config.class)} 로 켠다. 켜지 않은 컨텍스트에는 비용이 없다.
 */
public class SqlCapture implements StatementInspector {

    /**
     * 녹음은 <b>스레드별</b>이다. 저장소 호출과 MockMvc 요청이 테스트 스레드에서 동기로 돌므로
     * 한 스레드의 녹음이 다른 테스트의 문장을 줍거나 지우지 않는다.
     */
    private final ThreadLocal<List<String>> recording = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        List<String> statements = recording.get();
        if (statements != null) {
            statements.add(sql);
        }
        return sql;
    }

    /** 동작 하나가 이 스레드에서 내보낸 SQL 을 순서대로 돌려준다. */
    public List<String> record(Runnable action) {
        List<String> statements = new ArrayList<>();
        recording.set(statements);
        try {
            action.run();
        } finally {
            recording.remove();
        }
        return List.copyOf(statements);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        public SqlCapture sqlCapture() {
            return new SqlCapture();
        }

        @Bean
        public HibernatePropertiesCustomizer sqlCaptureCustomizer(SqlCapture sqlCapture) {
            return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, sqlCapture);
        }
    }
}
