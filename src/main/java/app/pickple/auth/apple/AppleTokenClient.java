package app.pickple.auth.apple;

public interface AppleTokenClient {

    AppleTokenResponse exchangeAuthorizationCode(String authorizationCode);

    void revokeRefreshToken(String refreshToken);
}
