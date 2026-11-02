package com.stog.backend;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RealJdbcProbe.class)
@ContextConfiguration(initializers = BasketSavePostgresIntegrationTest.DatabaseSafetyGate.class)
class TripProductPostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired RealJdbcProbe.Probe probe;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_JDBC_URL"));
        properties.add("spring.datasource.username", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_USERNAME"));
        properties.add("spring.datasource.password", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_PASSWORD"));
        properties.add("spring.datasource.hikari.schema", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.default-schema", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.schemas", () -> BasketSavePostgresIntegrationTest.required("STOG_TEST_DB_SCHEMA"));
        properties.add("spring.flyway.create-schemas", () -> "false");
    }

    @BeforeEach void resetProbe() { probe.reset(); }

    @Test
    void createTripMembershipAndLedgerCommitAtomicallyAndReplay24Hours() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID request = UUID.randomUUID();
            MvcResult first = create(f.user, "  Cafe\u0301 여행  ", "  제주  ", "2026-10-10", "2026-10-12", request);
            MvcResult replay = create(f.user, "Café 여행", "제주", "2026-10-10", "2026-10-12", request);
            assertEquals(201, first.getResponse().getStatus());
            assertEquals(200, replay.getResponse().getStatus());
            assertEquals(body(first), body(replay));
            UUID trip = UUID.fromString(body(first).get("trip_id").asText());
            assertEquals("Café 여행", jdbc.queryForObject("SELECT name FROM trip WHERE id=?", String.class, trip));
            assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM trip_member WHERE trip_id=? AND user_id=?",
                String.class, trip, f.user));
            assertEquals(1, count("SELECT count(*) FROM trip_create_execution WHERE principal_user_id=? AND request_id=?",
                f.user, request));
            assertEquals(86400.0, jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM(expires_at-completed_at)) "
                + "FROM trip_create_execution WHERE principal_user_id=? AND request_id=?", Double.class, f.user, request));
        }
    }

    @Test
    void concurrentCreateKeyHasOneTripAndLoserRollsBack() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID request = UUID.randomUUID();
            RealJdbcProbe.Gate bothStaged = probe.pauseTripCreateInserts(2);
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var futures = List.of(
                    executor.submit(() -> { ready.countDown(); start.await(); return create(f.user, "동시 여행", "서울", "2026-10-10", "2026-10-11", request); }),
                    executor.submit(() -> { ready.countDown(); start.await(); return create(f.user, "동시 여행", "서울", "2026-10-10", "2026-10-11", request); }));
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                bothStaged.awaitArrival();
                bothStaged.release();
                List<MvcResult> results = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
                assertEquals(List.of(200, 201), results.stream().map(r -> r.getResponse().getStatus()).sorted().toList());
                assertEquals(body(results.get(0)), body(results.get(1)));
                assertEquals(1, count("SELECT count(*) FROM trip_create_execution WHERE principal_user_id=? AND request_id=?", f.user, request));
                assertEquals(1, count("SELECT count(*) FROM trip t JOIN trip_member tm ON tm.trip_id=t.id WHERE tm.user_id=? AND t.name='동시 여행'", f.user));
            } finally {
                bothStaged.release();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void changedInputConflictsWithoutDisclosingWinner() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID request = UUID.randomUUID();
            create(f.user, "원본", "서울", "2026-10-10", "2026-10-11", request);
            MvcResult conflict = create(f.user, "변경", "서울", "2026-10-10", "2026-10-11", request);
            assertError(conflict, 409, "REQUEST_ID_REUSED");
            assertEquals(2, body(conflict).get("error").size());
            assertFalse(conflict.getResponse().getContentAsString().contains("trip_id"));
            MvcResult otherPrincipal = create(f.otherUser, "별도", "부산", "2026-10-10", "2026-10-11", request);
            assertEquals(201, otherPrincipal.getResponse().getStatus());
        }
    }

    @Test
    void myTripsAreMembershipScopedGroupedAndKeysetPagedWithFrozenAsOf() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            f.trip(f.user, "ongoing-late", today.minusDays(1), today.plusDays(2), 0, "MEMBER", false);
            f.trip(f.user, "ongoing-first", today.minusDays(2), today.plusDays(1), 0, "ADMIN", false);
            f.trip(f.user, "upcoming", today.plusDays(2), today.plusDays(3), 0, "MEMBER", false);
            f.trip(f.user, "past", today.minusDays(3), today.minusDays(2), 0, "MEMBER", false);
            f.trip(f.otherUser, "hidden", today, today.plusDays(1), 0, "ADMIN", false);

            List<String> names = new ArrayList<>();
            String cursor = null;
            LocalDate frozen = null;
            do {
                MvcResult page = list(f.user, 1, cursor);
                assertEquals(200, page.getResponse().getStatus());
                JsonNode response = body(page);
                if (frozen == null) frozen = LocalDate.parse(response.get("as_of_date").asText());
                assertEquals(frozen.toString(), response.get("as_of_date").asText());
                names.add(response.get("items").get(0).get("name").asText());
                cursor = response.get("next_cursor").isNull() ? null : response.get("next_cursor").asText();
            } while (cursor != null);
            assertEquals(List.of("ongoing-first", "ongoing-late", "upcoming", "past"), names);
            assertFalse(names.contains("hidden"));
        }
    }

    @Test
    void cursorTamperExpiryAndPrincipalEndpointMismatchAreInvalidWithoutDisclosure() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            UUID trip = f.trip(f.user, "a", today, today.plusDays(1), 0, "ADMIN", false);
            f.trip(f.user, "b", today.plusDays(2), today.plusDays(3), 0, "MEMBER", false);
            String cursor = body(list(f.user, 1, null)).get("next_cursor").asText();
            String tampered = cursor.substring(0, cursor.length() - 1) + (cursor.endsWith("A") ? "B" : "A");
            assertError(list(f.user, 1, tampered), 400, "INVALID_CURSOR");
            assertError(list(f.otherUser, 1, cursor), 400, "INVALID_CURSOR");
            assertError(list(f.user, 2, cursor), 400, "INVALID_CURSOR");
            MvcResult endpointMismatch = mvc.perform(get("/api/trips/{id}/trip-items?state=BASKET&limit=1&cursor={cursor}", trip, cursor)
                .with(auth(f.user))).andReturn();
            assertError(endpointMismatch, 400, "INVALID_CURSOR");
            assertFalse(endpointMismatch.getResponse().getContentAsString().contains(f.user.toString()));
        }
    }

    @Test
    void detailAndBasketHideNonmemberAndReadsDoNotMutate() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            UUID trip = f.trip(f.user, "read", today, today.plusDays(1), 4, "ADMIN", false);
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM trip WHERE id=?", trip);
            int ledgerBefore = count("SELECT count(*) FROM trip_create_execution");
            assertEquals(200, detail(f.user, trip).getResponse().getStatus());
            assertEquals(200, basket(f.user, trip, 20, null).getResponse().getStatus());
            MvcResult missing = detail(f.otherUser, UUID.randomUUID());
            MvcResult nonmember = detail(f.otherUser, trip);
            assertError(missing, 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            assertError(nonmember, 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            assertEquals(body(missing), body(nonmember));
            assertError(basket(f.otherUser, trip, 20, null), 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            assertEquals(before, jdbc.queryForMap("SELECT * FROM trip WHERE id=?", trip));
            assertEquals(ledgerBefore, count("SELECT count(*) FROM trip_create_execution"));
        }
    }

    @Test
    void basketPagesKeepOnePlanVersionOrConflictAndOrderDeterministically() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            UUID trip = f.trip(f.user, "basket", today, today.plusDays(1), 5, "ADMIN", false);
            UUID older = f.item(trip, f.place, f.user, Instant.parse("2026-10-01T03:04:05Z"));
            UUID newer = f.item(trip, f.otherPlace, f.user, Instant.parse("2026-10-01T03:04:06Z"));
            JsonNode first = body(basket(f.user, trip, 1, null));
            assertEquals(5, first.get("plan_version").asLong());
            assertEquals(newer.toString(), first.at("/items/0/trip_item_id").asText());
            String cursor = first.get("next_cursor").asText();
            JsonNode second = body(basket(f.user, trip, 1, cursor));
            assertEquals(older.toString(), second.at("/items/0/trip_item_id").asText());
            assertEquals(5, second.get("plan_version").asLong());
            jdbc.update("UPDATE trip SET plan_version=6 WHERE id=?", trip);
            MvcResult conflict = basket(f.user, trip, 1, cursor);
            assertError(conflict, 409, "PLAN_VERSION_CONFLICT");
            assertEquals(6, body(conflict).at("/error/current_plan_version").asLong());
        }
    }

    @Test
    void legacyNullMetadataAndIdOnlyPlaceRemainHonest() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            UUID trip = f.trip(f.user, null, today, today, 0, "MEMBER", true);
            UUID item = f.item(trip, f.place, f.user, Instant.parse("2026-10-01T03:04:05Z"));
            JsonNode detail = body(detail(f.user, trip));
            assertTrue(detail.get("name").isNull());
            assertTrue(detail.get("region_label").isNull());
            JsonNode basket = body(basket(f.user, trip, 20, null));
            assertEquals(item.toString(), basket.at("/items/0/trip_item_id").asText());
            assertEquals(f.place.toString(), basket.at("/items/0/place_id").asText());
            assertTrue(basket.at("/items/0/name").isMissingNode());
            assertTrue(basket.at("/items/0/address").isMissingNode());
        }
    }

    @Test
    void lateFailureRollsBackTripMemberAndExecution() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            jdbc.execute("CREATE FUNCTION test_fail_trip_create() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.input_name='late-failure' THEN RAISE EXCEPTION 'test failure' USING ERRCODE='23514'; END IF; "
                + "RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER test_fail_trip_create BEFORE INSERT ON trip_create_execution "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_trip_create()");
            try {
                MvcResult failed = create(f.user, "late-failure", "서울", "2026-10-10", "2026-10-11", UUID.randomUUID());
                assertError(failed, 503, "DATABASE_OPERATION_FAILED");
                assertEquals(0, count("SELECT count(*) FROM trip t JOIN trip_member tm ON tm.trip_id=t.id WHERE tm.user_id=? AND t.name='late-failure'", f.user));
                assertEquals(0, count("SELECT count(*) FROM trip_create_execution WHERE principal_user_id=? AND input_name='late-failure'", f.user));
            } finally {
                jdbc.execute("DROP TRIGGER test_fail_trip_create ON trip_create_execution");
                jdbc.execute("DROP FUNCTION test_fail_trip_create()");
            }
        }
    }

    private MvcResult create(UUID user, String name, String region, String start, String end, UUID request) throws Exception {
        String content = "{\"name\":" + json.writeValueAsString(name) + ",\"region_label\":"
            + json.writeValueAsString(region) + ",\"start_date\":\"" + start + "\",\"end_date\":\"" + end
            + "\",\"request_id\":\"" + request + "\"}";
        return mvc.perform(post("/api/trips").with(auth(user)).contentType("application/json").content(content)).andReturn();
    }

    private MvcResult list(UUID user, int limit, String cursor) throws Exception {
        String url = "/api/trips?limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
        return mvc.perform(get(url).with(auth(user))).andReturn();
    }

    private MvcResult detail(UUID user, UUID trip) throws Exception {
        return mvc.perform(get("/api/trips/{id}", trip).with(auth(user))).andReturn();
    }

    private MvcResult basket(UUID user, UUID trip, int limit, String cursor) throws Exception {
        String url = "/api/trips/" + trip + "/trip-items?state=BASKET&limit=" + limit
            + (cursor == null ? "" : "&cursor=" + cursor);
        return mvc.perform(get(url).with(auth(user))).andReturn();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(UUID user) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(user), null, List.of()));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus());
        assertEquals(code, body(result).at("/error/code").asText());
        assertTrue(body(result).at("/error/message").isTextual());
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    static final class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc;
        final UUID user = UUID.randomUUID();
        final UUID otherUser = UUID.randomUUID();
        final UUID place = UUID.randomUUID();
        final UUID otherPlace = UUID.randomUUID();

        Fixture(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
            jdbc.update("INSERT INTO stog_user(id) VALUES (?),(?)", user, otherUser);
            jdbc.update("INSERT INTO place(id) VALUES (?),(?)", place, otherPlace);
        }

        UUID trip(UUID member, String name, LocalDate start, LocalDate end, long version, String role, boolean nullRegion) {
            UUID trip = UUID.randomUUID();
            jdbc.update("INSERT INTO trip(id,name,region_label,start_date,end_date,plan_version) VALUES (?,?,?,?,?,?)",
                trip, name, nullRegion ? null : "지역", start, end, version);
            jdbc.update("INSERT INTO trip_member(trip_id,user_id,role) VALUES (?,?,?)", trip, member, role);
            return trip;
        }

        UUID item(UUID trip, UUID place, UUID creator, Instant createdAt) {
            UUID item = UUID.randomUUID();
            jdbc.update("INSERT INTO trip_item(id,trip_id,place_id,created_by_user_id,created_at,schedule_date,sort_order) "
                + "VALUES (?,?,?,?,?,NULL,NULL)", item, trip, place, creator, java.sql.Timestamp.from(createdAt));
            return item;
        }

        @Override public void close() {
            List<UUID> trips = jdbc.query("SELECT trip_id FROM trip_member WHERE user_id IN (?,?)",
                (rs, row) -> rs.getObject(1, UUID.class), user, otherUser);
            jdbc.update("DELETE FROM trip_create_execution WHERE principal_user_id IN (?,?)", user, otherUser);
            jdbc.update("DELETE FROM request_execution WHERE principal_user_id IN (?,?)", user, otherUser);
            for (UUID trip : trips) jdbc.update("DELETE FROM trip_item WHERE trip_id=?", trip);
            for (UUID trip : trips) jdbc.update("DELETE FROM trip_member WHERE trip_id=?", trip);
            for (UUID trip : trips) jdbc.update("DELETE FROM trip WHERE id=?", trip);
            jdbc.update("DELETE FROM place WHERE id IN (?,?)", place, otherPlace);
            jdbc.update("DELETE FROM stog_user WHERE id IN (?,?)", user, otherUser);
        }
    }
}
