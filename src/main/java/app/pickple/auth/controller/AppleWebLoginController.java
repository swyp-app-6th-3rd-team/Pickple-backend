package app.pickple.auth.controller;

import app.pickple.auth.apple.AppleWebLoginCompletionService;
import app.pickple.auth.apple.AppleWebLoginService;
import app.pickple.auth.service.AuthService;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@Tag(name = "Auth", description = "소셜 로그인 · JWT")
@RestController
@RequiredArgsConstructor
public class AppleWebLoginController {

    private final AppleWebLoginService loginService;
    private final AppleWebLoginCompletionService completionService;

    @Operation(summary = "Galaxy Apple 웹 로그인 시작",
            description = "앱 handoff S256 challenge를 서버 state·nonce와 묶고 Apple 로그인 화면으로 이동한다.")
    @GetMapping("/auth/apple/web")
    public ResponseEntity<Void> start(
            @Parameter(description = "앱이 보관한 verifier의 S256 Base64URL challenge")
            @RequestParam("code_challenge") String codeChallenge,
            @Parameter(description = "S256 고정")
            @RequestParam("code_challenge_method") String codeChallengeMethod,
            @Parameter(description = "앱의 로그인 시도 식별값")
            @RequestParam("app_state") String appState) {
        URI authorizationUri = loginService.start(codeChallenge, codeChallengeMethod, appState);
        return redirect(HttpStatus.FOUND, authorizationUri);
    }

    @Operation(summary = "Apple 웹 로그인 콜백",
            description = "Apple의 form POST를 검증하고 자격 증명이 없는 일회용 앱 handoff code로 리다이렉트한다.")
    @PostMapping(value = "/auth/apple/web/callback",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> callback(HttpServletRequest request, HttpServletResponse response) {
        preventCaching(response);
        Map<String, String[]> parameters = request.getParameterMap();
        AppleWebLoginService.CallbackCommand command = new AppleWebLoginService.CallbackCommand(
                single(parameters, "code"), requiredSingle(parameters, "state"),
                single(parameters, "user"), single(parameters, "error"));
        return redirect(HttpStatus.SEE_OTHER, loginService.callback(command));
    }

    @Operation(summary = "Galaxy Apple 로그인 토큰 교환",
            description = "딥링크의 일회용 code와 앱이 보관한 verifier를 교환해 Pickple access/refresh JWT를 받는다.")
    @PostMapping("/auth/apple/web/exchange")
    public ApiResponse<AuthController.MobileTokenResponse> exchange(
            @Valid @RequestBody AppleWebExchangeRequest request,
            HttpServletResponse response) {
        AuthService.TokenPair tokens = completionService.exchange(request.code(), request.codeVerifier());
        preventCaching(response);
        return ApiResponse.success(AuthController.MobileTokenResponse.from(tokens));
    }

    private ResponseEntity<Void> redirect(HttpStatus status, URI location) {
        return ResponseEntity.status(status)
                .location(location)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    private String requiredSingle(Map<String, String[]> parameters, String name) {
        String value = single(parameters, name);
        if (value == null) {
            throw new ApiException(ResponseCode.INVALID_REQUEST);
        }
        return value;
    }

    private String single(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);
        if (values == null) {
            return null;
        }
        if (values.length != 1 || values[0] == null || values[0].isBlank()) {
            throw new ApiException(ResponseCode.INVALID_REQUEST);
        }
        return values[0];
    }

    private void preventCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    public record AppleWebExchangeRequest(
            @Schema(description = "딥링크로 받은 60초 일회용 handoff code")
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String code,
            @Schema(description = "로그인 시작 전에 앱이 생성·보관한 RFC 7636 verifier")
            @NotBlank @Size(min = 43, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._~-]+") String codeVerifier) {

        @Override
        public String toString() {
            return "AppleWebExchangeRequest[redacted]";
        }
    }
}
