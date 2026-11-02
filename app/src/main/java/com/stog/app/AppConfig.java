package com.stog.app;

import java.net.URI;
import java.net.URISyntaxException;

public final class AppConfig {
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private AppConfig() {}

    public static boolean isConfigured() {
        return validatedBaseUri() != null;
    }

    public static URI serverBaseUri() {
        URI uri = validatedBaseUri();
        if (uri == null) throw new IllegalStateException("Server configuration is missing or invalid");
        return uri;
    }

    static URI validateBaseUri(String value, boolean debug) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = new URI(value.trim());
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean debugLoopback = debug && "http".equalsIgnoreCase(uri.getScheme())
                && LOOPBACK_HOST.equals(uri.getHost());
            if ((!https && !debugLoopback) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) return null;
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !path.startsWith("/")) return null;
            String normalized = uri.toString();
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            return new URI(normalized);
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static URI validatedBaseUri() {
        return validateBaseUri(BuildConfig.STOG_SERVER_BASE_URL, BuildConfig.DEBUG);
    }
}
