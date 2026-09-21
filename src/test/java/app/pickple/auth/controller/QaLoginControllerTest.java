package app.pickple.auth.controller;

import app.pickple.auth.service.QaLoginRateLimiter;
import app.pickple.auth.service.QaLoginService;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QaLoginControllerTest {
    @Test
    void rejectsBeforeServiceAndDoesNotTrustArbitraryForwardedHeaders() throws Exception {
        var service = mock(QaLoginService.class);
        when(service.login("review", "wrong-password")).thenThrow(new ApiException(ResponseCode.UNAUTHORIZED));
        var controller = new QaLoginController(service,
                new QaLoginRateLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (int i = 0; i < 11; i++) {
            var result = mvc.perform(post("/auth/login").with(request -> {
                        request.setRemoteAddr("192.0.2.1");
                        return request;
                    }).header("X-Forwarded-For", "198.51.100." + i)
                    .header("Forwarded", "for=198.51.100." + i)
                    .header("X-Real-IP", "198.51.100." + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"loginId\":\"review\",\"password\":\"wrong-password\"}"));
            if (i < 10) result.andExpect(status().isUnauthorized());
            else result.andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        }
        verify(service, times(10)).login("review", "wrong-password");
        verifyNoMoreInteractions(service);
    }
}
