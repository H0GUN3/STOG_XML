package com.stog.app.auth;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.stog.app.MainActivity;
import com.stog.app.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertNotNull;

/** Current product gate: username/password signup and login, not external-provider UI. */
@RunWith(AndroidJUnit4.class)
public final class ConfiguredAuthUiTest {
    private final FakeSession session = new FakeSession();

    @After public void cleanup() { AuthViewModel.clearDebugFixture(); }

    @Test public void nativeSignupFormValidatesAndLoginOpensSignedInShell() {
        AuthViewModel.installDebugFixture(context -> new AuthViewModel(new AuthStateMachine(true, session)));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertNotNull(session.restore.get());
            scenario.onActivity(activity -> session.restore.get().completed(false));
            onView(withId(R.id.auth_title)).check(matches(withText(R.string.auth_login_title)));
            onView(withId(R.id.auth_signup_mode)).perform(click());
            onView(withId(R.id.auth_display_name)).check(matches(isDisplayed()));
            onView(withId(R.id.auth_username)).perform(replaceText("traveler01"));
            onView(withId(R.id.auth_display_name)).perform(replaceText("여행자"));
            onView(withId(R.id.auth_password)).perform(replaceText("J7!q2V#"));
            onView(withId(R.id.auth_password_confirm)).perform(replaceText("J7!q2V#"));
            onView(withId(R.id.auth_primary_action)).perform(click());
            onView(withId(R.id.auth_error)).check(matches(withText(R.string.auth_password_not_acceptable)));

            onView(withId(R.id.auth_login_mode)).perform(click());
            onView(withId(R.id.auth_password)).perform(replaceText("J7!q2V#x"));
            onView(withId(R.id.auth_primary_action)).perform(click());
            assertNotNull(session.login.get());
            scenario.onActivity(activity -> session.login.get().completed(true, null));
            onView(withId(R.id.product_navigation)).check(matches(isDisplayed()));

            scenario.onActivity(activity -> new androidx.lifecycle.ViewModelProvider(activity)
                    .get(AuthViewModel.class).logout());
            assertNotNull(session.logout.get());
            scenario.onActivity(activity -> session.logout.get().completed(true));
            onView(withId(R.id.auth_title)).check(matches(withText(R.string.auth_login_title)));
        }
    }

    private static final class FakeSession implements AuthStateMachine.SessionGateway {
        final AtomicReference<AuthStateMachine.CompletionCallback> restore = new AtomicReference<>();
        final AtomicReference<AuthStateMachine.AuthCallback> login = new AtomicReference<>();
        final AtomicReference<AuthStateMachine.CompletionCallback> logout = new AtomicReference<>();
        @Override public void restore(AuthStateMachine.CompletionCallback callback) { restore.set(callback); }
        @Override public void login(String username, String password, AuthStateMachine.AuthCallback callback) { login.set(callback); }
        @Override public void logout(AuthStateMachine.CompletionCallback callback) { logout.set(callback); }
    }
}
