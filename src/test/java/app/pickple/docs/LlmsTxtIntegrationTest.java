package app.pickple.docs;

import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배선 검증 — springdoc 스펙이 실제로 렌더까지 흘러오는지 본다.
 *
 * <p>렌더링 로직은 {@link OpenApiMarkdownRendererTest} 가 컨테이너 없이 덮으므로
 * 여기서는 <b>단위 테스트로는 못 잡는 것</b>만 확인한다. 즉 스프링 배선과 시큐리티다.
 * 그래서 의도적으로 얇다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class LlmsTxtIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 시큐리티 필터를 붙이지 않으면 permitAll 검증이 아무것도 증명하지 못한다.
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    /**
     * SecurityConfig 의 PUBLIC_GET 회귀 가드.
     *
     * <p>FE 가 브라우저로 열어 긁어가는 문서라 인증 없이 200 이어야 한다.
     * 누군가 PUBLIC_GET 에서 경로를 빼면 401 이 되고 이 테스트가 잡는다.
     */
    @Test
    @DisplayName("인증 없이도 200 이고 text/plain 으로 나간다")
    void servedWithoutAuthentication() throws Exception {
        String body = mockMvc.perform(get("/llms.txt"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isNotBlank();
    }

    @Test
    @DisplayName("Content-Type 이 text/plain 이라 브라우저가 다운로드하지 않는다")
    void servesPlainText() throws Exception {
        String contentType = mockMvc.perform(get("/llms.txt"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentType();

        assertThat(contentType).startsWith("text/plain");
    }

    @Test
    @DisplayName("현재 기동된 컨트롤러의 경로가 문서에 실린다")
    void reflectsRunningControllers() throws Exception {
        // 이 기능의 존재 이유 — 손으로 쓴 문서가 아니라 springdoc 스펙에서 나온다는 증거.
        String body = mockMvc.perform(get("/llms.txt"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("/api/auth/me");
    }

    @Test
    @DisplayName("/llms.md 는 /llms.txt 와 같은 본문이다")
    void markdownAliasServesSameBody() throws Exception {
        String txt = mockMvc.perform(get("/llms.txt"))
                .andReturn().getResponse().getContentAsString();
        String md = mockMvc.perform(get("/llms.md"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(md).isEqualTo(txt);
    }
}
