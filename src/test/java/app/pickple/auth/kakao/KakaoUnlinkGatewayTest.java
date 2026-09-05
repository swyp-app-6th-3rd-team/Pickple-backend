package app.pickple.auth.kakao;

import app.pickple.common.ResponseCode;
import app.pickple.config.KakaoProperties;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class KakaoUnlinkGatewayTest {

    @Mock
    private KakaoUnlinkClient unlinkClient;

    private KakaoUnlinkGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new KakaoUnlinkGateway(properties("admin-key"), unlinkClient);
    }

    @Test
    void suppliesAdminAuthorizationAndTargetForUnlink() {
        given(unlinkClient.unlink("KakaoAK admin-key", "user_id", "1234567890"))
                .willReturn(new KakaoUnlinkClient.KakaoUnlinkResponse(1234567890L));

        gateway.unlink("1234567890");

        verify(unlinkClient).unlink("KakaoAK admin-key", "user_id", "1234567890");
    }

    @Test
    void rejectsMissingNullOrMismatchedResponseId() {
        given(unlinkClient.unlink("KakaoAK admin-key", "user_id", "missing-response"))
                .willReturn(null);
        given(unlinkClient.unlink("KakaoAK admin-key", "user_id", "missing-id"))
                .willReturn(new KakaoUnlinkClient.KakaoUnlinkResponse(null));
        given(unlinkClient.unlink("KakaoAK admin-key", "user_id", "9876543210"))
                .willReturn(new KakaoUnlinkClient.KakaoUnlinkResponse(999L));

        assertUnavailable(() -> gateway.unlink("missing-response"));
        assertUnavailable(() -> gateway.unlink("missing-id"));
        assertUnavailable(() -> gateway.unlink("9876543210"));
    }

    @Test
    void rejectsMissingAdminKeyAndInvalidProviderIdWithoutCallingKakao() {
        KakaoUnlinkGateway noAdmin = new KakaoUnlinkGateway(
                properties("not-configured"), unlinkClient);

        assertUnavailable(() -> noAdmin.unlink("1234567890"));
        assertUnavailable(() -> gateway.unlink(null));
        assertUnavailable(() -> gateway.unlink(" "));
        assertUnavailable(() -> gateway.unlink("1".repeat(256)));
        verifyNoInteractions(unlinkClient);
    }

    @Test
    void alreadyUnlinkedUserConvergesToSuccess() {
        RestClientResponseException alreadyUnlinked = responseException(
                new KakaoUnlinkGateway.KakaoErrorResponse(-101));
        given(unlinkClient.unlink("KakaoAK admin-key", "user_id", "1234567890"))
                .willThrow(alreadyUnlinked);

        assertThatCode(() -> gateway.unlink("1234567890")).doesNotThrowAnyException();
    }

    @Test
    void mapsOtherKakaoAndNetworkFailuresToRevocationUnavailable() {
        RestClientResponseException kakaoFailure = responseException(
                new KakaoUnlinkGateway.KakaoErrorResponse(-2));
        given(unlinkClient.unlink("KakaoAK admin-key", "user_id", "1234567890"))
                .willThrow(kakaoFailure);
        given(unlinkClient.unlink("KakaoAK admin-key", "user_id", "9876543210"))
                .willThrow(new ResourceAccessException("network unavailable"));

        assertUnavailable(() -> gateway.unlink("1234567890"));
        assertUnavailable(() -> gateway.unlink("9876543210"));
    }

    @SuppressWarnings("unchecked")
    private static RestClientResponseException responseException(
            KakaoUnlinkGateway.KakaoErrorResponse error) {
        RestClientResponseException exception = org.mockito.Mockito.mock(RestClientResponseException.class);
        given(exception.getResponseBodyAs(KakaoUnlinkGateway.KakaoErrorResponse.class)).willReturn(error);
        return exception;
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ResponseCode.KAKAO_ACCOUNT_REVOCATION_UNAVAILABLE);
    }

    private static KakaoProperties properties(String adminKey) {
        return new KakaoProperties(
                "native-app-key",
                adminKey,
                "https://kauth.kakao.com",
                "https://kauth.kakao.com/.well-known/jwks.json",
                "https://kapi.kakao.test");
    }
}
