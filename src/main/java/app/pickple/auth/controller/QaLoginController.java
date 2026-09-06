package app.pickple.auth.controller;

import app.pickple.auth.service.QaLoginService;
import app.pickple.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "소셜·QA 로그인 · 서비스 JWT")
@RestController
@RequiredArgsConstructor
@Profile("dev & !prod & !production")
@ConditionalOnProperty(prefix = "app.auth.qa-login", name = "enabled", havingValue = "true")
public class QaLoginController {

    private final QaLoginService qaLoginService;

    @Operation(summary = "QA 아이디·비밀번호 로그인",
            description = "dev에서 명시적으로 활성화한 경우에만 제공한다. 서버 설정의 QA 아이디와 "
                    + "BCrypt 비밀번호 해시를 검증한 뒤 기존 계정의 서비스 JWT를 발급한다. "
                    + "prod 또는 production이 함께 활성화되면 제공하지 않는다.")
    @PostMapping("/auth/login")
    public ApiResponse<AuthController.MobileTokenResponse> login(
            @Valid @RequestBody QaLoginRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        return ApiResponse.success(AuthController.MobileTokenResponse.from(
                qaLoginService.login(request.loginId(), request.password())));
    }

    public record QaLoginRequest(
            @Schema(description = "서버에 설정한 QA 로그인 아이디", example = "qa-user")
            @NotBlank @Size(max = 100)
            @Pattern(regexp = "^[A-Za-z0-9._@+-]+$") String loginId,
            @Schema(description = "QA 로그인 비밀번호")
            @NotBlank @Size(max = 256) String password) {

        @Override
        public String toString() {
            return "QaLoginRequest[redacted]";
        }
    }
}
