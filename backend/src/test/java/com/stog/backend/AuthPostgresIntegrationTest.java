package com.stog.backend;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = BasketSavePostgresIntegrationTest.DatabaseSafetyGate.class)
class AuthPostgresIntegrationTest {
    private static final ProviderFixture PROVIDER = ProviderFixture.start();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}") int serverPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_JDBC_URL"));
        properties.add("spring.datasource.username", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_USERNAME"));
        properties.add("spring.datasource.password", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_PASSWORD"));
        properties.add("spring.datasource.hikari.schema", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.default-schema", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.schemas", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.create-schemas", () -> "false");
        properties.add("stog.auth.restricted-owner.enabled", () -> "false");
        properties.add("stog.auth.kakao.enabled", () -> "true");
        properties.add("stog.auth.kakao.expected-app-id", () -> "12345");
        properties.add("stog.auth.kakao.fixture-mode", () -> "true");
        properties.add("stog.auth.kakao.token-info-uri", () -> PROVIDER.uri("/token-info"));
        properties.add("stog.auth.kakao.user-info-uri", () -> PROVIDER.uri("/user-info"));
    }

    @AfterEach
    void cleanAuthRows() {
        jdbc.update("DELETE FROM auth_refresh_token");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM auth_login_exchange");
        jdbc.update("DELETE FROM auth_identity");
        jdbc.update("DELETE FROM stog_user WHERE NOT EXISTS (SELECT 1 FROM trip_member m WHERE m.user_id=stog_user.id)");
        PROVIDER.clear();
    }

    @AfterAll static void stopProvider() { PROVIDER.close(); }

    @Test
    void malformedRequestsNeverCallProviderOrWriteDatabase() throws Exception {
        int before = PROVIDER.calls.get();
        assertError(post("/api/auth/kakao/exchange", "{\"kakao_access_token\":null}"), 400, "INVALID_REQUEST");
        assertError(post("/api/auth/kakao/exchange", "{\"kakao_access_token\":\"a\",\"kakao_access_token\":\"b\"}"), 400, "INVALID_REQUEST");
        assertError(post("/api/auth/kakao/exchange", "{\"kakao_access_token\":\"line\\r\\nbreak\"}"), 400, "INVALID_REQUEST");
        assertError(post("/api/auth/kakao/exchange", "{\"kakao_access_token\":\"a\",\"extra\":1}"), 400, "INVALID_REQUEST");
        assertEquals(before, PROVIDER.calls.get());
        assertEquals(0, count("SELECT count(*) FROM auth_login_exchange"));
        assertNoStore(post("/api/auth/refresh", "{\"refresh_token\":null}"));
    }

    @Test
    void providerInvalidMismatchAndUnavailableAreSeparatedWithoutWrites() throws Exception {
        String invalid = PROVIDER.register(Proof.invalid());
        String wrongApp = PROVIDER.register(Proof.valid("7001", "99999"));
        String wrongUser = PROVIDER.register(new Proof(200, "7002", "12345", "7003", false, 3600));
        String unavailable = PROVIDER.register(Proof.unavailable());
        String malformed = PROVIDER.register(Proof.malformedResponse());

        assertError(exchange(invalid), 401, "AUTHENTICATION_REQUIRED");
        assertError(exchange(wrongApp), 401, "AUTHENTICATION_REQUIRED");
        assertError(exchange(wrongUser), 401, "AUTHENTICATION_REQUIRED");
        assertError(exchange(unavailable), 503, "AUTH_PROVIDER_UNAVAILABLE");
        assertError(exchange(malformed), 503, "AUTH_PROVIDER_UNAVAILABLE");
        assertEquals(0, count("SELECT count(*) FROM stog_user"));
        assertEquals(0, count("SELECT count(*) FROM auth_login_exchange"));
    }

    @Test
    void oversizedProviderExpiryIsUnavailableWithoutProvisioning() throws Exception {
        String credential = PROVIDER.register(Proof.oversizedExpiry());

        assertError(exchange(credential), 503, "AUTH_PROVIDER_UNAVAILABLE");

        assertEquals(0, count("SELECT count(*) FROM stog_user"));
        assertEquals(0, count("SELECT count(*) FROM auth_identity"));
        assertEquals(0, count("SELECT count(*) FROM auth_login_exchange"));
        assertEquals(0, count("SELECT count(*) FROM auth_session"));
        assertEquals(0, count("SELECT count(*) FROM auth_refresh_token"));
    }

    @Test
    void exchangeClientCredentialLengthBoundaryIsExactAndHasNoDatabaseSideEffects() throws Exception {
        String acceptedLength = PROVIDER.register(Proof.invalid(), 4096);
        int callsBefore = PROVIDER.calls.get();
        assertError(exchange(acceptedLength), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(callsBefore + 1, PROVIDER.calls.get());

        String rejectedLength = "x".repeat(4097);
        assertError(exchange(rejectedLength), 400, "INVALID_REQUEST");
        assertEquals(callsBefore + 1, PROVIDER.calls.get());
        assertEquals(0, count("SELECT count(*) FROM stog_user"));
        assertEquals(0, count("SELECT count(*) FROM auth_login_exchange"));
    }

    @Test
    void exchangeProvisionsOneIdentityUsesOpaqueHashesAndAuthenticatesProtectedRoute() throws Exception {
        TokenPair first = successfulExchange("8001");
        UUID user = jdbc.queryForObject("SELECT user_id FROM auth_identity WHERE provider='KAKAO' AND subject='8001'", UUID.class);
        assertEquals(1, count("SELECT count(*) FROM stog_user"));
        assertEquals(1, count("SELECT count(*) FROM auth_session WHERE user_id=?", user));
        assertEquals(0, count("SELECT count(*) FROM auth_session WHERE encode(access_token_hash,'base64')=?", first.access()));
        assertEquals(0, count("SELECT count(*) FROM auth_refresh_token WHERE encode(token_hash,'base64')=?", first.refresh()));
        assertEquals(404, protectedRequest(first.access()).statusCode());

        TokenPair second = successfulExchange("8001");
        assertEquals(1, count("SELECT count(*) FROM stog_user"));
        assertEquals(2, count("SELECT count(*) FROM auth_session WHERE user_id=?", user));
        assertNotEquals(first.access(), second.access());
    }

    @Test
    void consumedExchangeAllowsAtMostOneSessionUnderConcurrency() throws Exception {
        String credential = PROVIDER.register(Proof.valid("8101", "12345"));
        var executor = Executors.newFixedThreadPool(2);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try {
            var one = executor.submit(() -> exchange(credential));
            var two = executor.submit(() -> exchange(credential));
            responses.add(one.get(10, TimeUnit.SECONDS));
            responses.add(two.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(List.of(200, 401), responses.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(1, count("SELECT count(*) FROM auth_session"));
        assertEquals(1, count("SELECT count(*) FROM auth_login_exchange"));
    }

    @Test
    void lateSessionFailureRollsBackExchangeIdentityAndCandidateUser() throws Exception {
        jdbc.execute("CREATE FUNCTION test_fail_auth_session() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
            + "RAISE EXCEPTION 'test auth failure' USING ERRCODE='23514'; END $$");
        jdbc.execute("CREATE TRIGGER test_fail_auth_session BEFORE INSERT ON auth_session "
            + "FOR EACH ROW EXECUTE FUNCTION test_fail_auth_session()");
        try {
            assertError(exchange(PROVIDER.register(Proof.valid("8151", "12345"))), 503, "DATABASE_OPERATION_FAILED");
            assertEquals(0, count("SELECT count(*) FROM stog_user"));
            assertEquals(0, count("SELECT count(*) FROM auth_identity"));
            assertEquals(0, count("SELECT count(*) FROM auth_login_exchange"));
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_auth_session ON auth_session");
            jdbc.execute("DROP FUNCTION test_fail_auth_session()");
        }
    }

    @Test
    void concurrentDistinctCredentialsForOneSubjectCreateOneUserAndTwoSessions() throws Exception {
        String first = PROVIDER.register(Proof.valid("8201", "12345"));
        String second = PROVIDER.register(Proof.valid("8201", "12345"));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> exchange(first));
            var two = executor.submit(() -> exchange(second));
            assertEquals(200, one.get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(200, two.get(10, TimeUnit.SECONDS).statusCode());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("SELECT count(*) FROM stog_user"));
        assertEquals(1, count("SELECT count(*) FROM auth_identity"));
        assertEquals(2, count("SELECT count(*) FROM auth_session"));
    }

    @Test
    void refreshRotatesBothTokensAndReplayRevokesTheFamily() throws Exception {
        TokenPair issued = successfulExchange("8301");
        HttpResponse<String> refreshedResponse = post("/api/auth/refresh", refreshBody(issued.refresh()));
        assertEquals(200, refreshedResponse.statusCode());
        assertNoStore(refreshedResponse);
        TokenPair refreshed = tokens(refreshedResponse);
        assertEquals(401, protectedRequest(issued.access()).statusCode());
        assertEquals(404, protectedRequest(refreshed.access()).statusCode());

        assertError(post("/api/auth/refresh", refreshBody(issued.refresh())), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(401, protectedRequest(refreshed.access()).statusCode());
        assertError(post("/api/auth/refresh", refreshBody(refreshed.refresh())), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(1, count("SELECT count(*) FROM auth_session WHERE revoked_at IS NOT NULL"));
    }

    @Test
    void concurrentRefreshHasOneSuccessThenReplayRevokesItsReturnedFamily() throws Exception {
        TokenPair issued = successfulExchange("8351");
        var executor = Executors.newFixedThreadPool(2);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try {
            var one = executor.submit(() -> post("/api/auth/refresh", refreshBody(issued.refresh())));
            var two = executor.submit(() -> post("/api/auth/refresh", refreshBody(issued.refresh())));
            responses.add(one.get(10, TimeUnit.SECONDS));
            responses.add(two.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(List.of(200, 401), responses.stream().map(HttpResponse::statusCode).sorted().toList());
        HttpResponse<String> success = responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow();
        assertEquals(401, protectedRequest(tokens(success).access()).statusCode());
        assertEquals(1, count("SELECT count(*) FROM auth_session WHERE revoked_at IS NOT NULL"));
    }

    @Test
    void logoutIsIdempotentAndRevokesOnlyItsSession() throws Exception {
        TokenPair first = successfulExchange("8401");
        TokenPair second = successfulExchange("8401");
        assertEquals(204, post("/api/auth/logout", refreshBody(first.refresh())).statusCode());
        assertEquals(204, post("/api/auth/logout", refreshBody(first.refresh())).statusCode());
        assertEquals(204, post("/api/auth/logout", refreshBody(UUID.randomUUID().toString())).statusCode());
        assertEquals(401, protectedRequest(first.access()).statusCode());
        assertEquals(404, protectedRequest(second.access()).statusCode());
        assertError(post("/api/auth/refresh", refreshBody(first.refresh())), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void logoutAcceptsUsedFamilyTokenAndRevokesRotatedCredentials() throws Exception {
        TokenPair issued = successfulExchange("8451");
        TokenPair rotated = tokens(post("/api/auth/refresh", refreshBody(issued.refresh())));
        assertEquals(204, post("/api/auth/logout", refreshBody(issued.refresh())).statusCode());
        assertEquals(401, protectedRequest(rotated.access()).statusCode());
        assertError(post("/api/auth/refresh", refreshBody(rotated.refresh())), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void twoNormalSyntheticProviderUsersReachR2aThroughBearerSecurityFilter() throws Exception {
        TokenPair admin = successfulExchange("8601");
        TokenPair member = successfulExchange("8602");
        UUID trip = null;
        try {
            UUID createRequest = UUID.randomUUID();
            HttpResponse<String> created = authorized(admin.access(), "POST", "/api/trips",
                "{\"name\":\"보안 경계 여행\",\"region_label\":\"서울\",\"start_date\":\"2026-10-10\","
                    + "\"end_date\":\"2026-10-12\",\"request_id\":\"" + createRequest + "\"}");
            assertEquals(201, created.statusCode());
            trip = UUID.fromString(json.readTree(created.body()).get("trip_id").asText());

            byte[] tokenBytes = new byte[32];
            new SecureRandom().nextBytes(tokenBytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            HttpResponse<String> issued = authorized(admin.access(), "POST", "/api/trips/" + trip + "/invitation",
                "{\"invitation_token\":\"" + token + "\",\"request_id\":\"" + UUID.randomUUID() + "\"}");
            assertEquals(201, issued.statusCode());
            HttpResponse<String> joined = authorized(member.access(), "POST", "/api/trip-invitations/join",
                "{\"invitation_token\":\"" + token + "\",\"member_label\":\"민지\",\"request_id\":\""
                    + UUID.randomUUID() + "\"}");
            assertEquals(201, joined.statusCode());
            assertEquals("JOINED", json.readTree(joined.body()).get("result").asText());

            HttpResponse<String> edited = authorized(member.access(), "PUT", "/api/trips/" + trip,
                "{\"name\":\"구성원 편집\",\"region_label\":\"서울\",\"start_date\":\"2026-10-10\","
                    + "\"end_date\":\"2026-10-12\",\"expected_plan_version\":1,\"request_id\":\""
                    + UUID.randomUUID() + "\"}");
            assertEquals(200, edited.statusCode());
            assertEquals("MEMBER", json.readTree(edited.body()).get("role").asText());
            assertEquals(2, json.readTree(edited.body()).get("plan_version").asLong());
        } finally {
            if (trip != null) {
                jdbc.update("DELETE FROM trip_invitation_attempt WHERE principal_user_id IN (SELECT user_id FROM trip_member WHERE trip_id=?)", trip);
                jdbc.update("DELETE FROM trip_command_execution WHERE trip_id=?", trip);
                jdbc.update("DELETE FROM trip_invitation WHERE trip_id=?", trip);
                jdbc.update("DELETE FROM trip_create_execution WHERE result_trip_id=?", trip);
                jdbc.update("DELETE FROM trip_member WHERE trip_id=?", trip);
                jdbc.update("DELETE FROM trip WHERE id=?", trip);
            }
        }
    }

    @Test
    void removedMemberCoverDenialCompletesJsonHeadersAndBodyForImageAccept() throws Exception {
        TokenPair removedMember = successfulExchange("8611");
        UUID removedUser = jdbc.queryForObject(
            "SELECT user_id FROM auth_identity WHERE provider='KAKAO' AND subject='8611'", UUID.class);
        UUID owner = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID cover = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO stog_user(id) VALUES (?)", owner);
            jdbc.update("INSERT INTO trip(id,name,region_label,start_date,end_date,plan_version) "
                + "VALUES (?,?,?,CURRENT_DATE+1,CURRENT_DATE+2,0)", trip, "denied cover", "서울");
            jdbc.update("INSERT INTO trip_member(trip_id,user_id,role) VALUES (?,?,'ADMIN'),(?,?,'MEMBER')",
                trip, owner, trip, removedUser);
            jdbc.update("INSERT INTO trip_cover(id,trip_id,created_by_user_id,media_type,storage_key,byte_size,width,height,"
                + "thumbnail_byte_size,thumbnail_width,thumbnail_height,input_sha256,created_at) "
                + "VALUES (?,?,?,'image/jpeg',?,1,1,1,1,1,1,?,clock_timestamp())",
                cover, trip, owner, UUID.randomUUID(), new byte[32]);
            jdbc.update("UPDATE trip SET cover_id=? WHERE id=?", cover, trip);
            jdbc.update("DELETE FROM trip_member WHERE trip_id=? AND user_id=?", trip, removedUser);

            for (String accept : List.of("image/jpeg", "*/*", "application/json")) {
                HttpRequest request = HttpRequest.newBuilder(baseUri("/api/trips/" + trip + "/covers/" + cover + "/file"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + removedMember.access())
                    .header("Accept", accept).GET().build();
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                byte[] body;
                try (InputStream input = response.body()) { body = input.readAllBytes(); }
                assertEquals(404, response.statusCode(), accept + ": " + new String(body, StandardCharsets.UTF_8));
                assertEquals("application/json", response.headers().firstValue("Content-Type").orElse("").split(";")[0]);
                assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
                assertEquals("TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE", json.readTree(body).at("/error/code").asText(), accept);
            }
        } finally {
            jdbc.update("UPDATE trip SET cover_id=NULL WHERE id=?", trip);
            jdbc.update("DELETE FROM trip_cover WHERE id=?", cover);
            jdbc.update("DELETE FROM trip_member WHERE trip_id=?", trip);
            jdbc.update("DELETE FROM trip WHERE id=?", trip);
            jdbc.update("DELETE FROM stog_user WHERE id=?", owner);
        }
    }

    @Test
    void expiredCredentialsAreRejected() throws Exception {
        TokenPair issued = successfulExchange("8501");
        jdbc.update("UPDATE auth_session SET created_at=created_at-INTERVAL '1 day', "
            + "access_expires_at=clock_timestamp()-INTERVAL '1 second'");
        jdbc.update("UPDATE auth_refresh_token SET issued_at=issued_at-INTERVAL '1 day', "
            + "expires_at=clock_timestamp()-INTERVAL '1 second'");
        assertEquals(401, protectedRequest(issued.access()).statusCode());
        assertError(post("/api/auth/refresh", refreshBody(issued.refresh())), 401, "AUTHENTICATION_REQUIRED");
    }

    private TokenPair successfulExchange(String subject) throws Exception {
        HttpResponse<String> response = exchange(PROVIDER.register(Proof.valid(subject, "12345")));
        assertEquals(200, response.statusCode());
        assertNoStore(response);
        JsonNode body = json.readTree(response.body());
        assertEquals("Bearer", body.get("token_type").asText());
        assertEquals(900, body.get("access_expires_in").asLong());
        assertEquals(2_592_000, body.get("refresh_expires_in").asLong());
        return tokens(response);
    }

    private TokenPair tokens(HttpResponse<String> response) {
        JsonNode body = json.readTree(response.body());
        return new TokenPair(body.get("access_token").asText(), body.get("refresh_token").asText());
    }

    private HttpResponse<String> exchange(String credential) throws Exception {
        return post("/api/auth/kakao/exchange", "{\"kakao_access_token\":\"" + credential + "\"}");
    }

    private HttpResponse<String> protectedRequest(String access) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(baseUri("/api/trips/" + UUID.randomUUID() + "/trip-items/" + UUID.randomUUID()))
            .header("Authorization", "Bearer " + access).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(baseUri(path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> authorized(String access, String method, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(baseUri(path)).header("Authorization", "Bearer " + access)
            .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private URI baseUri(String path) { return URI.create("http://127.0.0.1:" + serverPort + path); }
    private String refreshBody(String value) { return "{\"refresh_token\":\"" + value + "\"}"; }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertEquals(code, json.readTree(response.body()).at("/error/code").asText());
        assertNoStore(response);
    }

    private static void assertNoStore(HttpResponse<?> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElse(""));
    }

    private record TokenPair(String access, String refresh) {}
    private record Proof(int tokenStatus, String subject, String appId, String userSubject, boolean malformed, long expiresIn) {
        static Proof valid(String subject, String appId) { return new Proof(200, subject, appId, subject, false, 3600); }
        static Proof invalid() { return new Proof(401, null, null, null, false, 3600); }
        static Proof unavailable() { return new Proof(503, null, null, null, false, 3600); }
        static Proof malformedResponse() { return new Proof(200, null, null, null, true, 3600); }
        static Proof oversizedExpiry() { return new Proof(200, "7050", "12345", "7050", false, Long.MAX_VALUE); }
    }

    private static final class ProviderFixture implements AutoCloseable {
        final HttpServer server;
        final Map<String, Proof> proofs = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();

        private ProviderFixture(HttpServer server) {
            this.server = server;
            server.createContext("/token-info", exchange -> respond(exchange, true));
            server.createContext("/user-info", exchange -> respond(exchange, false));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        static ProviderFixture start() {
            try { return new ProviderFixture(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)); }
            catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
        }

        String register(Proof proof) {
            return register(proof, 73);
        }

        String register(Proof proof, int length) {
            String prefix = UUID.randomUUID().toString().replace("-", "");
            String credential = (prefix + "x".repeat(length)).substring(0, length);
            proofs.put(credential, proof);
            return credential;
        }

        String uri(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        void clear() { proofs.clear(); }

        private void respond(HttpExchange exchange, boolean tokenInfo) throws IOException {
            calls.incrementAndGet();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            Proof proof = authorization != null && authorization.startsWith("Bearer ")
                ? proofs.get(authorization.substring(7)) : null;
            int status = proof == null ? 401 : (tokenInfo ? proof.tokenStatus() : 200);
            String body;
            if (proof != null && proof.malformed()) body = "{\"id\":1,\"id\":2}";
            else if (status != 200) body = "{}";
            else if (tokenInfo) body = "{\"id\":" + proof.subject() + ",\"app_id\":" + proof.appId() + ",\"expires_in\":" + proof.expiresIn() + "}";
            else body = "{\"id\":" + proof.userSubject() + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override public void close() { server.stop(0); }
    }
}
