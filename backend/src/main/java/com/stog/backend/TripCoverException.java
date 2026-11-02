package com.stog.backend;

final class TripCoverException extends RuntimeException {
    private final int status;
    private final String code;
    private final Long currentPlanVersion;

    TripCoverException(int status, String code, String message) {
        this(status, code, message, null);
    }

    TripCoverException(int status, String code, String message, Long currentPlanVersion) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentPlanVersion = currentPlanVersion;
    }

    int status() { return status; }
    String code() { return code; }
    Long currentPlanVersion() { return currentPlanVersion; }
}
