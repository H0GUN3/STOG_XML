package com.stog.backend;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = BasketSavePostgresIntegrationTest.DatabaseSafetyGate.class)
class NativeAuthPostgresIntegrationTest {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String PASSWORD = "fixture native passphrase 2048!";

    @Value("${local.server.port}") int serverPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_JDBC_URL"));
        properties.add("spring.datasource.username", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_USERNAME"));
        properties.add("spring.datasource.password", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_PASSWORD"));
        properties.add("spring.datasource.hikari.schema", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.default-schema", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.schemas", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.create-schemas", () -> "false");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        properties.add("spring.datasource.hikari.minimum-idle", () -> "0");
        properties.add("stog.auth.restricted-owner.enabled", () -> "false");
        properties.add("stog.auth.kakao.enabled", () -> "false");
    }

    @AfterEach
    void cleanNativeAuthRows() {
        jdbc.update("DELETE FROM auth_refresh_token WHERE session_id IN ("
            + "SELECT id FROM auth_session WHERE user_id IN (SELECT user_id FROM auth_password_credential))");
        jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT user_id FROM auth_password_credential)");
        jdbc.update("DELETE FROM auth_password_attempt");
        jdbc.update("DELETE FROM auth_password_credential");
        jdbc.update("DELETE FROM stog_user WHERE display_name IS NOT NULL "
            + "AND NOT EXISTS (SELECT 1 FROM trip_member m WHERE m.user_id=stog_user.id)");
    }

    @Test
    void v7SchemaIsAdditiveAndConstrained() {
        assertEquals(1, count("SELECT count(*) FROM information_schema.columns "
            + "WHERE table_schema=current_schema() AND table_name='stog_user' AND column_name='display_name'"));
        assertEquals(1, count("SELECT count(*) FROM information_schema.tables "
            + "WHERE table_schema=current_schema() AND table_name='auth_password_credential'"));
        assertEquals(1, count("SELECT count(*) FROM information_schema.tables "
            + "WHERE table_schema=current_schema() AND table_name='auth_password_attempt'"));
        assertEquals(7, jdbc.queryForObject(
            "SELECT max(version::integer) FROM flyway_schema_history WHERE success", Integer.class));
    }

    @Test
    void meReturnsOnlyTheAuthenticatedUsersDisplayName() throws Exception {
        TokenPair issued = tokens(signup(uniqueUsername("profile"), PASSWORD, "여행자"));
        HttpResponse<String> response = me(issued.access());
        assertEquals(200, response.statusCode());
        assertNoStore(response);
        assertEquals("{\"display_name\":\"여행자\"}", response.body());
        assertEquals(401, me("invalid-token").statusCode());
    }

    @Test
    void signupAndLoginUseOneUserRealSessionAndEmptyTrips() throws Exception {
        String username = uniqueUsername("native");
        HttpResponse<String> signup = signup(username, PASSWORD, "여행자");
        assertEquals(201, signup.statusCode());
        assertNoStore(signup);
        TokenPair issued = tokens(signup);
        UUID userId = jdbc.queryForObject(
            "SELECT user_id FROM auth_password_credential WHERE username=?", UUID.class, username);
        String storedHash = jdbc.queryForObject(
            "SELECT password_hash FROM auth_password_credential WHERE username=?", String.class, username);
        assertTrue(storedHash.startsWith("{pbkdf2-stog-v1}"));
        assertFalse(storedHash.contains(PASSWORD));
        assertEquals("여행자", jdbc.queryForObject("SELECT display_name FROM stog_user WHERE id=?",
            String.class, userId));
        assertEquals(1, count("SELECT count(*) FROM auth_session WHERE user_id=?", userId));
        assertEquals(200, trips(issued.access()).statusCode());
        assertEquals(0, json.readTree(trips(issued.access()).body()).get("items").size());

        HttpResponse<String> login = login("  " + username.toUpperCase(java.util.Locale.ROOT) + "  ", PASSWORD);
        assertEquals(200, login.statusCode());
        assertNoStore(login);
        TokenPair second = tokens(login);
        assertNotEquals(issued.access(), second.access());
        assertEquals(1, count("SELECT count(*) FROM stog_user WHERE id=?", userId));
        assertEquals(2, count("SELECT count(*) FROM auth_session WHERE user_id=?", userId));
    }

    @Test
    void passwordPolicyAndDuplicateSignupHaveNoPartialRows() throws Exception {
        String username = uniqueUsername("policy");
        assertError(signup(username, "films+pic+galeries", "여행자"), 400, "PASSWORD_NOT_ACCEPTABLE");
        assertEquals(0, count("SELECT count(*) FROM auth_password_credential WHERE username=?", username));

        assertEquals(201, signup(username, PASSWORD, "여행자").statusCode());
        assertError(signup(username.toUpperCase(java.util.Locale.ROOT), PASSWORD, "다른 이름"),
            409, "ACCOUNT_NOT_CREATED");
        assertEquals(1, count("SELECT count(*) FROM auth_password_credential WHERE username=?", username));
        assertEquals(1, count("SELECT count(*) FROM stog_user WHERE display_name IS NOT NULL"));
    }

    @Test
    void unknownWrongAndBlockedLoginsAreExternallyIndistinguishable() throws Exception {
        String username = uniqueUsername("throttle");
        assertEquals(201, signup(username, PASSWORD, "여행자").statusCode());
        String unknown = uniqueUsername("unknown");

        HttpResponse<String> unknownFailure = login(unknown, "wrong native passphrase 2048!");
        HttpResponse<String> wrongFailure = login(username, "wrong native passphrase 2048!");
        assertSameAuthenticationFailure(unknownFailure, wrongFailure);

        for (int attempt = 2; attempt <= 5; attempt++) {
            assertError(login(username, "wrong native passphrase 2048!"), 401, "AUTHENTICATION_REQUIRED");
        }
        HttpResponse<String> blockedCorrect = login(username, PASSWORD);
        assertSameAuthenticationFailure(wrongFailure, blockedCorrect);
        assertEquals(5, jdbc.queryForObject(
            "SELECT failure_count FROM auth_password_attempt WHERE username=?", Integer.class, username));
        assertTrue(jdbc.queryForObject(
            "SELECT blocked_until>clock_timestamp() FROM auth_password_attempt WHERE username=?",
            Boolean.class, username));

        jdbc.update("UPDATE auth_password_attempt SET window_started_at=clock_timestamp()-INTERVAL '31 minutes',"
            + "blocked_until=clock_timestamp()-INTERVAL '1 minute',updated_at=clock_timestamp()-INTERVAL '1 minute' "
            + "WHERE username=?", username);
        assertEquals(200, login(username, PASSWORD).statusCode());
        assertEquals(0, count("SELECT count(*) FROM auth_password_attempt WHERE username=?", username));
    }

    @Test
    void concurrentDuplicateSignupCreatesExactlyOneAccount() throws Exception {
        String username = uniqueUsername("race");
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try {
            var first = executor.submit(() -> { start.await(); return signup(username, PASSWORD, "여행자"); });
            var second = executor.submit(() -> { start.await(); return signup(username, PASSWORD, "여행자"); });
            responses.add(first.get(20, TimeUnit.SECONDS));
            responses.add(second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(List.of(201, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(1, count("SELECT count(*) FROM auth_password_credential WHERE username=?", username));
        assertEquals(1, count("SELECT count(*) FROM stog_user WHERE display_name='여행자'"));
        assertEquals(1, count("SELECT count(*) FROM auth_session WHERE user_id=("
            + "SELECT user_id FROM auth_password_credential WHERE username=?)", username));
    }

    @Test
    void validSpacesNfcAndPasswordPolicyErrorsMatchWireContract() throws Exception {
        String spacesUser = uniqueUsername("spaces");
        assertEquals(201, signup(spacesUser, "               ", "공백 사용자").statusCode());
        assertEquals(200, login(spacesUser, "               ").statusCode());

        String nfcUser = uniqueUsername("nfc");
        assertEquals(201, signup(nfcUser, "A\u030A secure passphrase", "NFC 사용자").statusCode());
        assertEquals(200, login(nfcUser, "Å secure passphrase").statusCode());

        assertError(signup(uniqueUsername("short"), "too short", "짧음"), 400, "PASSWORD_NOT_ACCEPTABLE");
        assertError(signup(uniqueUsername("long"), "x".repeat(129), "길음"), 400, "PASSWORD_NOT_ACCEPTABLE");
        assertError(post("/api/auth/signup",
            "{\"username\":\"" + uniqueUsername("nul") + "\",\"password\":\"valid password\\u0000value\","
                + "\"display_name\":\"널\"}"), 400, "INVALID_REQUEST");
    }

    @Test
    void chunkedOversizedAuthBodyIsRejectedBeforeAnyAccountWrite() throws Exception {
        String username = uniqueUsername("chunked");
        byte[] body = ("{\"username\":\"" + username + "\",\"password\":\""
            + "x".repeat(AuthRequestSizeFilter.MAX_AUTH_BODY_BYTES)
            + "\",\"display_name\":\"Chunked\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(baseUri("/api/auth/signup")).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertError(response, 400, "INVALID_REQUEST");
        assertEquals(0, count("SELECT count(*) FROM auth_password_credential WHERE username=?", username));
    }

    @Test
    void knownAndUnknownFailuresEachUseExactlyOneProductionEncoderMatch() throws Exception {
        String username = uniqueUsername("count");
        assertEquals(201, signup(username, PASSWORD, "Count User").statusCode());

        reset(passwordEncoder);
        assertError(login(uniqueUsername("missing"), "wrong native passphrase 2048!"),
            401, "AUTHENTICATION_REQUIRED");
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());

        reset(passwordEncoder);
        assertError(login(username, "wrong native passphrase 2048!"), 401, "AUTHENTICATION_REQUIRED");
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void concurrentFirstFailuresCreateOneAttemptRowAndCountBoth() throws Exception {
        String username = uniqueUsername("firstfail");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(anyString(), anyString());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> login(username, "wrong native passphrase 2048!"));
            var second = executor.submit(() -> login(username, "wrong native passphrase 2048!"));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            release.countDown();
            assertError(first.get(20, TimeUnit.SECONDS), 401, "AUTHENTICATION_REQUIRED");
            assertError(second.get(20, TimeUnit.SECONDS), 401, "AUTHENTICATION_REQUIRED");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            reset(passwordEncoder);
        }
        assertEquals(1, count("SELECT count(*) FROM auth_password_attempt WHERE username=?", username));
        assertEquals(2, jdbc.queryForObject(
            "SELECT failure_count FROM auth_password_attempt WHERE username=?", Integer.class, username));
    }

    @Test
    void correctPasswordAlreadyInFlightCannotBeatFifthFailureBlock() throws Exception {
        String username = uniqueUsername("fifth");
        assertEquals(201, signup(username, PASSWORD, "Fifth User").statusCode());
        for (int attempt = 0; attempt < 4; attempt++) {
            assertError(login(username, "wrong native passphrase 2048!"), 401, "AUTHENTICATION_REQUIRED");
        }
        CountDownLatch correctEntered = new CountDownLatch(1);
        CountDownLatch releaseCorrect = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (PASSWORD.equals(invocation.getArgument(0))) {
                correctEntered.countDown();
                assertTrue(releaseCorrect.await(10, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(anyString(), anyString());

        var executor = Executors.newSingleThreadExecutor();
        try {
            var correct = executor.submit(() -> login(username, PASSWORD));
            assertTrue(correctEntered.await(10, TimeUnit.SECONDS));
            assertError(login(username, "wrong native passphrase 2048!"), 401, "AUTHENTICATION_REQUIRED");
            releaseCorrect.countDown();
            assertError(correct.get(20, TimeUnit.SECONDS), 401, "AUTHENTICATION_REQUIRED");
        } finally {
            releaseCorrect.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            reset(passwordEncoder);
        }
        assertTrue(jdbc.queryForObject(
            "SELECT blocked_until>clock_timestamp() FROM auth_password_attempt WHERE username=?",
            Boolean.class, username));
    }

    @Test
    void staleAttemptCleanupDeletesAtMostOneHundredRowsPerLogin() throws Exception {
        for (int index = 0; index < 101; index++) {
            jdbc.update("INSERT INTO auth_password_attempt(username,window_started_at,failure_count,updated_at) "
                    + "VALUES (?,clock_timestamp()-INTERVAL '2 days',1,clock_timestamp()-INTERVAL '2 days')",
                "stale" + String.format(java.util.Locale.ROOT, "%05d", index));
        }
        String username = uniqueUsername("cleanup");
        assertError(login(username, "wrong native passphrase 2048!"), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(1, count("SELECT count(*) FROM auth_password_attempt WHERE username LIKE 'stale%'"));
        assertEquals(1, count("SELECT count(*) FROM auth_password_attempt WHERE username=?", username));
    }

    @Test
    void lateSessionFailureRollsBackNativeUserAndCredential() throws Exception {
        String username = uniqueUsername("rollback");
        jdbc.execute("CREATE FUNCTION test_fail_native_auth_session() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
            + "RAISE EXCEPTION 'test native auth failure' USING ERRCODE='23514'; END $$");
        jdbc.execute("CREATE TRIGGER test_fail_native_auth_session BEFORE INSERT ON auth_session "
            + "FOR EACH ROW EXECUTE FUNCTION test_fail_native_auth_session()");
        try {
            assertError(signup(username, PASSWORD, "여행자"), 503, "DATABASE_OPERATION_FAILED");
            assertEquals(0, count("SELECT count(*) FROM auth_password_credential WHERE username=?", username));
            assertEquals(0, count("SELECT count(*) FROM stog_user WHERE display_name='여행자'"));
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_native_auth_session ON auth_session");
            jdbc.execute("DROP FUNCTION test_fail_native_auth_session()");
        }
    }

    private HttpResponse<String> signup(String username, String password, String displayName) throws Exception {
        return post("/api/auth/signup", "{\"username\":" + string(username) + ",\"password\":"
            + string(password) + ",\"display_name\":" + string(displayName) + "}");
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        return post("/api/auth/login", "{\"username\":" + string(username) + ",\"password\":"
            + string(password) + "}");
    }

    private HttpResponse<String> trips(String access) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(baseUri("/api/trips?limit=20"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + access).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> me(String access) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(baseUri("/api/me"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + access).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(baseUri(path)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertSameAuthenticationFailure(HttpResponse<String> first, HttpResponse<String> second) {
        assertEquals(401, first.statusCode());
        assertEquals(first.statusCode(), second.statusCode());
        assertEquals(first.body(), second.body());
        assertEquals("AUTHENTICATION_REQUIRED", json.readTree(first.body()).at("/error/code").asText());
        assertNoStore(first);
        assertNoStore(second);
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).at("/error/code").asText());
        assertNoStore(response);
    }

    private TokenPair tokens(HttpResponse<String> response) {
        JsonNode body = json.readTree(response.body());
        assertEquals("Bearer", body.get("token_type").asText());
        assertEquals(900, body.get("access_expires_in").asLong());
        assertEquals(2_592_000, body.get("refresh_expires_in").asLong());
        return new TokenPair(body.get("access_token").asText(), body.get("refresh_token").asText());
    }

    private static void assertNoStore(HttpResponse<?> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElse(""));
    }

    private URI baseUri(String path) { return URI.create("http://127.0.0.1:" + serverPort + path); }
    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }
    private String string(String value) { return json.writeValueAsString(value); }
    private static String uniqueUsername(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
    private record TokenPair(String access, String refresh) {}
}
