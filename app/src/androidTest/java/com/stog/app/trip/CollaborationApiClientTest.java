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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Local HTTP fixture verifies R2a uses the existing authenticated transport, not a parallel client. */
@RunWith(AndroidJUnit4.class)
public final class CollaborationApiClientTest {
    private static final String TRIP = "940e857d-b025-4f72-b420-2ee354a514ad";
    private static final String MEMBER = "f6f1128e-8c48-47c1-a084-6be4e854133f";
    private final SecureRefreshStore store = new SecureRefreshStore(ApplicationProvider.getApplicationContext());
    @After public void cleanup() { store.clear(); }

    @Test public void mapsVersionAndInvitationErrorsWithoutExposingCapabilitiesToUiText() throws Exception {
        try (Fixture fixture = new Fixture(); AuthApiClient client = signedIn(fixture)) {
            AuthApiClient.CollaborationResult conflict = await(callback -> client.updateTrip(TRIP,
                new AuthApiClient.TripUpdateRequest("제주", "제주", "2026-10-10", "2026-10-12", 4,
                    "cb96754c-37f3-42fb-91b6-a5d9ad6cb80e"), callback));
            assertEquals(AuthApiClient.CollaborationOutcome.VERSION_CONFLICT, conflict.outcome());

            AuthApiClient.CollaborationResult members = await(callback -> client.members(TRIP, null, callback));
            assertEquals(AuthApiClient.CollaborationOutcome.SUCCESS, members.outcome());
            assertEquals("민지", members.members().items().get(0).memberLabel());
            assertEquals(MEMBER, members.members().items().get(0).memberUserId());

            AuthApiClient.CollaborationResult unusable = await(callback -> client.joinInvitation(
                "n4mYxW5u4nS5JH1xQjQfn0zhvCq8kG9GqfdrkZ5ve08", "민지",
                "0302f303-e57f-4166-a338-d9a068f19a93", callback));
            assertEquals(AuthApiClient.CollaborationOutcome.INVITATION_UNUSABLE, unusable.outcome());
            assertTrue(fixture.allProtected.get());
        }
    }

    private AuthApiClient signedIn(Fixture fixture) throws Exception {
        AuthApiClient client = new AuthApiClient(fixture.baseUri(), store);
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<Boolean> result = new AtomicReference<>();
        client.exchange("synthetic-provider-token", value -> { result.set(value); latch.countDown(); });
        assertTrue(latch.await(10, TimeUnit.SECONDS)); assertEquals(Boolean.TRUE, result.get()); return client;
    }
    private static AuthApiClient.CollaborationResult await(Call call) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<AuthApiClient.CollaborationResult> result = new AtomicReference<>();
        call.call(value -> { result.set(value); latch.countDown(); }); assertTrue(latch.await(10, TimeUnit.SECONDS)); return result.get();
    }
    private interface Call { void call(AuthApiClient.CollaborationCallback callback); }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final java.util.concurrent.atomic.AtomicBoolean allProtected = new java.util.concurrent.atomic.AtomicBoolean();
        Fixture() throws IOException { executor.execute(this::serve); }
        URI baseUri() { return URI.create("http://127.0.0.1:" + server.getLocalPort()); }
        private void serve() { while (!server.isClosed()) try (Socket socket = server.accept()) { respond(socket); } catch (IOException ignored) { } }
        private void respond(Socket socket) throws IOException {
            InputStream input = socket.getInputStream(); String headers = headers(input); String request = headers.substring(0, headers.indexOf("\r\n"));
            int length = 0; for (String line : headers.split("\r\n")) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            while (length > 0) { int read = input.read(new byte[Math.min(length, 1024)]); if (read < 0) throw new IOException(); length -= read; }
            String path = request.split(" ")[1];
            if (path.equals("/api/auth/kakao/exchange")) { json(socket, 200, tokens()); return; }
            allProtected.set(headers.contains("Authorization: Bearer access"));
            if (path.equals("/api/trips/" + TRIP) && request.startsWith("PUT")) { json(socket, 409, error("PLAN_VERSION_CONFLICT")); return; }
            if (path.equals("/api/trips/" + TRIP + "/members?limit=20")) { json(socket, 200, members()); return; }
            if (path.equals("/api/trip-invitations/join")) { json(socket, 404, error("INVITATION_NOT_FOUND_OR_NOT_USABLE")); return; }
            json(socket, 404, error("TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE"));
        }
        private static String members() { return "{\"trip_id\":\"" + TRIP + "\",\"plan_version\":4,\"member_count\":1,\"items\":[{\"member_user_id\":\"" + MEMBER + "\",\"role\":\"MEMBER\",\"member_label\":\"민지\",\"is_self\":true}],\"next_cursor\":null}"; }
        private static String tokens() { return "{\"token_type\":\"Bearer\",\"access_token\":\"access\",\"access_expires_in\":900,\"refresh_token\":\"refresh\",\"refresh_expires_in\":2592000}"; }
        private static String error(String code) { return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"safe\"}}"; }
        private static void json(Socket socket, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); OutputStream output = socket.getOutputStream(); output.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(bytes); output.flush(); }
        private static String headers(InputStream input) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] end = {'\r','\n','\r','\n'}; int matched = 0; while (matched < end.length) { int value = input.read(); if (value < 0) throw new IOException(); output.write(value); matched = value == end[matched] ? matched + 1 : value == end[0] ? 1 : 0; } return output.toString(StandardCharsets.US_ASCII); }
        @Override public void close() throws Exception { server.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
