package app.pickple.docs;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class LlmsTxtControllerTest {

    @Mock
    private OpenApiWebMvcResource openApiResource;
    @Mock
    private OpenApiMarkdownRenderer renderer;
    @Mock
    private HttpServletRequest request;
    @InjectMocks
    private LlmsTxtController controller;

    @Test
    void mapsOpenApiFailureToSystemError() throws Exception {
        RuntimeException cause = new RuntimeException("springdoc failure");
        given(openApiResource.openapiJson(request, "/v3/api-docs", Locale.KOREAN)).willThrow(cause);

        assertThatThrownBy(() -> controller.llmsTxt(request, Locale.KOREAN))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ResponseCode.SYSTEM_ERROR);
                    assertThat(exception.getCause()).isSameAs(cause);
                });
    }
}
