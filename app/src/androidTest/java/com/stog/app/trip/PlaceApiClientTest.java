package com.stog.app.trip;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class PlaceApiClientTest {
    private static final String PLACE = "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db";
    private static final String TRIP = "940e857d-b025-4f72-b420-2ee354a514ad";
    private final SecureRefreshStore store = new SecureRefreshStore(ApplicationProvider.getApplicationContext());

    @After public void clear() { store.clear(); }

    @Test public void searchDetailAndSaveUseExactAuthenticatedWireAndNullableFields() throws Exception {
        try (Fixture fixture = new Fixture(); AuthApiClient client = signedIn(fixture)) {
            AuthApiClient.PlacePageResult page = awaitPlacePage(callback -> client.searchPlaces("성산 일출", "제주", "관광지", null, callback));
            assertEquals(AuthApiClient.PlaceOutcome.SUCCESS, page.outcome());
            assertEquals(1, page.page().items().size());
            assertEquals("cursor_1", page.page().nextCursor());
            assertEquals("/api/places?query=%EC%84%B1%EC%82%B0%20%EC%9D%BC%EC%B6%9C&region=%EC%A0%9C%EC%A3%BC&category=%EA%B4%80%EA%B4%91%EC%A7%80&limit=20", fixture.searchPath.get());

            AuthApiClient.PlaceResult detail = awaitPlace(callback -> client.placeDetail(PLACE, callback));
            assertEquals(AuthApiClient.PlaceOutcome.SUCCESS, detail.outcome());
            assertNull(detail.place().roadAddress());
            assertNull(detail.place().latitude());
            assertNull(detail.place().longitude());

            AuthApiClient.BasketSaveResult saved = awaitSave(callback -> client.savePlaceToBasket(TRIP, PLACE, 7,
                "cb96754c-37f3-42fb-91b6-a5d9ad6cb80e", callback));
            assertEquals(AuthApiClient.BasketSaveOutcome.CREATED, saved.outcome());
            assertTrue(fixture.saveBody.get().contains("\"expected_plan_version\":7"));
            assertTrue(fixture.saveBody.get().contains("\"place_id\":\"" + PLACE + "\""));
        }
    }

    @Test public void malformedPlaceSuccessAndConflictAreMappedWithoutInventingContent() throws Exception {
        try (Fixture fixture = new Fixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.malformed = true;
            assertEquals(AuthApiClient.PlaceOutcome.RETRYABLE_ERROR,
                awaitPlace(callback -> client.placeDetail(PLACE, callback)).outcome());
            fixture.malformed = false; fixture.conflict = true;
            assertEquals(AuthApiClient.BasketSaveOutcome.VERSION_CONFLICT,
                awaitSave(callback -> client.savePlaceToBasket(TRIP, PLACE, 7,
                    "cb96754c-37f3-42fb-91b6-a5d9ad6cb80e", callback)).outcome());
        }
    }

    private AuthApiClient signedIn(Fixture fixture) throws Exception {
        AuthApiClient client = new AuthApiClient(fixture.baseUri(), store);
        CountDownLatch done = new CountDownLatch(1); AtomicReference<Boolean> result = new AtomicReference<>();
        client.exchange("fixture", value -> { result.set(value); done.countDown(); });
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertEquals(Boolean.TRUE, result.get()); return client;
    }
    private interface PageCall { void call(AuthApiClient.PlacePageCallback callback); }
    private interface PlaceCall { void call(AuthApiClient.PlaceCallback callback); }
    private interface SaveCall { void call(AuthApiClient.BasketSaveCallback callback); }
    private static AuthApiClient.PlacePageResult awaitPlacePage(PageCall call) throws Exception { CountDownLatch done = new CountDownLatch(1); AtomicReference<AuthApiClient.PlacePageResult> value = new AtomicReference<>(); call.call(result -> { value.set(result); done.countDown(); }); assertTrue(done.await(10, TimeUnit.SECONDS)); return value.get(); }
    private static AuthApiClient.PlaceResult awaitPlace(PlaceCall call) throws Exception { CountDownLatch done = new CountDownLatch(1); AtomicReference<AuthApiClient.PlaceResult> value = new AtomicReference<>(); call.call(result -> { value.set(result); done.countDown(); }); assertTrue(done.await(10, TimeUnit.SECONDS)); return value.get(); }
    private static AuthApiClient.BasketSaveResult awaitSave(SaveCall call) throws Exception { CountDownLatch done = new CountDownLatch(1); AtomicReference<AuthApiClient.BasketSaveResult> value = new AtomicReference<>(); call.call(result -> { value.set(result); done.countDown(); }); assertTrue(done.await(10, TimeUnit.SECONDS)); return value.get(); }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final AtomicReference<String> searchPath = new AtomicReference<>();
        final AtomicReference<String> saveBody = new AtomicReference<>();
        volatile boolean malformed; volatile boolean conflict;
        Fixture() throws IOException { executor.execute(this::serve); }
        URI baseUri() { return URI.create("http://127.0.0.1:" + server.getLocalPort()); }
        private void serve() { while (!server.isClosed()) try (Socket socket = server.accept()) { respond(socket); } catch (IOException ignored) { } }
        private void respond(Socket socket) throws IOException {
            InputStream input = socket.getInputStream(); String headers = headers(input); String first = headers.substring(0, headers.indexOf("\r\n")); String path = first.split(" ")[1]; byte[] body = body(input, length(headers));
            if (path.equals("/api/auth/kakao/exchange")) { json(socket, 200, "{\"token_type\":\"Bearer\",\"access_token\":\"access\",\"access_expires_in\":900,\"refresh_token\":\"refresh\",\"refresh_expires_in\":2592000}"); return; }
            if (path.startsWith("/api/places?")) { searchPath.set(path); json(socket, 200, "{\"items\":[" + summary() + "],\"next_cursor\":\"cursor_1\"}"); return; }
            if (path.equals("/api/places/" + PLACE)) { json(socket, 200, malformed ? "{\"place_id\":\"" + PLACE + "\"}" : detail()); return; }
            if (path.equals("/api/trips/" + TRIP + "/trip-items")) { saveBody.set(new String(body, StandardCharsets.UTF_8)); json(socket, conflict ? 409 : 201, conflict ? error("PLAN_VERSION_CONFLICT") : "{\"result\":\"CREATED\",\"trip_item_id\":\"bc3a32fb-230c-4f43-83ca-ce1903eb1545\",\"place_id\":\"" + PLACE + "\",\"plan_version\":8}"); return; }
            json(socket, 404, error("PLACE_NOT_FOUND"));
        }
        private static String summary() { return "{\"place_id\":\"" + PLACE + "\",\"name\":\"성산일출봉\",\"category\":\"관광지\",\"region_label\":\"제주\",\"address\":null,\"latitude\":null,\"longitude\":null}"; }
        private static String detail() { return "{\"place_id\":\"" + PLACE + "\",\"name\":\"성산일출봉\",\"category\":\"관광지\",\"region_label\":\"제주\",\"address\":null,\"road_address\":null,\"description\":null,\"latitude\":null,\"longitude\":null}"; }
        private static String error(String code) { return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"safe\"}}"; }
        private static void json(Socket socket, int status, String text) throws IOException { byte[] bytes = text.getBytes(StandardCharsets.UTF_8); OutputStream output = socket.getOutputStream(); output.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(bytes); output.flush(); }
        private static int length(String headers) { String value = header(headers, "content-length"); return value == null ? 0 : Integer.parseInt(value); }
        private static byte[] body(InputStream input, int length) throws IOException { byte[] bytes = new byte[length]; for (int offset = 0; offset < length;) { int read = input.read(bytes, offset, length - offset); if (read < 0) throw new IOException(); offset += read; } return bytes; }
        private static String header(String headers, String name) { for (String line : headers.split("\r\n")) { int colon = line.indexOf(':'); if (colon > 0 && line.substring(0, colon).toLowerCase(Locale.ROOT).equals(name)) return line.substring(colon + 1).trim(); } return null; }
        private static String headers(InputStream input) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] end = {'\r','\n','\r','\n'}; int matched = 0; while (matched < end.length) { int value = input.read(); if (value < 0) throw new IOException(); output.write(value); matched = value == end[matched] ? matched + 1 : value == end[0] ? 1 : 0; } return output.toString(StandardCharsets.US_ASCII); }
        @Override public void close() throws Exception { server.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
