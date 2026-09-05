package app.pickple.auth.kakao;

import app.pickple.common.ResponseCode;
import app.pickple.config.KakaoProperties;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Kakao 연결 해제 요청에 인증값을 채우고 외부 오류를 서비스 오류로 변환한다. */
@Component
@RequiredArgsConstructor
public class KakaoUnlinkGateway {

    private static final int MAX_PROVIDER_ID_LENGTH = 255;
    private static final String TARGET_ID_TYPE = "user_id";

    private final KakaoProperties properties;
    private final KakaoUnlinkClient unlinkClient;

    public void unlink(String providerId) {
        if (!properties.unlinkConfigured()
                || providerId == null || providerId.isBlank()
                || providerId.length() > MAX_PROVIDER_ID_LENGTH) {
            throw unavailable();
        }

        try {
            KakaoUnlinkClient.KakaoUnlinkResponse response = unlinkClient.unlink(
                    "KakaoAK " + properties.adminKey(), TARGET_ID_TYPE, providerId);
            if (response == null || response.id() == null
                    || !providerId.equals(String.valueOf(response.id()))) {
                throw unavailable();
            }
        } catch (RestClientResponseException exception) {
            if (isAlreadyUnlinked(exception)) {
                return;
            }
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private boolean isAlreadyUnlinked(RestClientResponseException exception) {
        try {
            KakaoErrorResponse error = exception.getResponseBodyAs(KakaoErrorResponse.class);
            // -101은 해당 앱과 이미 연결되지 않은 사용자다. 목표 상태가 충족됐으므로 성공으로 수렴한다.
            return error != null && Integer.valueOf(-101).equals(error.code());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private ApiException unavailable() {
        return new ApiException(ResponseCode.KAKAO_ACCOUNT_REVOCATION_UNAVAILABLE);
    }

    record KakaoErrorResponse(Integer code) {
    }
}
