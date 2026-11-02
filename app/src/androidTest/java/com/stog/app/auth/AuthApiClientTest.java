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
public final class AuthApiClientTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    private final SecureRefreshStore store = new SecureRefreshStore(context);

    @After public void clear() { store.clear(); }

    @Test public void loopbackFixtureExchangesCoalescesRestoreAndLogsOut() throws Exception {
        try (FixtureServer fixture = new FixtureServer()) {
            AuthApiClient first = new AuthApiClient(fixture.baseUri(), store);
            AtomicReference<Boolean> exchange = new AtomicReference<>();
            CountDownLatch exchanged = new CountDownLatch(1);
            first.exchange("fixture-provider-token", succeeded -> {
                exchange.set(succeeded);
                exchanged.countDown();
            });
            assertTrue(exchanged.await(10, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, exchange.get());
            assertEquals("fixture-access-token", first.accessToken());
            first.close();

            AuthApiClient restored = new AuthApiClient(fixture.baseUri(), store);
            CountDownLatch restoredTwice = new CountDownLatch(2);
            restored.restore(succeeded -> { assertTrue(succeeded); restoredTwice.countDown(); });
            restored.restore(succeeded -> { assertTrue(succeeded); restoredTwice.countDown(); });
            assertTrue(restoredTwice.await(10, TimeUnit.SECONDS));
            assertEquals(1, fixture.refreshCalls.get());
            assertEquals("fixture-access-token", restored.accessToken());

            CountDownLatch loggedOut = new CountDownLatch(1);
            restored.logout(succeeded -> { assertTrue(succeeded); loggedOut.countDown(); });
            assertTrue(loggedOut.await(10, TimeUnit.SECONDS));
            assertNull(store.read());
            restored.close();
        }
    }

    private static final class FixtureServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final AtomicInteger refreshCalls = new AtomicInteger();

        FixtureServer() throws IOException {
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
            int contentLength = 0;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            for (int remaining = contentLength; remaining > 0;) {
                int count = input.read(new byte[Math.min(remaining, 4096)]);
                if (count < 0) throw new IOException("Unexpected request end");
                remaining -= count;
            }

            OutputStream output = socket.getOutputStream();
            if (firstLine.startsWith("POST /api/auth/logout ")) {
                output.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } else {
                if (firstLine.startsWith("POST /api/auth/refresh ")) refreshCalls.incrementAndGet();
                byte[] body = ("{\"token_type\":\"Bearer\",\"access_token\":\"fixture-access-token\","
                    + "\"access_expires_in\":900,\"refresh_token\":\"fixture-refresh-token\","
                    + "\"refresh_expires_in\":2592000}").getBytes(StandardCharsets.UTF_8);
                String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n";
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                output.write(body);
            }
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
                if (matched == end.length) return bytes.toString(StandardCharsets.US_ASCII);
            }
            throw new IOException("Request headers too large");
        }

        @Override public void close() throws Exception {
            server.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
