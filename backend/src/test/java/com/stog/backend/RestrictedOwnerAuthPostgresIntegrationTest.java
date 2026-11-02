package com.stog.backend;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = BasketSavePostgresIntegrationTest.DatabaseSafetyGate.class)
class RestrictedOwnerAuthPostgresIntegrationTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final ProviderFixture PROVIDER = ProviderFixture.start();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicLong SUBJECTS = new AtomicLong(4_000_000_000L);

    @Value("${local.server.port}") int serverPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwordEncoder;

    private String ownerSubject;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_JDBC_URL"));
        properties.add("spring.datasource.username", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_USERNAME"));
        properties.add("spring.datasource.password", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_PASSWORD"));
        properties.add("spring.datasource.hikari.schema", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.default-schema", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.schemas", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.create-schemas", () -> "false");
        properties.add("stog.auth.restricted-owner.enabled", () -> "true");
        properties.add("stog.auth.restricted-owner.user-id", OWNER::toString);
        properties.add("stog.auth.kakao.enabled", () -> "true");
        properties.add("stog.auth.kakao.expected-app-id", () -> "12345");
        properties.add("stog.auth.kakao.fixture-mode", () -> "true");
        properties.add("stog.auth.kakao.token-info-uri", () -> PROVIDER.uri("/token-info"));
        properties.add("stog.auth.kakao.user-info-uri", () -> PROVIDER.uri("/user-info"));
    }

    @BeforeEach
    void mapOwner() {
        ownerSubject = nextSubject();
        insertIdentity(OWNER, ownerSubject);
    }

    @AfterEach
    void cleanAuthRows() {
        jdbc.update("DELETE FROM auth_refresh_token");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM auth_login_exchange");
        jdbc.update("DELETE FROM auth_password_attempt");
        jdbc.update("DELETE FROM auth_password_credential");
        jdbc.update("DELETE FROM auth_identity");
        jdbc.update("DELETE FROM stog_user WHERE NOT EXISTS (SELECT 1 FROM trip_member m WHERE m.user_id=stog_user.id)");
        PROVIDER.clear();
    }

    @AfterAll static void stopProvider() { PROVIDER.close(); }

    @Test
    void mappedOwnerCanExchangeRefreshAndAuthenticate() throws Exception {
        TokenPair issued = tokens(assertStatus(exchange(PROVIDER.register(ownerSubject)), 200));
        assertCounts(1, 1, 1, 1, 1);
        assertEquals(404, protectedRequest(issued.access()).statusCode());

        HttpResponse<String> refresh = post("/api/auth/refresh", refreshBody(issued.refresh()));
        TokenPair rotated = tokens(assertStatus(refresh, 200));
        assertEquals(401, protectedRequest(issued.access()).statusCode());
        assertEquals(404, protectedRequest(rotated.access()).statusCode());
        assertEquals(1, count("SELECT count(*) FROM auth_session WHERE user_id=? AND revoked_at IS NULL", OWNER));
        assertEquals(1, count("SELECT count(*) FROM auth_refresh_token WHERE used_at IS NULL"));
    }

    @Test
    void mappedNonownerExchangeIsGenericUnauthorizedWithoutAuthWrites() throws Exception {
        String subject = nextSubject();
        insertIdentity(UUID.randomUUID(), subject);

        assertAuthenticationRequired(exchange(PROVIDER.register(subject)));

        assertCounts(2, 2, 0, 0, 0);
    }

    @Test
    void unregisteredExchangeDoesNotProvisionOrMutateAuthState() throws Exception {
        int[] before = authCounts();

        assertAuthenticationRequired(exchange(PROVIDER.register(nextSubject())));

        assertArrayEquals(before, authCounts());
        assertCounts(1, 1, 0, 0, 0);
    }

    @Test
    void preexistingNonownerSessionCannotAccessOrRefreshAndIsNotMutated() throws Exception {
        UUID nonowner = UUID.randomUUID();
        insertIdentity(nonowner, nextSubject());
        SessionCredentials credentials = insertSession(nonowner);
        byte[] accessHashBefore = jdbc.queryForObject("SELECT access_token_hash FROM auth_session WHERE id=?", byte[].class, credentials.sessionId());

        assertEquals(401, protectedRequest(credentials.access()).statusCode());
        assertAuthenticationRequired(post("/api/auth/refresh", refreshBody(credentials.refresh())));

        assertArrayEquals(accessHashBefore,
            jdbc.queryForObject("SELECT access_token_hash FROM auth_session WHERE id=?", byte[].class, credentials.sessionId()));
        assertNull(jdbc.queryForObject("SELECT used_at FROM auth_refresh_token WHERE session_id=?", OffsetDateTime.class, credentials.sessionId()));
        assertNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?", OffsetDateTime.class, credentials.sessionId()));
        assertCounts(2, 2, 0, 1, 1);
    }

    @Test
    void nonownerLogoutRemainsAvailableAndRevokesItsSession() throws Exception {
        UUID nonowner = UUID.randomUUID();
        insertIdentity(nonowner, nextSubject());
        SessionCredentials credentials = insertSession(nonowner);

        assertStatus(post("/api/auth/logout", refreshBody(credentials.refresh())), 204);

        assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?", OffsetDateTime.class, credentials.sessionId()));
        assertEquals(401, protectedRequest(credentials.access()).statusCode());
        assertAuthenticationRequired(post("/api/auth/refresh", refreshBody(credentials.refresh())));
    }

    @Test
    void nativeRegistrationIsDisabledAndOnlyOwnerCredentialCanLogin() throws Exception {
        String password = "fixture restricted native passphrase!";
        assertStatus(post("/api/auth/signup",
            "{\"username\":\"restrictedowner\",\"password\":\"" + password
                + "\",\"display_name\":\"Owner\"}"), 503);

        insertPasswordCredential(OWNER, "restrictedowner", password);
        assertStatus(post("/api/auth/login",
            "{\"username\":\"restrictedowner\",\"password\":\"" + password + "\"}"), 200);

        UUID nonowner = UUID.randomUUID();
        jdbc.update("INSERT INTO stog_user(id,display_name) VALUES (?,?)", nonowner, "Nonowner");
        insertPasswordCredential(nonowner, "restrictedother", password);
        assertAuthenticationRequired(post("/api/auth/login",
            "{\"username\":\"restrictedother\",\"password\":\"" + password + "\"}"));
    }

    private void insertIdentity(UUID user, String subject) {
        jdbc.update("INSERT INTO stog_user(id) VALUES (?)", user);
        jdbc.update("INSERT INTO auth_identity(provider,subject,user_id,created_at) VALUES ('KAKAO',?,?,clock_timestamp())", subject, user);
    }

    private SessionCredentials insertSession(UUID user) {
        String access = UUID.randomUUID().toString();
        String refresh = UUID.randomUUID().toString();
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_session(id,user_id,access_token_hash,access_expires_at,created_at,absolute_expires_at) "
                + "VALUES (?,?,?,clock_timestamp()+INTERVAL '15 minutes',clock_timestamp(),clock_timestamp()+INTERVAL '90 days')",
            session, user, AuthService.hash(access));
        jdbc.update("INSERT INTO auth_refresh_token(token_hash,session_id,issued_at,expires_at) "
                + "VALUES (?,?,clock_timestamp(),clock_timestamp()+INTERVAL '30 days')",
            AuthService.hash(refresh), session);
        return new SessionCredentials(session, access, refresh);
    }

    private void insertPasswordCredential(UUID user, String username, String password) {
        jdbc.update("INSERT INTO auth_password_credential(user_id,username,password_hash,created_at,password_changed_at) "
                + "VALUES (?,?,?,clock_timestamp(),clock_timestamp())",
            user, username, passwordEncoder.encode(password));
    }

    private void assertCounts(int users, int identities, int exchanges, int sessions, int refreshTokens) {
        assertArrayEquals(new int[] {users, identities, exchanges, sessions, refreshTokens}, authCounts());
    }

    private int[] authCounts() {
        return new int[] {
            count("SELECT count(*) FROM stog_user"),
            count("SELECT count(*) FROM auth_identity"),
            count("SELECT count(*) FROM auth_login_exchange"),
            count("SELECT count(*) FROM auth_session"),
            count("SELECT count(*) FROM auth_refresh_token")
        };
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

    private URI baseUri(String path) { return URI.create("http://127.0.0.1:" + serverPort + path); }
    private String refreshBody(String value) { return "{\"refresh_token\":\"" + value + "\"}"; }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private HttpResponse<String> assertStatus(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return response;
    }

    private void assertAuthenticationRequired(HttpResponse<String> response) {
        assertStatus(response, 401);
        assertEquals("AUTHENTICATION_REQUIRED", json.readTree(response.body()).at("/error/code").asText());
    }

    private static String nextSubject() { return Long.toString(SUBJECTS.incrementAndGet()); }

    private record TokenPair(String access, String refresh) {}
    private record SessionCredentials(UUID sessionId, String access, String refresh) {}

    private static final class ProviderFixture implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, String> subjects = new ConcurrentHashMap<>();

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

        String register(String subject) {
            String credential = UUID.randomUUID().toString() + UUID.randomUUID();
            subjects.put(credential, subject);
            return credential;
        }

        String uri(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        void clear() { subjects.clear(); }

        private void respond(HttpExchange exchange, boolean tokenInfo) throws IOException {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String subject = authorization != null && authorization.startsWith("Bearer ")
                ? subjects.get(authorization.substring(7)) : null;
            int status = subject == null ? 401 : 200;
            String body = status == 200
                ? (tokenInfo ? "{\"id\":" + subject + ",\"app_id\":12345,\"expires_in\":3600}" : "{\"id\":" + subject + "}")
                : "{}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override public void close() { server.stop(0); }
    }
}
