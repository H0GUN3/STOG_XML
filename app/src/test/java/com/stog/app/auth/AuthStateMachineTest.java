package com.stog.app.auth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Native credential state transitions; no provider gateway is part of the current app flow. */
public final class AuthStateMachineTest {
    @Test public void missingConfigurationNeverStartsRestoreOrSubmit() {
        FakeSession session = new FakeSession();
        AuthStateMachine machine = new AuthStateMachine(false, session);
        machine.start();
        machine.login("traveler01", "correct horse battery staple");
        assertEquals(AuthStateMachine.Kind.MISSING_CONFIGURATION, machine.state().kind());
        assertEquals(0, session.restoreCalls);
        assertEquals(0, session.loginCalls);
    }

    @Test public void restoreSignsInWithoutExposingCredentialFields() {
        FakeSession session = new FakeSession();
        AuthStateMachine machine = new AuthStateMachine(true, session);
        machine.start();
        assertEquals(AuthStateMachine.Kind.RESTORING, machine.state().kind());
        session.restore.completed(true);
        assertEquals(AuthStateMachine.Kind.SIGNED_IN, machine.state().kind());
    }

    @Test public void loginSubmitsCanonicalFieldsAndSignsIn() {
        FakeSession session = new FakeSession();
        AuthStateMachine machine = signedOut(session);
        machine.login("traveler01", "correct horse battery staple");
        assertEquals(AuthStateMachine.Kind.SUBMITTING_LOGIN, machine.state().kind());
        assertEquals("traveler01", session.username);
        session.login.completed(true, null);
        assertEquals(AuthStateMachine.Kind.SIGNED_IN, machine.state().kind());
    }

    @Test public void signupErrorRetainsContractCodeAndMode() {
        FakeSession session = new FakeSession();
        AuthStateMachine machine = signedOut(session);
        machine.showSignup();
        machine.signup("traveler01", "correct horse battery staple", "여행자");
        assertEquals(AuthStateMachine.Kind.SUBMITTING_SIGNUP, machine.state().kind());
        session.signup.completed(false, "ACCOUNT_NOT_CREATED");
        assertEquals(AuthStateMachine.Kind.ERROR, machine.state().kind());
        assertEquals("ACCOUNT_NOT_CREATED:signup", machine.state().errorCode());
        machine.showLogin();
        assertEquals(AuthStateMachine.Kind.SIGNED_OUT_LOGIN, machine.state().kind());
        assertEquals(null, machine.state().errorCode());
    }

    @Test public void staleSubmitCannotOverwriteNewMode() {
        FakeSession session = new FakeSession();
        AuthStateMachine machine = signedOut(session);
        machine.login("traveler01", "correct horse battery staple");
        AuthStateMachine.AuthCallback stale = session.login;
        machine.showSignup();
        stale.completed(true, null);
        assertEquals(AuthStateMachine.Kind.SIGNED_OUT_SIGNUP, machine.state().kind());
    }

    @Test public void logoutShowsRevokeWarningWhenServerCannotConfirm() {
        FakeSession session = new FakeSession();
        AuthStateMachine machine = new AuthStateMachine(true, session);
        machine.start();
        session.restore.completed(true);
        machine.logout();
        session.logout.completed(false);
        assertEquals(AuthStateMachine.Kind.SIGNED_OUT_REVOKE_UNCONFIRMED, machine.state().kind());
    }

    @Test public void localValidationMatchesNativeContractBounds() {
        assertTrue(AuthViewModel.isValidUsername("traveler01"));
        assertFalse(AuthViewModel.isValidUsername("Admin"));
        assertTrue(AuthViewModel.isValidDisplayName(" 여행자 "));
        assertFalse(AuthViewModel.isValidPassword("short"));
        assertTrue(AuthViewModel.isValidPassword("correct horse battery staple"));
    }

    private static AuthStateMachine signedOut(FakeSession session) {
        AuthStateMachine machine = new AuthStateMachine(true, session);
        machine.start();
        session.restore.completed(false);
        return machine;
    }

    private static final class FakeSession implements AuthStateMachine.SessionGateway {
        int restoreCalls;
        int loginCalls;
        String username;
        AuthStateMachine.CompletionCallback restore;
        AuthStateMachine.AuthCallback login;
        AuthStateMachine.AuthCallback signup;
        AuthStateMachine.CompletionCallback logout;

        @Override public void restore(AuthStateMachine.CompletionCallback callback) {
            restoreCalls++;
            restore = callback;
        }
        @Override public void login(String value, String password, AuthStateMachine.AuthCallback callback) {
            loginCalls++;
            username = value;
            login = callback;
        }
        @Override public void signup(String username, String password, String displayName, AuthStateMachine.AuthCallback callback) {
            signup = callback;
        }
        @Override public void logout(AuthStateMachine.CompletionCallback callback) { logout = callback; }
    }
}
