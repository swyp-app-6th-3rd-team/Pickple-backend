package app.pickple.auth.apple;

import app.pickple.auth.config.AppleProperties;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class AppleRestTokenClientTest {

    private static final String TOKEN_URI = "https://appleid.apple.com/auth/token";
    private static final String REVOKE_URI = "https://appleid.apple.com/auth/revoke";

    @Mock
    private AppleClientSecretProvider clientSecretProvider;

    private MockRestServiceServer server;
    private AppleRestTokenClient client;

    @BeforeEach
    void setUp() {
        AppleProperties properties = new AppleProperties(
                true, "TEAM", "KEY", "app.pickple.ios", "base64-key",
                "k1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "k1",
                "https://appleid.apple.com", TOKEN_URI,
                REVOKE_URI,
                "https://appleid.apple.com/auth/keys", Duration.ofMinutes(10));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AppleRestTokenClient(properties, clientSecretProvider, builder.build());
        given(clientSecretProvider.create()).willReturn("signed-client-secret");
    }

    @Test
    void exchangesAuthorizationCodeAsForm() {
        var expected = new LinkedMultiValueMap<String, String>();
        expected.add("client_id", "app.pickple.ios");
        expected.add("client_secret", "signed-client-secret");
        expected.add("code", "one-time-code");
        expected.add("grant_type", "authorization_code");
        server.expect(once(), requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess("""
                        {"access_token":"apple-access","expires_in":300,
                         "id_token":"apple-id-token","refresh_token":"apple-refresh","token_type":"Bearer"}
                        """, MediaType.APPLICATION_JSON));

        AppleTokenResponse response = client.exchangeAuthorizationCode("one-time-code");

        assertThat(response.idToken()).isEqualTo("apple-id-token");
        assertThat(response.refreshToken()).isEqualTo("apple-refresh");
        server.verify();
    }

    @Test
    void distinguishesExpiredCodeFromServerConfigurationError() {
        server.expect(once(), requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> client.exchangeAuthorizationCode("expired-code"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.OAUTH2_FAILED);
        server.verify();

        RestClient.Builder secondBuilder = RestClient.builder();
        MockRestServiceServer secondServer = MockRestServiceServer.bindTo(secondBuilder).build();
        AppleRestTokenClient secondClient = new AppleRestTokenClient(
                new AppleProperties(true, "TEAM", "KEY", "app.pickple.ios", "base64-key",
                        "k1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "k1",
                        "https://appleid.apple.com", TOKEN_URI,
                        REVOKE_URI,
                        "https://appleid.apple.com/auth/keys", Duration.ofMinutes(10)),
                clientSecretProvider, secondBuilder.build());
        secondServer.expect(once(), requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));

        assertThatThrownBy(() -> secondClient.exchangeAuthorizationCode("valid-looking-code"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        secondServer.verify();
    }

    @Test
    void rejectsTokenResponseWithoutProviderRefreshToken() {
        server.expect(once(), requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"id_token\":\"apple-id-token\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeAuthorizationCode("one-time-code"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.OAUTH2_FAILED);
        server.verify();
    }

    @Test
    void revokesProviderRefreshTokenAsForm() {
        var expected = new LinkedMultiValueMap<String, String>();
        expected.add("client_id", "app.pickple.ios");
        expected.add("client_secret", "signed-client-secret");
        expected.add("token", "apple-provider-refresh");
        expected.add("token_type_hint", "refresh_token");
        server.expect(once(), requestTo(REVOKE_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess());

        client.revokeRefreshToken("apple-provider-refresh");

        server.verify();
    }

    @Test
    void mapsRevokeFailureToDedicatedUnavailableCode() {
        server.expect(once(), requestTo(REVOKE_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));

        assertThatThrownBy(() -> client.revokeRefreshToken("apple-provider-refresh"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        server.verify();
    }
}
