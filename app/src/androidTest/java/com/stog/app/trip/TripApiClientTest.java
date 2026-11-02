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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class TripApiClientTest {
    private static final String TRIP = "940e857d-b025-4f72-b420-2ee354a514ad";
    private final SecureRefreshStore store = new SecureRefreshStore(ApplicationProvider.getApplicationContext());

    @After public void clear() { store.clear(); }

    @Test public void exactCreateListDetailAndEmptyBasketUseSharedBearerTransport() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            AuthApiClient.TripResult created = await(callback -> client.createTrip(new AuthApiClient.TripCreateRequest(
                "제주 여행", "제주", "2026-10-10", "2026-10-12", "cb96754c-37f3-42fb-91b6-a5d9ad6cb80e"), callback));
            assertEquals(AuthApiClient.TripOutcome.SUCCESS, created.outcome());
            assertEquals(TRIP, created.trip().tripId());
            assertEquals("제주 여행", created.trip().name());
            assertTrue(fixture.createBody.get().contains("\"request_id\""));

            AuthApiClient.TripPageResult list = awaitPage(client::currentTrips);
            assertEquals(AuthApiClient.TripOutcome.SUCCESS, list.outcome());
            assertEquals(1, list.page().items().size());
            assertEquals(TRIP, list.page().items().get(0).tripId());

            AuthApiClient.TripResult detail = await(callback -> client.tripDetail(TRIP, callback));
            assertEquals(AuthApiClient.TripOutcome.SUCCESS, detail.outcome());
            assertEquals("2026-10-01", fixture.asOfSeen.get());

            AuthApiClient.BasketResult basket = awaitBasket(callback -> client.tripBasket(TRIP, callback));
            assertEquals(AuthApiClient.BasketOutcome.SUCCESS, basket.outcome());
            assertTrue(basket.page().items().isEmpty());
            assertEquals(4, fixture.protectedCalls.get());
        }
    }

    @Test public void legacyDetailAllowsNullMetadataButCreateStillUsesTheSameExactWire() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            String nullMetadata = TripFixture.trip(true).replace("\"name\":\"제주 여행\"", "\"name\":null")
                .replace("\"region_label\":\"제주\"", "\"region_label\":null");
            fixture.createResponse = nullMetadata;
            assertEquals(AuthApiClient.TripOutcome.RETRYABLE_ERROR, await(callback -> client.createTrip(
                new AuthApiClient.TripCreateRequest("제주 여행", "제주", "2026-10-10", "2026-10-12",
                    "cb96754c-37f3-42fb-91b6-a5d9ad6cb80e"), callback)).outcome());
            fixture.detailBody = nullMetadata;

            AuthApiClient.TripResult detail = await(callback -> client.tripDetail(TRIP, callback));

            assertEquals(AuthApiClient.TripOutcome.SUCCESS, detail.outcome());
            assertEquals(TRIP, detail.trip().tripId());
            assertEquals(null, detail.trip().name());
            assertEquals(null, detail.trip().regionLabel());
        }
    }

    @Test public void requiredNullableCoverMetadataIsAcceptedFromTheAuthenticatedTripDto() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.detailBody = TripFixture.trip(true).replace("}", ",\"cover\":{\"cover_id\":\"b9d8ae64-654a-4fe3-a569-a7c9f8706524\",\"media_type\":\"image/jpeg\",\"byte_size\":483210,\"width\":1600,\"height\":1200,\"thumbnail_byte_size\":42510,\"thumbnail_width\":512,\"thumbnail_height\":384,\"file_path\":\"/api/trips/" + TRIP + "/covers/b9d8ae64-654a-4fe3-a569-a7c9f8706524/file\",\"thumbnail_path\":\"/api/trips/" + TRIP + "/covers/b9d8ae64-654a-4fe3-a569-a7c9f8706524/thumbnail\"}}");
            AuthApiClient.TripResult detail = await(callback -> client.tripDetail(TRIP, callback));
            assertEquals(AuthApiClient.TripOutcome.SUCCESS, detail.outcome());
        }
    }

    @Test public void multipartCoverUploadUsesSharedBearerAndMapsSetResponse() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            java.io.File image = java.io.File.createTempFile("stog-cover", ".jpg", ApplicationProvider.getApplicationContext().getCacheDir());
            try (OutputStream output = new java.io.FileOutputStream(image)) { output.write(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}); }
            try {
                AuthApiClient.CoverResult result = awaitCover(callback -> client.setTripCover(TRIP,
                    new AuthApiClient.CoverUpload(image, "image/jpeg", 0, "cb96754c-37f3-42fb-91b6-a5d9ad6cb80e"), callback));
                assertEquals(AuthApiClient.CoverOutcome.SUCCESS, result.outcome());
                assertEquals("b9d8ae64-654a-4fe3-a569-a7c9f8706524", result.cover().coverId());
                assertTrue(fixture.coverBody.get().contains("name=\"image\""));
                assertTrue(fixture.coverBody.get().contains("name=\"command\""));
                assertTrue(fixture.coverBody.get().contains("expected_plan_version"));
            } finally { image.delete(); }
        }
    }

    @Test public void basket200WithMissingRequiredItemKeysIsNeverContent() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.basketBody = TripFixture.basketWithItem("{\"trip_item_id\":\"bc3a32fb-230c-4f43-83ca-ce1903eb1545\",\"place_id\":\"4d0f73ea-43ab-43d7-9ca9-bc61b69f20db\",\"state\":\"BASKET\",\"schedule_date\":null,\"created_at\":\"2026-10-01T03:04:05Z\",\"unexpected\":null}");
            assertEquals(AuthApiClient.BasketOutcome.RETRYABLE_ERROR, awaitBasket(callback -> client.tripBasket(TRIP, callback)).outcome());

            fixture.basketBody = TripFixture.basketWithItem("{\"trip_item_id\":\"bc3a32fb-230c-4f43-83ca-ce1903eb1545\",\"place_id\":\"4d0f73ea-43ab-43d7-9ca9-bc61b69f20db\",\"state\":\"BASKET\",\"sort_order\":null,\"created_at\":\"2026-10-01T03:04:05Z\",\"unexpected\":null}");
            assertEquals(AuthApiClient.BasketOutcome.RETRYABLE_ERROR, awaitBasket(callback -> client.tripBasket(TRIP, callback)).outcome());
        }
    }

    private AuthApiClient signedIn(TripFixture fixture) throws Exception {
        AuthApiClient client = new AuthApiClient(fixture.baseUri(), store);
        CountDownLatch done = new CountDownLatch(1); AtomicReference<Boolean> result = new AtomicReference<>();
        client.exchange("synthetic-provider-token", value -> { result.set(value); done.countDown(); });
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertEquals(Boolean.TRUE, result.get()); return client;
    }
    private static AuthApiClient.TripResult await(TripCall call) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<AuthApiClient.TripResult> result = new AtomicReference<>();
        call.call(value -> { result.set(value); done.countDown(); }); assertTrue(done.await(10, TimeUnit.SECONDS)); return result.get();
    }
    private static AuthApiClient.TripPageResult awaitPage(PageCall call) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<AuthApiClient.TripPageResult> result = new AtomicReference<>();
        call.call(value -> { result.set(value); done.countDown(); }); assertTrue(done.await(10, TimeUnit.SECONDS)); return result.get();
    }
    private static AuthApiClient.BasketResult awaitBasket(BasketCall call) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<AuthApiClient.BasketResult> result = new AtomicReference<>();
        call.call(value -> { result.set(value); done.countDown(); }); assertTrue(done.await(10, TimeUnit.SECONDS)); return result.get();
    }
    private interface TripCall { void call(AuthApiClient.TripCallback callback); }
    private interface PageCall { void call(AuthApiClient.TripPageCallback callback); }
    private interface BasketCall { void call(AuthApiClient.BasketCallback callback); }
    private interface CoverCall { void call(AuthApiClient.CoverCallback callback); }
    private static AuthApiClient.CoverResult awaitCover(CoverCall call) throws Exception { CountDownLatch done = new CountDownLatch(1); AtomicReference<AuthApiClient.CoverResult> result = new AtomicReference<>(); call.call(value -> { result.set(value); done.countDown(); }); assertTrue(done.await(10, TimeUnit.SECONDS)); return result.get(); }

    private static final class TripFixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final AtomicInteger protectedCalls = new AtomicInteger();
        final AtomicReference<String> createBody = new AtomicReference<>("");
        final AtomicReference<String> coverBody = new AtomicReference<>("");
        final AtomicReference<String> asOfSeen = new AtomicReference<>("");
        volatile String createResponse;
        volatile String detailBody;
        volatile String basketBody;
        TripFixture() throws IOException { executor.execute(this::serve); }
        URI baseUri() { return URI.create("http://127.0.0.1:" + server.getLocalPort()); }
        private void serve() { while (!server.isClosed()) try (Socket socket = server.accept()) { respond(socket); } catch (IOException ignored) { } }
        private void respond(Socket socket) throws IOException {
            InputStream input = socket.getInputStream(); String headers = headers(input); String first = headers.substring(0, headers.indexOf("\r\n"));
            byte[] body = readBody(input, length(headers)); String path = first.split(" ")[1];
            if (path.equals("/api/auth/kakao/exchange")) { json(socket, 200, tokens()); return; }
            if (path.equals("/api/trips") && first.startsWith("POST")) { protectedCalls.incrementAndGet(); createBody.set(new String(body, StandardCharsets.UTF_8)); json(socket, 201, createResponse == null ? trip(true) : createResponse); return; }
            if (path.equals("/api/trips/" + TRIP + "/cover") && first.startsWith("POST")) { protectedCalls.incrementAndGet(); coverBody.set(new String(body, StandardCharsets.ISO_8859_1)); json(socket, 201, coverSet()); return; }
            if (path.startsWith("/api/trips?")) { protectedCalls.incrementAndGet(); json(socket, 200, "{\"as_of_date\":\"2026-10-01\",\"items\":[" + trip(false) + "],\"next_cursor\":null}"); return; }
            if (path.equals("/api/trips/" + TRIP)) { protectedCalls.incrementAndGet(); asOfSeen.set("2026-10-01"); json(socket, 200, detailBody == null ? trip(true) : detailBody); return; }
            if (path.startsWith("/api/trips/" + TRIP + "/trip-items?state=BASKET")) { protectedCalls.incrementAndGet(); json(socket, 200, basketBody == null ? basketWithItem("") : basketBody); return; }
            json(socket, 404, "{\"error\":{\"code\":\"TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE\",\"message\":\"safe\"}}");
        }
        private static String trip(boolean asOf) { return "{\"trip_id\":\"" + TRIP + "\",\"name\":\"제주 여행\",\"region_label\":\"제주\",\"start_date\":\"2026-10-10\",\"end_date\":\"2026-10-12\",\"status\":\"UPCOMING\"" + (asOf ? ",\"as_of_date\":\"2026-10-01\"" : "") + ",\"role\":\"ADMIN\",\"member_count\":1,\"plan_version\":0,\"cover\":null}"; }
        private static String basketWithItem(String item) { return "{\"trip_id\":\"" + TRIP + "\",\"plan_version\":0,\"items\":[" + item + "],\"next_cursor\":null}"; }
        private static String coverSet() { return "{\"result\":\"SET\",\"cover\":{\"cover_id\":\"b9d8ae64-654a-4fe3-a569-a7c9f8706524\",\"media_type\":\"image/jpeg\",\"byte_size\":4,\"width\":1,\"height\":1,\"thumbnail_byte_size\":4,\"thumbnail_width\":1,\"thumbnail_height\":1,\"file_path\":\"/api/trips/" + TRIP + "/covers/b9d8ae64-654a-4fe3-a569-a7c9f8706524/file\",\"thumbnail_path\":\"/api/trips/" + TRIP + "/covers/b9d8ae64-654a-4fe3-a569-a7c9f8706524/thumbnail\"},\"plan_version\":1}"; }
        private static String tokens() { return "{\"token_type\":\"Bearer\",\"access_token\":\"access\",\"access_expires_in\":900,\"refresh_token\":\"refresh\",\"refresh_expires_in\":2592000}"; }
        private static void json(Socket socket, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); OutputStream output = socket.getOutputStream(); output.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(bytes); output.flush(); }
        private static int length(String headers) { String value = header(headers, "content-length"); return value == null ? 0 : Integer.parseInt(value); }
        private static byte[] readBody(InputStream input, int length) throws IOException { byte[] body = new byte[length]; for (int offset = 0; offset < length;) { int read = input.read(body, offset, length - offset); if (read < 0) throw new IOException("Unexpected request end"); offset += read; } return body; }
        private static String header(String headers, String name) { for (String line : headers.split("\r\n")) { int colon = line.indexOf(':'); if (colon > 0 && line.substring(0, colon).toLowerCase(Locale.ROOT).equals(name)) return line.substring(colon + 1).trim(); } return null; }
        private static String headers(InputStream input) throws IOException { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] end = {'\r','\n','\r','\n'}; int matched = 0; while (matched < end.length) { int value = input.read(); if (value < 0) throw new IOException(); bytes.write(value); matched = value == end[matched] ? matched + 1 : (value == end[0] ? 1 : 0); } return bytes.toString(StandardCharsets.US_ASCII); }
        @Override public void close() throws Exception { server.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
