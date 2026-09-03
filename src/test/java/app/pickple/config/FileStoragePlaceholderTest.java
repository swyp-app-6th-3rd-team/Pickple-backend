package app.pickple.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전환기 폴백(#63)이 실제로 구키를 읽는지 확인한다.
 *
 * <p>FILE_* 로 개명했지만 배포 인스턴스의 fetch-secrets.sh 는 user_data 최초 부팅에만
 * 갱신되므로(ADR-0027) 한동안 IMAGE_* 로 .env 가 쓰인다. 그 구간에서 업로드가
 * 조용히 죽지 않아야 한다 — 헬스체크로는 안 잡히는 실패라 여기서 고정한다.
 */
class FileStoragePlaceholderTest {

    private static final String EXPR = "${FILE_S3_BUCKET:${IMAGE_S3_BUCKET:not-configured}}";

    private String resolve(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", env));
        return environment.resolvePlaceholders(EXPR);
    }

    @Test
    @DisplayName("신키가 없으면 구키를 읽는다")
    void fallsBackToOldKey() {
        assertThat(resolve(Map.of("IMAGE_S3_BUCKET", "old-bucket"))).isEqualTo("old-bucket");
    }

    @Test
    @DisplayName("신키가 있으면 신키가 이긴다")
    void prefersNewKey() {
        assertThat(resolve(Map.of("FILE_S3_BUCKET", "new-bucket", "IMAGE_S3_BUCKET", "old-bucket")))
                .isEqualTo("new-bucket");
    }

    @Test
    @DisplayName("신키가 빈 문자열이면 구키로 넘어가지 않는다 — compose 가 빈 값을 넣으면 안 된다")
    void emptyNewKeyDoesNotFallBack() {
        // 빈 문자열도 "정의된 값"이라 폴백이 발동하지 않는다.
        // 그래서 compose 는 FILE_S3_BUCKET 을 아예 넘기지 않아야 한다.
        assertThat(resolve(Map.of("FILE_S3_BUCKET", "", "IMAGE_S3_BUCKET", "old-bucket"))).isEmpty();
    }

    @Test
    @DisplayName("둘 다 없으면 not-configured")
    void finalDefault() {
        assertThat(resolve(Map.of())).isEqualTo("not-configured");
    }
}
