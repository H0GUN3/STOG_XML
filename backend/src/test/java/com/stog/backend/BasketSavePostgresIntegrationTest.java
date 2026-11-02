package com.stog.backend;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

import org.springframework.dao.DataIntegrityViolationException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({BasketSavePostgresIntegrationTest.EventProbe.class, RealJdbcProbe.class})
@ContextConfiguration(initializers = BasketSavePostgresIntegrationTest.DatabaseSafetyGate.class)
class BasketSavePostgresIntegrationTest {
    private static final String URL = "STOG_TEST_JDBC_URL";
    private static final String USERNAME = "STOG_TEST_DB_USERNAME";
    private static final String PASSWORD = "STOG_TEST_DB_PASSWORD";
    private static final String DATABASE = "STOG_TEST_DB_NAME";
    private static final String SCHEMA = "STOG_TEST_DB_SCHEMA";
    private static final String ROLE = "STOG_TEST_DB_ROLE";

    @Autowired MockMvc mvc;
    @Value("${local.server.port}") int serverPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired EventProbe events;
    @Autowired RealJdbcProbe.Probe jdbcProbe;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> required(URL));
        properties.add("spring.datasource.username", () -> required(USERNAME));
        properties.add("spring.datasource.password", () -> required(PASSWORD));
        properties.add("spring.datasource.hikari.schema", () -> required(SCHEMA));
        properties.add("spring.flyway.default-schema", () -> required(SCHEMA));
        properties.add("spring.flyway.schemas", () -> required(SCHEMA));
        properties.add("spring.flyway.create-schemas", () -> "false");
    }

    @BeforeEach void clearObservedEvents() {
        events.events.clear();
        events.committedVisible.clear();
        events.fail = false;
        jdbcProbe.reset();
    }

    @Test
    void realServerRejectsForgedUserHeaderWithAuthenticationEnvelope() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/api/trips/" + UUID.randomUUID() + "/trip-items"))
            .header("X-User-Id", "forged").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", json.readTree(response.body()).at("/error/code").asText());

        HttpRequest getRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/api/trips/"
                + UUID.randomUUID() + "/trip-items/" + UUID.randomUUID()))
            .header("X-User-Id", "forged").GET().build();
        HttpResponse<String> getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, getResponse.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", json.readTree(getResponse.body()).at("/error/code").asText());
    }

    @Test
    void readsCurrentBasketItemSnapshot() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID item = f.item(f.trip, f.place, f.user, null, null);

            MvcResult result = read(f.user, f.trip, item);

            assertEquals(200, result.getResponse().getStatus());
            JsonNode response = body(result);
            assertEquals(item.toString(), response.get("trip_item_id").asText());
            assertEquals(f.place.toString(), response.get("place_id").asText());
            assertEquals("BASKET", response.get("state").asText());
            assertTrue(response.get("schedule_date").isNull());
            assertTrue(response.get("sort_order").isNull());
            assertEquals(3, response.get("plan_version").asLong());
        }
    }

    @Test
    void readsScheduledUpdateWithNewVersionFromEndedTrip() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID item = f.item(f.trip, f.place, f.user, null, null);
            LocalDate scheduledDate = LocalDate.of(2030, 1, 2);
            jdbc.update("UPDATE trip_item SET schedule_date=?,sort_order=7 WHERE id=?", scheduledDate, item);
            jdbc.update("UPDATE trip SET plan_version=4,start_date=CURRENT_DATE-2,end_date=CURRENT_DATE-1 WHERE id=?", f.trip);

            JsonNode response = body(read(f.user, f.trip, item));

            assertEquals(item.toString(), response.get("trip_item_id").asText());
            assertEquals("SCHEDULED", response.get("state").asText());
            assertEquals(scheduledDate.toString(), response.get("schedule_date").asText());
            assertEquals(7, response.get("sort_order").asLong());
            assertEquals(4, response.get("plan_version").asLong());
        }
    }

    @Test
    void hidesTripsAndReturnsItemNotFoundOnlyAfterAuthorization() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID outsider = UUID.randomUUID();
            f.users.add(outsider);
            jdbc.update("INSERT INTO stog_user(id) VALUES (?)", outsider);
            UUID item = f.item(f.trip, f.place, f.user, null, null);

            MvcResult missingTrip = read(outsider, UUID.randomUUID(), item);
            MvcResult nonmember = read(outsider, f.trip, item);
            assertError(missingTrip, 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            assertError(nonmember, 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            assertEquals(body(missingTrip), body(nonmember));

            UUID otherTrip = f.trip(2);
            f.member(otherTrip, f.user);
            UUID crossTripItem = f.item(otherTrip, f.otherPlace, f.user, null, null);
            MvcResult missingItem = read(f.user, f.trip, UUID.randomUUID());
            MvcResult crossTrip = read(f.user, f.trip, crossTripItem);
            jdbc.update("DELETE FROM trip_item WHERE id=?", item);
            MvcResult deletedItem = read(f.user, f.trip, item);
            assertError(missingItem, 404, "TRIP_ITEM_NOT_FOUND");
            assertError(crossTrip, 404, "TRIP_ITEM_NOT_FOUND");
            assertError(deletedItem, 404, "TRIP_ITEM_NOT_FOUND");
            assertEquals(body(missingItem), body(crossTrip));
            assertEquals(body(missingItem), body(deletedItem));
        }
    }

    @Test
    void currentReadDoesNotChangeItemTripLedgerOrEvents() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID request = UUID.randomUUID();
            JsonNode created = body(call(f.user, f.trip, f.place, 3, request));
            UUID item = UUID.fromString(created.get("trip_item_id").asText());
            events.events.clear();
            Map<String, Object> itemBefore = jdbc.queryForMap("SELECT * FROM trip_item WHERE id=?", item);
            Map<String, Object> tripBefore = jdbc.queryForMap("SELECT * FROM trip WHERE id=?", f.trip);
            Map<String, Object> ledgerBefore = jdbc.queryForMap("SELECT * FROM request_execution WHERE principal_user_id=? "
                + "AND command_type=? AND request_id=?", f.user, BasketSaveService.COMMAND, request);

            assertEquals(200, read(f.user, f.trip, item).getResponse().getStatus());

            assertEquals(itemBefore, jdbc.queryForMap("SELECT * FROM trip_item WHERE id=?", item));
            assertEquals(tripBefore, jdbc.queryForMap("SELECT * FROM trip WHERE id=?", f.trip));
            assertEquals(ledgerBefore, jdbc.queryForMap("SELECT * FROM request_execution WHERE principal_user_id=? "
                + "AND command_type=? AND request_id=?", f.user, BasketSaveService.COMMAND, request));
            assertEquals(0, events.events.size());
        }
    }

    @Test
    void oneSelectReturnsOnlyAnOldOrNewAtomicDatabaseSnapshot() throws Exception {
        try (Fixture f = new Fixture(jdbc); Connection writer = connection()) {
            UUID item = f.item(f.trip, f.place, f.user, null, null);
            writer.setAutoCommit(false);
            try (var updateItem = writer.prepareStatement(
                    "UPDATE trip_item SET schedule_date=CURRENT_DATE+1,sort_order=5 WHERE id=?");
                 var updateTrip = writer.prepareStatement("UPDATE trip SET plan_version=4 WHERE id=?")) {
                updateItem.setObject(1, item);
                updateItem.executeUpdate();
                updateTrip.setObject(1, f.trip);
                updateTrip.executeUpdate();
            }

            RealJdbcProbe.Gate beforeSelect = jdbcProbe.pauseCurrentItemSelect();
            var executor = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> oldFuture = executor.submit(() -> read(f.user, f.trip, item));
                beforeSelect.awaitArrival();
                beforeSelect.release();
                JsonNode oldSnapshot = body(oldFuture.get(10, TimeUnit.SECONDS));
                assertEquals("BASKET", oldSnapshot.get("state").asText());
                assertEquals(3, oldSnapshot.get("plan_version").asLong());
                assertTrue(oldSnapshot.get("schedule_date").isNull());
                assertTrue(oldSnapshot.get("sort_order").isNull());

                writer.commit();
                JsonNode newSnapshot = body(read(f.user, f.trip, item));
                assertEquals("SCHEDULED", newSnapshot.get("state").asText());
                assertEquals(4, newSnapshot.get("plan_version").asLong());
                assertEquals(5, newSnapshot.get("sort_order").asLong());
            } finally {
                beforeSelect.release();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void createsThenReturnsExistingWithoutChangingMetadataOrVersion() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID scheduled = f.item(f.trip, f.place, f.otherUser, LocalDate.now(), 1L);
            UUID otherTrip = f.trip(3);
            f.member(otherTrip, f.user);
            UUID otherTripItem = f.item(otherTrip, f.place, f.otherUser, null, null);

            Map<String, Object> scheduledBefore = jdbc.queryForMap("SELECT * FROM trip_item WHERE id=?", scheduled);
            Map<String, Object> otherTripBefore = jdbc.queryForMap("SELECT * FROM trip_item WHERE id=?", otherTripItem);

            MvcResult created = call(f.user, f.trip, f.place, 3, UUID.randomUUID());
            JsonNode first = json.readTree(created.getResponse().getContentAsString());
            assertEquals(201, created.getResponse().getStatus());
            assertEquals("CREATED", first.get("result").asText());
            assertEquals(4, first.get("plan_version").asLong());
            UUID basket = UUID.fromString(first.get("trip_item_id").asText());
            assertEquals(1, events.events.size());
            assertEquals(1, count("SELECT count(*) FROM trip_item WHERE trip_id=? AND place_id=? AND schedule_date IS NULL", f.trip, f.place));

            Map<String, Object> basketBefore = jdbc.queryForMap("SELECT * FROM trip_item WHERE id=?", basket);
            MvcResult existing = call(f.otherUser, f.trip, f.place, 4, UUID.randomUUID());
            JsonNode second = json.readTree(existing.getResponse().getContentAsString());
            assertEquals(200, existing.getResponse().getStatus());
            assertEquals("ALREADY_EXISTS", second.get("result").asText());
            assertEquals(basket.toString(), second.get("trip_item_id").asText());
            assertEquals(4, second.get("plan_version").asLong());
            assertEquals(basketBefore, jdbc.queryForMap("SELECT * FROM trip_item WHERE id=?", basket));
            assertEquals(scheduledBefore, jdbc.queryForMap("SELECT * FROM trip_item WHERE id=?", scheduled));
            assertEquals(otherTripBefore, jdbc.queryForMap("SELECT * FROM trip_item WHERE id=?", otherTripItem));
            assertEquals(f.user, jdbc.queryForObject("SELECT created_by_user_id FROM trip_item WHERE id=?", UUID.class, basket));
            assertTrue(jdbc.queryForObject("SELECT created_at IS NOT NULL FROM trip_item WHERE id=?", Boolean.class, basket));
            assertEquals(1, events.events.size());
            assertTrue(f.ids.containsAll(List.of(scheduled, otherTripItem)));
        }
    }

    @Test
    void replaySurvivesNewerVersionEndedTripAndDeletedItemButNotLostMembership() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID request = UUID.randomUUID();
            JsonNode original = body(call(f.user, f.trip, f.place, 3, request));
            UUID item = UUID.fromString(original.get("trip_item_id").asText());
            jdbc.update("UPDATE trip SET plan_version=9, start_date=CURRENT_DATE-2, end_date=CURRENT_DATE-1 WHERE id=?", f.trip);
            jdbc.update("DELETE FROM trip_item WHERE id=?", item);

            MvcResult replay = call(f.user, f.trip, f.place, 3, request);
            assertEquals(201, replay.getResponse().getStatus());
            assertEquals(original, body(replay));
            assertEquals(0, count("SELECT count(*) FROM trip_item WHERE id=?", item));
            assertEquals(1, events.events.size());
            assertError(read(f.user, f.trip, item), 404, "TRIP_ITEM_NOT_FOUND");

            jdbc.update("DELETE FROM trip_member WHERE trip_id=? AND user_id=?", f.trip, f.user);
            MvcResult denied = call(f.user, f.trip, f.place, 3, request);
            assertError(denied, 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
        }
    }

    @Test
    void validatesPlaceAndStaleVersionBeforeLookingUpExistingBasket() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            f.item(f.trip, f.place, f.user, null, null);
            MvcResult stale = call(f.user, f.trip, f.place, 2, UUID.randomUUID());
            assertError(stale, 409, "PLAN_VERSION_CONFLICT");
            assertEquals(3, body(stale).at("/error/current_plan_version").asLong());

            MvcResult missing = call(f.user, f.trip, UUID.randomUUID(), 3, UUID.randomUUID());
            assertError(missing, 404, "PLACE_NOT_FOUND");
            assertEquals(0, events.events.size());
        }
    }

    @Test
    void sameRequestDifferentTypedInputConflictsAndExpiredKeyIsReprocessed() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID request = UUID.randomUUID();
            call(f.user, f.trip, f.place, 3, request);
            MvcResult reused = call(f.user, f.trip, f.otherPlace, 3, request);
            assertError(reused, 409, "REQUEST_ID_REUSED");

            jdbc.update("WITH sampled AS (SELECT clock_timestamp() AS expiry) UPDATE request_execution "
                + "SET completed_at=expiry-INTERVAL '24 hours', expires_at=expiry FROM sampled "
                + "WHERE principal_user_id=? AND command_type=? AND request_id=?",
                f.user, BasketSaveService.COMMAND, request);
            MvcResult reprocessed = call(f.user, f.trip, f.place, 4, request);
            assertEquals(200, reprocessed.getResponse().getStatus());
            assertEquals("ALREADY_EXISTS", body(reprocessed).get("result").asText());
            assertEquals(1, count("SELECT count(*) FROM request_execution WHERE principal_user_id=? AND command_type=? AND request_id=?",
                f.user, BasketSaveService.COMMAND, request));
            double lifetime = jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (expires_at-completed_at)) "
                + "FROM request_execution WHERE principal_user_id=? AND command_type=? AND request_id=?",
                Double.class, f.user, BasketSaveService.COMMAND, request);
            assertEquals(24 * 60 * 60, lifetime);
        }
    }

    @Test
    void validReplayDoesNotExtendExpiryAndDeletedTripIsNotDisclosed() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID request = UUID.randomUUID();
            call(f.user, f.trip, f.place, 3, request);
            Map<String, Object> before = jdbc.queryForMap("SELECT completed_at, expires_at FROM request_execution "
                + "WHERE principal_user_id=? AND command_type=? AND request_id=?", f.user, BasketSaveService.COMMAND, request);
            call(f.user, f.trip, f.place, 3, request);
            Map<String, Object> after = jdbc.queryForMap("SELECT completed_at, expires_at FROM request_execution "
                + "WHERE principal_user_id=? AND command_type=? AND request_id=?", f.user, BasketSaveService.COMMAND, request);
            assertEquals(before, after);

            jdbc.update("DELETE FROM trip WHERE id=?", f.trip);
            f.trips.remove(f.trip);
            MvcResult denied = call(f.user, f.trip, f.place, 3, request);
            assertError(denied, 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
        }
    }

    @Test
    void missingTripAndNonmemberAreIndistinguishableAndEndedTripCannotCreate() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID outsider = UUID.randomUUID();
            f.users.add(outsider);
            jdbc.update("INSERT INTO stog_user(id) VALUES (?)", outsider);
            MvcResult missing = call(outsider, UUID.randomUUID(), f.place, 3, UUID.randomUUID());
            MvcResult nonmember = call(outsider, f.trip, f.place, 3, UUID.randomUUID());
            assertError(missing, 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            assertError(nonmember, 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            assertEquals(body(missing), body(nonmember));

            jdbc.update("UPDATE trip SET start_date=CURRENT_DATE-2, end_date=CURRENT_DATE-1 WHERE id=?", f.trip);
            assertError(call(f.user, f.trip, f.place, 3, UUID.randomUUID()), 409, "TRIP_NOT_ELIGIBLE");
            assertEquals(0, count("SELECT count(*) FROM trip_item WHERE trip_id=?", f.trip));
        }
    }

    @Test
    void listenerFailureCannotUndoCommittedSuccess() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            events.fail = true;
            MvcResult result = call(f.user, f.trip, f.place, 3, UUID.randomUUID());
            assertEquals(201, result.getResponse().getStatus());
            assertEquals(1, count("SELECT count(*) FROM trip_item WHERE trip_id=?", f.trip));
            assertEquals(4L, jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, f.trip));
            assertEquals(1, events.events.size());
        }
    }

    @Test
    void concurrentSamePlaceHasOneCreationAndOneVersionConflict() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            List<MvcResult> results = concurrentlyWithTripLock(f,
                () -> call(f.user, f.trip, f.place, 3, UUID.randomUUID()),
                () -> call(f.otherUser, f.trip, f.place, 3, UUID.randomUUID()));
            assertEquals(List.of(201, 409), results.stream().map(r -> r.getResponse().getStatus()).sorted().toList());
            assertEquals(1, count("SELECT count(*) FROM trip_item WHERE trip_id=? AND place_id=?", f.trip, f.place));
            assertEquals(4L, jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, f.trip));
            assertEquals(1, events.events.size());
        }
    }

    @Test
    void concurrentIdenticalRequestReplaysExactWinner() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID request = UUID.randomUUID();
            List<MvcResult> results = concurrentlyWithTripLock(f,
                () -> call(f.user, f.trip, f.place, 3, request),
                () -> call(f.user, f.trip, f.place, 3, request));
            assertEquals(201, results.get(0).getResponse().getStatus());
            assertEquals(201, results.get(1).getResponse().getStatus());
            assertEquals(body(results.get(0)), body(results.get(1)));
            assertEquals(1, count("SELECT count(*) FROM trip_item WHERE trip_id=?", f.trip));
            assertEquals(1, events.events.size());
        }
    }

    @Test
    void globalRequestCollisionRollsBackLoserTripAndReadsWinnerOnce() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID secondTrip = f.trip(6);
            f.member(secondTrip, f.user);
            UUID request = UUID.randomUUID();
            RealJdbcProbe.Gate bothStaged = jdbcProbe.pauseRequestInserts(2);
            List<MvcResult> results = concurrentlyAtJdbcBarrier(bothStaged,
                () -> call(f.user, f.trip, f.place, 3, request),
                () -> call(f.user, secondTrip, f.otherPlace, 6, request));
            assertEquals(List.of(201, 409), results.stream().map(r -> r.getResponse().getStatus()).sorted().toList());
            MvcResult conflict = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertEquals("REQUEST_ID_REUSED", body(conflict).at("/error/code").asText());
            assertEquals(1, count("SELECT count(*) FROM request_execution WHERE principal_user_id=? AND request_id=?", f.user, request));
            assertEquals(1, count("SELECT count(*) FROM trip_item WHERE trip_id IN (?,?)", f.trip, secondTrip));
            long firstVersion = jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, f.trip);
            long secondVersion = jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, secondTrip);
            assertTrue((firstVersion == 4 && secondVersion == 6) || (firstVersion == 3 && secondVersion == 7));
            assertEquals(1, events.events.size());
        }
    }

    @Test
    void existingGlobalRequestConflictDoesNotMutateOrEmit() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID request = UUID.randomUUID();
            UUID otherTrip = f.trip(8);
            f.member(otherTrip, f.user);
            call(f.user, otherTrip, f.otherPlace, 8, request);
            events.events.clear();

            MvcResult loser = call(f.user, f.trip, f.place, 3, request);
            assertError(loser, 409, "REQUEST_ID_REUSED");
            assertEquals(3L, jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, f.trip));
            assertEquals(0, count("SELECT count(*) FROM trip_item WHERE trip_id=?", f.trip));
            assertEquals(0, events.events.size());
        }
    }

    @Test
    void upcomingTripCanSaveAndExistingResultCanReplayExactly() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            jdbc.update("UPDATE trip SET start_date=CURRENT_DATE+2,end_date=CURRENT_DATE+5 WHERE id=?", f.trip);
            f.item(f.trip, f.place, f.user, null, null);
            UUID request = UUID.randomUUID();
            MvcResult first = call(f.user, f.trip, f.place, 3, request);
            Map<String, Object> execution = jdbc.queryForMap("SELECT * FROM request_execution WHERE principal_user_id=? "
                + "AND command_type=? AND request_id=?", f.user, BasketSaveService.COMMAND, request);
            MvcResult replay = call(f.user, f.trip, f.place, 3, request);
            assertEquals(200, first.getResponse().getStatus());
            assertEquals(body(first), body(replay));
            assertEquals(execution, jdbc.queryForMap("SELECT * FROM request_execution WHERE principal_user_id=? "
                + "AND command_type=? AND request_id=?", f.user, BasketSaveService.COMMAND, request));
            assertEquals(0, events.events.size());
        }
    }

    @Test
    void expiredReplaySamplesClockAfterRequestRowLockReturns() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID request = UUID.randomUUID();
            call(f.user, f.trip, f.place, 3, request);
            RealJdbcProbe.Gate observed = jdbcProbe.observeExecutionSelect();
            RealJdbcProbe.Gate returned = jdbcProbe.observeExecutionSelectReturned();
            RealJdbcProbe.Gate clock = jdbcProbe.observeClockQuery();
            try (Connection blocker = connection()) {
                blocker.setAutoCommit(false);
                try (var lock = blocker.prepareStatement("SELECT 1 FROM request_execution WHERE principal_user_id=? "
                        + "AND command_type=? AND request_id=? FOR UPDATE")) {
                    lock.setObject(1, f.user); lock.setString(2, BasketSaveService.COMMAND); lock.setObject(3, request);
                    lock.executeQuery().close();
                }
                var executor = Executors.newSingleThreadExecutor();
                try {
                    Future<MvcResult> future = executor.submit(() -> call(f.user, f.trip, f.place, 3, request));
                    observed.awaitArrival();
                    try (var expire = blocker.prepareStatement("WITH sampled AS (SELECT clock_timestamp() AS t) "
                            + "UPDATE request_execution SET completed_at=t-INTERVAL '24 hours',expires_at=t FROM sampled "
                            + "WHERE principal_user_id=? AND command_type=? AND request_id=?")) {
                        expire.setObject(1, f.user); expire.setString(2, BasketSaveService.COMMAND); expire.setObject(3, request);
                        expire.executeUpdate();
                    }
                    blocker.commit();
                    returned.awaitArrival();
                    clock.awaitArrival();
                    assertError(future.get(10, TimeUnit.SECONDS), 409, "PLAN_VERSION_CONFLICT");
                } finally {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void memberRemovalHoldingTripLockWinsBeforeFinalAuthorization() throws Exception {
        try (Fixture f = new Fixture(jdbc); Connection blocker = connection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT 1 FROM trip WHERE id=? FOR UPDATE")) {
                lock.setObject(1, f.trip); lock.executeQuery().close();
            }
            RealJdbcProbe.Gate observed = jdbcProbe.observeTripSelect();
            var executor = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> future = executor.submit(() -> call(f.user, f.trip, f.place, 3, UUID.randomUUID()));
                observed.awaitArrival();
                try (var remove = blocker.prepareStatement("DELETE FROM trip_member WHERE trip_id=? AND user_id=?")) {
                    remove.setObject(1, f.trip); remove.setObject(2, f.user); remove.executeUpdate();
                }
                blocker.commit();
                assertError(future.get(10, TimeUnit.SECONDS), 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void commitCallbackRunsOnlyAfterCommitAndSeesCommittedState() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            RealJdbcProbe.Gate beforeCommit = jdbcProbe.pauseCommit();
            var executor = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> future = executor.submit(() -> call(f.user, f.trip, f.place, 3, UUID.randomUUID()));
                beforeCommit.awaitArrival();
                assertEquals(0, events.events.size());
                assertEquals(0, independentCount("SELECT count(*) FROM trip_item WHERE trip_id='" + f.trip + "'"));
                beforeCommit.release();
                assertEquals(201, future.get(10, TimeUnit.SECONDS).getResponse().getStatus());
                assertEquals(List.of(true), events.committedVisible);
            } finally {
                beforeCommit.release();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void lateDatabaseFailureRollsBackItemVersionResultAndNeverNotifies() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            jdbc.execute("CREATE FUNCTION test_fail_result_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.result_plan_version=99 THEN RAISE EXCEPTION 'test late failure' USING ERRCODE='23514'; END IF; "
                + "RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER test_fail_result_insert BEFORE INSERT ON request_execution "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_result_insert()");
            try {
                jdbc.update("UPDATE trip SET plan_version=98 WHERE id=?", f.trip);
                assertError(call(f.user, f.trip, f.place, 98, UUID.randomUUID()), 503, "DATABASE_OPERATION_FAILED");
                assertEquals(98L, jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, f.trip));
                assertEquals(0, count("SELECT count(*) FROM trip_item WHERE trip_id=?", f.trip));
                assertEquals(0, count("SELECT count(*) FROM request_execution WHERE input_trip_id=?", f.trip));
                assertEquals(0, events.events.size());
            } finally {
                jdbc.execute("DROP TRIGGER test_fail_result_insert ON request_execution");
                jdbc.execute("DROP FUNCTION test_fail_result_insert()");
            }
        }
    }

    @Test
    void lockWaitIsDatabaseBoundedAndReturnsNonSecretEnvelope() throws Exception {
        try (Fixture f = new Fixture(jdbc); Connection blocker = connection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT 1 FROM trip WHERE id=? FOR UPDATE")) {
                lock.setObject(1, f.trip); lock.executeQuery().close();
            }
            MvcResult busy = call(f.user, f.trip, f.place, 3, UUID.randomUUID());
            assertError(busy, 503, "DATABASE_BUSY");
            blocker.rollback();
            assertEquals(0, count("SELECT count(*) FROM trip_item WHERE trip_id=?", f.trip));
        }
    }

    @Test
    void placeReferenceIsRestrictedAndMaximumVersionNeverWraps() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            f.item(f.trip, f.place, f.user, null, null);
            org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM place WHERE id=?", f.place));
            jdbc.update("DELETE FROM trip_item WHERE trip_id=?", f.trip);
            jdbc.update("UPDATE trip SET plan_version=? WHERE id=?", Long.MAX_VALUE, f.trip);
            MvcResult maximum = call(f.user, f.trip, f.place, Long.MAX_VALUE, UUID.randomUUID());
            assertError(maximum, 409, "PLAN_VERSION_LIMIT_REACHED");
            assertEquals(Long.MAX_VALUE, jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, f.trip));
            assertEquals(0, count("SELECT count(*) FROM trip_item WHERE trip_id=?", f.trip));
        }
    }

    private Connection connection() throws Exception {
        Connection connection = DriverManager.getConnection(required(URL), required(USERNAME), required(PASSWORD));
        connection.setSchema(required(SCHEMA));
        return connection;
    }

    private int independentCount(String sql) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private MvcResult read(UUID user, UUID trip, UUID item) throws Exception {
        return mvc.perform(get("/api/trips/{tripId}/trip-items/{tripItemId}", trip, item)
                .with(authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(user), null, List.of()))))
            .andReturn();
    }

    private MvcResult call(UUID user, UUID trip, UUID place, long version, UUID request) throws Exception {
        String content = "{\"place_id\":\"" + place + "\",\"expected_plan_version\":" + version
            + ",\"request_id\":\"" + request + "\"}";
        return mvc.perform(post("/api/trips/{tripId}/trip-items", trip)
                .with(authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(user), null, List.of())))
                .contentType("application/json").content(content)).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus());
        assertEquals(code, body(result).at("/error/code").asText());
        assertTrue(body(result).at("/error/message").isTextual());
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    @SafeVarargs
    private final List<MvcResult> concurrentlyWithTripLock(Fixture f, ThrowingCall... calls) throws Exception {
        try (Connection blocker = connection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT 1 FROM trip WHERE id=? FOR UPDATE")) {
                lock.setObject(1, f.trip); lock.executeQuery().close();
            }
            RealJdbcProbe.Gate reached = jdbcProbe.observeTripSelect(2);
            Thread releaseLock = new Thread(() -> {
                try {
                    reached.awaitArrival();
                    blocker.commit();
                } catch (Exception exception) {
                    throw new AssertionError("Could not release fixture trip lock", exception);
                }
            });
            releaseLock.start();
            List<MvcResult> results = concurrently(calls);
            releaseLock.join(10_000);
            assertTrue(!releaseLock.isAlive());
            jdbcProbe.assertIndependentTransactions(2);
            return results;
        }
    }

    @SafeVarargs
    private final List<MvcResult> concurrently(ThrowingCall... calls) throws Exception {
        return concurrentlyAtJdbcBarrier(null, calls);
    }

    @SafeVarargs
    private final List<MvcResult> concurrentlyAtJdbcBarrier(RealJdbcProbe.Gate jdbcBarrier,
            ThrowingCall... calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(calls.length);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<MvcResult>>();
            for (ThrowingCall call : calls) {
                futures.add(executor.submit(() -> { ready.countDown(); start.await(); return call.run(); }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            if (jdbcBarrier != null) {
                jdbcBarrier.awaitArrival();
                jdbcBarrier.release();
            }
            List<MvcResult> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            if (jdbcBarrier != null) jdbcBarrier.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @FunctionalInterface interface ThrowingCall { MvcResult run() throws Exception; }

    static class EventProbe {
        final List<TripPlanChangedEvent> events = new CopyOnWriteArrayList<>();
        final List<Boolean> committedVisible = new CopyOnWriteArrayList<>();
        volatile boolean fail;
        @EventListener public void receive(TripPlanChangedEvent event) {
            events.add(event);
            try (Connection connection = DriverManager.getConnection(required(URL), required(USERNAME), required(PASSWORD))) {
                connection.setSchema(required(SCHEMA));
                try (var statement = connection.prepareStatement("SELECT plan_version=? AND EXISTS "
                        + "(SELECT 1 FROM trip_item WHERE trip_id=?) FROM trip WHERE id=?")) {
                    statement.setLong(1, event.planVersion());
                    statement.setObject(2, event.tripId());
                    statement.setObject(3, event.tripId());
                    try (ResultSet result = statement.executeQuery()) {
                        committedVisible.add(result.next() && result.getBoolean(1));
                    }
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException("Independent post-commit visibility check failed", exception);
            }
            if (fail) throw new IllegalStateException("deterministic test listener failure");
        }
    }

    static class DatabaseSafetyGate implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            String url = required(URL), username = required(USERNAME), password = required(PASSWORD);
            String expectedDatabase = required(DATABASE), expectedSchema = required(SCHEMA), expectedRole = required(ROLE);
            if (!url.startsWith("jdbc:postgresql:")) throw new IllegalStateException("PostgreSQL is required");
            String normalizedSchema = expectedSchema.toLowerCase(java.util.Locale.ROOT);
            if (!normalizedSchema.matches("stog_m1_002_[a-z0-9_]+") || normalizedSchema.equals("public")
                    || normalizedSchema.startsWith("task14")) {
                throw new IllegalStateException("Refusing non-task-private PostgreSQL schema: " + expectedSchema);
            }
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 var prepared = connection.prepareStatement("SELECT current_database(), current_user, "
                     + "(SELECT rolsuper FROM pg_roles WHERE rolname=current_user), "
                     + "(SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname=?)")) {
                prepared.setString(1, expectedSchema);
                try (ResultSet result = prepared.executeQuery()) {
                    result.next();
                    if (!expectedDatabase.equals(result.getString(1)) || !expectedRole.equals(result.getString(2))
                            || result.getBoolean(3) || !expectedRole.equals(result.getString(4))) {
                        throw new IllegalStateException("Database/schema ownership safety gate mismatch or superuser role; no fixtures were written");
                    }
                }
                Set<String> expectedTables = Set.of("flyway_schema_history", "stog_user", "trip", "trip_member",
                    "place", "trip_item", "request_execution", "trip_create_execution", "auth_identity",
                    "auth_login_exchange", "auth_session", "auth_refresh_token", "trip_invitation",
                    "trip_command_execution", "trip_invitation_attempt", "trip_cover",
                    "auth_password_credential", "auth_password_attempt");
                Set<String> tables = new java.util.HashSet<>();
                try (var tableQuery = connection.prepareStatement("SELECT tablename FROM pg_tables WHERE schemaname=?")) {
                    tableQuery.setString(1, expectedSchema);
                    try (ResultSet result = tableQuery.executeQuery()) {
                        while (result.next()) tables.add(result.getString(1));
                    }
                }
                if (!tables.isEmpty() && !tables.equals(expectedTables)) {
                    throw new IllegalStateException("Refusing non-fresh or unrelated PostgreSQL schema; no fixtures were written");
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException("Database safety gate failed before fixture writes", exception);
            }
        }
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required real PostgreSQL test environment variable: " + name);
        }
        return value;
    }

    static final class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc;
        final UUID user = UUID.randomUUID(), otherUser = UUID.randomUUID();
        final UUID trip = UUID.randomUUID(), place = UUID.randomUUID(), otherPlace = UUID.randomUUID();
        final List<UUID> trips = new ArrayList<>(), users = new ArrayList<>(), places = new ArrayList<>(), ids = new ArrayList<>();

        Fixture(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
            users.addAll(List.of(user, otherUser)); places.addAll(List.of(place, otherPlace)); trips.add(trip);
            jdbc.update("INSERT INTO stog_user(id) VALUES (?),(?)", user, otherUser);
            jdbc.update("INSERT INTO place(id) VALUES (?),(?)", place, otherPlace);
            jdbc.update("INSERT INTO trip(id,start_date,end_date,plan_version) VALUES (?,CURRENT_DATE,CURRENT_DATE+7,3)", trip);
            member(trip, user); member(trip, otherUser);
        }
        UUID trip(long version) {
            UUID id = UUID.randomUUID(); trips.add(id);
            jdbc.update("INSERT INTO trip(id,start_date,end_date,plan_version) VALUES (?,CURRENT_DATE,CURRENT_DATE+7,?)", id, version);
            return id;
        }
        void member(UUID tripId, UUID userId) {
            jdbc.update("INSERT INTO trip_member(trip_id,user_id,role) VALUES (?,?,'MEMBER')", tripId, userId);
        }
        UUID item(UUID tripId, UUID placeId, UUID creator, LocalDate date, Long order) {
            UUID id = UUID.randomUUID(); ids.add(id);
            jdbc.update("INSERT INTO trip_item(id,trip_id,place_id,created_by_user_id,created_at,schedule_date,sort_order) "
                    + "VALUES (?,?,?,?,clock_timestamp(),?,?)",
                id, tripId, placeId, creator, date, order);
            return id;
        }
        @Override public void close() {
            for (UUID userId : users) jdbc.update("DELETE FROM request_execution WHERE principal_user_id=?", userId);
            for (UUID tripId : trips) jdbc.update("DELETE FROM trip_item WHERE trip_id=?", tripId);
            for (UUID tripId : trips) jdbc.update("DELETE FROM trip_member WHERE trip_id=?", tripId);
            for (UUID tripId : trips) jdbc.update("DELETE FROM trip WHERE id=?", tripId);
            for (UUID placeId : places) jdbc.update("DELETE FROM place WHERE id=?", placeId);
            for (UUID userId : users) jdbc.update("DELETE FROM stog_user WHERE id=?", userId);
        }
    }
}
