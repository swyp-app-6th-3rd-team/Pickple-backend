package app.pickple.auth.apple;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Apple OAuth token API의 HTTP 계약. */
@HttpExchange(contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
public interface AppleTokenClient {

    @PostExchange(url = "/auth/token", accept = MediaType.APPLICATION_JSON_VALUE)
    AppleTokenResponse exchangeAuthorizationCode(
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam("code") String authorizationCode,
            @RequestParam("grant_type") String grantType);

    @PostExchange("/auth/revoke")
    void revokeRefreshToken(
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam("token") String refreshToken,
            @RequestParam("token_type_hint") String tokenTypeHint);
}
