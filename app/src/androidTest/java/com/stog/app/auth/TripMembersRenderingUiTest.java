package com.stog.app.auth;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.action.GeneralSwipeAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stog.app.MainActivity;
import com.stog.app.MainFragment;
import com.stog.app.R;
import com.stog.app.trip.TripViewModel;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.Press.FINGER;
import static androidx.test.espresso.action.Swipe.FAST;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Focused regression for the native member-row rendering surface. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class TripMembersRenderingUiTest {
    private static final String TRIP = "940e857d-b025-4f72-b420-2ee354a514ad";
    private static final String OWNER = "f6f1128e-8c48-47c1-a084-6be4e854133f";
    private static final String MEMBER = "3640f312-f119-4d39-b490-45697d9d611b";

    private String originalFontScale;
    private String originalAccelerometerRotation;
    private String originalUserRotation;
    private String originalWindowAnimationScale;
    private String originalTransitionAnimationScale;
    private String originalAnimatorDurationScale;

    @Before public void rememberDeviceSettings() throws Exception {
        originalFontScale = shell("settings get system font_scale").trim();
        originalAccelerometerRotation = shell("settings get system accelerometer_rotation").trim();
        originalUserRotation = shell("settings get system user_rotation").trim();
        originalWindowAnimationScale = shell("settings get global window_animation_scale").trim();
        originalTransitionAnimationScale = shell("settings get global transition_animation_scale").trim();
        originalAnimatorDurationScale = shell("settings get global animator_duration_scale").trim();
        shell("settings put global window_animation_scale 0");
        shell("settings put global transition_animation_scale 0");
        shell("settings put global animator_duration_scale 0");
    }

    @After public void cleanup() throws Exception {
        AuthViewModel.clearDebugFixture();
        new SecureRefreshStore(InstrumentationRegistry.getInstrumentation().getTargetContext()).clear();
        if ("1".equals(originalAccelerometerRotation)) {
            shell("cmd window user-rotation free");
        } else if (originalUserRotation != null && !originalUserRotation.isBlank() && !"null".equals(originalUserRotation)) {
            shell("cmd window user-rotation lock " + originalUserRotation);
        }
        restoreSetting("system", "font_scale", originalFontScale);
        restoreSetting("system", "accelerometer_rotation", originalAccelerometerRotation);
        restoreSetting("system", "user_rotation", originalUserRotation);
        restoreSetting("global", "window_animation_scale", originalWindowAnimationScale);
        restoreSetting("global", "transition_animation_scale", originalTransitionAnimationScale);
        restoreSetting("global", "animator_duration_scale", originalAnimatorDurationScale);
    }

    @Test public void aRendersTwoActionableMemberRowsAtDefaultFontScale() throws Exception {
        renderAndAssert(1.0f, "member-rows-font-1.00.png");
    }

    @Test public void bRendersTwoActionableMemberRowsAtLargeFontScale() throws Exception {
        renderAndAssert(1.3f, "member-rows-font-1.30.png");
    }

    @Test public void cReachesAndTapsEveryMemberControlAtLargeFontScalePortrait() throws Exception {
        exerciseEveryMemberControl(false);
    }

    @Test public void dRemainsPortraitAndReachesEveryMemberControlUnderBothLandscapeDeviceRotations() throws Exception {
        exerciseEveryMemberControl(true);
    }

    private void exerciseEveryMemberControl(boolean rotatedDevice) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new SecureRefreshStore(context).clear();
        try (Fixture fixture = new Fixture()) {
            AuthViewModel.installDebugFixture(app -> {
                AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(app));
                return new AuthViewModel(api, new AuthStateMachine(true, new ImmediateLogin(), api));
            });
            setFontScaleBeforeLaunch(1.3f);
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                AtomicReference<TripViewModel> model = new AtomicReference<>();
                CountDownLatch listReady = new CountDownLatch(1);
                scenario.onActivity(activity -> {
                    assertEquals(Configuration.ORIENTATION_PORTRAIT,
                        activity.getResources().getConfiguration().orientation);
                    assertEquals(1.3f, activity.getResources().getConfiguration().fontScale, 0.01f);
                    MainFragment main = (MainFragment) activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                    TripViewModel trips = new ViewModelProvider(main).get(TripViewModel.class);
                    model.set(trips);
                    trips.list().observeForever(state -> {
                        if (state.kind() == TripViewModel.ListKind.CONTENT && state.trips().size() == 1) listReady.countDown();
                    });
                });
                assertTrue("signed-out fixture did not become ready",
                    observeSignedOut(scenario).await(10, TimeUnit.SECONDS));
                onView(withId(R.id.auth_primary_action)).check(matches(allOf(isEnabled(),
                    withText(R.string.auth_login_action)))).perform(click());
                boolean rendered = listReady.await(20, TimeUnit.SECONDS);
                assertTrue("trip fixture did not render; state=" + model.get().list().getValue()
                    + "; requests=" + fixture.requests(), rendered);
                CountDownLatch detailReady = observeDetail(model.get());
                reach(withText("멤버 렌더 회귀 여행"));
                onView(withText("멤버 렌더 회귀 여행")).perform(click());
                assertTrue("detail fixture did not render", detailReady.await(10, TimeUnit.SECONDS));
                reach(withId(R.id.trip_detail_members));
                CountDownLatch membersReady = observeMembers(model.get());
                onView(withId(R.id.trip_detail_members)).perform(click());
                assertTrue("member fixture did not reach CONTENT(2)", membersReady.await(10, TimeUnit.SECONDS));
                if (rotatedDevice) {
                    assertPortraitUnderDeviceRotation(scenario, 1);
                    assertPortraitUnderDeviceRotation(scenario, 3);
                }
                awaitMemberPreDraw(scenario);
                assertListContract(scenario);

                String orientation = rotatedDevice ? "portrait-rotation" : "portrait";
                exerciseRow(scenario, "나", "역할: ADMIN", orientation + "-owner");
                exerciseRow(scenario, "민지", "역할: MEMBER", orientation + "-member");
            }
        }
    }

    private static void exerciseRow(ActivityScenario<MainActivity> scenario, String label, String role,
            String screenshotPrefix) throws Exception {
        reach(withText(label));
        reach(withText(role));
        exerciseControl(scenario, label, R.id.trip_member_role_action, R.string.trip_confirm_role_title,
            screenshotPrefix + "-role");
        exerciseControl(scenario, label, R.id.trip_member_remove_action, R.string.trip_confirm_remove_title,
            screenshotPrefix + "-remove");
    }

    private static void exerciseControl(ActivityScenario<MainActivity> scenario, String label, int actionId,
            int dialogTitle, String screenshotPrefix) throws Exception {
        Matcher<View> action = allOf(withId(actionId), withParent(hasDescendant(withText(label))));
        reach(action);
        screenshot(scenario, screenshotPrefix + "-control.png", "Pictures/STOG-R2a-Member-Control-Proof");
        onView(action).perform(click());
        onView(withText(dialogTitle)).check(matches(isDisplayed()));
        screenshot(scenario, screenshotPrefix + "-dialog.png", "Pictures/STOG-R2a-Member-Control-Proof");
        onView(withText(R.string.trip_confirm_cancel)).perform(click());
    }

    private static void reach(Matcher<View> target) {
        for (int gesture = 0; gesture < 12; gesture++) {
            AtomicReference<ReachState> state = new AtomicReference<>();
            try {
                onView(target).check((view, missing) -> {
                    if (missing != null) throw missing;
                    Rect visible = new Rect();
                    boolean hasVisibleRect = view.getGlobalVisibleRect(visible);
                    long visibleArea = hasVisibleRect ? (long) visible.width() * visible.height() : 0;
                    long area = (long) view.getWidth() * view.getHeight();
                    int[] location = new int[2];
                    view.getLocationOnScreen(location);
                    state.set(new ReachState(area > 0 && visibleArea * 100 >= area * 90,
                        location[1], location[1] + view.getHeight(), location[1] + view.getHeight() / 2));
                });
            } catch (NoMatchingViewException detachedRow) {
                onView(withId(R.id.trip_members_list)).perform(shortSwipeUp());
                continue;
            }
            if (state.get().visible()) {
                onView(target).check(matches(isDisplayingAtLeast(90)));
                return;
            }
            AtomicReference<Rect> listBounds = new AtomicReference<>();
            try {
                onView(withId(R.id.trip_members_list)).check((view, missing) -> {
                    if (missing != null) throw missing;
                    int[] location = new int[2];
                    view.getLocationOnScreen(location);
                    listBounds.set(new Rect(location[0], location[1], location[0] + view.getWidth(),
                        location[1] + view.getHeight()));
                });
            } catch (NoMatchingViewException beforeMembersScreen) {
                listBounds.set(null);
            }
            if (listBounds.get() != null && state.get().top() < listBounds.get().top) {
                onView(withId(R.id.trip_members_list)).perform(shortSwipeDown());
                continue;
            }
            if (listBounds.get() != null && state.get().bottom() > listBounds.get().bottom) {
                onView(withId(R.id.trip_members_list)).perform(shortSwipeUp());
                continue;
            }
            AtomicReference<Rect> viewport = new AtomicReference<>();
            onView(withId(R.id.auth_scroll)).check((view, missing) -> {
                if (missing != null) throw missing;
                Rect bounds = new Rect();
                assertTrue("outer scroll viewport is not visible", view.getGlobalVisibleRect(bounds));
                viewport.set(bounds);
            });
            onView(withId(R.id.auth_scroll)).perform(state.get().centerY() < viewport.get().centerY()
                ? outerSwipeDown() : outerSwipeUp());
        }
        onView(target).check(matches(isDisplayingAtLeast(90)));
    }

    private static GeneralSwipeAction outerSwipeUp() { return boundedSwipe(true, true); }

    private static GeneralSwipeAction outerSwipeDown() { return boundedSwipe(false, true); }

    private static GeneralSwipeAction shortSwipeUp() { return boundedSwipe(true, false); }

    private static GeneralSwipeAction shortSwipeDown() { return boundedSwipe(false, false); }

    private static GeneralSwipeAction boundedSwipe(boolean up, boolean outerGutter) {
        return new GeneralSwipeAction(FAST, view -> {
            float distance = 32f * view.getResources().getDisplayMetrics().density;
            float x = outerGutter
                ? view.getResources().getDimension(R.dimen.screen_padding_horizontal)
                    - 2f * view.getResources().getDisplayMetrics().density
                : view.getWidth() / 2f;
            return new float[] { x, view.getHeight() / 2f + (up ? distance : -distance) };
        }, view -> {
            float distance = 32f * view.getResources().getDisplayMetrics().density;
            float x = outerGutter
                ? view.getResources().getDimension(R.dimen.screen_padding_horizontal)
                    - 2f * view.getResources().getDisplayMetrics().density
                : view.getWidth() / 2f;
            return new float[] { x, view.getHeight() / 2f + (up ? -distance : distance) };
        }, FINGER);
    }

    private static void assertPortraitUnderDeviceRotation(ActivityScenario<MainActivity> scenario, int rotation) throws Exception {
        shell("cmd window user-rotation lock " + rotation);
        shell("cmd window wait-for-all-windows-drawn");
        scenario.onActivity(activity -> assertEquals("STOG host must remain portrait under device rotation " + rotation,
            Configuration.ORIENTATION_PORTRAIT, activity.getResources().getConfiguration().orientation));
    }

    private static void setFontScaleBeforeLaunch(float fontScale) throws Exception {
        Application application = (Application) InstrumentationRegistry.getInstrumentation().getTargetContext()
            .getApplicationContext();
        if (Math.abs(application.getResources().getConfiguration().fontScale - fontScale) < 0.01f) return;
        CountDownLatch applied = new CountDownLatch(1);
        ComponentCallbacks callbacks = new ComponentCallbacks() {
            @Override public void onConfigurationChanged(Configuration configuration) {
                if (Math.abs(configuration.fontScale - fontScale) < 0.01f) applied.countDown();
            }
            @Override public void onLowMemory() { }
        };
        application.registerComponentCallbacks(callbacks);
        try {
            shell("settings put system font_scale " + String.format(Locale.ROOT, "%.2f", fontScale));
            assertTrue("font-scale configuration event timed out", applied.await(20, TimeUnit.SECONDS));
        } finally {
            application.unregisterComponentCallbacks(callbacks);
        }
    }

    private record ReachState(boolean visible, int top, int bottom, int centerY) { }

    private void renderAndAssert(float fontScale, String screenshotName) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new SecureRefreshStore(context).clear();
        try (Fixture fixture = new Fixture()) {
            AuthViewModel.installDebugFixture(app -> {
                AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(app));
                return new AuthViewModel(api, new AuthStateMachine(true, new ImmediateLogin(), api));
            });
            setFontScaleBeforeLaunch(fontScale);
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                AtomicReference<TripViewModel> model = new AtomicReference<>();
                CountDownLatch listReady = new CountDownLatch(1);
                scenario.onActivity(activity -> {
                    assertEquals(fontScale, activity.getResources().getConfiguration().fontScale, 0.01f);
                    MainFragment main = (MainFragment) activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                    TripViewModel trips = new ViewModelProvider(main).get(TripViewModel.class);
                    model.set(trips);
                    trips.list().observeForever(state -> {
                        if (state.kind() == TripViewModel.ListKind.CONTENT && state.trips().size() == 1) listReady.countDown();
                    });
                });
                assertTrue("signed-out fixture did not become ready",
                    observeSignedOut(scenario).await(10, TimeUnit.SECONDS));
                onView(withId(R.id.auth_primary_action)).check(matches(allOf(isEnabled(),
                    withText(R.string.auth_login_action)))).perform(click());
                boolean rendered = listReady.await(20, TimeUnit.SECONDS);
                assertTrue("trip fixture did not render; state=" + model.get().list().getValue()
                    + "; requests=" + fixture.requests(), rendered);

                CountDownLatch detailReady = observeDetail(model.get());
                onView(withText("멤버 렌더 회귀 여행")).perform(click());
                assertTrue("detail fixture did not render", detailReady.await(10, TimeUnit.SECONDS));
                onView(withId(R.id.trip_detail_members)).perform(scrollTo()).check(matches(isDisplayed()));

                CountDownLatch membersReady = observeMembers(model.get());
                onView(withId(R.id.trip_detail_members)).perform(click());
                assertTrue("member fixture did not reach CONTENT(2)", membersReady.await(10, TimeUnit.SECONDS));
                awaitMemberPreDraw(scenario);
                assertListContract(scenario);
                assertRowAtPosition(scenario, 0, "나", "역할: ADMIN");
                assertRowAtPosition(scenario, 1, "민지", "역할: MEMBER");
                onView(withText("민지")).check(matches(isDisplayed()));
                screenshot(scenario, screenshotName, "Pictures/STOG-R2a-Row-Regression");
            }
        }
    }

    private static CountDownLatch observeSignedOut(ActivityScenario<MainActivity> scenario) {
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            AuthViewModel auth = new ViewModelProvider(activity).get(AuthViewModel.class);
            @SuppressWarnings("unchecked") Observer<AuthStateMachine.State>[] observer = new Observer[1];
            observer[0] = state -> {
                if (state.kind() == AuthStateMachine.Kind.SIGNED_OUT) {
                    auth.state().removeObserver(observer[0]);
                    latch.countDown();
                }
            };
            auth.state().observeForever(observer[0]);
        });
        return latch;
    }

    private static CountDownLatch observeDetail(TripViewModel model) {
        CountDownLatch latch = new CountDownLatch(1);
        @SuppressWarnings("unchecked") Observer<TripViewModel.DetailState>[] observer = new Observer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            observer[0] = state -> {
                if (state.kind() == TripViewModel.DetailKind.CONTENT && state.trip() != null) {
                    model.detail().removeObserver(observer[0]);
                    latch.countDown();
                }
            };
            model.detail().observeForever(observer[0]);
        });
        return latch;
    }

    private static CountDownLatch observeMembers(TripViewModel model) {
        CountDownLatch latch = new CountDownLatch(1);
        @SuppressWarnings("unchecked") Observer<TripViewModel.MembersState>[] observer = new Observer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            observer[0] = state -> {
                if (state.kind() == TripViewModel.MembersKind.CONTENT && state.page() != null
                        && state.page().items().size() == 2) {
                    model.members().removeObserver(observer[0]);
                    latch.countDown();
                }
            };
            model.members().observeForever(observer[0]);
        });
        return latch;
    }

    private static void awaitMemberPreDraw(ActivityScenario<MainActivity> scenario) throws Exception {
        CountDownLatch preDraw = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.trip_members_list);
            list.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    list.getViewTreeObserver().removeOnPreDrawListener(this);
                    preDraw.countDown();
                    return true;
                }
            });
            list.requestLayout();
        });
        assertTrue("member list pre-draw timed out", preDraw.await(10, TimeUnit.SECONDS));
    }

    private static void assertListContract(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.trip_members_list);
            assertNotNull("member list requires a LayoutManager", list.getLayoutManager());
            assertEquals("loaded members must remain two UI rows", 2, list.getAdapter().getItemCount());
        });
    }

    private static void assertRowAtPosition(ActivityScenario<MainActivity> scenario, int position,
            String label, String role) throws Exception {
        CountDownLatch preDraw = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.trip_members_list);
            list.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    list.getViewTreeObserver().removeOnPreDrawListener(this);
                    preDraw.countDown();
                    return true;
                }
            });
            ((androidx.recyclerview.widget.LinearLayoutManager) list.getLayoutManager())
                .scrollToPositionWithOffset(position, 0);
        });
        assertTrue("member row " + position + " pre-draw timed out", preDraw.await(10, TimeUnit.SECONDS));
        scenario.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.trip_members_list);
            View row = list.getLayoutManager().findViewByPosition(position);
            assertRow(row, label, role);
            List<String> renderedText = new ArrayList<>();
            collectText(row, renderedText);
            assertFalse(renderedText.contains(OWNER));
            assertFalse(renderedText.contains(MEMBER));
            for (String text : renderedText) assertFalse("raw UUID must not be rendered: " + text,
                text.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*"));
        });
    }

    private static void assertRow(View row, String label, String role) {
        assertNotNull(row);
        assertTrue("row must have measured bounds", row.getWidth() > 0 && row.getHeight() > 0);
        assertEquals(label, ((TextView) row.findViewById(R.id.trip_member_name)).getText().toString());
        assertEquals(role, ((TextView) row.findViewById(R.id.trip_member_role)).getText().toString());
        View roleAction = row.findViewById(R.id.trip_member_role_action);
        View removeAction = row.findViewById(R.id.trip_member_remove_action);
        assertEquals(View.VISIBLE, roleAction.getVisibility());
        assertEquals(View.VISIBLE, removeAction.getVisibility());
        assertTrue(roleAction.isEnabled() && roleAction.isClickable());
        assertTrue(removeAction.isEnabled() && removeAction.isClickable());
    }

    private static void collectText(View view, List<String> output) {
        if (view instanceof TextView text && text.getText() != null) output.add(text.getText().toString());
        if (view.getContentDescription() != null) output.add(view.getContentDescription().toString());
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) collectText(group.getChildAt(index), output);
    }

    private static void screenshot(ActivityScenario<MainActivity> scenario, String name, String relativePath) throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            activity.getWindow().getDecorView().getViewTreeObserver().registerFrameCommitCallback(frame::countDown);
            activity.getWindow().getDecorView().invalidate();
        });
        assertTrue("screenshot frame timed out", frame.await(10, TimeUnit.SECONDS));
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storedPath = relativePath + "/";
        context.getContentResolver().delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.DISPLAY_NAME + " = ? AND " + MediaStore.Images.Media.RELATIVE_PATH + " = ?",
            new String[] { name, storedPath });
        ContentValues values = new ContentValues();
        String uniqueName = name.substring(0, name.length() - 4) + "-" + System.nanoTime() + ".png";
        values.put(MediaStore.Images.Media.DISPLAY_NAME, uniqueName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, storedPath);
        android.net.Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            assertNotNull(output);
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
    }

    private static void restoreSetting(String namespace, String key, String value) throws Exception {
        if (value != null && !value.isBlank() && !"null".equals(value)) shell("settings put " + namespace + " " + key + " " + value);
    }

    private static String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class ImmediateLogin implements AuthStateMachine.KakaoGateway {
        @Override public void login(AuthStateMachine.LoginCallback callback) { callback.succeeded("fixture-provider-token"); }
        @Override public void cleanup(AuthStateMachine.CompletionCallback callback) { callback.completed(true); }
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());

        Fixture() throws IOException { executor.execute(this::serve); }
        URI baseUri() { return URI.create("http://127.0.0.1:" + server.getLocalPort()); }
        String requests() { return requests.toString(); }

        private void serve() {
            while (!server.isClosed()) try (Socket socket = server.accept()) { respond(socket); }
            catch (IOException ignored) { }
        }

        private void respond(Socket socket) throws IOException {
            InputStream input = socket.getInputStream();
            String headers = headers(input);
            String request = headers.substring(0, headers.indexOf("\r\n"));
            requests.add(request);
            int length = 0;
            for (String line : headers.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            while (length > 0) { int read = input.read(new byte[Math.min(length, 1024)]); if (read < 0) throw new IOException(); length -= read; }
            String path = request.split(" ")[1];
            if (path.equals("/api/auth/kakao/exchange")) { json(socket, tokens()); return; }
            if (path.equals("/api/trips?limit=20")) { json(socket, tripPage()); return; }
            if (path.equals("/api/trips/" + TRIP)) { json(socket, tripDetail()); return; }
            if (path.equals("/api/trips/" + TRIP + "/trip-items?state=BASKET&limit=20")) { json(socket, basket()); return; }
            if (path.equals("/api/trips/" + TRIP + "/members?limit=20")) { json(socket, members()); return; }
            throw new IOException("Unexpected fixture request: " + request);
        }

        private static String tokens() { return "{\"token_type\":\"Bearer\",\"access_token\":\"access\",\"access_expires_in\":900,\"refresh_token\":\"refresh\",\"refresh_expires_in\":2592000}"; }
        private static String trip() { return "\"trip_id\":\"" + TRIP + "\",\"name\":\"멤버 렌더 회귀 여행\",\"region_label\":\"서울\",\"start_date\":\"2026-10-10\",\"end_date\":\"2026-10-12\",\"status\":\"UPCOMING\",\"role\":\"ADMIN\",\"member_count\":2,\"plan_version\":4,\"cover\":null"; }
        private static String tripPage() { return "{\"as_of_date\":\"2026-10-01\",\"items\":[{" + trip() + "}],\"next_cursor\":null}"; }
        private static String tripDetail() { return "{" + trip() + ",\"as_of_date\":\"2026-10-01\"}"; }
        private static String basket() { return "{\"trip_id\":\"" + TRIP + "\",\"plan_version\":4,\"items\":[],\"next_cursor\":null}"; }
        private static String members() { return "{\"trip_id\":\"" + TRIP + "\",\"plan_version\":4,\"member_count\":2,\"items\":[{\"member_user_id\":\"" + OWNER + "\",\"role\":\"ADMIN\",\"member_label\":null,\"is_self\":true},{\"member_user_id\":\"" + MEMBER + "\",\"role\":\"MEMBER\",\"member_label\":\"민지\",\"is_self\":false}],\"next_cursor\":null}"; }
        private static void json(Socket socket, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes); output.flush();
        }
        private static String headers(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] end = {'\r','\n','\r','\n'}; int matched = 0;
            while (matched < end.length) { int value = input.read(); if (value < 0) throw new IOException(); output.write(value); matched = value == end[matched] ? matched + 1 : value == end[0] ? 1 : 0; }
            return output.toString(StandardCharsets.US_ASCII);
        }
        @Override public void close() throws Exception { server.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
