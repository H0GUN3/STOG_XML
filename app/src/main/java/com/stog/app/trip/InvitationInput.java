package com.stog.app.trip;

import java.net.URI;
/** Memory-only invitation capability parser. It deliberately exposes no token formatting for UI. */
public final class InvitationInput {
    private InvitationInput() { }

    public static String parse(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        String token = value;
        if (value.contains("://")) {
            try {
                URI uri = URI.create(value);
                if (!"stog".equals(uri.getScheme()) || !"trip-invitations".equals(uri.getHost())
                        || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
                        || uri.getFragment() != null || uri.getPath() == null
                        || !uri.getPath().matches("/[A-Za-z0-9_-]{43}")) return null;
                token = uri.getPath().substring(1);
            } catch (IllegalArgumentException exception) { return null; }
        }
        if (!token.matches("[A-Za-z0-9_-]{43}")) return null;
        // A 43-character unpadded Base64URL value represents exactly 32 bytes only when
        // its final sextet has no noncanonical unused bits. This avoids API-26 java.util.Base64.
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        return (alphabet.indexOf(token.charAt(42)) & 3) == 0 ? token : null;
    }
}
