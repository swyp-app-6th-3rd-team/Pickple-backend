package app.pickple.docs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서 공통 설정.
 */
@Configuration
public class DocsConfig {

    /** 문서 최상단(Scalar 의 Introduction)에 싣는 소개. 마크다운이 그대로 렌더된다. */
    private static final String DESCRIPTION = """
            Pickple API

            ## ERD

            **[전체 화면으로 열기](/docs/erd.html)** — 이 소개 칼럼은 폭이 470px 남짓이라
            16개 엔티티가 다 들어가면 글자가 뭉갠다. 아래 그림은 미리보기이고, 실제로 읽을
            때는 위 링크를 연다(확대·팬·검색과 테마 전환이 된다).

            [![Pickple ERD](/docs/erd.svg)](/docs/erd.html)

            도메인 구조를 읽기 위한 논리 ERD 다. 자료형·인덱스까지 담은 물리 ERD 는
            `docs/erd/erd.mmd` 에 있고, 실제 스키마와 어긋나면 CI 가 막는다.
            원본을 고쳤으면 `scripts/render-erd.sh` 로 다시 렌더한다.

            ## 인증

            자물쇠가 붙은 요청에는 `Authorization: Bearer {accessToken}` 이 필요하다.
            우측 상단 **Authorize** 에 토큰을 넣으면 이 문서에서 그대로 시험할 수 있다.
            자물쇠가 없는 요청은 로그인 없이 부를 수 있다.
            """;

    /**
     * 스펙의 제목·버전·소개, 그리고 인증 스킴.
     *
     * <p>이 빈이 없으면 springdoc 이 "OpenAPI definition / v0" 이라는 기본값을 쓴다.
     * Swagger UI·Scalar·{@code /llms.txt} 가 모두 같은 스펙을 읽으므로 여기 한 곳만 채우면
     * 세 문서 표면에 함께 반영된다.
     *
     * <p>{@code llms-txt.enabled} 조건을 걸지 않는다 — llms.txt 를 꺼도
     * Swagger UI 와 Scalar 는 제목이 필요하다.
     *
     * <p><b>여기서는 스킴만 정의한다</b>(ADR-0034). springdoc 은 {@code SecurityConfig} 를
     * 읽지 않으므로 스킴을 등록하지 않으면 Authorize 버튼 자체가 뜨지 않는다.
     *
     * <p>어느 API 가 인증을 요구하는지는 <b>각 핸들러의
     * {@code @SecurityRequirement(name = "bearerAuth")}</b> 가 정한다.
     * 전역 {@code addSecurityItem} 을 걸고 공개 엔드포인트에서 푸는 방법도 있지만,
     * 그러면 인증이 필요한 API 에는 아무 표시가 없고 공개 API 에만 애노테이션이 붙어
     * 코드를 읽는 사람이 정반대로 읽는다. 표시는 요구하는 쪽에 붙인다.
     */
    @Bean
    public OpenAPI pickpleOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Pickple API")
                        .description(DESCRIPTION)
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("로그인 응답의 accessToken. 접두어 없이 토큰만 넣는다.")));
    }

    /**
     * 문서 렌더러.
     *
     * <p>렌더러 자체는 스프링을 모르는 순수 클래스다(그래야 순환 참조 전개를 컨테이너 없이 테스트한다).
     * 그래서 {@code @Component} 를 붙이는 대신 여기서 조립한다.
     * {@link LlmsTextController} 와 같은 조건을 걸어 둘이 함께 켜지고 함께 꺼지도록 한다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "llms-txt", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OpenApiMarkdownRenderer openApiMarkdownRenderer() {
        return new OpenApiMarkdownRenderer();
    }
}
