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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class AuthApiClientSecurityTest {
    private static final String TOKENS = "{\"token_type\":\"Bearer\",\"access_token\":\"fixture-access-token\","
        + "\"access_expires_in\":900,\"refresh_token\":\"fixture-refresh-token\","
        + "\"refresh_expires_in\":2592000}";

    private final Context context = ApplicationProvider.getApplicationContext();
    private final SecureRefreshStore store = new SecureRefreshStore(context);

    @After public void clear() { store.clear(); }

    @Test public void redirectsNeverForwardCredentialBodyOrAuthorization() throws Exception {
        for (int redirectStatus : new int[] {302, 307, 308}) {
            try (RawServer receiver = new RawServer(request -> Reply.json(200, TOKENS));
                 RawServer redirector = new RawServer(request -> Reply.redirect(redirectStatus, receiver.baseUri()))) {
                AuthApiClient client = new AuthApiClient(redirector.baseUri(), store);
                assertFalse(await(callback -> client.exchange("fixture-provider-token", callback)));
                assertEquals(0, receiver.requestCount.get());
                assertEquals("", receiver.lastBody.get());
                assertNull(receiver.lastAuthorization.get());
                assertNull(client.accessToken());
                client.close();
            }
        }
    }

    @Test public void errorStatusesAndMalformedSuccessNeverCreateSession() throws Exception {
        Reply[] replies = {
            Reply.json(400, "{}"), Reply.json(401, "{}"), Reply.json(503, "{}"), Reply.json(200, "{malformed")
        };
        for (Reply reply : replies) {
            try (RawServer server = new RawServer(request -> reply)) {
                AuthApiClient client = new AuthApiClient(server.baseUri(), store);
                assertFalse(await(callback -> client.exchange("fixture-provider-token", callback)));
                assertNull(client.accessToken());
                assertNull(store.read());
                client.close();
            }
        }
    }

    @Test public void missingRefreshMakesLogoutUnconfirmedAndClearsMemoryAccess() throws Exception {
        try (RawServer server = tokenAndLogoutServer()) {
            AuthApiClient client = signedIn(server);
            store.clear();

            assertFalse(await(client::logout));
            assertNull(client.accessToken());
            assertEquals(0, server.logoutCount.get());
            client.close();
        }
    }

    @Test public void corruptRefreshMakesLogoutUnconfirmedAndClearsMemoryAccess() throws Exception {
        try (RawServer server = tokenAndLogoutServer()) {
            AuthApiClient client = signedIn(server);
            context.getSharedPreferences(SecureRefreshStore.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
                .putString(SecureRefreshStore.ENCRYPTED_REFRESH_KEY, "corrupt-fixture-ciphertext").commit();

            assertFalse(await(client::logout));
            assertNull(client.accessToken());
            assertNull(store.read());
            assertEquals(0, server.logoutCount.get());
            client.close();
        }
    }

    private AuthApiClient signedIn(RawServer server) throws Exception {
        AuthApiClient client = new AuthApiClient(server.baseUri(), store);
        assertTrue(await(callback -> client.exchange("fixture-provider-token", callback)));
        assertEquals("fixture-access-token", client.accessToken());
        return client;
    }

    private static RawServer tokenAndLogoutServer() throws IOException {
        return new RawServer(request -> request.path.contains("/logout")
            ? Reply.empty(204) : Reply.json(200, TOKENS));
    }

    private static boolean await(Operation operation) throws Exception {
        AtomicReference<Boolean> result = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        operation.run(succeeded -> { result.set(succeeded); completed.countDown(); });
        assertTrue(completed.await(10, TimeUnit.SECONDS));
        return Boolean.TRUE.equals(result.get());
    }

    private interface Operation { void run(AuthStateMachine.CompletionCallback callback); }
    private interface Responder { Reply reply(Request request); }
    private record Request(String path, String authorization, String body) {}
    private record Reply(int status, String body, String location) {
        static Reply json(int status, String body) { return new Reply(status, body, null); }
        static Reply empty(int status) { return new Reply(status, "", null); }
        static Reply redirect(int status, URI location) { return new Reply(status, "", location.toString()); }
    }

    private static final class RawServer implements AutoCloseable {
        private final ServerSocket socket;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Responder responder;
        final AtomicInteger requestCount = new AtomicInteger();
        final AtomicInteger logoutCount = new AtomicInteger();
        final AtomicReference<String> lastBody = new AtomicReference<>("");
        final AtomicReference<String> lastAuthorization = new AtomicReference<>();

        RawServer(Responder responder) throws IOException {
            this.responder = responder;
            socket = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
            executor.execute(this::serve);
        }

        URI baseUri() { return URI.create("http://127.0.0.1:" + socket.getLocalPort()); }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket accepted = socket.accept()) { respond(accepted); }
                catch (IOException exception) { if (!socket.isClosed()) throw new RuntimeException(exception); }
            }
        }

        private void respond(Socket accepted) throws IOException {
            InputStream input = accepted.getInputStream();
            String headers = readHeaders(input);
            String firstLine = headers.substring(0, headers.indexOf("\r\n"));
            String path = firstLine.split(" ")[1];
            int contentLength = headerInt(headers, "content-length");
            String authorization = header(headers, "authorization");
            byte[] body = readExactly(input, contentLength);
            String bodyText = new String(body, StandardCharsets.UTF_8);
            requestCount.incrementAndGet();
            if (path.contains("/logout")) logoutCount.incrementAndGet();
            lastBody.set(bodyText);
            lastAuthorization.set(authorization);

            Reply reply = responder.reply(new Request(path, authorization, bodyText));
            byte[] responseBody = reply.body.getBytes(StandardCharsets.UTF_8);
            String reason = reply.status == 204 ? "No Content" : reply.status >= 300 && reply.status < 400 ? "Redirect" : "Response";
            StringBuilder response = new StringBuilder("HTTP/1.1 ").append(reply.status).append(' ').append(reason)
                .append("\r\nConnection: close\r\nContent-Length: ").append(responseBody.length).append("\r\n");
            if (reply.location != null) response.append("Location: ").append(reply.location).append("\r\n");
            response.append("\r\n");
            OutputStream output = accepted.getOutputStream();
            output.write(response.toString().getBytes(StandardCharsets.US_ASCII));
            output.write(responseBody);
            output.flush();
        }

        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while (bytes.size() < 16_384) {
                int next = input.read();
                if (next < 0) throw new IOException("Unexpected header end");
                bytes.write(next);
                matched = next == end[matched] ? matched + 1 : (next == end[0] ? 1 : 0);
                if (matched == end.length) return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            }
            throw new IOException("Request headers too large");
        }

        private static String header(String headers, String name) {
            for (String line : headers.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }

        private static int headerInt(String headers, String name) {
            String value = header(headers, name);
            return value == null ? 0 : Integer.parseInt(value);
        }

        private static byte[] readExactly(InputStream input, int length) throws IOException {
            byte[] value = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = input.read(value, offset, length - offset);
                if (count < 0) throw new IOException("Unexpected body end");
                offset += count;
            }
            return value;
        }

        @Override public void close() throws Exception {
            socket.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
