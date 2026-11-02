package com.stog.app.auth;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuthPasswordValidationTest {
    @Test public void passwordLengthBoundaryRemainsEightTo128CodePoints() {
        assertFalse(AuthViewModel.isValidPassword("J7!q2V#"));
        assertTrue(AuthViewModel.isValidPassword("J7!q2V#x"));
        assertTrue(AuthViewModel.isValidPassword("x".repeat(128)));
        assertFalse(AuthViewModel.isValidPassword("x".repeat(129)));
    }
}
