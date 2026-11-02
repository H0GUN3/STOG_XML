package com.stog.app.auth;

import com.stog.app.BuildConfig;

import org.junit.Test;

import static org.junit.Assert.assertThrows;

public final class AuthViewModelFixtureGuardTest {
    @Test public void releaseRejectsEveryFixtureMutationEntryPoint() {
        if (BuildConfig.DEBUG) {
            AuthViewModel.clearDebugFixture();
            return;
        }
        assertThrows(IllegalStateException.class,
            () -> AuthViewModel.installDebugFixture(context -> null));
        assertThrows(IllegalStateException.class, AuthViewModel::clearDebugFixture);
    }
}
