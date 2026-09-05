package app.pickple.auth.kakao;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Kakao 사용자 연결 해제 API의 HTTP 계약. */
@HttpExchange(contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
public interface KakaoUnlinkClient {

    @PostExchange(url = "/v1/user/unlink", accept = MediaType.APPLICATION_JSON_VALUE)
    KakaoUnlinkResponse unlink(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestParam("target_id_type") String targetIdType,
            @RequestParam("target_id") String targetId);

    record KakaoUnlinkResponse(Long id) {
    }
}
