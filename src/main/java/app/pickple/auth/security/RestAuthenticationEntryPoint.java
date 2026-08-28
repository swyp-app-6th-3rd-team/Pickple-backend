package app.pickple.auth.security;

import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인증되지 않은 요청에 401 을 돌려준다.
 *
 * <p>이걸 두지 않으면 Spring 기본 동작으로 떨어진다. {@code httpBasic} 이 켜져 있으면
 * 브라우저에 <b>Basic 인증 팝업</b>이 뜬다. REST API 에서는 JSON 으로 응답해야 한다.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, objectMapper, ResponseCode.UNAUTHORIZED);
    }

    static void write(HttpServletResponse response, ObjectMapper objectMapper, ResponseCode code)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code));
    }
}
