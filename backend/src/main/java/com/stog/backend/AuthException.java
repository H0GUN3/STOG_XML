package com.stog.backend;

final class AuthException extends RuntimeException {
    private final int status;
    private final String code;

    AuthException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    int status() { return status; }
    String code() { return code; }

    static AuthException invalid() {
        return new AuthException(400, "INVALID_REQUEST", "The request is invalid");
    }

    static AuthException required() {
        return new AuthException(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
    }

    static AuthException unavailable() {
        return new AuthException(503, "AUTH_PROVIDER_UNAVAILABLE", "Authentication provider is unavailable");
    }

    static AuthException passwordNotAcceptable() {
        return new AuthException(400, "PASSWORD_NOT_ACCEPTABLE", "The password is not acceptable");
    }

    static AuthException accountNotCreated() {
        return new AuthException(409, "ACCOUNT_NOT_CREATED", "The account could not be created");
    }

    static AuthException registrationUnavailable() {
        return new AuthException(503, "AUTH_REGISTRATION_UNAVAILABLE", "Account registration is unavailable");
    }
}
