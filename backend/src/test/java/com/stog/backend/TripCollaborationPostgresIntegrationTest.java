package com.stog.backend;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = BasketSavePostgresIntegrationTest.DatabaseSafetyGate.class)
@Import(TripCollaborationPostgresIntegrationTest.EventConfig.class)
class TripCollaborationPostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired EventProbe eventProbe;

    @BeforeEach void resetEvents() { eventProbe.events.clear(); eventProbe.fail = false; }

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

    @Test
    void memberMetadataEditNoopReplayAndDateConflictAreAtomic() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID trip = f.trip(4);
            f.member(trip, f.member, "MEMBER", "민지");
            UUID place = UUID.randomUUID();
            jdbc.update("INSERT INTO place(id) VALUES (?)", place);
            UUID item = UUID.randomUUID();
            jdbc.update("INSERT INTO trip_item(id,trip_id,place_id,created_by_user_id,schedule_date,sort_order) "
                + "VALUES (?,?,?,?,?,1)", item, trip, place, f.admin, LocalDate.of(2026, 10, 13));
            f.ownedPlaces.add(place);

            UUID conflictRequest = UUID.randomUUID();
            MvcResult conflict = update(f.member, trip, "여행", "서울", "2026-10-10", "2026-10-12", 4, conflictRequest);
            assertError(conflict, 409, "TRIP_DATE_RANGE_CONFLICT");
            assertEquals(1, body(conflict).at("/error/scheduled_outside_count").asLong());
            assertEquals(4L, version(trip));
            assertEquals(LocalDate.of(2026, 10, 15), jdbc.queryForObject("SELECT end_date FROM trip WHERE id=?", LocalDate.class, trip));

            UUID request = UUID.randomUUID();
            MvcResult changed = update(f.member, trip, "  새 여행  ", " 서울 ", "2026-10-10", "2026-10-15", 4, request);
            assertEquals(200, changed.getResponse().getStatus());
            assertEquals(5, body(changed).get("plan_version").asLong());
            assertEquals("MEMBER", body(changed).get("role").asText());
            assertEquals(body(changed), body(update(f.member, trip, "새 여행", "서울", "2026-10-10", "2026-10-15", 4, request)));
            UUID noop = UUID.randomUUID();
            assertEquals(5, body(update(f.admin, trip, "새 여행", "서울", "2026-10-10", "2026-10-15", 5, noop))
                .get("plan_version").asLong());
            assertEquals(5L, version(trip));
            assertEquals(2, count("SELECT count(*) FROM trip_command_execution WHERE trip_id=? AND command_type='UPDATE_TRIP_V1'", trip));
            assertEquals(1, eventProbe.events.size());
            assertEquals("TRIP_METADATA", eventProbe.events.get(0).changedScope());
        }
    }

    @Test
    void invitationStoresOnlyHashRecipeAndJoinReplayIsIdempotent() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID trip = f.trip(0);
            String token = token();
            UUID issueRequest = UUID.randomUUID();
            MvcResult issued = issue(f.admin, trip, token, issueRequest);
            assertEquals(201, issued.getResponse().getStatus());
            assertEquals("stog://trip-invitations/" + token, body(issued).get("invite_uri").asText());
            assertEquals(600.0, jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM(expires_at-issued_at)) FROM trip_invitation WHERE trip_id=?", Double.class, trip));
            String recipe = jdbc.queryForObject("SELECT result_json::text FROM trip_command_execution WHERE trip_id=? AND command_type='ISSUE_TRIP_INVITE_V1'", String.class, trip);
            assertFalse(recipe.contains(token));
            assertFalse(recipe.contains("stog://"));
            assertEquals(32, jdbc.queryForObject("SELECT octet_length(token_hash) FROM trip_invitation WHERE trip_id=?", Integer.class, trip));
            MvcResult replay = issue(f.admin, trip, token, issueRequest);
            assertEquals(200, replay.getResponse().getStatus());
            assertEquals(body(issued), body(replay));

            UUID joinRequest = UUID.randomUUID();
            MvcResult joined = join(f.member, token, "  민지  ", joinRequest);
            assertEquals(201, joined.getResponse().getStatus());
            assertEquals("JOINED", body(joined).get("result").asText());
            assertEquals(1, count("SELECT count(*) FROM trip_member WHERE trip_id=? AND user_id=?", trip, f.member));
            assertEquals(body(joined), body(join(f.member, token, "민지", joinRequest)));
            assertEquals(1L, version(trip));
            MvcResult already = join(f.admin, token, "다른 이름", UUID.randomUUID());
            assertEquals(200, already.getResponse().getStatus());
            assertEquals("ALREADY_MEMBER", body(already).get("result").asText());
            assertTrue(body(already).get("member_label").isNull());
        }
    }

    @Test
    void reissueAndStopInvalidateLinksButPreserveMembership() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID trip = f.trip(0);
            String oldToken = token();
            UUID issueRequest = UUID.randomUUID();
            issue(f.admin, trip, oldToken, issueRequest);
            assertEquals(201, join(f.member, oldToken, "민지", UUID.randomUUID()).getResponse().getStatus());
            String replacement = token();
            UUID replacementRequest = UUID.randomUUID();
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var reissue = executor.submit(() -> { ready.countDown(); start.await(); return issue(f.admin, trip, replacement, replacementRequest); });
                var replay = executor.submit(() -> { ready.countDown(); start.await(); return issue(f.admin, trip, oldToken, issueRequest); });
                assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
                MvcResult reissued = reissue.get(20, TimeUnit.SECONDS);
                MvcResult oldIssueReplay = replay.get(20, TimeUnit.SECONDS);
                assertEquals(201, reissued.getResponse().getStatus());
                assertEquals(200, oldIssueReplay.getResponse().getStatus());
                assertEquals("stog://trip-invitations/" + oldToken, body(oldIssueReplay).get("invite_uri").asText());
            } finally {
                executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
            assertError(join(f.outsider, oldToken, "외부", UUID.randomUUID()), 404, "INVITATION_NOT_FOUND_OR_NOT_USABLE");
            assertEquals(1, count("SELECT count(*) FROM trip_member WHERE trip_id=? AND user_id=?", trip, f.member));
            MvcResult stopped = revoke(f.admin, trip, UUID.randomUUID());
            assertEquals("REVOKED", body(stopped).get("result").asText());
            assertError(join(f.outsider, replacement, "외부", UUID.randomUUID()), 404, "INVITATION_NOT_FOUND_OR_NOT_USABLE");
            String restarted = token();
            assertEquals(201, issue(f.admin, trip, restarted, UUID.randomUUID()).getResponse().getStatus());
            assertEquals(1, count("SELECT count(*) FROM trip_member WHERE trip_id=? AND user_id=?", trip, f.member));
        }
    }

    @Test
    void joinValidationPrecedenceAndNonDisclosureAreExact() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            for (String body : List.of(
                "{}",
                "{\"invitation_token\":null,\"member_label\":\"x\",\"request_id\":\"" + UUID.randomUUID() + "\"}",
                "{\"invitation_token\":1,\"member_label\":\"x\",\"request_id\":\"" + UUID.randomUUID() + "\"}",
                "{\"invitation_token\":\"x\",\"invitation_token\":\"y\",\"member_label\":\"x\",\"request_id\":\"" + UUID.randomUUID() + "\"}")) {
                MvcResult response = mvc.perform(post("/api/trip-invitations/join").with(auth(f.member))
                    .contentType("application/json").content(body)).andReturn();
                assertError(response, 400, "INVALID_REQUEST");
            }
            int attempts = count("SELECT count(*) FROM trip_invitation_attempt");
            for (String malformed : List.of("x".repeat(42), "x".repeat(44), "=".repeat(43), "+".repeat(43))) {
                assertError(join(f.member, malformed, "민지", UUID.randomUUID()), 404, "INVITATION_NOT_FOUND_OR_NOT_USABLE");
            }
            assertEquals(attempts, count("SELECT count(*) FROM trip_invitation_attempt"));
            String unknown = token();
            MvcResult unknownResult = join(f.member, unknown, "민지", UUID.randomUUID());
            assertError(unknownResult, 404, "INVITATION_NOT_FOUND_OR_NOT_USABLE");
            assertFalse(unknownResult.getResponse().getContentAsString().contains(unknown));
            assertEquals(attempts + 1, count("SELECT count(*) FROM trip_invitation_attempt"));
        }
    }

    @Test
    void roleRemoveLeaveAndDeleteEnforcePermissionAndLastAdmin() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID trip = f.trip(0);
            f.member(trip, f.member, "MEMBER", "민지");
            assertError(role(f.member, trip, f.member, "ADMIN", 0, UUID.randomUUID()), 403, "ADMIN_REQUIRED");
            assertError(remove(f.member, trip, f.admin, 0, UUID.randomUUID()), 403, "ADMIN_REQUIRED");
            assertError(issue(f.member, trip, token(), UUID.randomUUID()), 403, "ADMIN_REQUIRED");
            assertError(revoke(f.member, trip, UUID.randomUUID()), 403, "ADMIN_REQUIRED");
            assertError(deleteTrip(f.member, trip, 0, UUID.randomUUID()), 403, "ADMIN_REQUIRED");
            assertError(leave(f.admin, trip, 0, UUID.randomUUID()), 403, "ADMIN_CANNOT_LEAVE");
            assertError(remove(f.admin, trip, f.admin, 0, UUID.randomUUID()), 409, "ADMIN_SELF_REMOVAL_FORBIDDEN");
            assertError(role(f.admin, trip, f.admin, "MEMBER", 0, UUID.randomUUID()), 409, "LAST_ADMIN_REQUIRED");

            MvcResult promoted = role(f.admin, trip, f.member, "ADMIN", 0, UUID.randomUUID());
            assertEquals(200, promoted.getResponse().getStatus());
            assertEquals(1, body(promoted).get("plan_version").asLong());
            UUID removeRequest = UUID.randomUUID();
            assertEquals(204, remove(f.member, trip, f.admin, 1, removeRequest).getResponse().getStatus());
            assertEquals(204, remove(f.member, trip, f.admin, 1, removeRequest).getResponse().getStatus());
            assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM trip_member WHERE trip_id=? AND user_id=?", String.class, trip, f.member));

            UUID leaveTrip = f.trip(0);
            f.member(leaveTrip, f.outsider, "MEMBER", "외부");
            UUID leaveRequest = UUID.randomUUID();
            assertEquals(204, leave(f.outsider, leaveTrip, 0, leaveRequest).getResponse().getStatus());
            assertEquals(204, leave(f.outsider, leaveTrip, 0, leaveRequest).getResponse().getStatus());
            assertEquals(0, count("SELECT count(*) FROM trip_member WHERE trip_id=? AND user_id=?", leaveTrip, f.outsider));
        }
    }

    @Test
    void softDeletePreservesRowsHidesR1AndInvalidatesCreateReplay() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID createRequest = UUID.randomUUID();
            MvcResult created = create(f.admin, createRequest);
            UUID trip = UUID.fromString(body(created).get("trip_id").asText());
            f.ownedTrips.add(trip);
            UUID deleteRequest = UUID.randomUUID();
            assertEquals(204, deleteTrip(f.admin, trip, 0, deleteRequest).getResponse().getStatus());
            assertEquals(204, deleteTrip(f.admin, trip, 0, deleteRequest).getResponse().getStatus());
            assertEquals(1, count("SELECT count(*) FROM trip WHERE id=?", trip));
            assertEquals(1, count("SELECT count(*) FROM trip_member WHERE trip_id=?", trip));
            assertEquals(1, count("SELECT count(*) FROM trip_create_execution WHERE result_trip_id=? AND invalidated_at IS NOT NULL", trip));
            assertError(detail(f.admin, trip), 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            assertError(create(f.admin, createRequest), 409, "REQUEST_RESULT_NO_LONGER_AVAILABLE");
            JsonNode list = body(list(f.admin));
            assertFalse(list.toString().contains(trip.toString()));
            assertEquals(1, eventProbe.events.size());
            assertEquals("TRIP_DELETED", eventProbe.events.get(0).changedScope());
            assertEquals(List.of(f.admin), eventProbe.events.get(0).recipientUserIds());
        }
    }

    @Test
    void memberCursorFreezesVersionAndRemovedUserCannotReplayOldSnapshot() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID trip = f.trip(0);
            f.member(trip, f.member, "MEMBER", "민지");
            f.member(trip, f.outsider, "MEMBER", "준");
            MvcResult first = members(f.admin, trip, 1, null);
            assertEquals(200, first.getResponse().getStatus());
            assertEquals("ADMIN", body(first).at("/items/0/role").asText());
            assertEquals(3, body(first).get("member_count").asLong());
            String cursor = body(first).get("next_cursor").asText();

            UUID editRequest = UUID.randomUUID();
            assertEquals(200, update(f.member, trip, "변경", "서울", "2026-10-10", "2026-10-15", 0, editRequest).getResponse().getStatus());
            assertError(members(f.admin, trip, 1, cursor), 409, "PLAN_VERSION_CONFLICT");
            assertEquals(204, remove(f.admin, trip, f.member, 1, UUID.randomUUID()).getResponse().getStatus());
            assertError(update(f.member, trip, "변경", "서울", "2026-10-10", "2026-10-15", 0, editRequest),
                404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
        }
    }

    @Test
    void issueAndJoinRateLimitsReturnBoundedRetryAfterWithoutTokenDisclosure() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID trip = f.trip(0);
            for (int index = 0; index < 5; index++) {
                assertEquals(201, issue(f.admin, trip, token(), UUID.randomUUID()).getResponse().getStatus());
            }
            MvcResult issueLimited = issue(f.admin, trip, token(), UUID.randomUUID());
            assertError(issueLimited, 429, "INVITATION_RATE_LIMITED");
            long issueRetry = Long.parseLong(issueLimited.getResponse().getHeader("Retry-After"));
            assertTrue(issueRetry >= 1 && issueRetry <= 3600);

            for (int index = 0; index < 10; index++) {
                assertError(join(f.member, token(), "민지", UUID.randomUUID()), 404,
                    "INVITATION_NOT_FOUND_OR_NOT_USABLE");
            }
            String limitedToken = token();
            MvcResult joinLimited = join(f.member, limitedToken, "민지", UUID.randomUUID());
            assertError(joinLimited, 429, "INVITATION_RATE_LIMITED");
            long joinRetry = Long.parseLong(joinLimited.getResponse().getHeader("Retry-After"));
            assertTrue(joinRetry >= 1 && joinRetry <= 600);
            assertFalse(joinLimited.getResponse().getContentAsString().contains(limitedToken));

            String sharedToken = token();
            byte[] sharedHash = TripCollaborationService.tokenHash(sharedToken);
            for (int index = 0; index < 49; index++) {
                jdbc.update("INSERT INTO trip_invitation_attempt(principal_user_id,token_hash,attempted_at,succeeded) "
                    + "VALUES (?,?,clock_timestamp(),false)", f.member, sharedHash);
            }
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> { ready.countDown(); start.await(); return join(f.admin, sharedToken, "관리자", UUID.randomUUID()); });
                var second = executor.submit(() -> { ready.countDown(); start.await(); return join(f.outsider, sharedToken, "외부", UUID.randomUUID()); });
                assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
                List<Integer> statuses = List.of(first.get(20, TimeUnit.SECONDS).getResponse().getStatus(),
                    second.get(20, TimeUnit.SECONDS).getResponse().getStatus()).stream().sorted().toList();
                assertEquals(List.of(404, 429), statuses);
                assertEquals(50, count("SELECT count(*) FROM trip_invitation_attempt WHERE token_hash=?", sharedHash));
            } finally {
                executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void lateLedgerFailureRollsBackMetadataAndLeavesNoSuccessExecution() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID trip = f.trip(0);
            jdbc.execute("CREATE FUNCTION test_fail_r2_command() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.command_type='UPDATE_TRIP_V1' THEN RAISE EXCEPTION 'test failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER test_fail_r2_command BEFORE INSERT ON trip_command_execution "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_r2_command()");
            try {
                MvcResult failed = update(f.admin, trip, "rollback", "서울", "2026-10-10", "2026-10-15", 0, UUID.randomUUID());
                assertError(failed, 503, "DATABASE_OPERATION_FAILED");
                assertEquals("여행", jdbc.queryForObject("SELECT name FROM trip WHERE id=?", String.class, trip));
                assertEquals(0L, version(trip));
                assertEquals(0, count("SELECT count(*) FROM trip_command_execution WHERE trip_id=?", trip));
                assertTrue(eventProbe.events.isEmpty());
            } finally {
                jdbc.execute("DROP TRIGGER test_fail_r2_command ON trip_command_execution");
                jdbc.execute("DROP FUNCTION test_fail_r2_command()");
            }
            eventProbe.fail = true;
            MvcResult committed = update(f.admin, trip, "committed", "서울", "2026-10-10", "2026-10-15", 0, UUID.randomUUID());
            assertEquals(200, committed.getResponse().getStatus());
            assertEquals("committed", jdbc.queryForObject("SELECT name FROM trip WHERE id=?", String.class, trip));
        }
    }

    @Test
    void myTripsCursorContinuesAfterDeletedAnchorWithoutGenerationInvalidation() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            UUID anchor = f.trip(0);
            jdbc.update("UPDATE trip SET start_date=?,end_date=?,name='anchor' WHERE id=?", today, today.plusDays(1), anchor);
            UUID later = f.trip(0);
            jdbc.update("UPDATE trip SET start_date=?,end_date=?,name='later' WHERE id=?", today.plusDays(2), today.plusDays(3), later);
            MvcResult first = mvc.perform(get("/api/trips?limit=1").with(auth(f.admin))).andReturn();
            assertEquals(anchor.toString(), body(first).at("/items/0/trip_id").asText());
            String cursor = body(first).get("next_cursor").asText();
            assertEquals(204, deleteTrip(f.admin, anchor, 0, UUID.randomUUID()).getResponse().getStatus());
            MvcResult continuation = mvc.perform(get("/api/trips?limit=1&cursor=" + cursor).with(auth(f.admin))).andReturn();
            assertEquals(200, continuation.getResponse().getStatus());
            assertEquals(later.toString(), body(continuation).at("/items/0/trip_id").asText());
        }
    }

    @Test
    void concurrentLastAdminChangesAndJoinDeleteRemainSerializable() throws Exception {
        try (Fixture f = new Fixture(jdbc)) {
            UUID trip = f.trip(0);
            f.member(trip, f.member, "ADMIN", "민지");
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var demote = executor.submit(() -> { ready.countDown(); start.await(); return role(f.admin, trip, f.member, "MEMBER", 0, UUID.randomUUID()); });
                var remove = executor.submit(() -> { ready.countDown(); start.await(); return remove(f.member, trip, f.admin, 0, UUID.randomUUID()); });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                demote.get(20, TimeUnit.SECONDS); remove.get(20, TimeUnit.SECONDS);
                assertTrue(count("SELECT count(*) FROM trip_member WHERE trip_id=? AND role='ADMIN'", trip) >= 1);
            } finally {
                executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }

            UUID secondTrip = f.trip(0);
            String token = token(); issue(f.admin, secondTrip, token, UUID.randomUUID());
            CountDownLatch ready2 = new CountDownLatch(2), start2 = new CountDownLatch(1);
            var executor2 = Executors.newFixedThreadPool(2);
            try {
                var joining = executor2.submit(() -> { ready2.countDown(); start2.await(); return join(f.outsider, token, "외부", UUID.randomUUID()); });
                var deleting = executor2.submit(() -> { ready2.countDown(); start2.await(); return deleteTrip(f.admin, secondTrip, 0, UUID.randomUUID()); });
                assertTrue(ready2.await(10, TimeUnit.SECONDS)); start2.countDown();
                joining.get(20, TimeUnit.SECONDS); deleting.get(20, TimeUnit.SECONDS);
                boolean deleted = jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM trip WHERE id=?", Boolean.class, secondTrip);
                if (deleted) assertEquals(0, count("SELECT count(*) FROM trip_member tm JOIN trip t ON t.id=tm.trip_id WHERE tm.trip_id=? AND tm.user_id=? AND t.deleted_at IS NULL", secondTrip, f.outsider));
                else {
                    assertEquals(1, count("SELECT count(*) FROM trip_member WHERE trip_id=? AND user_id=?", secondTrip, f.outsider));
                    assertEquals(1L, version(secondTrip));
                }
            } finally {
                executor2.shutdownNow(); assertTrue(executor2.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private MvcResult members(UUID user, UUID trip, int limit, String cursor) throws Exception {
        String url = "/api/trips/" + trip + "/members?limit=" + limit
            + (cursor == null ? "" : "&cursor=" + cursor);
        return mvc.perform(get(url).with(auth(user))).andReturn();
    }
    private MvcResult update(UUID user, UUID trip, String name, String region, String start, String end,
            long version, UUID request) throws Exception {
        return mvc.perform(put("/api/trips/{id}", trip).with(auth(user)).contentType("application/json")
            .content("{\"name\":" + json.writeValueAsString(name) + ",\"region_label\":" + json.writeValueAsString(region)
                + ",\"start_date\":\"" + start + "\",\"end_date\":\"" + end + "\",\"expected_plan_version\":"
                + version + ",\"request_id\":\"" + request + "\"}")).andReturn();
    }
    private MvcResult role(UUID user, UUID trip, UUID target, String role, long version, UUID request) throws Exception {
        return mvc.perform(put("/api/trips/{trip}/members/{target}/role", trip, target).with(auth(user)).contentType("application/json")
            .content("{\"role\":\"" + role + "\",\"expected_plan_version\":" + version + ",\"request_id\":\"" + request + "\"}" )).andReturn();
    }
    private MvcResult remove(UUID user, UUID trip, UUID target, long version, UUID request) throws Exception {
        return mvc.perform(delete("/api/trips/{trip}/members/{target}", trip, target).with(auth(user)).contentType("application/json")
            .content(versionBody(version, request))).andReturn();
    }
    private MvcResult leave(UUID user, UUID trip, long version, UUID request) throws Exception {
        return mvc.perform(post("/api/trips/{trip}/leave", trip).with(auth(user)).contentType("application/json")
            .content(versionBody(version, request))).andReturn();
    }
    private MvcResult issue(UUID user, UUID trip, String token, UUID request) throws Exception {
        return mvc.perform(post("/api/trips/{trip}/invitation", trip).with(auth(user)).contentType("application/json")
            .content("{\"invitation_token\":\"" + token + "\",\"request_id\":\"" + request + "\"}")).andReturn();
    }
    private MvcResult revoke(UUID user, UUID trip, UUID request) throws Exception {
        return mvc.perform(delete("/api/trips/{trip}/invitation", trip).with(auth(user)).contentType("application/json")
            .content("{\"request_id\":\"" + request + "\"}")).andReturn();
    }
    private MvcResult join(UUID user, String token, String label, UUID request) throws Exception {
        return mvc.perform(post("/api/trip-invitations/join").with(auth(user)).contentType("application/json")
            .content("{\"invitation_token\":" + json.writeValueAsString(token) + ",\"member_label\":"
                + json.writeValueAsString(label) + ",\"request_id\":\"" + request + "\"}")).andReturn();
    }
    private MvcResult deleteTrip(UUID user, UUID trip, long version, UUID request) throws Exception {
        return mvc.perform(delete("/api/trips/{trip}", trip).with(auth(user)).contentType("application/json")
            .content(versionBody(version, request))).andReturn();
    }
    private MvcResult create(UUID user, UUID request) throws Exception {
        return mvc.perform(post("/api/trips").with(auth(user)).contentType("application/json")
            .content("{\"name\":\"created\",\"region_label\":\"서울\",\"start_date\":\"2026-10-10\",\"end_date\":\"2026-10-11\",\"request_id\":\"" + request + "\"}")).andReturn();
    }
    private MvcResult detail(UUID user, UUID trip) throws Exception { return mvc.perform(get("/api/trips/{trip}", trip).with(auth(user))).andReturn(); }
    private MvcResult list(UUID user) throws Exception { return mvc.perform(get("/api/trips").with(auth(user))).andReturn(); }
    private static String versionBody(long version, UUID request) { return "{\"expected_plan_version\":" + version + ",\"request_id\":\"" + request + "\"}"; }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(UUID user) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(user), null, List.of()));
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(code, body(result).at("/error/code").asText());
        assertEquals(2 + ("PLAN_VERSION_CONFLICT".equals(code) || "TRIP_DATE_RANGE_CONFLICT".equals(code) ? 1 : 0), body(result).get("error").size());
    }
    private long version(UUID trip) { return jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, trip); }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static String token() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventConfig {
        @Bean EventProbe eventProbe() { return new EventProbe(); }
    }

    static class EventProbe {
        final List<TripPlanChangedEvent> events = new CopyOnWriteArrayList<>();
        volatile boolean fail;
        @EventListener void receive(TripPlanChangedEvent event) {
            events.add(event);
            if (fail) throw new IllegalStateException("synthetic callback failure");
        }
    }

    static final class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc;
        final UUID admin = UUID.randomUUID(), member = UUID.randomUUID(), outsider = UUID.randomUUID();
        final List<UUID> ownedTrips = new java.util.ArrayList<>(), ownedPlaces = new java.util.ArrayList<>();
        Fixture(JdbcTemplate jdbc) { this.jdbc = jdbc; jdbc.update("INSERT INTO stog_user(id) VALUES (?),(?),(?)", admin, member, outsider); }
        UUID trip(long version) {
            UUID id = UUID.randomUUID(); ownedTrips.add(id);
            jdbc.update("INSERT INTO trip(id,name,region_label,start_date,end_date,plan_version) VALUES (?,?,?,?,?,?)",
                id, "여행", "서울", LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 15), version);
            member(id, admin, "ADMIN", null); return id;
        }
        void member(UUID trip, UUID user, String role, String label) { jdbc.update("INSERT INTO trip_member(trip_id,user_id,role,member_label) VALUES (?,?,?,?)", trip, user, role, label); }
        @Override public void close() {
            jdbc.update("DELETE FROM trip_invitation_attempt WHERE principal_user_id IN (?,?,?)", admin, member, outsider);
            for (UUID trip : ownedTrips) {
                jdbc.update("DELETE FROM trip_command_execution WHERE trip_id=?", trip);
                jdbc.update("DELETE FROM trip_invitation WHERE trip_id=?", trip);
                jdbc.update("DELETE FROM request_execution WHERE input_trip_id=?", trip);
                jdbc.update("DELETE FROM trip_create_execution WHERE result_trip_id=?", trip);
                jdbc.update("DELETE FROM trip_item WHERE trip_id=?", trip);
                jdbc.update("DELETE FROM trip_member WHERE trip_id=?", trip);
                jdbc.update("DELETE FROM trip WHERE id=?", trip);
            }
            for (UUID place : ownedPlaces) jdbc.update("DELETE FROM place WHERE id=?", place);
            jdbc.update("DELETE FROM trip_create_execution WHERE principal_user_id IN (?,?,?)", admin, member, outsider);
            jdbc.update("DELETE FROM trip_command_execution WHERE principal_user_id IN (?,?,?)", admin, member, outsider);
            jdbc.update("DELETE FROM stog_user WHERE id IN (?,?,?)", admin, member, outsider);
        }
    }
}
