package com.stog.backend;

import java.util.UUID;

public record AuthenticatedUser(UUID userId) {
    public AuthenticatedUser {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
    }
}
