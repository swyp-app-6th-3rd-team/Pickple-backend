package app.pickple.auth.controller;

import app.pickple.auth.apple.AppleWebLoginCompletionService;
import app.pickple.auth.apple.AppleWebLoginService;
import app.pickple.auth.service.AuthService;
import app.pickple.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AppleWebLoginControllerTest {

    private static final String CHALLENGE = "c".repeat(43);
    private static final String APP_STATE = "a".repeat(22);
    private static final String STATE = "s".repeat(43);
    private static final String HANDOFF = "h".repeat(43);
    private static final String VERIFIER = "v".repeat(43);

    @Mock
    private AppleWebLoginService loginService;
    @Mock
    private AppleWebLoginCompletionService completionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AppleWebLoginController(loginService, completionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void startRedirectsToAppleWithoutCaching() throws Exception {
        given(loginService.start(CHALLENGE, "S256", APP_STATE))
                .willReturn(URI.create("https://appleid.apple.com/auth/authorize?state=test"));

        mockMvc.perform(get("/auth/apple/web")
                        .param("code_challenge", CHALLENGE)
                        .param("code_challenge_method", "S256")
                        .param("app_state", APP_STATE))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://appleid.apple.com/auth/authorize?state=test"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void formCallbackRedirectsWithSeeOtherAndRejectsDuplicateKeys() throws Exception {
        var command = new AppleWebLoginService.CallbackCommand("apple-code", STATE, null, null);
        given(loginService.callback(command)).willReturn(URI.create(
                "pickple://auth/callback?app_state=" + APP_STATE + "&code=" + HANDOFF));

        mockMvc.perform(post("/auth/apple/web/callback")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("code", "apple-code")
                        .param("state", STATE))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location",
                        "pickple://auth/callback?app_state=" + APP_STATE + "&code=" + HANDOFF))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(loginService).callback(command);

        mockMvc.perform(post("/auth/apple/web/callback")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("code", "first", "second")
                        .param("state", STATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void callbackRequiresPostFormContract() throws Exception {
        mockMvc.perform(post("/auth/apple/web/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        mockMvc.perform(get("/auth/apple/web/callback"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        verifyNoInteractions(loginService, completionService);
    }

    @Test
    void exchangeReturnsExistingMobileTokenContractWithoutCaching() throws Exception {
        given(completionService.exchange(HANDOFF, VERIFIER))
                .willReturn(new AuthService.TokenPair("service-access", "service-refresh"));

        mockMvc.perform(post("/auth/apple/web/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","codeVerifier":"%s"}
                                """.formatted(HANDOFF, VERIFIER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.returnObject.accessToken").value("service-access"))
                .andExpect(jsonPath("$.returnObject.refreshToken").value("service-refresh"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }
}
