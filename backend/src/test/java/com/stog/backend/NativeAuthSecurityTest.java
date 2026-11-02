package com.stog.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeAuthSecurityTest {
    @Test
    void nativeInputNormalizationIsExact() {
        assertEquals("traveler.01", AuthService.username("  Traveler.01  "));
        assertEquals("여행자", AuthService.displayName("\u2003여행자\u2003"));
        assertEquals("correct horse battery staple", AuthService.password("correct horse battery staple"));
        assertEquals("               ", AuthService.password("               "));
        assertEquals("Å secure passphrase", AuthService.password("A\u030A secure passphrase"));
        assertEquals("J7!q2V#x", AuthService.password("J7!q2V#x"));
        assertFalse(new PasswordBlocklist().contains("J7!q2V#x", "traveler01"));

        assertThrows(AuthException.class, () -> AuthService.username("admin"));
        assertThrows(AuthException.class, () -> AuthService.username("한글아이디"));
        assertThrows(AuthException.class, () -> AuthService.displayName("\n"));
        assertEquals("INVALID_REQUEST", assertThrows(AuthException.class, () -> AuthService.password(null)).code());
        assertEquals("INVALID_REQUEST", assertThrows(AuthException.class,
            () -> AuthService.password("valid password\u0000value")).code());
        assertEquals("PASSWORD_NOT_ACCEPTABLE", assertThrows(AuthException.class,
            () -> AuthService.password("J7!q2V#")).code());
        assertEquals("PASSWORD_NOT_ACCEPTABLE", assertThrows(AuthException.class,
            () -> AuthService.password("x".repeat(129))).code());
    }

    @Test
    void pinnedOfflineCorpusLoadsAndRejectsAcceptedLengthCompromisedAndContextValues() {
        PasswordBlocklist blocklist = new PasswordBlocklist();

        assertEquals(10_001, blocklist.size());
        assertTrue(blocklist.contains("password", "traveler01"));
        assertTrue(blocklist.contains("films+pic+galeries", "traveler01"));
        assertTrue(blocklist.contains("traveler01", "traveler01"));
        assertTrue(blocklist.contains("stogtraveler01", "traveler01"));
        assertFalse(blocklist.contains("correct horse battery staple", "traveler01"));
    }

    @Test
    void productionPasswordBeanUsesVersionedPbkdf2Contract() {
        new ApplicationContextRunner().withUserConfiguration(PasswordSecurityConfig.class).run(context -> {
            assertFalse(context.getStartupFailure() != null);
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
            String encoded = encoder.encode("correct horse battery staple");

            assertTrue(encoded.startsWith("{pbkdf2-stog-v1}"));
            assertTrue(encoder.matches("correct horse battery staple", encoded));
            assertFalse(encoder.matches("wrong horse battery staple", encoded));
            assertFalse(encoded.contains("correct horse battery staple"));
        });
    }
}
