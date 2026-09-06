package app.pickple.auth.apple;

import app.pickple.auth.domain.AppleClientType;
import app.pickple.common.ResponseCode;
import app.pickple.config.AppleProperties;
import app.pickple.config.AppleWebProperties;
import app.pickple.error.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Apple token HTTP 계약에 client별 인증값을 채우고 외부 오류를 서비스 오류로 변환한다. */
@Component
public class AppleTokenGateway {

    private static final String AUTHORIZATION_CODE_GRANT = "authorization_code";
    private static final String REFRESH_TOKEN_HINT = "refresh_token";

    private final AppleProperties properties;
    private final AppleWebProperties webProperties;
    private final AppleClientSecretProvider clientSecretProvider;
    private final AppleTokenClient tokenClient;

    @Autowired
    public AppleTokenGateway(AppleProperties properties,
                             AppleWebProperties webProperties,
                             AppleClientSecretProvider clientSecretProvider,
                             AppleTokenClient tokenClient) {
        this.properties = properties;
        this.webProperties = webProperties;
        this.clientSecretProvider = clientSecretProvider;
        this.tokenClient = tokenClient;
    }

    AppleTokenGateway(AppleProperties properties,
                      AppleClientSecretProvider clientSecretProvider,
                      AppleTokenClient tokenClient) {
        this(properties, AppleWebProperties.disabled(), clientSecretProvider, tokenClient);
    }

    public AppleTokenResponse exchangeAuthorizationCode(String authorizationCode) {
        ensureEnabled(AppleClientType.NATIVE);
        return exchange(AppleClientType.NATIVE, authorizationCode);
    }

    public AppleTokenResponse exchangeWebAuthorizationCode(String authorizationCode) {
        ensureEnabled(AppleClientType.WEB);
        return exchange(AppleClientType.WEB, authorizationCode);
    }

    public void revokeRefreshToken(String refreshToken) {
        revokeRefreshToken(AppleClientType.NATIVE, refreshToken);
    }

    public void revokeRefreshToken(AppleClientType clientType, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        }
        String clientId = clientId(clientType);
        try {
            tokenClient.revokeRefreshToken(clientId, clientSecretProvider.create(clientId),
                    refreshToken, REFRESH_TOKEN_HINT);
        } catch (ApiException | RestClientException e) {
            throw new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        }
    }

    private AppleTokenResponse exchange(AppleClientType clientType, String authorizationCode) {
        String clientId = clientId(clientType);
        try {
            String clientSecret = clientSecretProvider.create(clientId);
            AppleTokenResponse response = clientType == AppleClientType.WEB
                    ? tokenClient.exchangeWebAuthorizationCode(clientId, clientSecret, authorizationCode,
                    AUTHORIZATION_CODE_GRANT, webProperties.redirectUri())
                    : tokenClient.exchangeAuthorizationCode(clientId, clientSecret, authorizationCode,
                    AUTHORIZATION_CODE_GRANT);
            if (response == null || response.idToken() == null || response.idToken().isBlank()
                    || response.refreshToken() == null || response.refreshToken().isBlank()) {
                throw new ApiException(ResponseCode.OAUTH2_FAILED);
            }
            return response;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError() && isInvalidGrant(e)) {
                throw new ApiException(ResponseCode.OAUTH2_FAILED);
            }
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        } catch (RestClientException e) {
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }
    }

    private String clientId(AppleClientType clientType) {
        if (clientType == null) {
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }
        return clientType == AppleClientType.WEB ? webProperties.clientId() : properties.clientId();
    }

    private void ensureEnabled(AppleClientType clientType) {
        boolean enabled = clientType == AppleClientType.WEB ? webProperties.enabled() : properties.enabled();
        if (!enabled) {
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }
    }

    private boolean isInvalidGrant(RestClientResponseException exception) {
        try {
            AppleTokenError error = exception.getResponseBodyAs(AppleTokenError.class);
            return error != null && "invalid_grant".equals(error.error());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    record AppleTokenError(String error) {
    }
}
