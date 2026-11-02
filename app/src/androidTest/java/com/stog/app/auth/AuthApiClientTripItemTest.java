package com.stog.app.auth;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class AuthApiClientTripItemTest {
    private static final String TRIP = "940e857d-b025-4f72-b420-2ee354a514ad";
    private static final String ITEM = "bc3a32fb-230c-4f43-83ca-ce1903eb1545";
    private final SecureRefreshStore store = new SecureRefreshStore(ApplicationProvider.getApplicationContext());

    @After public void clear() { store.clear(); }

    @Test public void protectedGetMapsExactContentAndNotFoundEnvelopes() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            AuthApiClient.TripItemResult content = read(client, TRIP, ITEM);
            assertEquals(AuthApiClient.TripItemOutcome.SUCCESS, content.outcome());
            assertEquals(ITEM, content.item().tripItemId());
            assertEquals("4d0f73ea-43ab-43d7-9ca9-bc61b69f20db", content.item().placeId());
            assertEquals("BASKET", content.item().state());
            assertNull(content.item().scheduleDate());
            assertNull(content.item().sortOrder());
            assertEquals(13L, content.item().planVersion());
            assertEquals(AuthApiClient.TripItemOutcome.TRIP_NOT_FOUND,
                read(client, "00000000-0000-0000-0000-000000000404", ITEM).outcome());
            assertEquals(AuthApiClient.TripItemOutcome.ITEM_NOT_FOUND,
                read(client, TRIP, "00000000-0000-0000-0000-000000000404").outcome());
            assertEquals(3, fixture.getCalls.get());
        }
    }

    @Test public void malformedSuccessIdentifiersDatesAndStateCombinationsAreNeverContent() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            List<String> malformed = List.of(
                success("not-a-uuid", "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db", "BASKET", "null", "null"),
                success(ITEM, "4D0F73EA-43AB-43D7-9CA9-BC61B69F20DB", "BASKET", "null", "null"),
                success(ITEM, "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db", "SCHEDULED", "\"2025-02-30\"", "1"),
                success(ITEM, "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db", "SCHEDULED", "\"2025/02/28\"", "1"),
                success(ITEM, "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db", "BASKET", "\"2025-02-28\"", "1"),
                success(ITEM, "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db", "SCHEDULED", "null", "null"));
            List<AuthApiClient.TripItemOutcome> outcomes = new ArrayList<>();
            for (String body : malformed) {
                fixture.successBody = body;
                outcomes.add(read(client, TRIP, ITEM).outcome());
            }
            assertEquals(List.of(
                AuthApiClient.TripItemOutcome.RETRYABLE_ERROR,
                AuthApiClient.TripItemOutcome.RETRYABLE_ERROR,
                AuthApiClient.TripItemOutcome.RETRYABLE_ERROR,
                AuthApiClient.TripItemOutcome.RETRYABLE_ERROR,
                AuthApiClient.TripItemOutcome.RETRYABLE_ERROR,
                AuthApiClient.TripItemOutcome.RETRYABLE_ERROR), outcomes);
        }
    }

    @Test public void scheduledContentPreservesExactValidatedFields() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.successBody = success(ITEM, "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db",
                "SCHEDULED", "\"2025-02-28\"", "7");
            AuthApiClient.TripItemResult result = read(client, TRIP, ITEM);
            assertEquals(AuthApiClient.TripItemOutcome.SUCCESS, result.outcome());
            assertEquals(ITEM, result.item().tripItemId());
            assertEquals("4d0f73ea-43ab-43d7-9ca9-bc61b69f20db", result.item().placeId());
            assertEquals("SCHEDULED", result.item().state());
            assertEquals("2025-02-28", result.item().scheduleDate());
            assertEquals(Long.valueOf(7), result.item().sortOrder());
            assertEquals(13L, result.item().planVersion());
        }
    }

    @Test public void one401RefreshesOnceAndQueuedReadUsesRotatedAccess() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.expireOldAccess = true;
            AtomicReference<AuthApiClient.TripItemResult> stale = new AtomicReference<>();
            AtomicReference<AuthApiClient.TripItemResult> latest = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            client.currentTripItem(TRIP, ITEM, stale::set);
            client.currentTripItem(TRIP, ITEM, result -> { latest.set(result); completed.countDown(); });

            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertNull(stale.get());
            assertEquals(AuthApiClient.TripItemOutcome.SUCCESS, latest.get().outcome());
            assertEquals(1, fixture.refreshCalls.get());
            assertEquals(3, fixture.getCalls.get());
        }
    }

    @Test public void failedRefreshEndsAuthAndClearsBothCredentials() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.expireOldAccess = true;
            fixture.failRefresh = true;

            assertEquals(AuthApiClient.TripItemOutcome.AUTH_ENDED, read(client, TRIP, ITEM).outcome());
            assertNull(client.accessToken());
            assertNull(store.read());
            assertEquals(1, fixture.refreshCalls.get());
            assertEquals(1, fixture.getCalls.get());
        }
    }

    @Test public void second401EndsAuthWithoutAnotherRefreshOrReadRetry() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.expireOldAccess = true;
            fixture.rejectNewAccess = true;

            assertEquals(AuthApiClient.TripItemOutcome.AUTH_ENDED, read(client, TRIP, ITEM).outcome());
            assertEquals(1, fixture.refreshCalls.get());
            assertEquals(2, fixture.getCalls.get());
            assertNull(client.accessToken());
            assertNull(store.read());
        }
    }

    @Test public void malformedOversizeAndRedirectResponsesAreRetryableWithoutFollowing() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.malformed = true;
            assertEquals(AuthApiClient.TripItemOutcome.RETRYABLE_ERROR, read(client, TRIP, ITEM).outcome());
            fixture.malformed = false;
            fixture.oversize = true;
            assertEquals(AuthApiClient.TripItemOutcome.RETRYABLE_ERROR, read(client, TRIP, ITEM).outcome());
            fixture.oversize = false;
            fixture.redirect = true;
            assertEquals(AuthApiClient.TripItemOutcome.RETRYABLE_ERROR, read(client, TRIP, ITEM).outcome());
            assertEquals(0, fixture.redirectTargetCalls.get());
            assertEquals("old-access", client.accessToken());
        }
    }

    @Test public void logoutInvalidatesBlockedReadBeforeItCanPublishOrReviveSession() throws Exception {
        try (TripFixture fixture = new TripFixture(); AuthApiClient client = signedIn(fixture)) {
            fixture.blockGet = true;
            AtomicReference<AuthApiClient.TripItemResult> stale = new AtomicReference<>();
            client.currentTripItem(TRIP, ITEM, stale::set);
            assertTrue(fixture.getStarted.await(10, TimeUnit.SECONDS));

            CountDownLatch loggedOut = new CountDownLatch(1);
            client.logout(ignored -> loggedOut.countDown());
            fixture.releaseGet.countDown();

            assertTrue(loggedOut.await(10, TimeUnit.SECONDS));
            assertNull(stale.get());
            assertNull(client.accessToken());
            assertNull(store.read());
        }
    }

    private AuthApiClient signedIn(TripFixture fixture) throws Exception {
        AuthApiClient client = new AuthApiClient(fixture.baseUri(), store);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> result = new AtomicReference<>();
        client.exchange("synthetic-provider-token", succeeded -> { result.set(succeeded); done.countDown(); });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, result.get());
        return client;
    }

    private static AuthApiClient.TripItemResult read(AuthApiClient client, String trip, String item) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AuthApiClient.TripItemResult> result = new AtomicReference<>();
        client.currentTripItem(trip, item, value -> { result.set(value); done.countDown(); });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return result.get();
    }

    private static String success(String itemId, String placeId, String state,
            String scheduleDateJson, String sortOrderJson) {
        return "{\"trip_item_id\":\"" + itemId + "\",\"place_id\":\"" + placeId
            + "\",\"state\":\"" + state + "\",\"schedule_date\":" + scheduleDateJson
            + ",\"sort_order\":" + sortOrderJson + ",\"plan_version\":13}";
    }

    private static final class TripFixture implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final AtomicInteger refreshCalls = new AtomicInteger();
        private final AtomicInteger getCalls = new AtomicInteger();
        private final AtomicInteger redirectTargetCalls = new AtomicInteger();
        private final CountDownLatch getStarted = new CountDownLatch(1);
        private final CountDownLatch releaseGet = new CountDownLatch(1);
        private volatile boolean expireOldAccess;
        private volatile boolean failRefresh;
        private volatile boolean rejectNewAccess;
        private volatile boolean malformed;
        private volatile boolean oversize;
        private volatile boolean redirect;
        private volatile boolean blockGet;
        private volatile String successBody;

        TripFixture() throws IOException {
            server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
            executor.execute(this::serve);
        }
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
            String firstLine = headers.substring(0, headers.indexOf("\r\n"));
            int contentLength = contentLength(headers);
            while (contentLength > 0) {
                int count = input.read(new byte[Math.min(contentLength, 4096)]);
                if (count < 0) throw new IOException("Unexpected request end");
                contentLength -= count;
            }
            if (firstLine.startsWith("POST /api/auth/kakao/exchange ")) {
                json(socket, 200, tokens("old-access", "old-refresh"));
            } else if (firstLine.startsWith("POST /api/auth/refresh ")) {
                refreshCalls.incrementAndGet();
                if (failRefresh) json(socket, 401, error("AUTHENTICATION_REQUIRED"));
                else json(socket, 200, tokens("new-access", "new-refresh"));
            } else if (firstLine.startsWith("POST /api/auth/logout ")) {
                noContent(socket);
            } else if (firstLine.startsWith("GET ")) {
                getCalls.incrementAndGet();
                getStarted.countDown();
                if (blockGet) await(releaseGet);
                String authorization = header(headers, "authorization");
                if (firstLine.startsWith("GET /redirect-target ")) {
                    redirectTargetCalls.incrementAndGet();
                    json(socket, 200, "{}");
                } else if (redirect) {
                    redirect(socket);
                } else if (oversize) {
                    json(socket, 200, "{\"padding\":\"" + new String(new char[33_000]).replace('\0', 'x') + "\"}");
                } else if (malformed) {
                    json(socket, 200, "{\"trip_item_id\":null}");
                } else if (expireOldAccess && "Bearer old-access".equals(authorization)) {
                    json(socket, 401, error("AUTHENTICATION_REQUIRED"));
                } else if (rejectNewAccess && "Bearer new-access".equals(authorization)) {
                    json(socket, 401, error("AUTHENTICATION_REQUIRED"));
                } else if (firstLine.contains("/api/trips/00000000-0000-0000-0000-000000000404/")) {
                    json(socket, 404, error("TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE"));
                } else if (firstLine.contains("/trip-items/00000000-0000-0000-0000-000000000404 ")) {
                    json(socket, 404, error("TRIP_ITEM_NOT_FOUND"));
                } else {
                    String body = successBody == null
                        ? success(ITEM, "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db", "BASKET", "null", "null")
                        : successBody;
                    json(socket, 200, body);
                }
            } else {
                json(socket, 404, error("NOT_FOUND"));
            }
        }

        private static void await(CountDownLatch latch) throws IOException {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Fixture release timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
        }

        private static String tokens(String access, String refresh) {
            return "{\"token_type\":\"Bearer\",\"access_token\":\"" + access
                + "\",\"access_expires_in\":900,\"refresh_token\":\"" + refresh
                + "\",\"refresh_expires_in\":2592000}";
        }
        private static String error(String code) {
            return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"safe\"}}";
        }
        private static void json(Socket socket, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : "Not Found";
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json\r\nContent-Length: "
                + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
        }
        private static void noContent(Socket socket) throws IOException {
            socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        private void redirect(Socket socket) throws IOException {
            socket.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: " + baseUri()
                + "/redirect-target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        private static int contentLength(String headers) {
            String value = header(headers, "content-length");
            return value == null ? 0 : Integer.parseInt(value);
        }
        private static String header(String headers, String name) {
            for (String line : headers.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).toLowerCase(Locale.ROOT).equals(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }
        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] end = {'\r', '\n', '\r', '\n'};
            int matched = 0;
            while (bytes.size() < 16_384) {
                int next = input.read();
                if (next < 0) throw new IOException("Unexpected header end");
                bytes.write(next);
                matched = next == end[matched] ? matched + 1 : (next == end[0] ? 1 : 0);
                if (matched == end.length) return bytes.toString(StandardCharsets.US_ASCII);
            }
            throw new IOException("Request headers too large");
        }
        @Override public void close() throws Exception {
            releaseGet.countDown();
            server.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
