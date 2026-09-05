package app.pickple.auth.kakao;

import app.pickple.config.KakaoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoUnlinkClientTest {

    private static final String KAKAO_BASE_URL = "https://kapi.kakao.test";
    private static final String UNLINK_URI = KAKAO_BASE_URL + "/v1/user/unlink";

    private MockRestServiceServer server;
    private KakaoUnlinkClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(KAKAO_BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClientAdapter adapter = RestClientAdapter.create(builder.build());
        client = HttpServiceProxyFactory.builderFor(adapter)
                .build()
                .createClient(KakaoUnlinkClient.class);
    }

    @Test
    void unlinksUserThroughDeclaredFormContract() {
        var expectedForm = new LinkedMultiValueMap<String, String>();
        expectedForm.add("target_id_type", "user_id");
        expectedForm.add("target_id", "1234567890");
        server.expect(once(), requestTo(UNLINK_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK admin-key"))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("{\"id\":1234567890}", MediaType.APPLICATION_JSON));

        KakaoUnlinkClient.KakaoUnlinkResponse response = client.unlink(
                "KakaoAK admin-key", "user_id", "1234567890");

        assertThat(response.id()).isEqualTo(1234567890L);
        server.verify();
    }

    @Test
    void exposesKakaoErrorBodyToGatewayForIdempotentUnlink() {
        server.expect(once(), requestTo(UNLINK_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":-101,\"msg\":\"NotRegisteredUserException\"}"));
        KakaoUnlinkGateway gateway = new KakaoUnlinkGateway(
                new KakaoProperties(
                        "native-app-key",
                        "admin-key",
                        "https://kauth.kakao.com",
                        "https://kauth.kakao.com/.well-known/jwks.json",
                        KAKAO_BASE_URL),
                client);

        assertThatCode(() -> gateway.unlink("1234567890")).doesNotThrowAnyException();
        server.verify();
    }
}
