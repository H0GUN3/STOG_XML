package com.stog.backend;

final class TripProductException extends RuntimeException {
    private final int status;
    private final String code;
    private final Long currentPlanVersion;

    TripProductException(int status, String code, String message) {
        this(status, code, message, null);
    }

    TripProductException(int status, String code, String message, Long currentPlanVersion) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentPlanVersion = currentPlanVersion;
    }

    int status() { return status; }
    String code() { return code; }
    Long currentPlanVersion() { return currentPlanVersion; }
}
