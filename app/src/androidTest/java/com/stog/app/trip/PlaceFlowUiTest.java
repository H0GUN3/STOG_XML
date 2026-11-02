package com.stog.app.auth;

import android.app.Application;
import android.graphics.Bitmap;
import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import androidx.lifecycle.ViewModelProvider;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralSwipeAction;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.stog.app.MainActivity;
import com.stog.app.R;
import com.stog.app.trip.TripViewModel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.Press.FINGER;
import static androidx.test.espresso.action.Swipe.FAST;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class PlaceFlowUiTest {
    private static final String TRIP = "940e857d-b025-4f72-b420-2ee354a514ad";
    private static final String PLACE = "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db";

    @After public void cleanup() { AuthViewModel.clearDebugFixture(); }

    @Test public void selectedTripSearchDetailSaveBackAndRecreationPreserveMeaningfulState() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<AuthViewModel> auth = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> { AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context)); AuthViewModel value = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedIn(api))); auth.set(value); return value; });
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                CountDownLatch signedIn = new CountDownLatch(1); scenario.onActivity(activity -> auth.get().state().observeForever(state -> { if (state.kind() == AuthStateMachine.Kind.SIGNED_IN) signedIn.countDown(); }));
                assertTrue(signedIn.await(10, TimeUnit.SECONDS)); assertTrue(fixture.list.await(10, TimeUnit.SECONDS));
                onView(withText("제주 여행")).perform(click()); assertTrue(fixture.basket.await(10, TimeUnit.SECONDS));
                onView(withId(R.id.trip_add_place)).perform(scrollTo(), click());
                TripViewModel model = model(scenario); CountDownLatch results = observeSearch(model, TripViewModel.PlaceSearchKind.CONTENT);
                onView(withId(R.id.place_query_input)).perform(replaceText("성산 일출")); closeSoftKeyboard(); onView(withId(R.id.place_search_submit)).perform(click());
                assertTrue(results.await(10, TimeUnit.SECONDS)); screenshot(scenario, "search.png"); CountDownLatch detail = observeDetail(model, TripViewModel.PlaceDetailKind.CONTENT); onView(withText("성산일출봉")).perform(click());
                assertTrue(detail.await(10, TimeUnit.SECONDS));
                onView(withText(R.string.place_coordinates_missing)).check(matches(withText(R.string.place_coordinates_missing))); screenshot(scenario, "detail.png");
                CountDownLatch saved = observeSave(model, TripViewModel.PlaceSaveKind.CREATED); CountDownLatch basketReloaded = observeBasketCount(model, 1); onView(withId(R.id.place_save_action)).perform(scrollTo(), click()); assertTrue(saved.await(10, TimeUnit.SECONDS)); assertTrue(basketReloaded.await(10, TimeUnit.SECONDS));
                assertTrue(fixture.saved.await(10, TimeUnit.SECONDS)); assertTrue(fixture.saveBody.get().contains("\"expected_plan_version\":7")); onView(withText(R.string.place_save_created)).check(matches(withText(R.string.place_save_created))); screenshot(scenario, "saved.png");
                pressBack(); onView(withId(R.id.place_query_input)).check(matches(withText("성산 일출"))); assertEquals(1, model.placeSearch().getValue().places().size());
                recreateAndAwait(scenario); onView(withId(R.id.place_query_input)).check(matches(withText("성산 일출"))); onView(withText("성산일출봉")).check(matches(withText("성산일출봉")));
                scenario.onActivity(activity -> assertEquals(android.content.res.Configuration.ORIENTATION_PORTRAIT, activity.getResources().getConfiguration().orientation));
            }
        }
    }

    @Test public void nativeResultGesturesLoadOneContinuationWithoutDuplicateRequest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pagedPlaces = true; AtomicReference<AuthViewModel> auth = new AtomicReference<>();
            AuthViewModel.installDebugFixture(context -> { AuthApiClient api = new AuthApiClient(fixture.baseUri(), new SecureRefreshStore(context)); AuthViewModel value = new AuthViewModel(api, new AuthStateMachine(true, null, new SignedIn(api))); auth.set(value); return value; });
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                CountDownLatch signedIn = new CountDownLatch(1); scenario.onActivity(activity -> auth.get().state().observeForever(state -> { if (state.kind() == AuthStateMachine.Kind.SIGNED_IN) signedIn.countDown(); })); assertTrue(signedIn.await(10, TimeUnit.SECONDS)); assertTrue(fixture.list.await(10, TimeUnit.SECONDS));
                onView(withText("제주 여행")).perform(click()); assertTrue(fixture.basket.await(10, TimeUnit.SECONDS)); onView(withId(R.id.trip_add_place)).perform(scrollTo(), click()); TripViewModel model = model(scenario);
                CountDownLatch first = observePlaceCount(model, 20); onView(withId(R.id.place_query_input)).perform(replaceText("paged")); closeSoftKeyboard(); onView(withId(R.id.place_search_submit)).perform(click()); assertTrue(first.await(10, TimeUnit.SECONDS));
                CountDownLatch second = observePlaceCount(model, 21); ViewAction[] gestures = new ViewAction[40]; Arrays.fill(gestures, shortSwipeUp()); onView(withId(R.id.place_search_results)).perform(gestures); assertTrue(second.await(10, TimeUnit.SECONDS));
                assertEquals(21, model.placeSearch().getValue().places().size()); assertEquals("continuation must be requested exactly once", 2, fixture.placeSearchRequests.get());
            }
        }
    }

    private static GeneralSwipeAction shortSwipeUp() { return new GeneralSwipeAction(FAST, view -> { float distance = 64f * view.getResources().getDisplayMetrics().density; return new float[]{view.getWidth() / 2f, view.getHeight() / 2f + distance}; }, view -> { float distance = 64f * view.getResources().getDisplayMetrics().density; return new float[]{view.getWidth() / 2f, view.getHeight() / 2f - distance}; }, FINGER); }

    private static CountDownLatch observePlaceCount(TripViewModel model, int count) { CountDownLatch latch = new CountDownLatch(1); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> model.placeSearch().observeForever(value -> { if (value.kind() == TripViewModel.PlaceSearchKind.CONTENT && value.places().size() == count) latch.countDown(); })); return latch; }

    private static TripViewModel model(ActivityScenario<MainActivity> scenario) { AtomicReference<TripViewModel> value = new AtomicReference<>(); scenario.onActivity(activity -> value.set(new ViewModelProvider(activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container)).get(TripViewModel.class))); return value.get(); }
    private static CountDownLatch observeSearch(TripViewModel model, TripViewModel.PlaceSearchKind kind) { CountDownLatch latch = new CountDownLatch(1); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> model.placeSearch().observeForever(value -> { if (value.kind() == kind) latch.countDown(); })); return latch; }
    private static CountDownLatch observeDetail(TripViewModel model, TripViewModel.PlaceDetailKind kind) { CountDownLatch latch = new CountDownLatch(1); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> model.placeDetail().observeForever(value -> { if (value.kind() == kind) latch.countDown(); })); return latch; }
    private static CountDownLatch observeSave(TripViewModel model, TripViewModel.PlaceSaveKind kind) { CountDownLatch latch = new CountDownLatch(1); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> model.placeSave().observeForever(value -> { if (value.kind() == kind) latch.countDown(); })); return latch; }
    private static CountDownLatch observeBasketCount(TripViewModel model, int count) { CountDownLatch latch = new CountDownLatch(1); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> model.detail().observeForever(value -> { if (value.kind() == TripViewModel.DetailKind.CONTENT && value.basket() != null && value.basket().items().size() == count) latch.countDown(); })); return latch; }
    private static void screenshot(ActivityScenario<MainActivity> scenario, String name) throws Exception {
        CountDownLatch committed = new CountDownLatch(1); scenario.onActivity(activity -> { ViewCommit.register(activity.getWindow().getDecorView(), committed); activity.getWindow().getDecorView().invalidate(); }); assertTrue(committed.await(10, TimeUnit.SECONDS));
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot(); android.content.ContentValues values = new android.content.ContentValues(); values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "r3a-" + name); values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png"); values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/STOG-R3a-QA"); android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); android.net.Uri uri = context.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) { assertTrue(stream != null && bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); } finally { bitmap.recycle(); }
    }
    private static final class ViewCommit { static void register(android.view.View view, CountDownLatch latch) { view.getViewTreeObserver().registerFrameCommitCallback(latch::countDown); } }

    private static void recreateAndAwait(ActivityScenario<MainActivity> scenario) throws Exception {
        AtomicReference<MainActivity> previous = new AtomicReference<>(); CountDownLatch resumed = new CountDownLatch(1); Application app = (Application) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() { @Override public void onActivityCreated(android.app.Activity a, Bundle b) { } @Override public void onActivityStarted(android.app.Activity a) { } @Override public void onActivityResumed(android.app.Activity a) { if (a instanceof MainActivity && a != previous.get()) resumed.countDown(); } @Override public void onActivityPaused(android.app.Activity a) { } @Override public void onActivityStopped(android.app.Activity a) { } @Override public void onActivitySaveInstanceState(android.app.Activity a, Bundle b) { } @Override public void onActivityDestroyed(android.app.Activity a) { } };
        app.registerActivityLifecycleCallbacks(callbacks); try { scenario.onActivity(previous::set); scenario.recreate(); assertTrue(resumed.await(10, TimeUnit.SECONDS)); } finally { app.unregisterActivityLifecycleCallbacks(callbacks); }
    }
    private static final class SignedIn implements AuthStateMachine.SessionGateway { private final AuthApiClient api; SignedIn(AuthApiClient api) { this.api = api; } @Override public void restore(AuthStateMachine.CompletionCallback callback) { api.exchange("fixture", callback); } @Override public void exchange(String token, AuthStateMachine.CompletionCallback callback) { callback.completed(true); } @Override public void logout(AuthStateMachine.CompletionCallback callback) { callback.completed(true); } }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1")); final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch list = new CountDownLatch(1), basket = new CountDownLatch(1), saved = new CountDownLatch(1); final AtomicReference<String> saveBody = new AtomicReference<>(); final AtomicInteger placeSearchRequests = new AtomicInteger(); volatile boolean hasItem; volatile boolean pagedPlaces;
        Fixture() throws IOException { executor.execute(this::serve); } URI baseUri() { return URI.create("http://127.0.0.1:" + server.getLocalPort()); }
        private void serve() { while (!server.isClosed()) try (Socket socket = server.accept()) { respond(socket); } catch (IOException ignored) { } }
        private void respond(Socket socket) throws IOException {
            InputStream input = socket.getInputStream(); String headers = headers(input); String first = headers.substring(0, headers.indexOf("\r\n")); String path = first.split(" ")[1]; byte[] body = body(input, length(headers));
            if (path.equals("/api/auth/kakao/exchange")) { json(socket, 200, tokens()); return; }
            if (path.equals("/api/trips?limit=20")) { list.countDown(); json(socket, 200, "{\"as_of_date\":\"2026-10-06\",\"items\":[" + trip(false) + "],\"next_cursor\":null}"); return; }
            if (path.equals("/api/trips/" + TRIP)) { json(socket, 200, trip(true)); return; }
            if (path.startsWith("/api/trips/" + TRIP + "/trip-items?")) { basket.countDown(); json(socket, 200, basket()); return; }
            if (path.startsWith("/api/places?")) { placeSearchRequests.incrementAndGet(); if (!pagedPlaces) json(socket, 200, "{\"items\":[" + summary() + "],\"next_cursor\":null}"); else if (path.contains("cursor=cursor1")) json(socket, 200, "{\"items\":[" + pagedSummary(20) + "],\"next_cursor\":null}"); else { StringBuilder items = new StringBuilder(); for (int index = 0; index < 20; index++) { if (index > 0) items.append(','); items.append(pagedSummary(index)); } json(socket, 200, "{\"items\":[" + items + "],\"next_cursor\":\"cursor1\"}"); } return; }
            if (path.equals("/api/places/" + PLACE)) { json(socket, 200, detail()); return; }
            if (path.equals("/api/trips/" + TRIP + "/trip-items") && first.startsWith("POST")) { saveBody.set(new String(body, StandardCharsets.UTF_8)); hasItem = true; saved.countDown(); json(socket, 201, "{\"result\":\"CREATED\",\"trip_item_id\":\"bc3a32fb-230c-4f43-83ca-ce1903eb1545\",\"place_id\":\"" + PLACE + "\",\"plan_version\":8}"); return; }
            json(socket, 404, error("TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE"));
        }
        private String trip(boolean detail) { return "{\"trip_id\":\"" + TRIP + "\",\"name\":\"제주 여행\",\"region_label\":\"제주\",\"start_date\":\"2026-10-10\",\"end_date\":\"2026-10-12\",\"status\":\"UPCOMING\"" + (detail ? ",\"as_of_date\":\"2026-10-06\"" : "") + ",\"role\":\"MEMBER\",\"member_count\":2,\"plan_version\":" + (hasItem ? 8 : 7) + ",\"cover\":null}"; }
        private String basket() { return "{\"trip_id\":\"" + TRIP + "\",\"plan_version\":" + (hasItem ? 8 : 7) + ",\"items\":" + (hasItem ? "[{\"trip_item_id\":\"bc3a32fb-230c-4f43-83ca-ce1903eb1545\",\"place_id\":\"" + PLACE + "\",\"state\":\"BASKET\",\"schedule_date\":null,\"sort_order\":null,\"created_at\":\"2026-10-06T00:00:00Z\"}]" : "[]") + ",\"next_cursor\":null}"; }
        private static String summary() { return "{\"place_id\":\"" + PLACE + "\",\"name\":\"성산일출봉\",\"category\":\"관광지\",\"region_label\":\"제주\",\"address\":\"제주특별자치도 서귀포시\",\"latitude\":null,\"longitude\":null}"; }
        private static String pagedSummary(int number) { return "{\"place_id\":\"" + String.format(Locale.ROOT, "%08x", number + 1) + "-43ab-43d7-9ca9-bc61b69f20db\",\"name\":\"Paged " + String.format(Locale.ROOT, "%02d", number) + "\",\"category\":null,\"region_label\":null,\"address\":null,\"latitude\":null,\"longitude\":null}"; }
        private static String detail() { return "{\"place_id\":\"" + PLACE + "\",\"name\":\"성산일출봉\",\"category\":\"관광지\",\"region_label\":\"제주\",\"address\":\"제주특별자치도 서귀포시\",\"road_address\":null,\"description\":null,\"latitude\":null,\"longitude\":null}"; }
        private static String tokens() { return "{\"token_type\":\"Bearer\",\"access_token\":\"access\",\"access_expires_in\":900,\"refresh_token\":\"refresh\",\"refresh_expires_in\":2592000}"; } private static String error(String code) { return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"safe\"}}"; }
        private static void json(Socket socket, int status, String text) throws IOException { byte[] bytes = text.getBytes(StandardCharsets.UTF_8); OutputStream out = socket.getOutputStream(); out.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush(); }
        private static int length(String headers) { String value = header(headers, "content-length"); return value == null ? 0 : Integer.parseInt(value); } private static byte[] body(InputStream in, int length) throws IOException { byte[] bytes = new byte[length]; for (int offset = 0; offset < length;) { int read = in.read(bytes, offset, length-offset); if (read < 0) throw new IOException(); offset += read; } return bytes; }
        private static String header(String headers, String name) { for (String line : headers.split("\r\n")) { int colon = line.indexOf(':'); if (colon > 0 && line.substring(0,colon).toLowerCase(Locale.ROOT).equals(name)) return line.substring(colon+1).trim(); } return null; } private static String headers(InputStream in) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] end = {'\r','\n','\r','\n'}; int match=0; while(match<4){int v=in.read();if(v<0)throw new IOException();out.write(v);match=v==end[match]?match+1:v==end[0]?1:0;}return out.toString(StandardCharsets.US_ASCII); }
        @Override public void close() throws Exception { server.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
