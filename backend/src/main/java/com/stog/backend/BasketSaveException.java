package com.stog.backend;

final class BasketSaveException extends RuntimeException {
    private final int status;
    private final String code;
    private final Long currentPlanVersion;

    BasketSaveException(int status, String code, String message) {
        this(status, code, message, null);
    }

    BasketSaveException(int status, String code, String message, Long currentPlanVersion) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentPlanVersion = currentPlanVersion;
    }

    int status() { return status; }
    String code() { return code; }
    Long currentPlanVersion() { return currentPlanVersion; }
}
