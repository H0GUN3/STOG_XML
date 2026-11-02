package com.stog.app.auth;

import android.app.UiModeManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stog.app.MainActivity;
import com.stog.app.MainFragment;
import com.stog.app.R;
import com.stog.app.trip.TripViewModel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Generated-principal Android UI -> actual local backend -> PostgreSQL R1 acceptance. */
@RunWith(AndroidJUnit4.class)
public final class RealBackendTripProductUiTest {
    private AuthApiClient api;
    private final AtomicReference<AuthViewModel> auth = new AtomicReference<>();
    private final AtomicReference<AuthStateMachine> authMachine = new AtomicReference<>();
    private final MutableKakao kakao = new MutableKakao();

    @After public void cleanup() {
        AuthViewModel.clearDebugFixture();
        if (api != null) api.close();
    }

    @Test public void createResponseLossReplaysOnceThenNonmemberCannotObserveTrip() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String memberToken = required(args, "memberProviderToken");
        String nonmemberToken = required(args, "nonmemberProviderToken");
        int controlPort = Integer.parseInt(required(args, "controlPort"));
        kakao.token.set(memberToken);

        AuthViewModel.installDebugFixture(context -> {
            api = new AuthApiClient(URI.create("http://127.0.0.1:8080"), new SecureRefreshStore(context));
            AuthStateMachine machine = new AuthStateMachine(true, kakao, api);
            AuthViewModel created = new AuthViewModel(api, machine);
            authMachine.set(machine);
            auth.set(created);
            return created;
        });
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((UiModeManager) target.getSystemService(Context.UI_MODE_SERVICE))
            .setApplicationNightMode(UiModeManager.MODE_NIGHT_NO);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitAuth(scenario, AuthStateMachine.Kind.SIGNED_OUT, null);
            awaitAuth(scenario, AuthStateMachine.Kind.SIGNED_IN,
                () -> onView(withId(R.id.auth_primary_action)).perform(scrollTo(), click()));
            TripViewModel trips = tripModel(scenario);
            awaitListAfter(trips, TripViewModel.ListKind.EMPTY, null);
            screenshot(scenario, "01-member-empty-light.png");

            onView(withId(R.id.trip_create_action)).perform(click());
            onView(withId(R.id.trip_create_submit)).perform(scrollTo(), click());
            onView(withId(R.id.trip_create_status)).check(matches(withText(R.string.trip_create_invalid)));

            LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(2);
            scenario.onActivity(activity -> {
                ((android.widget.EditText) activity.findViewById(R.id.trip_name_input)).setText("통합 검증 여행");
                ((android.widget.EditText) activity.findViewById(R.id.trip_region_input)).setText("서울");
                ((android.widget.EditText) activity.findViewById(R.id.trip_start_input)).setText(start.toString());
                ((android.widget.EditText) activity.findViewById(R.id.trip_end_input)).setText(start.plusDays(1).toString());
                activity.findViewById(R.id.trip_name_input).requestFocus();
            });
            showKeyboardAndAwaitInsets(scenario, R.id.trip_name_input);
            screenshot(scenario, "02-create-keyboard-focus.png");
            closeSoftKeyboard();

            CountDownLatch ambiguous = observeCreateAfter(trips, TripViewModel.CreateKind.RETRYABLE_ERROR);
            onView(withId(R.id.trip_create_submit)).perform(scrollTo(), click());
            assertTrue("first committed response must be deliberately lost", ambiguous.await(20, TimeUnit.SECONDS));
            onView(withId(R.id.trip_create_status)).perform(scrollTo());
            revealInOuterScroll(scenario, R.id.trip_create_status);
            screenshot(scenario, "02-response-loss-retry.png");

            CountDownLatch detail = observeDetailAfter(trips, TripViewModel.DetailKind.CONTENT);
            onView(withId(R.id.trip_create_submit)).perform(scrollTo(), click());
            assertTrue("same pending request must replay to detail", detail.await(20, TimeUnit.SECONDS));
            onView(withId(R.id.trip_detail_name)).check(matches(withText("통합 검증 여행")));
            onView(withId(R.id.trip_basket_status)).check(matches(withText(R.string.trip_basket_empty)));
            String tripId = trips.selectedTripId();
            assertNotNull(tripId);
            screenshot(scenario, "03-detail-empty-basket-light.png");

            changeConfigurationAndAwaitRecreation(scenario, android.app.Activity::recreate);
            trips = tripModel(scenario);
            awaitCurrentDetail(trips, TripViewModel.DetailKind.CONTENT);
            onView(withId(R.id.trip_detail_name)).check(matches(withText("통합 검증 여행")));
            onView(withId(R.id.trip_basket_status)).check(matches(withText(R.string.trip_basket_empty))).perform(scrollTo());
            screenshot(scenario, "04-detail-portrait-recreated-font-1_3.png");

            setNightModeAndAwaitRecreation(scenario);
            trips = tripModel(scenario);
            awaitCurrentDetail(trips, TripViewModel.DetailKind.CONTENT);
            onView(withId(R.id.trip_detail_name)).check(matches(withText("통합 검증 여행")));
            onView(withId(R.id.trip_basket_status)).perform(scrollTo());
            screenshot(scenario, "05-detail-dark.png");

            control(controlPort, "/insert-id-only-basket/" + tripId);
            CountDownLatch idOnlyBasket = observeDetailAfterLoading(trips, TripViewModel.DetailKind.CONTENT);
            TripViewModel detailModel = trips;
            scenario.onActivity(activity -> detailModel.select(tripId));
            assertTrue("ID-only basket detail reload timed out", idOnlyBasket.await(20, TimeUnit.SECONDS));
            assertEquals(1, trips.detail().getValue().basket().items().size());
            onView(withId(R.id.trip_basket_status)).perform(scrollTo());
            screenshot(scenario, "06-detail-id-only-basket.png");

            androidx.test.espresso.Espresso.pressBack();
            assertEquals(1, recyclerCount(scenario));
            onView(withText("통합 검증 여행")).check(matches(withText("통합 검증 여행")));
            screenshot(scenario, "07-member-list-exactly-one.png");

            control(controlPort, "/expire-member-access");
            TripViewModel refreshModel = trips;
            CountDownLatch refreshed = observeListAfterLoading(refreshModel, TripViewModel.ListKind.CONTENT);
            scenario.onActivity(activity -> refreshModel.loadTrips());
            assertTrue("expired access must refresh once", refreshed.await(20, TimeUnit.SECONDS));
            assertEquals(1, recyclerCount(scenario));

            authMachine.get().setKakaoGateway(kakao);
            awaitAuth(scenario, AuthStateMachine.Kind.SIGNED_OUT,
                () -> onView(withId(R.id.auth_primary_action)).perform(scrollTo(), click()));
            kakao.token.set(nonmemberToken);
            awaitAuth(scenario, AuthStateMachine.Kind.SIGNED_IN,
                () -> onView(withId(R.id.auth_primary_action)).perform(scrollTo(), click()));
            TripViewModel nonmemberTrips = tripModel(scenario);
            awaitListAfter(nonmemberTrips, TripViewModel.ListKind.EMPTY, null);
            assertEquals(0, recyclerCount(scenario));
            assertEquals(AuthApiClient.TripOutcome.TRIP_NOT_FOUND, awaitDetail(tripId).outcome());
            changeConfigurationAndAwaitRecreation(scenario, android.app.Activity::recreate);
            nonmemberTrips = tripModel(scenario);
            awaitListAfter(nonmemberTrips, TripViewModel.ListKind.EMPTY, null);
            onView(withId(R.id.trip_list_status)).perform(scrollTo()).check(matches(isDisplayed()));
            screenshot(scenario, "08-nonmember-empty-nondisclosure.png");

            awaitAuth(scenario, AuthStateMachine.Kind.SIGNED_OUT,
                () -> onView(withId(R.id.auth_primary_action)).perform(scrollTo(), click()));
            onView(withId(R.id.auth_status_title)).check(matches(withText(R.string.auth_signed_out_title)));
            screenshot(scenario, "09-logout-no-private-content.png");
        }
    }

    private static TripViewModel tripModel(ActivityScenario<MainActivity> scenario) {
        AtomicReference<TripViewModel> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            MainFragment main = (MainFragment) activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            result.set(new ViewModelProvider(main).get(TripViewModel.class));
        });
        return result.get();
    }

    private static void setNightModeAndAwaitRecreation(ActivityScenario<MainActivity> scenario) throws Exception {
        changeConfigurationAndAwaitRecreation(scenario,
            activity -> ((UiModeManager) activity.getSystemService(Context.UI_MODE_SERVICE))
                .setApplicationNightMode(UiModeManager.MODE_NIGHT_YES));
    }

    private static void changeConfigurationAndAwaitRecreation(ActivityScenario<MainActivity> scenario,
            androidx.core.util.Consumer<MainActivity> action) throws Exception {
        AtomicReference<MainActivity> previous = new AtomicReference<>();
        CountDownLatch resumed = new CountDownLatch(1);
        android.app.Application application = (android.app.Application) InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getApplicationContext();
        android.app.Application.ActivityLifecycleCallbacks callbacks =
            new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(android.app.Activity activity, Bundle state) { }
                @Override public void onActivityStarted(android.app.Activity activity) { }
                @Override public void onActivityResumed(android.app.Activity activity) {
                    if (activity instanceof MainActivity && activity != previous.get()) resumed.countDown();
                }
                @Override public void onActivityPaused(android.app.Activity activity) { }
                @Override public void onActivityStopped(android.app.Activity activity) { }
                @Override public void onActivitySaveInstanceState(android.app.Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(android.app.Activity activity) { }
            };
        application.registerActivityLifecycleCallbacks(callbacks);
        try {
            scenario.onActivity(activity -> { previous.set(activity); action.accept(activity); });
            assertTrue("configuration recreation timed out", resumed.await(20, TimeUnit.SECONDS));
            scenario.onActivity(activity -> { });
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks);
        }
    }

    private static void awaitListAfter(TripViewModel model, TripViewModel.ListKind expected, Runnable action) throws Exception {
        CountDownLatch latch = observeListAfter(model, expected);
        if (action != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
        if (model.list().getValue() != null && model.list().getValue().kind() == expected) return;
        assertTrue("trip list state timed out", latch.await(20, TimeUnit.SECONDS));
    }

    private static CountDownLatch observeListAfter(TripViewModel model, TripViewModel.ListKind expected) {
        CountDownLatch latch = new CountDownLatch(1);
        @SuppressWarnings("unchecked") Observer<TripViewModel.ListState>[] holder = new Observer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            holder[0] = state -> { if (state.kind() == expected) { model.list().removeObserver(holder[0]); latch.countDown(); } };
            model.list().observeForever(holder[0]);
        });
        return latch;
    }

    private static CountDownLatch observeListAfterLoading(TripViewModel model, TripViewModel.ListKind expected) {
        CountDownLatch latch = new CountDownLatch(1);
        @SuppressWarnings("unchecked") Observer<TripViewModel.ListState>[] holder = new Observer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AtomicBoolean loading = new AtomicBoolean();
            holder[0] = state -> {
                if (state.kind() == TripViewModel.ListKind.LOADING) loading.set(true);
                if (loading.get() && state.kind() == expected) {
                    model.list().removeObserver(holder[0]);
                    latch.countDown();
                }
            };
            model.list().observeForever(holder[0]);
        });
        return latch;
    }

    private static CountDownLatch observeCreateAfter(TripViewModel model, TripViewModel.CreateKind expected) {
        CountDownLatch latch = new CountDownLatch(1);
        @SuppressWarnings("unchecked") Observer<TripViewModel.CreateState>[] holder = new Observer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AtomicBoolean loading = new AtomicBoolean();
            holder[0] = state -> {
                if (state.kind() == TripViewModel.CreateKind.LOADING) loading.set(true);
                if (loading.get() && state.kind() == expected) { model.create().removeObserver(holder[0]); latch.countDown(); }
            };
            model.create().observeForever(holder[0]);
        });
        return latch;
    }

    private static CountDownLatch observeDetailAfter(TripViewModel model, TripViewModel.DetailKind expected) {
        CountDownLatch latch = new CountDownLatch(1);
        @SuppressWarnings("unchecked") Observer<TripViewModel.DetailState>[] holder = new Observer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            holder[0] = state -> { if (state.kind() == expected && state.basket() != null) { model.detail().removeObserver(holder[0]); latch.countDown(); } };
            model.detail().observeForever(holder[0]);
        });
        return latch;
    }

    private static CountDownLatch observeDetailAfterLoading(TripViewModel model, TripViewModel.DetailKind expected) {
        CountDownLatch latch = new CountDownLatch(1);
        @SuppressWarnings("unchecked") Observer<TripViewModel.DetailState>[] holder = new Observer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AtomicBoolean loading = new AtomicBoolean();
            holder[0] = state -> {
                if (state.kind() == TripViewModel.DetailKind.LOADING) loading.set(true);
                if (loading.get() && state.kind() == expected && state.basket() != null) {
                    model.detail().removeObserver(holder[0]);
                    latch.countDown();
                }
            };
            model.detail().observeForever(holder[0]);
        });
        return latch;
    }

    private static void awaitCurrentDetail(TripViewModel model, TripViewModel.DetailKind expected) throws Exception {
        CountDownLatch latch = observeDetailAfter(model, expected);
        TripViewModel.DetailState current = model.detail().getValue();
        if (current != null && current.kind() == expected && current.basket() != null) return;
        assertTrue("restored trip detail state timed out", latch.await(20, TimeUnit.SECONDS));
    }

    private void awaitAuth(ActivityScenario<MainActivity> scenario, AuthStateMachine.Kind expected,
            Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AuthStateMachine.Kind> terminalFailure = new AtomicReference<>();
        StringBuilder timeline = new StringBuilder();
        @SuppressWarnings("unchecked") Observer<AuthStateMachine.State>[] holder = new Observer[1];
        scenario.onActivity(activity -> {
            holder[0] = state -> {
                if (timeline.length() > 0) timeline.append('>');
                timeline.append(state.kind());
                if (state.kind() == expected) {
                    auth.get().state().removeObserver(holder[0]);
                    latch.countDown();
                } else if (isUnexpectedTerminal(state.kind(), expected)) {
                    terminalFailure.set(state.kind());
                    auth.get().state().removeObserver(holder[0]);
                    latch.countDown();
                }
            };
            auth.get().state().observeForever(holder[0]);
        });
        if (action != null) action.run();
        boolean completed = latch.await(20, TimeUnit.SECONDS);
        System.out.println("AUTH_STATE_TIMELINE expected=" + expected + " states=" + timeline
            + " completed=" + completed + " terminalFailure=" + (terminalFailure.get() != null));
        assertTrue("auth state timed out: " + expected + "; states=" + timeline, completed);
        assertEquals("auth reached unexpected terminal state; states=" + timeline,
            null, terminalFailure.get());
    }

    private static boolean isUnexpectedTerminal(AuthStateMachine.Kind actual, AuthStateMachine.Kind expected) {
        if (actual == expected) return false;
        return actual == AuthStateMachine.Kind.MISSING_CONFIGURATION
            || actual == AuthStateMachine.Kind.ERROR
            || actual == AuthStateMachine.Kind.ERROR_CLEANUP_UNCONFIRMED
            || actual == AuthStateMachine.Kind.SIGNED_OUT_REVOKE_UNCONFIRMED;
    }

    private AuthApiClient.TripResult awaitDetail(String tripId) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AuthApiClient.TripResult> result = new AtomicReference<>();
        api.tripDetail(tripId, value -> { result.set(value); latch.countDown(); });
        assertTrue(latch.await(20, TimeUnit.SECONDS));
        return result.get();
    }

    private static int recyclerCount(ActivityScenario<MainActivity> scenario) {
        AtomicReference<Integer> count = new AtomicReference<>();
        scenario.onActivity(activity -> count.set(((RecyclerView) activity.findViewById(R.id.trip_list)).getAdapter().getItemCount()));
        return count.get();
    }

    private static void control(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        try {
            connection.setConnectTimeout(10_000); connection.setReadTimeout(10_000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(0);
            connection.getOutputStream().close(); assertEquals(204, connection.getResponseCode());
        } finally { connection.disconnect(); }
    }

    private static String required(Bundle args, String name) {
        String value = args.getString(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing instrumentation argument: " + name);
        return value;
    }

    private static void revealInOuterScroll(ActivityScenario<MainActivity> scenario, int viewId) {
        scenario.onActivity(activity -> {
            android.widget.ScrollView outer = activity.findViewById(R.id.auth_scroll);
            View target = activity.findViewById(viewId);
            Rect bounds = new Rect();
            target.getDrawingRect(bounds);
            outer.offsetDescendantRectToMyCoords(target, bounds);
            outer.scrollTo(0, Math.max(0, bounds.bottom - outer.getHeight()));
        });
    }

    private static void showKeyboardAndAwaitInsets(ActivityScenario<MainActivity> scenario, int viewId) throws Exception {
        CountDownLatch visible = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            View input = activity.findViewById(viewId);
            ViewCompat.setOnApplyWindowInsetsListener(input, (view, insets) -> {
                if (insets.isVisible(WindowInsetsCompat.Type.ime())) visible.countDown();
                return insets;
            });
            input.requestFocus();
            WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(input);
            if (controller == null) throw new IllegalStateException("Window insets controller unavailable");
            controller.show(WindowInsetsCompat.Type.ime());
        });
        assertTrue("IME inset visibility timed out", visible.await(10, TimeUnit.SECONDS));
        scenario.onActivity(activity -> ViewCompat.setOnApplyWindowInsetsListener(activity.findViewById(viewId), null));
    }

    private static void screenshot(ActivityScenario<MainActivity> scenario, String name) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            View root = activity.getWindow().getDecorView();
            root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown); root.invalidate();
        });
        assertTrue(committed.await(10, TimeUnit.SECONDS));
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("device screenshot unavailable", bitmap);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/STOG-Trip-Real-QA");
        android.net.Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("Screenshot failed");
        } catch (Exception exception) { throw new IllegalStateException(exception); }
        finally { bitmap.recycle(); }
    }

    private static final class MutableKakao implements AuthStateMachine.KakaoGateway {
        final AtomicReference<String> token = new AtomicReference<>();
        @Override public void login(AuthStateMachine.LoginCallback callback) { callback.succeeded(token.get()); }
        @Override public void cleanup(AuthStateMachine.CompletionCallback callback) { callback.completed(true); }
    }
}
