package app.pickple.auth.controller;

import app.pickple.auth.apple.AppleAuthService;
import app.pickple.auth.domain.User;
import app.pickple.auth.kakao.KakaoAuthService;
import app.pickple.auth.oauth.OAuth2SuccessHandler;
import app.pickple.auth.security.CurrentUser;
import app.pickple.auth.service.AccountWithdrawalService;
import app.pickple.auth.service.AuthService;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import app.pickple.config.AuthProperties;
import app.pickple.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "소셜 로그인 · JWT")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AppleAuthService appleAuthService;
    private final KakaoAuthService kakaoAuthService;
    private final AccountWithdrawalService accountWithdrawalService;
    private final AuthProperties properties;

    @Operation(summary = "Apple 네이티브 로그인",
            description = "iOS가 받은 authorization code와 identity token을 서버에서 다시 검증한 뒤 서비스 JWT를 발급한다. "
                    + "iOS는 로그인마다 안전한 새 rawNonce를 만들고 Apple 요청 nonce에 "
                    + "lowercase hex SHA-256(rawNonce)를 넣어야 한다.")
    @PostMapping("/auth/apple")
    public ApiResponse<MobileTokenResponse> appleLogin(
            @Valid @RequestBody AppleLoginRequest request,
            HttpServletResponse response) {
        AuthService.TokenPair tokens = appleAuthService.login(
                request.authorizationCode(), request.identityToken(), request.rawNonce(), request.name());
        preventTokenCaching(response);
        return ApiResponse.success(MobileTokenResponse.from(tokens));
    }

    @Operation(summary = "Kakao 네이티브 로그인",
            description = "iOS Kakao SDK가 받은 OIDC ID token과 로그인 요청에 사용한 원문 nonce를 "
                    + "서버에서 검증한 뒤 서비스 JWT와 프로필 등록 완료 여부를 반환한다.")
    @PostMapping("/auth/kakao")
    public ApiResponse<KakaoLoginResponse> kakaoLogin(
            @Valid @RequestBody KakaoLoginRequest request,
            HttpServletResponse response) {
        AuthService.LoginResult result = kakaoAuthService.login(request.identityToken(), request.nonce());
        preventTokenCaching(response);
        return ApiResponse.success(KakaoLoginResponse.from(result));
    }

    @Operation(summary = "내 정보",
            description = "Authorization: Bearer {accessToken} 이 필요하다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/auth/me")
    public ApiResponse<MeResponse> me(@Parameter(hidden = true) @CurrentUser Long userId) {
        if (userId == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        return ApiResponse.success(MeResponse.from(authService.getById(userId)));
    }

    @Operation(summary = "토큰 재발급",
            description = "리프레시 토큰은 HttpOnly 쿠키에서 읽는다. 성공 시 쿠키도 새 값으로 교체된다.")
    @PostMapping("/auth/refresh")
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
    @PostMapping("/auth/mobile/refresh")
    public ApiResponse<MobileTokenResponse> mobileRefresh(
            @Valid @RequestBody MobileRefreshRequest request,
            HttpServletResponse response) {
        AuthService.TokenPair tokens = authService.refresh(request.refreshToken());
        preventTokenCaching(response);
        return ApiResponse.success(MobileTokenResponse.from(tokens));
    }

    @Operation(summary = "로그아웃", description = "저장된 리프레시 토큰을 폐기하고 쿠키를 지운다.")
    @PostMapping("/auth/logout")
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
                    + "Apple 일시 장애 시 로컬 상태를 변경하지 않고 503을 반환하므로 재시도할 수 있다. "
                    + "저장된 provider token이 없으면 로컬 탈퇴를 완료하고 수동 연결 해제가 필요한 성공 코드를 반환한다. "
                    + "Kakao 사용자는 서버가 Kakao 연결을 먼저 해제한 뒤 로컬 탈퇴를 확정한다.")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/auth/me")
    public ApiResponse<Void> withdraw(@Parameter(hidden = true) @CurrentUser Long userId,
                                      HttpServletResponse response) {
        if (userId == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        AccountWithdrawalService.WithdrawalOutcome outcome = accountWithdrawalService.withdraw(userId);
        expireRefreshTokenCookie(response);
        preventTokenCaching(response);
        if (outcome == AccountWithdrawalService.WithdrawalOutcome.COMPLETED_REQUIRES_MANUAL_APPLE_REVOCATION) {
            return ApiResponse.of(ResponseCode.APPLE_MANUAL_REVOCATION_REQUIRED, null);
        }
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

    public record MeResponse(
            @Schema(description = "사용자 식별자") Long userId,
            @Schema(description = "소셜 프로바이더가 준 이메일. 로그인마다 갱신된다") String email,
            @Schema(description = "소셜 프로바이더가 준 이름. 닉네임과 다르다") String name,
            @Schema(description = "GOOGLE | KAKAO | NAVER | APPLE") String provider,
            @Schema(description = "ROLE_USER | ROLE_ADMIN") String role) {

        public static MeResponse from(User user) {
            return new MeResponse(
                    user.id(), user.email(), user.name(),
                    user.provider().name(), user.role().name());
        }
    }

    /** 웹 OAuth2 흐름에서는 리프레시 토큰을 본문에 담지 않고 쿠키로만 전달한다. */
    public record TokenResponse(
            @Schema(description = "서비스 액세스 토큰. 리프레시 토큰은 본문에 없고 HttpOnly 쿠키로만 간다")
            String accessToken) {

        @Override
        public String toString() {
            return "TokenResponse[redacted]";
        }
    }

    /** 네이티브 앱은 두 토큰을 Keychain에 저장하므로 HTTPS JSON으로 함께 전달한다. */
    public record MobileTokenResponse(
            @Schema(description = "회전된 액세스 토큰") String accessToken,
            @Schema(description = "회전된 리프레시 토큰. Keychain 에 보관한다") String refreshToken) {

        static MobileTokenResponse from(AuthService.TokenPair tokens) {
            return new MobileTokenResponse(tokens.accessToken(), tokens.refreshToken());
        }

        @Override
        public String toString() {
            return "MobileTokenResponse[redacted]";
        }
    }

    /** Kakao 로그인은 프로필 등록 화면 분기에 필요한 상태를 토큰과 함께 반환한다. */
    public record KakaoLoginResponse(
            @Schema(description = "서비스 액세스 토큰") String accessToken,
            @Schema(description = "서비스 리프레시 토큰. Keychain에 보관한다") String refreshToken,
            @Schema(description = "서비스 프로필 등록 완료 여부. false이면 프로필 등록이 필요하다")
            boolean profileCompleted) {

        static KakaoLoginResponse from(AuthService.LoginResult result) {
            AuthService.TokenPair tokens = result.tokens();
            return new KakaoLoginResponse(
                    tokens.accessToken(), tokens.refreshToken(), result.profileCompleted());
        }

        @Override
        public String toString() {
            return "KakaoLoginResponse[redacted]";
        }
    }

    public record AppleLoginRequest(
            @Schema(description = "Apple 이 준 authorization code. 일회성 교환이 재전송 방어다")
            @NotBlank @Size(max = 4096) String authorizationCode,
            @Schema(description = "Apple ID token. 서버가 JWKS 의 RS256 서명을 다시 검증한다")
            @NotBlank @Size(max = 16_384) String identityToken,
            @Schema(description = "로그인마다 새로 만든 원문 nonce. Apple 요청에는 이것의 "
                    + "lowercase hex SHA-256 을 넣는다. 재사용하지 않는다")
            @NotBlank @Size(min = 16, max = 512) String rawNonce,
            @Schema(description = "Apple 이 최초 동의 때 준 경우만 보낸다")
            @Size(max = 100) String name) {

        @Override
        public String toString() {
            return "AppleLoginRequest[redacted]";
        }
    }

    public record KakaoLoginRequest(
            @Schema(description = "Kakao SDK가 발급한 OIDC ID token")
            @NotBlank @Size(max = 16_384) String identityToken,
            @Schema(description = "로그인마다 새로 만들고 Kakao SDK 요청에도 사용한 원문 nonce")
            @NotBlank @Size(min = 16, max = 512) String nonce) {

        @Override
        public String toString() {
            return "KakaoLoginRequest[redacted]";
        }
    }

    public record MobileRefreshRequest(
            @Schema(description = "Keychain 에 보관한 리프레시 토큰")
            @NotBlank @Size(max = 4096) String refreshToken) {

        @Override
        public String toString() {
            return "MobileRefreshRequest[redacted]";
        }
    }
}
