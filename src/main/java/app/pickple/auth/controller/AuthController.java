package app.pickple.auth.controller;

import app.pickple.auth.apple.AppleAuthService;
import app.pickple.auth.config.AuthProperties;
import app.pickple.auth.domain.User;
import app.pickple.auth.oauth.OAuth2SuccessHandler;
import app.pickple.auth.security.CurrentUser;
import app.pickple.auth.service.AccountWithdrawalService;
import app.pickple.auth.service.AuthService;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "소셜 로그인 · JWT")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AppleAuthService appleAuthService;
    private final AccountWithdrawalService accountWithdrawalService;
    private final AuthProperties properties;

    @Operation(summary = "Apple 네이티브 로그인",
            description = "iOS가 받은 authorization code와 identity token을 서버에서 다시 검증한 뒤 서비스 JWT를 발급한다. "
                    + "iOS는 로그인마다 안전한 새 rawNonce를 만들고 Apple 요청 nonce에 "
                    + "lowercase hex SHA-256(rawNonce)를 넣어야 한다.")
    @PostMapping("/apple")
    public ApiResponse<MobileTokenResponse> appleLogin(
            @Valid @RequestBody AppleLoginRequest request,
            HttpServletResponse response) {
        AuthService.TokenPair tokens = appleAuthService.login(
                request.authorizationCode(), request.identityToken(), request.rawNonce(), request.name());
        preventTokenCaching(response);
        return ApiResponse.success(MobileTokenResponse.from(tokens));
    }

    @Operation(summary = "내 정보",
            description = "Authorization: Bearer {accessToken} 이 필요하다.")
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@Parameter(hidden = true) @CurrentUser Long userId) {
        if (userId == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        return ApiResponse.success(MeResponse.from(authService.getById(userId)));
    }

    @Operation(summary = "토큰 재발급",
            description = "리프레시 토큰은 HttpOnly 쿠키에서 읽는다. 성공 시 쿠키도 새 값으로 교체된다.")
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(
            @CookieValue(name = OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {

        AuthService.TokenPair tokens = authService.refresh(refreshToken);
        addRefreshTokenCookie(response, tokens.refreshToken());
        preventTokenCaching(response);
        return ApiResponse.success(new TokenResponse(tokens.accessToken()));
    }

    @Operation(summary = "모바일 토큰 재발급",
            description = "Keychain에 보관한 refresh token을 받아 회전된 access/refresh token을 JSON으로 반환한다.")
    @PostMapping("/mobile/refresh")
    public ApiResponse<MobileTokenResponse> mobileRefresh(
            @Valid @RequestBody MobileRefreshRequest request,
            HttpServletResponse response) {
        AuthService.TokenPair tokens = authService.refresh(request.refreshToken());
        preventTokenCaching(response);
        return ApiResponse.success(MobileTokenResponse.from(tokens));
    }

    @Operation(summary = "로그아웃", description = "저장된 리프레시 토큰을 폐기하고 쿠키를 지운다.")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Parameter(hidden = true) @CurrentUser Long userId,
                                    HttpServletResponse response) {
        if (userId != null) {
            authService.logout(userId);
        }
        expireRefreshTokenCookie(response);
        return ApiResponse.success(null);
    }

    @Operation(summary = "회원 탈퇴",
            description = "Apple 사용자는 저장된 provider refresh token으로 Apple 연결을 해제한 뒤 계정을 비활성화한다. "
                    + "Apple 일시 장애 시 로컬 상태를 변경하지 않고 503을 반환하므로 재시도할 수 있다.")
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@Parameter(hidden = true) @CurrentUser Long userId,
                                      HttpServletResponse response) {
        if (userId == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        accountWithdrawalService.withdraw(userId);
        expireRefreshTokenCookie(response);
        preventTokenCaching(response);
        return ApiResponse.success(null);
    }

    private void addRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie(OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE, refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.auth().cookieSecure());
        cookie.setPath("/");
        cookie.setMaxAge((int) properties.jwt().refreshTokenValidity().toSeconds());
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private void expireRefreshTokenCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.auth().cookieSecure());
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private void preventTokenCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }

    public record MeResponse(Long userId, String email, String name, String provider, String role) {

        public static MeResponse from(User user) {
            return new MeResponse(
                    user.id(), user.email(), user.name(),
                    user.provider().name(), user.role().name());
        }
    }

    /** 웹 OAuth2 흐름에서는 리프레시 토큰을 본문에 담지 않고 쿠키로만 전달한다. */
    public record TokenResponse(String accessToken) {

        @Override
        public String toString() {
            return "TokenResponse[redacted]";
        }
    }

    /** 네이티브 앱은 두 토큰을 Keychain에 저장하므로 HTTPS JSON으로 함께 전달한다. */
    public record MobileTokenResponse(String accessToken, String refreshToken) {

        static MobileTokenResponse from(AuthService.TokenPair tokens) {
            return new MobileTokenResponse(tokens.accessToken(), tokens.refreshToken());
        }

        @Override
        public String toString() {
            return "MobileTokenResponse[redacted]";
        }
    }

    public record AppleLoginRequest(
            @NotBlank @Size(max = 4096) String authorizationCode,
            @NotBlank @Size(max = 16_384) String identityToken,
            @NotBlank @Size(min = 16, max = 512) String rawNonce,
            @Size(max = 100) String name) {

        @Override
        public String toString() {
            return "AppleLoginRequest[redacted]";
        }
    }

    public record MobileRefreshRequest(
            @NotBlank @Size(max = 4096) String refreshToken) {

        @Override
        public String toString() {
            return "MobileRefreshRequest[redacted]";
        }
    }
}
