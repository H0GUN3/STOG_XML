package com.stog.backend;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ProfileController {
    private final AuthService service;

    ProfileController(AuthService service) {
        this.service = service;
    }

    @GetMapping("/api/me")
    ResponseEntity<ProfileResponse> me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw AuthException.required();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
            .body(new ProfileResponse(service.currentDisplayName(user.userId())));
    }

    record ProfileResponse(@JsonProperty("display_name") String displayName) {}
}
