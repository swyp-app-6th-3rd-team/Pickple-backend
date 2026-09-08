package app.pickple.auth.controller;

import app.pickple.auth.service.AuthService;
import io.swagger.v3.oas.annotations.media.Schema;

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
