package com.stog.backend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
final class AuthController {
    private final AuthService service;

    AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/signup")
    ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
        return tokenResponse(HttpStatus.CREATED, service.signup(
            request.username(), request.password(), request.displayName()));
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return tokenResponse(HttpStatus.OK, service.login(request.username(), request.password()));
    }

    @PostMapping("/kakao/exchange")
    ResponseEntity<TokenResponse> exchange(@Valid @RequestBody KakaoExchangeRequest request) {
        return tokenResponse(HttpStatus.OK, service.exchange(request.kakaoAccessToken()));
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return tokenResponse(HttpStatus.OK, service.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        service.logout(request.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").build();
    }

    private static ResponseEntity<TokenResponse> tokenResponse(HttpStatus status, AuthService.Tokens tokens) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
            .body(new TokenResponse("Bearer", tokens.accessToken(), tokens.accessExpiresIn(),
                tokens.refreshToken(), tokens.refreshExpiresIn()));
    }

    record SignupRequest(
        @NotBlank @Size(max = 32) String username,
        @NotNull String password,
        @JsonProperty("display_name") @NotBlank @Size(max = 80) String displayName) {}

    record LoginRequest(
        @NotBlank @Size(max = 32) String username,
        @NotNull String password) {}

    record KakaoExchangeRequest(
        @JsonProperty("kakao_access_token") @NotBlank @Size(max = 4096) @Pattern(regexp = "\\S+") String kakaoAccessToken) {}

    record RefreshRequest(
        @JsonProperty("refresh_token") @NotBlank @Size(max = 4096) @Pattern(regexp = "\\S+") String refreshToken) {}

    record TokenResponse(
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("access_expires_in") long accessExpiresIn,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("refresh_expires_in") long refreshExpiresIn) {}
}
