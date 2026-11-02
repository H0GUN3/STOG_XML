package com.stog.app.auth;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stog.app.MainActivity;
import com.stog.app.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import androidx.test.espresso.matcher.ViewMatchers;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class RealBackendTripItemUiTest {
    private AuthApiClient api;
    private final AtomicReference<AuthViewModel> viewModel = new AtomicReference<>();

    @After public void cleanup() {
        AuthViewModel.clearDebugFixture();
        if (api != null) api.close();
    }

    @Test public void syntheticExchangeReadsRealFixtureRefreshesAndLogoutClearsContent() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String providerToken = required(arguments, "providerToken");
        String tripId = required(arguments, "tripId");
        String itemId = required(arguments, "itemId");
        String missingTripId = required(arguments, "missingTripId");
        String nonmemberTripId = required(arguments, "nonmemberTripId");
        String missingItemId = required(arguments, "missingItemId");
        int controlPort = Integer.parseInt(required(arguments, "controlPort"));

        AuthViewModel.installDebugFixture(context -> {
            api = new AuthApiClient(URI.create("http://127.0.0.1:8080"), new SecureRefreshStore(context));
            AuthStateMachine.KakaoGateway kakao = new SyntheticKakao(providerToken);
            AuthViewModel created = new AuthViewModel(api, new AuthStateMachine(true, kakao, api));
            viewModel.set(created);
            return created;
        });

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitAuth(scenario, AuthStateMachine.Kind.SIGNED_OUT, null);
            onView(withId(R.id.auth_status_title)).check(matches(withText(R.string.auth_signed_out_title)));
            awaitAuth(scenario, AuthStateMachine.Kind.SIGNED_IN,
                activity -> activity.findViewById(R.id.auth_primary_action).performClick());
            onView(withId(R.id.auth_status_title)).check(matches(withText(R.string.auth_signed_in_title)));

            control(controlPort, "/seed");
            read(scenario, tripId, itemId, TripItemReadState.Kind.CONTENT);
            onView(withId(R.id.trip_item_status)).check(matches(withText(
                containsText(itemId))));
            screenshot(scenario, "tripitem-content.png");

            scenario.recreate();
            onView(withId(R.id.trip_id_input)).check(matches(withText(tripId)));
            onView(withId(R.id.trip_item_id_input)).check(matches(withText(itemId)));
            onView(withId(R.id.trip_item_status)).check(matches(withText(containsText(itemId))));
            screenshot(scenario, "tripitem-rotation-large-font.png");

            read(scenario, missingTripId, itemId, TripItemReadState.Kind.TRIP_NOT_FOUND);
            onView(withId(R.id.trip_item_status)).check(matches(withText(R.string.trip_item_trip_not_found)));
            screenshot(scenario, "tripitem-missing-trip.png");

            read(scenario, nonmemberTripId, itemId, TripItemReadState.Kind.TRIP_NOT_FOUND);
            onView(withId(R.id.trip_item_status)).check(matches(withText(R.string.trip_item_trip_not_found)));

            read(scenario, tripId, missingItemId, TripItemReadState.Kind.ITEM_NOT_FOUND);
            onView(withId(R.id.trip_item_status)).check(matches(withText(R.string.trip_item_item_not_found)));
            screenshot(scenario, "tripitem-missing-item.png");

            control(controlPort, "/expire-access");
            read(scenario, tripId, itemId, TripItemReadState.Kind.CONTENT);
            onView(withId(R.id.trip_item_status)).check(matches(withText(containsText(itemId))));
            screenshot(scenario, "tripitem-after-refresh.png");

            awaitAuth(scenario, AuthStateMachine.Kind.SIGNED_OUT,
                activity -> activity.findViewById(R.id.auth_primary_action).performClick());
            onView(withId(R.id.auth_status_title)).check(matches(withText(R.string.auth_signed_out_title)));
            onView(withId(R.id.trip_item_card)).check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
            screenshot(scenario, "tripitem-logout-no-stale-content.png");
        }
    }

    private static org.hamcrest.Matcher<String> containsText(String value) {
        return org.hamcrest.Matchers.containsString(value);
    }

    private void read(ActivityScenario<MainActivity> scenario, String tripId, String itemId,
            TripItemReadState.Kind expected) throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        @SuppressWarnings("unchecked") androidx.lifecycle.Observer<TripItemReadState>[] holder = new androidx.lifecycle.Observer[1];
        java.util.concurrent.atomic.AtomicBoolean loadingSeen = new java.util.concurrent.atomic.AtomicBoolean();
        scenario.onActivity(activity -> {
            holder[0] = state -> {
                if (state.kind() == TripItemReadState.Kind.LOADING) loadingSeen.set(true);
                if (loadingSeen.get() && state.kind() == expected) {
                    viewModel.get().tripItemState().removeObserver(holder[0]);
                    reached.countDown();
                }
            };
            viewModel.get().tripItemState().observeForever(holder[0]);
            ((android.widget.EditText) activity.findViewById(R.id.trip_id_input)).setText(tripId);
            ((android.widget.EditText) activity.findViewById(R.id.trip_item_id_input)).setText(itemId);
            activity.findViewById(R.id.trip_item_load_action).performClick();
        });
        assertTrue(reached.await(10, TimeUnit.SECONDS));
    }

    private void awaitAuth(ActivityScenario<MainActivity> scenario, AuthStateMachine.Kind expected,
            androidx.core.util.Consumer<MainActivity> action) throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        @SuppressWarnings("unchecked") androidx.lifecycle.Observer<AuthStateMachine.State>[] holder = new androidx.lifecycle.Observer[1];
        scenario.onActivity(activity -> {
            holder[0] = state -> {
                if (state.kind() == expected) {
                    viewModel.get().state().removeObserver(holder[0]);
                    reached.countDown();
                }
            };
            viewModel.get().state().observeForever(holder[0]);
            if (action != null) action.accept(activity);
        });
        assertTrue(reached.await(10, TimeUnit.SECONDS));
    }

    private static void control(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestMethod("POST");
            connection.setFixedLengthStreamingMode(0);
            connection.setDoOutput(true);
            connection.getOutputStream().close();
            assertEquals(204, connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
    }

    private static String required(Bundle arguments, String name) {
        String value = arguments.getString(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing instrumentation argument: " + name);
        return value;
    }

    private static void screenshot(ActivityScenario<MainActivity> scenario, String name) throws Exception {
        if (name.contains("logout")) {
            scenario.onActivity(activity -> ((android.widget.ScrollView) activity.findViewById(R.id.auth_scroll)).scrollTo(0, 0));
        } else {
            onView(withId(R.id.trip_item_status)).perform(scrollTo());
        }
        CountDownLatch committed = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            View root = activity.getWindow().getDecorView();
            root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
            root.invalidate();
        });
        assertTrue(committed.await(10, TimeUnit.SECONDS));
        AtomicReference<MainActivity> activity = new AtomicReference<>();
        scenario.onActivity(activity::set);
        View root = activity.get().getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/STOG-TripItem-QA");
        android.net.Uri uri = activity.get().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Screenshot destination unavailable");
        try (OutputStream output = activity.get().getContentResolver().openOutputStream(uri)) {
            if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("Screenshot failed");
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static final class SyntheticKakao implements AuthStateMachine.KakaoGateway {
        private final String providerToken;
        SyntheticKakao(String providerToken) { this.providerToken = providerToken; }
        @Override public void login(AuthStateMachine.LoginCallback callback) { callback.succeeded(providerToken); }
        @Override public void cleanup(AuthStateMachine.CompletionCallback callback) { callback.completed(true); }
    }
}
