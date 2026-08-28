package app.pickple.docs;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서 공통 설정.
 */
@Configuration
public class DocsConfig {

    /**
     * 스펙의 제목과 버전.
     *
     * <p>이 빈이 없으면 springdoc 이 "OpenAPI definition / v0" 이라는 기본값을 쓴다.
     * Swagger UI·Scalar·{@code /llms.txt} 가 모두 같은 스펙을 읽으므로 여기 한 곳만 채우면
     * 세 문서 표면에 함께 반영된다.
     *
     * <p>{@code llms-txt.enabled} 조건을 걸지 않는다 — llms.txt 를 꺼도
     * Swagger UI 와 Scalar 는 제목이 필요하다.
     */
    @Bean
    public OpenAPI buyOrPassOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Buy or Pass API")
                .description("중고 거래 판단 서비스 API")
                .version("v1"));
    }

    /**
     * 문서 렌더러.
     *
     * <p>렌더러 자체는 스프링을 모르는 순수 클래스다(그래야 순환 참조 전개를 컨테이너 없이 테스트한다).
     * 그래서 {@code @Component} 를 붙이는 대신 여기서 조립한다.
     * {@link LlmsTxtController} 와 같은 조건을 걸어 둘이 함께 켜지고 함께 꺼지도록 한다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "llms-txt", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OpenApiMarkdownRenderer openApiMarkdownRenderer() {
        return new OpenApiMarkdownRenderer();
    }
}
