package app.pickple.auth.apple;

import app.pickple.auth.config.AppleProperties;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Apple token HTTP 계약에 인증값을 채우고 외부 오류를 서비스 오류로 변환한다. */
@Component
@RequiredArgsConstructor
public class AppleTokenGateway {

    private static final String AUTHORIZATION_CODE_GRANT = "authorization_code";
    private static final String REFRESH_TOKEN_HINT = "refresh_token";

    private final AppleProperties properties;
    private final AppleClientSecretProvider clientSecretProvider;
    private final AppleTokenClient tokenClient;

    public AppleTokenResponse exchangeAuthorizationCode(String authorizationCode) {
        if (!properties.enabled()) {
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }

        try {
            AppleTokenResponse response = tokenClient.exchangeAuthorizationCode(
                    properties.clientId(),
                    clientSecretProvider.create(),
                    authorizationCode,
                    AUTHORIZATION_CODE_GRANT);
            if (response == null
                    || response.idToken() == null || response.idToken().isBlank()
                    || response.refreshToken() == null || response.refreshToken().isBlank()) {
                throw new ApiException(ResponseCode.OAUTH2_FAILED);
            }
            return response;
        } catch (RestClientResponseException e) {
            // 오류 본문 전체는 로그에 남기지 않고, 만료·재사용 code인 invalid_grant만 사용자 실패로 본다.
            if (e.getStatusCode().is4xxClientError() && isInvalidGrant(e)) {
                throw new ApiException(ResponseCode.OAUTH2_FAILED);
            }
            // invalid_client/unauthorized_client는 Key ID·Bundle ID·client_secret 같은 서버 설정 문제다.
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        } catch (RestClientException e) {
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }
    }

    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        }

        try {
            tokenClient.revokeRefreshToken(
                    properties.clientId(),
                    clientSecretProvider.create(),
                    refreshToken,
                    REFRESH_TOKEN_HINT);
        } catch (ApiException | RestClientException e) {
            // 외부 오류 본문과 토큰은 로그에 남기지 않는다. 토큰은 보존되어 다음 탈퇴 요청에서 재시도한다.
            throw new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        }
    }

    private boolean isInvalidGrant(RestClientResponseException exception) {
        try {
            AppleTokenError error = exception.getResponseBodyAs(AppleTokenError.class);
            return error != null && "invalid_grant".equals(error.error());
        } catch (RuntimeException ignored) {
            // 해석할 수 없는 외부 오류 응답은 사용자 탓으로 단정하지 않고 가용성 오류로 처리한다.
            return false;
        }
    }

    record AppleTokenError(String error) {
    }
}
