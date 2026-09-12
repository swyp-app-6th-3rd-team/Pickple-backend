package app.pickple.docs;

import app.pickple.support.IntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 문서 표면을 <b>스펙 JSON 을 직접 떠서</b> 검증한다.
 *
 * <p><b>왜 이 테스트가 필요한가.</b> {@code openapi-documentation} 룰의 완료 판정은
 * "빌드 green" 을 대리지표로 금지하고 스펙 JSON 을 직접 세라고 요구한다. 그 계수를
 * 사람이 손으로 한 번 떠 보고 PR 에 적으면, <b>그 숫자는 그 순간에만 참</b>이다 —
 * 다음 사람이 엔드포인트를 하나 추가하면 조용히 틀린 값이 문서에 남는다.
 * (실제로 이 사이클에서 그런 일이 있었다: 손으로 센 "공개 11개" 가 다른 PR 의
 * {@code /posts/random} 이 머지되며 12개가 됐다.)
 *
 * <p><b>{@code ArchitectureTest} 와 다른 것을 본다.</b> 그쪽은 바이트코드를 읽어
 * 애노테이션 유무를 검사하지만, springdoc 이 실제로 <b>렌더링한 결과</b>는 보지 못한다.
 * 애노테이션이 붙어 있어도 스펙에 반영되지 않는 경우(속성 이름 오타, 렌더러 설정)를
 * 잡으려면 완성된 JSON 을 봐야 한다.
 *
 * <p>부팅에 {@code .env} 가 필요하지 않다 — 통합 테스트 컨텍스트로 뜨므로
 * 검증하는 사람이 로컬 비밀 값을 갖추지 않아도 재현된다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class OpenApiSurfaceIT {

    /**
     * 응답 스키마를 검증할 대상. 최근 사이클이 추가한 게시글 상세·검색 DTO 다.
     *
     * <p>전체 스키마를 훑지 않는 이유는 <b>기존 부채까지 이 테스트가 떠안으면
     * 빨간불이 상수가 되어 아무도 안 보게 되기 때문</b>이다. 새로 만드는 것부터
     * 결손 0 을 지키고, 목록은 도메인이 정리될 때 넓힌다.
     */
    private static final List<String> DOCUMENTED_SCHEMAS = List.of(
            "PostDetailResponse", "VoteSection", "ProductItem", "OptionItem",
            "PostSearchResponse", "PostSearchItem");

    @Autowired
    private WebApplicationContext context;

    private DocumentContext spec;

    @BeforeEach
    void setUp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        spec = JsonPath.parse(body);
    }

    @Test
    @DisplayName("인증 스킴이 스펙에 정의돼 있다")
    void declaresSecurityScheme() {
        // 없으면 문서 UI 에 Authorize 버튼 자체가 뜨지 않는다.
        Map<String, Object> schemes = spec.read("$.components.securitySchemes");
        assertThat(schemes).containsKey("bearerAuth");
    }

    @Test
    @DisplayName("게스트 허용 엔드포인트에는 security 가 없고, 그 목록이 ArchUnit 정본과 일치한다")
    void publicEndpointsMatchTheSecondSourceOfTruth() {
        // springdoc 은 Spring Security 설정을 읽지 않는다. 그래서 "실제 인가" 와 "문서" 가
        // 갈릴 수 있고, 그 사이를 잇는 것이 테스트의 두 번째 정본이다.
        // 여기서는 렌더링된 스펙이 그 정본과 같은지 본다.
        Set<String> publicInSpec = new TreeSet<>();
        Set<String> authenticatedInSpec = new TreeSet<>();
        collectOperations(publicInSpec, authenticatedInSpec);

        assertThat(publicInSpec)
                .as("스펙의 공개 엔드포인트가 ArchUnit PUBLIC_ENDPOINTS 와 어긋난다. "
                        + "둘 중 하나만 고쳤다는 뜻이다")
                .isEqualTo(new TreeSet<>(ArchitectureTestPublicEndpoints.VALUES));

        // 개수까지 대조한다 — 집합이 같아도 총량이 바뀌면 새 엔드포인트가 누락된 것이다.
        assertThat(publicInSpec).hasSize(ArchitectureTestPublicEndpoints.VALUES.size());
        assertThat(authenticatedInSpec)
                .as("인증 엔드포인트가 하나도 없으면 렌더링이 깨진 것이다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("게시글 상세는 게스트 허용이라 security 가 붙지 않는다")
    void postDetailIsPublicInSpec() {
        Map<String, Object> operation = spec.read("$.paths['/posts/{id}'].get");
        assertThat(operation)
                .as("게스트 허용 엔드포인트에 @SecurityRequirement 가 붙으면 문서가 거짓말을 한다")
                .doesNotContainKey("security");

        List<String> tags = spec.read("$.paths['/posts/{id}'].get.tags");
        assertThat(tags).containsExactly("Post");
    }

    @Test
    @DisplayName("자동 생성된 *-controller 태그 잔재가 없다")
    void hasNoGeneratedControllerTags() {
        // @Tag 를 빠뜨리면 springdoc 이 클래스명에서 vote-controller 같은 태그를 만들어
        // 사람이 붙인 태그와 표기가 섞인다. llms.txt 렌더러가 tags[0] 으로 묶으므로
        // 태그가 곧 문서의 목차다.
        JSONArray tags = spec.read("$.paths..tags[*]");
        List<String> generated = tags.stream()
                .map(String::valueOf)
                .filter(tag -> tag.endsWith("-controller"))
                .toList();

        assertThat(generated).isEmpty();
    }

    @Test
    @DisplayName("새 게시글 응답 DTO 는 모든 필드에 설명이 있다")
    void postSchemasDocumentEveryField() {
        // 필드 의미를 지어내지 않기 위한 규율의 뒷면이다 — 근거를 못 찾으면 최소 서술을
        // 남기더라도 빈 칸으로 두지 않는다. 빈 칸은 시간순으로 쌓여 부채가 된다.
        List<String> missing = new ArrayList<>();
        int total = 0;

        for (String schema : DOCUMENTED_SCHEMAS) {
            Map<String, Map<String, Object>> properties =
                    spec.read("$.components.schemas." + schema + ".properties");
            assertThat(properties)
                    .as("%s 스키마가 스펙에 없다 — 이름이 바뀌었거나 렌더링되지 않았다", schema)
                    .isNotEmpty();

            for (Map.Entry<String, Map<String, Object>> field : properties.entrySet()) {
                total++;
                Object description = field.getValue().get("description");
                if (description == null || String.valueOf(description).isBlank()) {
                    missing.add(schema + "." + field.getKey());
                }
            }
        }

        assertThat(missing).as("설명이 빈 필드").isEmpty();
        assertThat(total).as("검사한 필드가 없으면 이 테스트는 아무것도 지키지 않는다").isPositive();
    }

    /** {@code "GET /posts"} 형태로 모으고 security 유무로 가른다. */
    private void collectOperations(Set<String> publicOnes, Set<String> authenticatedOnes) {
        Map<String, Map<String, Object>> paths = spec.read("$.paths");
        for (Map.Entry<String, Map<String, Object>> path : paths.entrySet()) {
            for (Map.Entry<String, Object> operation : path.getValue().entrySet()) {
                if (!HTTP_METHODS.contains(operation.getKey())) {
                    continue;
                }
                String key = operation.getKey().toUpperCase() + " " + path.getKey();
                Map<?, ?> body = (Map<?, ?>) operation.getValue();
                if (body.containsKey("security")) {
                    authenticatedOnes.add(key);
                } else {
                    publicOnes.add(key);
                }
            }
        }
    }

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete");

    /**
     * ArchUnit 이 들고 있는 공개 목록의 사본.
     *
     * <p>{@code ArchitectureTest.PUBLIC_ENDPOINTS} 는 중첩 클래스의 private 상수라
     * 밖에서 읽을 수 없다. 세 번째 정본을 만드는 셈이지만, <b>어긋나면 이 테스트가
     * 실패해서 알려준다</b>는 점이 값어치다 — 조용히 갈라지는 것보다 낫다.
     * 공개 엔드포인트를 늘리거나 줄이면 {@code SecurityConfig}·{@code ArchitectureTest}·
     * 여기 셋을 함께 고쳐야 한다.
     */
    static final class ArchitectureTestPublicEndpoints {

        static final List<String> VALUES = List.of(
                "GET /posts",
                "GET /posts/popular",
                "GET /posts/search",
                "GET /posts/random",
                "GET /posts/{id}",
                "GET /users/nickname/availability",
                "GET /rankings",
                "GET /rankings/top",
                "POST /auth/apple",
                "POST /auth/kakao",
                "POST /auth/refresh",
                "POST /auth/mobile/refresh",
                "POST /auth/logout");

        private ArchitectureTestPublicEndpoints() {
        }
    }
}
