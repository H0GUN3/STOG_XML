package com.stog.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class AppConfigTest {
    @Test public void releaseAcceptsOnlyHttpsWithoutCredentialsQueryOrFragment() {
        assertEquals("https://example.test/api", AppConfig.validateBaseUri("https://example.test/api/", false).toString());
        assertNull(AppConfig.validateBaseUri("http://example.test", false));
        assertNull(AppConfig.validateBaseUri("https://user@example.test", false));
        assertNull(AppConfig.validateBaseUri("https://example.test?token=value", false));
    }

    @Test public void debugCleartextIsLimitedToLiteralIpv4Loopback() {
        assertEquals("http://127.0.0.1:8080", AppConfig.validateBaseUri("http://127.0.0.1:8080", true).toString());
        assertNull(AppConfig.validateBaseUri("http://localhost:8080", true));
        assertNull(AppConfig.validateBaseUri("http://10.0.2.2:8080", true));
        assertNull(AppConfig.validateBaseUri("http://127.0.0.1:8080", false));
    }
}
