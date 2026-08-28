package app.pickple.config;

import com.scalar.maven.core.ScalarHtmlRenderer;
import com.scalar.maven.core.ScalarProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Scalar API 문서 UI 를 직접 등록한다.
 *
 * <p><b>왜 자동설정을 쓰지 않는가</b> — {@code scalar-webmvc:0.6.61} 은 Spring Boot 3 기준으로
 * 빌드되어 Boot 4 에서 자동설정이 아예 로드되지 않는다(디버그 로그의 조건 평가 보고서에
 * 후보로도 오르지 않는다). 라이브러리의 렌더러({@code scalar-core})는 프레임워크와
 * 무관하게 동작하므로 컨트롤러만 직접 등록해 쓴다.
 *
 * <p>Boot 4 를 지원하는 버전이 나오면 이 클래스를 지우고
 * {@code application.yml} 의 {@code scalar.*} 설정만 남기면 된다.
 */
@Configuration
@RestController
@ConditionalOnProperty(prefix = "scalar", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ScalarConfig {

    private final ScalarProperties properties;

    public ScalarConfig(
            @Value("${scalar.url:/v3/api-docs}") String url,
            @Value("${scalar.path:/scalar}") String path,
            @Value("${scalar.page-title:API Docs}") String pageTitle,
            @Value("${scalar.dark-mode:true}") boolean darkMode) {
        this.properties = new ScalarProperties();
        this.properties.setUrl(url);
        this.properties.setPath(path);
        this.properties.setPageTitle(pageTitle);
        this.properties.setDarkMode(darkMode);
    }

    @GetMapping(value = "${scalar.path:/scalar}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> docs() throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(ScalarHtmlRenderer.render(properties));
    }

    /** 문서 페이지가 불러오는 Scalar 자바스크립트 번들. */
    @GetMapping("${scalar.path:/scalar}/scalar.js")
    public ResponseEntity<byte[]> scalarJs() throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/javascript"))
                .body(ScalarHtmlRenderer.getScalarJsContent());
    }
}
