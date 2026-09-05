package app.pickple.auth.controller;

import app.pickple.auth.service.DevLoginService;
import app.pickple.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "소셜 로그인 · JWT")
@RestController
@RequiredArgsConstructor
@Profile("dev & !prod & !production")
@ConditionalOnProperty(prefix = "app.auth.dev-login", name = "enabled", havingValue = "true")
public class DevLoginController {

    private final DevLoginService devLoginService;

    @Operation(summary = "dev 전용 QA 로그인",
            description = "dev에서 명시적으로 활성화한 경우에만 제공한다. X-QA-Login-Key와 허용된 QA 사용자 ID로 "
                    + "서비스 JWT를 발급한다. prod 또는 production이 함께 활성화되면 제공하지 않는다.")
    @PostMapping("/auth/dev/login")
    public ApiResponse<AuthController.MobileTokenResponse> login(
            @Valid @RequestBody DevLoginRequest request,
            @Parameter(description = "서버에 설정한 QA 전용 키", required = true)
            @RequestHeader(name = "X-QA-Login-Key", required = false) String key,
            HttpServletResponse response) {
        // 오류 응답에도 토큰·자격증명 관련 응답을 캐시하지 않는다.
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        return ApiResponse.success(AuthController.MobileTokenResponse.from(
                devLoginService.login(request.userId(), key)));
    }

    public record DevLoginRequest(
            @Schema(description = "서버의 허용 목록에 등록한 QA 사용자 식별자", example = "123")
            @NotNull @Positive Long userId) {
    }
}
