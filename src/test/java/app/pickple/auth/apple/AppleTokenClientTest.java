package app.pickple.auth.apple;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AppleTokenClientTest {

    private static final String APPLE_BASE_URL = "https://appleid.apple.com";
    private static final String TOKEN_URI = APPLE_BASE_URL + "/auth/token";
    private static final String REVOKE_URI = APPLE_BASE_URL + "/auth/revoke";

    private MockRestServiceServer server;
    private AppleTokenClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(APPLE_BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = AppleTokenClientConfiguration.createClient(builder.build());
    }

    @Test
    void exchangesAuthorizationCodeThroughDeclaredFormContract() {
        var expected = new LinkedMultiValueMap<String, String>();
        expected.add("client_id", "app.pickple.ios");
        expected.add("client_secret", "signed-client-secret");
        expected.add("code", "one-time-code");
        expected.add("grant_type", "authorization_code");
        server.expect(once(), requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess("""
                        {"access_token":"apple-access","expires_in":300,
                         "id_token":"apple-id-token","refresh_token":"apple-refresh","token_type":"Bearer"}
                        """, MediaType.APPLICATION_JSON));

        AppleTokenResponse response = client.exchangeAuthorizationCode(
                "app.pickple.ios", "signed-client-secret", "one-time-code", "authorization_code");

        assertThat(response.idToken()).isEqualTo("apple-id-token");
        assertThat(response.refreshToken()).isEqualTo("apple-refresh");
        assertThat(response.toString()).isEqualTo("AppleTokenResponse[redacted]");
        server.verify();
    }

    @Test
    void revokesProviderRefreshTokenThroughDeclaredFormContract() {
        var expected = new LinkedMultiValueMap<String, String>();
        expected.add("client_id", "app.pickple.ios");
        expected.add("client_secret", "signed-client-secret");
        expected.add("token", "apple-provider-refresh");
        expected.add("token_type_hint", "refresh_token");
        server.expect(once(), requestTo(REVOKE_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess());

        client.revokeRefreshToken(
                "app.pickple.ios", "signed-client-secret", "apple-provider-refresh", "refresh_token");

        server.verify();
    }
}
