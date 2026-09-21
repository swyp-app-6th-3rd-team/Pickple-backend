package app.pickple.auth.controller;

import app.pickple.auth.service.QaLoginService;
import app.pickple.auth.domain.QaAccount;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "소셜·QA 로그인 · 서비스 JWT")
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.auth.qa-login", name = "enabled", havingValue = "true")
public class QaLoginController {

    private final QaLoginService qaLoginService;

    @Operation(summary = "QA 아이디·비밀번호 로그인",
            description = "DB에 저장된 QA 아이디와 BCrypt 해시를 "
                    + "검증하고 전용 일반 사용자의 서비스 JWT를 발급한다. OAuth2 인증이나 소셜 가입이 필요하지 않다. "
                    + "잘못된 자격증명이나 사용할 수 없는 계정은 401이다.")
    @PostMapping("/auth/login")
    public ApiResponse<MobileTokenResponse> login(
            @Valid @RequestBody QaLoginRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        return ApiResponse.success(MobileTokenResponse.from(
                qaLoginService.login(request.loginId(), request.password())));
    }

    public record QaLoginRequest(
            @Schema(description = "관리자가 생성한 QA 로그인 아이디. 대소문자 구분", example = "qa-user")
            @NotBlank @Size(max = 100)
            @Pattern(regexp = QaAccount.LOGIN_ID_PATTERN) String loginId,
            @Schema(description = "QA 로그인 비밀번호. UTF-8 기준 최대 72바이트")
            @NotBlank @Size(max = 256) String password) {

        @Override
        public String toString() {
            return "QaLoginRequest[redacted]";
        }
    }
}
