package app.pickple.auth.controller;

import app.pickple.auth.config.AuthProperties;
import app.pickple.auth.domain.User;
import app.pickple.auth.oauth.OAuth2SuccessHandler;
import app.pickple.auth.security.CurrentUser;
import app.pickple.auth.service.AuthService;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "소셜 로그인 · JWT")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthProperties properties;

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
        return ApiResponse.success(new TokenResponse(tokens.accessToken()));
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

    public record MeResponse(Long userId, String email, String name, String provider, String role) {

        public static MeResponse from(User user) {
            return new MeResponse(
                    user.id(), user.email(), user.name(),
                    user.provider().name(), user.role().name());
        }
    }

    /** 리프레시 토큰은 본문에 담지 않는다. 쿠키로만 오간다. */
    public record TokenResponse(String accessToken) {
    }
}
