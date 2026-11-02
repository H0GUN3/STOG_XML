package com.stog.backend;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KakaoTokenVerifierTest {
    @Test
    void untrustedConfiguredDestinationIsRejectedBeforeBearerTransmission() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/steal", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        receiver.start();
        try {
            URI destination = URI.create("http://127.0.0.1:" + receiver.getAddress().getPort() + "/steal?next=kakao");
            KakaoTokenVerifier verifier = new KakaoTokenVerifier(new ObjectMapper(), true, "12345", false,
                destination, destination);

            AuthException failure = assertThrows(AuthException.class, () -> verifier.verify("must-not-be-sent"));

            assertEquals(503, failure.status());
            assertEquals("AUTH_PROVIDER_UNAVAILABLE", failure.code());
            assertEquals(0, requests.get());
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void normalModeAcceptsOnlyExactOfficialEndpoints() {
        assertTrue(KakaoTokenVerifier.areTrustedDestinations(false,
            KakaoTokenVerifier.OFFICIAL_TOKEN_INFO_URI, KakaoTokenVerifier.OFFICIAL_USER_INFO_URI));
        for (URI altered : new URI[] {
                URI.create("http://kapi.kakao.com/v1/user/access_token_info"),
                URI.create("https://user@kapi.kakao.com/v1/user/access_token_info"),
                URI.create("https://kapi.kakao.com:443/v1/user/access_token_info"),
                URI.create("https://kapi.kakao.com/v1/user/access_token_info?next=x"),
                URI.create("https://kapi.kakao.com/v1/user/access_token_info#fragment"),
                URI.create("https://kapi.kakao.com/v1/user/access_token_info/extra") }) {
            assertFalse(KakaoTokenVerifier.areTrustedDestinations(false, altered,
                KakaoTokenVerifier.OFFICIAL_USER_INFO_URI));
        }
    }

    @Test
    void fixtureModeRejectsHostnameBeforeBearerTransmission() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/token-info", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        receiver.start();
        try {
            int port = receiver.getAddress().getPort();
            KakaoTokenVerifier verifier = new KakaoTokenVerifier(new ObjectMapper(), true, "12345", true,
                URI.create("http://localhost:" + port + "/token-info"),
                URI.create("http://localhost:" + port + "/user-info"));

            AuthException failure = assertThrows(AuthException.class, () -> verifier.verify("must-not-be-sent"));

            assertEquals(503, failure.status());
            assertEquals(0, requests.get());
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void fixtureModeDoesNotFollowProviderRedirect() throws IOException {
        AtomicInteger redirectedRequests = new AtomicInteger();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/token-info", exchange -> {
            exchange.getResponseHeaders().add("Location", "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        receiver.createContext("/redirected", exchange -> {
            redirectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        receiver.start();
        try {
            int port = receiver.getAddress().getPort();
            KakaoTokenVerifier verifier = new KakaoTokenVerifier(new ObjectMapper(), true, "12345", true,
                URI.create("http://127.0.0.1:" + port + "/token-info"),
                URI.create("http://127.0.0.1:" + port + "/user-info"));

            AuthException failure = assertThrows(AuthException.class, () -> verifier.verify("fixture-value"));

            assertEquals(503, failure.status());
            assertEquals(0, redirectedRequests.get());
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void missingRequiredAppConfigurationFailsClosedBeforeNetwork() {
        KakaoTokenVerifier verifier = new KakaoTokenVerifier(new ObjectMapper(), false, "", false,
            URI.create("http://127.0.0.1:1/token-info"), URI.create("http://127.0.0.1:1/user-info"));

        AuthException failure = assertThrows(AuthException.class, () -> verifier.verify("not-sent"));

        assertEquals(503, failure.status());
        assertEquals("AUTH_PROVIDER_UNAVAILABLE", failure.code());
    }
}
