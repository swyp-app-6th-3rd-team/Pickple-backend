package app.pickple.docs;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * API 문서를 LLM 이 읽기 좋은 마크다운으로 서빙한다.
 *
 * <p>FE 개발자가 API 계약을 LLM 프롬프트에 그대로 붙여넣기 위한 표면이다.
 * Swagger UI 와 Scalar 는 사람이 브라우저로 읽는 전제라 긁으면 마크업이 딸려오고,
 * {@code /v3/api-docs} 는 {@code $ref} 로 정규화돼 있어 스키마가 흩어진다.
 *
 * <p><b>왜 JSON 을 한 번 거치는가</b> — 완성된 {@code OpenAPI} 객체를 주는
 * {@code AbstractOpenApiResource.getOpenApi(Locale)} 는 <b>protected</b> 라 밖에서 부를 수 없다.
 * public 으로 열린 건 JSON 바이트를 주는 {@link OpenApiWebMvcResource#openapiJson} 뿐이다.
 * 직렬화 후 다시 파싱하는 왕복이 한 번 생기지만, 문서 생성 경로는 요청당 ms 가 아쉬운 자리가
 * 아니라서 <b>public API 만 쓰는 안정성</b>을 택했다.
 *
 * <p><b>여기서 손대면 안 되는 것</b> — 이 왕복이 낭비로 보인다고
 * {@code OpenApiWebMvcResource} 를 상속해 {@code getOpenApi} 를 뚫으면 안 된다.
 * 그 클래스에는 {@code @RestController} 와 {@code @GetMapping} 이 붙어 있어서
 * 하위 클래스를 빈으로 등록하는 순간 {@code /v3/api-docs} 매핑이 중복돼 <b>기동이 깨진다</b>.
 * 자세한 사유는 ADR-0011 의 "검토한 대안" 참조.
 *
 * <p><b>Jackson 주의</b> — springdoc 은 Jackson 2({@code com.fasterxml.jackson})로 직렬화한다.
 * 이 프로젝트에는 Spring Boot 4 가 쓰는 Jackson 3({@code tools.jackson})도 함께 올라와 있고
 * 두 라이브러리의 {@code ObjectMapper} 이름이 같아 import 를 잘못 써도 컴파일이 통과한다.
 * 그래서 스프링이 관리하는 {@code ObjectMapper} 빈을 주입하지 않고,
 * swagger 모델과 버전이 맞춰진 {@link Json31#mapper()} 를 쓴다.
 */
@Hidden      // 문서 엔드포인트 자신이 문서에 실릴 이유가 없다
@RestController
@ConditionalOnProperty(prefix = "llms-txt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LlmsTxtController {

    private final OpenApiWebMvcResource openApiResource;
    private final OpenApiMarkdownRenderer renderer;

    public LlmsTxtController(OpenApiWebMvcResource openApiResource, OpenApiMarkdownRenderer renderer) {
        this.openApiResource = openApiResource;
        this.renderer = renderer;
    }

    /**
     * {@code text/markdown} 이 아니라 {@code text/plain} 이다.
     * {@code text/markdown} 을 주면 브라우저가 파일 다운로드를 띄우는 경우가 있어,
     * FE 가 브라우저에서 열어 긁어가는 동선이 끊긴다.
     */
    @GetMapping(value = {"/llms.txt", "/llms.md"}, produces = "text/plain;charset=UTF-8")
    public String llmsTxt(HttpServletRequest request, Locale locale) {
        return renderer.render(readSpec(request, locale));
    }

    private JsonNode readSpec(HttpServletRequest request, Locale locale) {
        try {
            byte[] json = openApiResource.openapiJson(request, "/v3/api-docs", locale);
            return Json31.mapper().readTree(json);
        } catch (Exception e) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "OpenAPI 스펙을 읽지 못했다", e);
        }
    }
}
