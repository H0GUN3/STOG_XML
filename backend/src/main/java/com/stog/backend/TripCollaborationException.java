package com.stog.backend;

final class TripCollaborationException extends RuntimeException {
    private final int status;
    private final String code;
    private final Long currentPlanVersion;
    private final Long scheduledOutsideCount;
    private final Long retryAfterSeconds;

    TripCollaborationException(int status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    TripCollaborationException(int status, String code, String message, Long currentPlanVersion,
            Long scheduledOutsideCount, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentPlanVersion = currentPlanVersion;
        this.scheduledOutsideCount = scheduledOutsideCount;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    int status() { return status; }
    String code() { return code; }
    Long currentPlanVersion() { return currentPlanVersion; }
    Long scheduledOutsideCount() { return scheduledOutsideCount; }
    Long retryAfterSeconds() { return retryAfterSeconds; }
}
