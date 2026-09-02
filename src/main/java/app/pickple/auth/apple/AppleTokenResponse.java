package app.pickple.auth.apple;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Apple /auth/token 성공 응답. 값은 로그에 남기지 않는다. */
public record AppleTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("id_token") String idToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType) {

    @Override
    public String toString() {
        return "AppleTokenResponse[redacted]";
    }
}
