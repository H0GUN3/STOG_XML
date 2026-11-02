package com.stog.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {BasketSaveController.class, AuthController.class})
@Import({SecurityConfig.class, JsonConfig.class})
@EnableWebSecurity
class AuthenticationBoundaryTest {
    @Autowired MockMvc mvc;
    @MockitoBean BasketSaveService service;
    @MockitoBean AuthService authService;

    @AfterEach
    void storageIsNeverEntered() {
        verifyNoInteractions(service, authService);
    }

    @Test
    void productionDisablesGeneratedDefaultUserConfiguration() {
        assertTrue(java.util.Arrays.stream(StogBackendApplication.class.getAnnotation(org.springframework.boot.autoconfigure.SpringBootApplication.class).exclude())
            .anyMatch(UserDetailsServiceAutoConfiguration.class::equals));
    }

    @Test
    void anonymousBasketSaveGetsAuthenticationRequiredEnvelope() throws Exception {
        mvc.perform(post("/api/trips/trip-1/trip-items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"forged\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.error.message").isString());
    }

    @Test
    void forgedUserHeaderCannotAuthenticate() throws Exception {
        mvc.perform(post("/api/trips/trip-1/trip-items")
                .header("X-User-Id", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.error.message").isString());
    }

    @Test
    void malformedPublicExchangeIsRejectedBeforeAuthentication() throws Exception {
        mvc.perform(post("/api/auth/kakao/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kakao_access_token\":null}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedNativeSignupIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"traveler01\",\"password\":null,\"display_name\":\"여행자\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedNativeLoginIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"traveler01\","
                    + "\"password\":\"valid native passphrase 2048!\",\"extra\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void nullNativeLoginPasswordIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"traveler01\",\"password\":null}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void nonstringNativeLoginPasswordIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"traveler01\",\"password\":123456789012345}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void duplicateNativeLoginPasswordIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"traveler01\",\"password\":\"valid native passphrase 2048!\","
                    + "\"password\":\"second valid passphrase 2048!\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void oversizedNativeAuthBodyIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"traveler01\",\"password\":\""
                    + "x".repeat(AuthRequestSizeFilter.MAX_AUTH_BODY_BYTES)
                    + "\",\"display_name\":\"여행자\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

}
