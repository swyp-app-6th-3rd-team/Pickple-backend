package app.pickple.auth.apple;

import app.pickple.auth.config.AppleProperties;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/** Apple authorization code를 서버 간 통신으로 token으로 교환한다. */
@Component
public class AppleRestTokenClient implements AppleTokenClient {

    private final AppleProperties properties;
    private final AppleClientSecretProvider clientSecretProvider;
    private final RestClient restClient;

    @Autowired
    public AppleRestTokenClient(AppleProperties properties,
                                AppleClientSecretProvider clientSecretProvider) {
        this(properties, clientSecretProvider, createRestClient());
    }

    AppleRestTokenClient(AppleProperties properties,
                         AppleClientSecretProvider clientSecretProvider,
                         RestClient restClient) {
        this.properties = properties;
        this.clientSecretProvider = clientSecretProvider;
        this.restClient = restClient;
    }

    @Override
    public AppleTokenResponse exchangeAuthorizationCode(String authorizationCode) {
        if (!properties.enabled()) {
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", clientSecretProvider.create());
        form.add("code", authorizationCode);
        form.add("grant_type", "authorization_code");

        try {
            AppleTokenResponse response = restClient.post()
                    .uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(AppleTokenResponse.class);
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

    @Override
    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", properties.clientId());
        try {
            form.add("client_secret", clientSecretProvider.create());
            form.add("token", refreshToken);
            form.add("token_type_hint", "refresh_token");

            restClient.post()
                    .uri(properties.revokeUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (ApiException e) {
            throw new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        } catch (RestClientException e) {
            // 외부 오류 본문과 토큰은 로그에 남기지 않는다. 토큰은 보존되어 다음 탈퇴 요청에서 재시도한다.
            throw new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        }
    }

    private static RestClient createRestClient() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory).build();
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

    private record AppleTokenError(String error) {
    }
}
