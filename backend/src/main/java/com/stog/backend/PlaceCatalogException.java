package com.stog.backend;

final class PlaceCatalogException extends RuntimeException {
    private final int status;
    private final String code;

    PlaceCatalogException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    int status() { return status; }
    String code() { return code; }
}
