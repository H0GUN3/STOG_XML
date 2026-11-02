package com.stog.app.trip;

import androidx.lifecycle.SavedStateHandle;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stog.app.auth.AuthApiClient;
import com.stog.app.auth.SecureRefreshStore;

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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class TripViewModelRecoveryTest {
    private static final String TRIP = "940e857d-b025-4f72-b420-2ee354a514ad";
    private static final String SECOND_TRIP = "a40e857d-b025-4f72-b420-2ee354a514ad";
    private final SecureRefreshStore store = new SecureRefreshStore(ApplicationProvider.getApplicationContext());

    @After public void clear() { store.clear(); }

    @Test public void invalidContinuationCursorReloadsTheFirstPageInsteadOfRetainingItForRetry() throws Exception {
        try (RecoveryFixture fixture = new RecoveryFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.invalidateContinuationCursor = true;
            AtomicReference<TripViewModel> model = new AtomicReference<>();
            AtomicReference<TripViewModel.ListState> latest = new AtomicReference<>();
            CountDownLatch firstPage = new CountDownLatch(1);
            CountDownLatch recoveredFirstPage = new CountDownLatch(1);

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TripViewModel created = new TripViewModel(client, new SavedStateHandle());
                created.list().observeForever(state -> {
                    latest.set(state);
                    if (state.kind() == TripViewModel.ListKind.CONTENT && state.nextCursor() != null) firstPage.countDown();
                    if (state.kind() == TripViewModel.ListKind.EMPTY && fixture.firstPageReads.get() == 2) recoveredFirstPage.countDown();
                });
                model.set(created);
                created.loadTrips();
            });
            assertTrue(firstPage.await(10, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> model.get().loadNextTrips());

            assertTrue(recoveredFirstPage.await(10, TimeUnit.SECONDS));
            assertEquals(2, fixture.firstPageReads.get());
            assertEquals(1, fixture.continuationReads.get());
            assertEquals(TripViewModel.ListKind.EMPTY, latest.get().kind());
            assertEquals(null, latest.get().nextCursor());
        }
    }

    @Test public void basketVersionConflictRetriesOneFreshDetailRead() throws Exception {
        assertBasketRecovery("PLAN_VERSION_CONFLICT");
    }

    @Test public void basketInvalidCursorRetriesOneFreshDetailRead() throws Exception {
        assertBasketRecovery("INVALID_CURSOR");
    }

    @Test public void processRestorationReloadsSavedPageDepthUntilTheStableAnchorIsPresent() throws Exception {
        try (RecoveryFixture fixture = new RecoveryFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.restorationPages = true;
            SavedStateHandle saved = new SavedStateHandle();
            AtomicReference<TripViewModel> first = new AtomicReference<>();
            CountDownLatch firstPage = new CountDownLatch(1);
            CountDownLatch secondPage = new CountDownLatch(1);

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TripViewModel created = new TripViewModel(client, saved);
                created.list().observeForever(state -> {
                    if (state.kind() == TripViewModel.ListKind.CONTENT && state.trips().size() == 1) firstPage.countDown();
                    if (state.kind() == TripViewModel.ListKind.CONTENT && state.trips().size() == 2) secondPage.countDown();
                });
                first.set(created);
                created.loadTrips();
            });
            assertTrue(firstPage.await(10, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> first.get().loadNextTrips());
            assertTrue(secondPage.await(10, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> first.get().saveAnchor(SECOND_TRIP, -37));

            CountDownLatch restored = new CountDownLatch(1);
            AtomicReference<TripViewModel.ListState> latest = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TripViewModel recreated = new TripViewModel(client, saved);
                recreated.list().observeForever(state -> {
                    latest.set(state);
                    if (state.kind() == TripViewModel.ListKind.CONTENT && state.trips().size() == 2) restored.countDown();
                });
                recreated.loadTrips();
            });

            assertTrue("restoration must reload through the saved page depth", restored.await(10, TimeUnit.SECONDS));
            assertEquals(2, fixture.firstPageReads.get());
            assertEquals(2, fixture.continuationReads.get());
            assertEquals(SECOND_TRIP, latest.get().trips().get(1).tripId());
        }
    }

    private void assertBasketRecovery(String firstBasketError) throws Exception {
        try (RecoveryFixture fixture = new RecoveryFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.firstBasketError = firstBasketError;
            AtomicReference<TripViewModel> model = new AtomicReference<>();
            AtomicReference<TripViewModel.DetailState> latest = new AtomicReference<>();
            CountDownLatch recoveredContent = new CountDownLatch(1);

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TripViewModel created = new TripViewModel(client, new SavedStateHandle());
                created.detail().observeForever(state -> {
                    latest.set(state);
                    if (state.kind() == TripViewModel.DetailKind.CONTENT && state.basket() != null
                            && fixture.basketReads.get() == 2) recoveredContent.countDown();
                });
                model.set(created);
                created.select(TRIP);
            });

            assertTrue(recoveredContent.await(10, TimeUnit.SECONDS));
            assertEquals(2, fixture.detailReads.get());
            assertEquals(2, fixture.basketReads.get());
            assertEquals(TripViewModel.DetailKind.CONTENT, latest.get().kind());
            assertTrue(latest.get().basket().items().isEmpty());
        }
    }

    private AuthApiClient signedIn(RecoveryFixture fixture) throws Exception {
        AuthApiClient client = new AuthApiClient(fixture.baseUri(), store);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Boolean> result = new AtomicReference<>();
        client.exchange("synthetic-provider-token", value -> { result.set(value); complete.countDown(); });
        assertTrue(complete.await(10, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, result.get());
        return client;
    }

    private static final class RecoveryFixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final AtomicInteger firstPageReads = new AtomicInteger();
        final AtomicInteger continuationReads = new AtomicInteger();
        final AtomicInteger detailReads = new AtomicInteger();
        final AtomicInteger basketReads = new AtomicInteger();
        volatile boolean invalidateContinuationCursor;
        volatile boolean restorationPages;
        volatile String firstBasketError;

        RecoveryFixture() throws IOException { executor.execute(this::serve); }
        URI baseUri() { return URI.create("http://127.0.0.1:" + server.getLocalPort()); }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) { respond(socket); }
                catch (IOException exception) { if (!server.isClosed()) throw new RuntimeException(exception); }
            }
        }

        private void respond(Socket socket) throws IOException {
            InputStream input = socket.getInputStream();
            String headers = readHeaders(input);
            String request = headers.substring(0, headers.indexOf("\r\n"));
            readBody(input, contentLength(headers));
            String path = request.split(" ")[1];
            if (path.equals("/api/auth/kakao/exchange")) { json(socket, 200, tokens()); return; }
            if (path.equals("/api/trips?limit=20")) {
                int number = firstPageReads.incrementAndGet();
                json(socket, 200, restorationPages || number == 1
                    ? page("[" + trip(TRIP, false) + "]", "\"cursor1\"") : page("[]", "null"));
                return;
            }
            if (path.equals("/api/trips?limit=20&cursor=cursor1")) {
                continuationReads.incrementAndGet();
                json(socket, invalidateContinuationCursor ? 400 : 200,
                    invalidateContinuationCursor ? error("INVALID_CURSOR")
                        : restorationPages ? page("[" + trip(SECOND_TRIP, false) + "]", "null") : page("[]", "null"));
                return;
            }
            if (path.equals("/api/trips/" + TRIP)) { detailReads.incrementAndGet(); json(socket, 200, trip(TRIP, true)); return; }
            if (path.startsWith("/api/trips/" + TRIP + "/trip-items?state=BASKET")) {
                int number = basketReads.incrementAndGet();
                boolean firstError = firstBasketError != null && number == 1;
                json(socket, firstError && "INVALID_CURSOR".equals(firstBasketError) ? 400 : firstError ? 409 : 200,
                    firstError ? error(firstBasketError) : basket());
                return;
            }
            json(socket, 404, error("TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE"));
        }

        private static String page(String items, String cursor) { return "{\"as_of_date\":\"2026-10-01\",\"items\":" + items + ",\"next_cursor\":" + cursor + "}"; }
        private static String basket() { return "{\"trip_id\":\"" + TRIP + "\",\"plan_version\":0,\"items\":[],\"next_cursor\":null}"; }
        private static String trip(String id, boolean asOf) { return "{\"trip_id\":\"" + id + "\",\"name\":\"제주 여행\",\"region_label\":\"제주\",\"start_date\":\"2026-10-10\",\"end_date\":\"2026-10-12\",\"status\":\"UPCOMING\"" + (asOf ? ",\"as_of_date\":\"2026-10-01\"" : "") + ",\"role\":\"ADMIN\",\"member_count\":1,\"plan_version\":0,\"cover\":null}"; }
        private static String tokens() { return "{\"token_type\":\"Bearer\",\"access_token\":\"access\",\"access_expires_in\":900,\"refresh_token\":\"refresh\",\"refresh_expires_in\":2592000}"; }
        private static String error(String code) { return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"safe\"}}"; }
        private static void json(Socket socket, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes); output.flush();
        }
        private static int contentLength(String headers) { String value = header(headers, "content-length"); return value == null ? 0 : Integer.parseInt(value); }
        private static void readBody(InputStream input, int length) throws IOException { while (length > 0) { int read = input.read(new byte[Math.min(length, 4096)]); if (read < 0) throw new IOException("Unexpected request end"); length -= read; } }
        private static String header(String headers, String name) { for (String line : headers.split("\r\n")) { int colon = line.indexOf(':'); if (colon > 0 && line.substring(0, colon).toLowerCase(Locale.ROOT).equals(name)) return line.substring(colon + 1).trim(); } return null; }
        private static String readHeaders(InputStream input) throws IOException { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] end = {'\r', '\n', '\r', '\n'}; int matched = 0; while (matched < end.length) { int value = input.read(); if (value < 0) throw new IOException("Unexpected request end"); bytes.write(value); matched = value == end[matched] ? matched + 1 : (value == end[0] ? 1 : 0); } return bytes.toString(StandardCharsets.US_ASCII); }
        @Override public void close() throws Exception { server.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
