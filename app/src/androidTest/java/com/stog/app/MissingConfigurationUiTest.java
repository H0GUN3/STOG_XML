package com.stog.app;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;

@RunWith(AndroidJUnit4.class)
public final class MissingConfigurationUiTest {
    @Test public void missingConfigurationIsActionableDisabledAndSurvivesRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertMissingConfiguration();
            scenario.recreate();
            assertMissingConfiguration();
        }
    }

    private static void assertMissingConfiguration() {
        onView(withId(R.id.auth_status_title)).check(matches(withText(R.string.auth_missing_title)));
        onView(withId(R.id.auth_status_body)).check(matches(withText(R.string.auth_missing_body)));
        onView(withId(R.id.auth_primary_action)).check(matches(not(isEnabled())));
    }
}
