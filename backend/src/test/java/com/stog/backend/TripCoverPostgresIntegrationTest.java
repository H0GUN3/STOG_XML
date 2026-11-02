package com.stog.backend;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockPart;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = BasketSavePostgresIntegrationTest.DatabaseSafetyGate.class)
@Import({RealJdbcProbe.class, TripCollaborationPostgresIntegrationTest.EventConfig.class})
class TripCoverPostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TripCoverService service;
    @Autowired TripCoverStorage storage;
    @Autowired RealJdbcProbe.Probe probe;
    @Autowired TripCollaborationPostgresIntegrationTest.EventProbe eventProbe;

    @BeforeEach void resetProbe() { probe.reset(); eventProbe.events.clear(); eventProbe.fail = false; }

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
    void privateUploadReplacementReplayRemoveAndDtoAreExact() throws Exception {
        try (Fixture f = new Fixture(jdbc, storage)) {
            UUID trip = f.trip();
            byte[] png = image("png", 1200, 600, true);
            UUID firstRequest = UUID.randomUUID();
            MvcResult first = upload(f.admin, trip, 0, firstRequest, "image/png", png);
            MvcResult replay = upload(f.admin, trip, 0, firstRequest, "image/png", png);
            assertEquals(201, first.getResponse().getStatus());
            assertEquals(200, replay.getResponse().getStatus());
            assertEquals(body(first), body(replay));
            UUID firstCover = UUID.fromString(body(first).at("/cover/cover_id").asText());
            assertEquals(1, body(first).get("plan_version").asLong());
            assertEquals("private, no-store", first.getResponse().getHeader("Cache-Control"));
            assertEquals("nosniff", first.getResponse().getHeader("X-Content-Type-Options"));
            assertEquals(1, count("SELECT count(*) FROM trip_cover WHERE trip_id=?", trip));
            assertEquals(1, count("SELECT count(*) FROM trip_command_execution WHERE trip_id=? AND command_type='SET_TRIP_COVER_V1'", trip));
            assertEquals(1, eventProbe.events.size());
            assertEquals("COVER", eventProbe.events.get(0).changedScope());
            MvcResult metadata = detail(f.member, trip);
            assertEquals(firstCover.toString(), body(metadata).at("/cover/cover_id").asText());
            assertEquals("private, no-store", metadata.getResponse().getHeader("Cache-Control"));
            assertEquals("nosniff", metadata.getResponse().getHeader("X-Content-Type-Options"));
            MvcResult trips = mvc.perform(get("/api/trips").with(auth(f.member))).andReturn();
            assertEquals(firstCover.toString(), body(trips).at("/items/0/cover/cover_id").asText());

            MvcResult file = file(f.member, trip, firstCover, false);
            MvcResult thumbnail = file(f.member, trip, firstCover, true);
            assertEquals(200, file.getResponse().getStatus());
            assertEquals("image/jpeg", file.getResponse().getContentType());
            assertEquals("private, no-store", file.getResponse().getHeader("Cache-Control"));
            assertTrue(ImageIO.read(new java.io.ByteArrayInputStream(file.getResponse().getContentAsByteArray())) != null);
            assertTrue(thumbnail.getResponse().getContentAsByteArray().length <= TripCoverStorage.MAX_THUMBNAIL);
            assertError(detail(f.outsider, trip), 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            assertError(file(f.outsider, trip, firstCover, false), 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");

            UUID replacementRequest = UUID.randomUUID();
            MvcResult replacement = upload(f.member, trip, 1, replacementRequest, "image/jpeg",
                image("jpeg", 400, 800, false));
            assertEquals(201, replacement.getResponse().getStatus());
            UUID secondCover = UUID.fromString(body(replacement).at("/cover/cover_id").asText());
            assertNotEquals(firstCover, secondCover);
            assertEquals(200, file(f.admin, trip, firstCover, false).getResponse().getStatus());
            assertTrue(jdbc.queryForObject("SELECT retired_at IS NOT NULL FROM trip_cover WHERE id=?",
                Boolean.class, firstCover));

            MvcResult removed = remove(f.admin, trip, 2, replacementRequest);
            assertEquals(200, removed.getResponse().getStatus());
            assertEquals("REMOVED", body(removed).get("result").asText());
            assertEquals(3, body(removed).get("plan_version").asLong());
            assertEquals(2, count("SELECT count(*) FROM trip_command_execution WHERE trip_id=? AND request_id=?", trip,
                replacementRequest));
            assertEquals(3, eventProbe.events.size());

            UUID noCoverRequest = UUID.randomUUID();
            MvcResult unchanged = remove(f.member, trip, 3, noCoverRequest);
            assertEquals("UNCHANGED", body(unchanged).get("result").asText());
            assertEquals(3, eventProbe.events.size());
            UUID thirdRequest = UUID.randomUUID();
            assertEquals(201, upload(f.member, trip, 3, thirdRequest, "image/png", png).getResponse().getStatus());
            MvcResult unchangedReplay = remove(f.member, trip, 3, noCoverRequest);
            assertEquals(body(unchanged), body(unchangedReplay));
            assertEquals(4L, jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, trip));
            assertEquals(4, eventProbe.events.size());

            JsonNode detail = body(detail(f.admin, trip));
            assertTrue(detail.get("cover").isObject());
            assertEquals(detail.at("/cover/cover_id").asText(),
                jdbc.queryForObject("SELECT cover_id::text FROM trip WHERE id=?", String.class, trip));
        }
    }

    @Test
    void invalidMimeDecodePolyglotDimensionAndByteLimitsLeaveNoRowsOrFiles() throws Exception {
        try (Fixture f = new Fixture(jdbc, storage)) {
            UUID trip = f.trip();
            byte[] png = image("png", 20, 10, false);
            assertError(upload(f.admin, trip, 0, UUID.randomUUID(), "image/gif", png), 415,
                "UNSUPPORTED_MEDIA_TYPE");
            assertError(upload(f.admin, trip, 0, UUID.randomUUID(), "image/jpeg", png), 400,
                "INVALID_COVER");
            assertError(upload(f.admin, trip, 0, UUID.randomUUID(), "image/png",
                java.util.Arrays.copyOf(png, png.length - 3)), 400, "INVALID_COVER");
            byte[] trailing = java.util.Arrays.copyOf(png, png.length + 4);
            trailing[trailing.length - 1] = 'X';
            assertError(upload(f.admin, trip, 0, UUID.randomUUID(), "image/png", trailing), 400,
                "INVALID_COVER");
            assertError(upload(f.admin, trip, 0, UUID.randomUUID(), "image/png",
                image("png", 8193, 1, false)), 413, "COVER_TOO_LARGE");
            assertError(upload(f.admin, trip, 0, UUID.randomUUID(), "image/png",
                new byte[(int) TripCoverStorage.MAX_INPUT + 1]), 413, "COVER_TOO_LARGE");
            assertEquals(0L, jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, trip));
            assertEquals(0, count("SELECT count(*) FROM trip_cover WHERE trip_id=?", trip));
            assertEquals(0, count("SELECT count(*) FROM trip_command_execution WHERE trip_id=?", trip));
            assertEquals(0, ownedFinalDirectoryCount());
        }
    }

    @Test
    void concurrentRequestClaimAndVersionSerializationLeaveOneVisibleCover() throws Exception {
        try (Fixture f = new Fixture(jdbc, storage)) {
            UUID trip = f.trip();
            byte[] png = image("png", 64, 32, false);
            UUID request = UUID.randomUUID();
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var one = executor.submit(() -> { ready.countDown(); start.await(); return upload(f.admin, trip, 0, request, "image/png", png); });
                var two = executor.submit(() -> { ready.countDown(); start.await(); return upload(f.admin, trip, 0, request, "image/png", png); });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                List<MvcResult> results = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
                assertEquals(List.of(200, 201), results.stream().map(r -> r.getResponse().getStatus()).sorted().toList());
                assertEquals(body(results.get(0)), body(results.get(1)));
                assertEquals(1, count("SELECT count(*) FROM trip_cover WHERE trip_id=?", trip));
                assertEquals(1, count("SELECT count(*) FROM trip_command_execution WHERE trip_id=? AND request_id=?", trip, request));
                assertEquals(1, ownedFinalDirectoryCount());
            } finally {
                start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }

            UUID current = jdbc.queryForObject("SELECT cover_id FROM trip WHERE id=?", UUID.class, trip);
            CountDownLatch ready2 = new CountDownLatch(2), start2 = new CountDownLatch(1);
            var executor2 = Executors.newFixedThreadPool(2);
            try {
                var replacing = executor2.submit(() -> { ready2.countDown(); start2.await(); return upload(f.member, trip, 1,
                    UUID.randomUUID(), "image/png", png); });
                var removing = executor2.submit(() -> { ready2.countDown(); start2.await(); return remove(f.admin, trip, 1,
                    UUID.randomUUID()); });
                assertTrue(ready2.await(10, TimeUnit.SECONDS)); start2.countDown();
                List<MvcResult> results = List.of(replacing.get(30, TimeUnit.SECONDS), removing.get(30, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(r -> r.getResponse().getStatus() == 409).count());
                assertEquals(1, results.stream().filter(r -> r.getResponse().getStatus() == 200
                    || r.getResponse().getStatus() == 201).count());
                assertEquals(2L, jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, trip));
                assertTrue(jdbc.queryForObject("SELECT retired_at IS NOT NULL FROM trip_cover WHERE id=?", Boolean.class,
                    current));
            } finally {
                start2.countDown(); executor2.shutdownNow(); assertTrue(executor2.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void byteReadKeepsMembershipDecisionLockedThroughStorageBoundaryAndRemovedReplayIsDenied() throws Exception {
        try (Fixture f = new Fixture(jdbc, storage)) {
            UUID trip = f.trip();
            byte[] png = image("png", 80, 40, false);
            UUID request = UUID.randomUUID();
            UUID cover = UUID.fromString(body(upload(f.member, trip, 0, request, "image/png", png))
                .at("/cover/cover_id").asText());
            RealJdbcProbe.Gate bytes = probe.pauseCoverBytesSelect();
            var executor = Executors.newFixedThreadPool(2);
            RealJdbcProbe.Gate removal = null;
            try {
                var reading = executor.submit(() -> file(f.member, trip, cover, false));
                bytes.awaitArrival();
                removal = probe.observeCollaborationTripSelect();
                var removing = executor.submit(() -> removeMember(f.admin, trip, f.member, 1, UUID.randomUUID()));
                removal.awaitArrival();
                bytes.release();
                assertEquals(200, reading.get(30, TimeUnit.SECONDS).getResponse().getStatus());
                assertEquals(204, removing.get(30, TimeUnit.SECONDS).getResponse().getStatus());
                assertError(file(f.member, trip, cover, false), 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
                assertError(upload(f.member, trip, 0, request, "image/png", png), 404,
                    "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
                assertEquals(1, count("SELECT count(*) FROM trip_cover WHERE trip_id=?", trip));
            } finally {
                bytes.release();
                if (removal != null) removal.release();
                executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void failedStorageAndDatabaseCommitRollbackOnlyOwnedFiles() throws Exception {
        try (Fixture f = new Fixture(jdbc, storage)) {
            UUID storageTrip = f.trip();
            Path covers = Path.of(BasketSavePostgresIntegrationTest.required("STOG_MEDIA_ROOT")).resolve("covers");
            Files.delete(covers);
            try {
                MvcResult failed = upload(f.admin, storageTrip, 0, UUID.randomUUID(), "image/png",
                    image("png", 20, 10, false));
                assertError(failed, 503, "MEDIA_STORAGE_FAILED");
                assertEquals(0L, jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, storageTrip));
                assertEquals(0, count("SELECT count(*) FROM trip_cover WHERE trip_id=?", storageTrip));
            } finally {
                Files.createDirectories(covers);
            }

            UUID databaseTrip = f.trip();
            jdbc.execute("CREATE FUNCTION test_fail_cover_ledger() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.command_type='SET_TRIP_COVER_V1' THEN RAISE EXCEPTION 'test failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER test_fail_cover_ledger BEFORE INSERT ON trip_command_execution "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_cover_ledger()");
            try {
                MvcResult failed = upload(f.admin, databaseTrip, 0, UUID.randomUUID(), "image/png",
                    image("png", 20, 10, false));
                assertError(failed, 503, "DATABASE_OPERATION_FAILED");
                assertEquals(0L, jdbc.queryForObject("SELECT plan_version FROM trip WHERE id=?", Long.class, databaseTrip));
                assertEquals(0, count("SELECT count(*) FROM trip_cover WHERE trip_id=?", databaseTrip));
                assertEquals(0, ownedFinalDirectoryCount());
            } finally {
                jdbc.execute("DROP TRIGGER test_fail_cover_ledger ON trip_command_execution");
                jdbc.execute("DROP FUNCTION test_fail_cover_ledger()");
            }

            UUID corruptTrip = f.trip();
            UUID corruptCover = UUID.fromString(body(upload(f.admin, corruptTrip, 0, UUID.randomUUID(),
                "image/png", image("png", 20, 10, false))).at("/cover/cover_id").asText());
            UUID corruptStorage = jdbc.queryForObject("SELECT storage_key FROM trip_cover WHERE id=?", UUID.class,
                corruptCover);
            Files.delete(covers.resolve(corruptStorage.toString()).resolve("display.jpg"));
            assertError(file(f.admin, corruptTrip, corruptCover, false), 503, "MEDIA_STORAGE_FAILED");
            assertEquals(corruptCover, jdbc.queryForObject("SELECT cover_id FROM trip WHERE id=?", UUID.class,
                corruptTrip));
        }
    }

    @Test
    void reconciliationDeletesOnlyOwnedExpiredOrphansAndRetiredBytesButPreservesDeletedTripMedia() throws Exception {
        try (Fixture f = new Fixture(jdbc, storage)) {
            UUID orphanKey = UUID.randomUUID();
            Path root = Path.of(BasketSavePostgresIntegrationTest.required("STOG_MEDIA_ROOT"));
            Path orphan = root.resolve("covers").resolve(orphanKey.toString());
            Files.createDirectory(orphan);
            Files.writeString(orphan.resolve(".staging"), "STAGING");
            Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(0));
            Path unrelated = root.resolve("covers").resolve(UUID.randomUUID().toString());
            Files.createDirectory(unrelated);
            Files.writeString(unrelated.resolve("real-user-file"), "preserve");
            service.reconcileDaily();
            assertFalse(Files.exists(orphan));
            assertTrue(Files.exists(unrelated.resolve("real-user-file")));

            UUID trip = f.trip();
            UUID firstRequest = UUID.randomUUID();
            UUID firstCover = UUID.fromString(body(upload(f.admin, trip, 0, firstRequest, "image/png",
                image("png", 40, 20, false))).at("/cover/cover_id").asText());
            UUID firstStorage = jdbc.queryForObject("SELECT storage_key FROM trip_cover WHERE id=?", UUID.class, firstCover);
            upload(f.admin, trip, 1, UUID.randomUUID(), "image/png", image("png", 30, 20, false));
            jdbc.update("UPDATE trip_cover SET created_at=clock_timestamp()-INTERVAL '26 hours',"
                + "retired_at=clock_timestamp()-INTERVAL '25 hours' WHERE id=?", firstCover);
            jdbc.update("WITH sampled AS (SELECT clock_timestamp()-INTERVAL '25 hours' expired) "
                + "UPDATE trip_command_execution SET completed_at=sampled.expired-INTERVAL '24 hours',"
                + "expires_at=sampled.expired FROM sampled WHERE trip_id=? AND command_type='SET_TRIP_COVER_V1' "
                + "AND request_id=?", trip, firstRequest);
            service.reconcileDaily();
            assertFalse(Files.exists(root.resolve("covers").resolve(firstStorage.toString())));
            assertEquals(1, count("SELECT count(*) FROM trip_cover WHERE id=?", firstCover));

            UUID currentCover = jdbc.queryForObject("SELECT cover_id FROM trip WHERE id=?", UUID.class, trip);
            UUID currentStorage = jdbc.queryForObject("SELECT storage_key FROM trip_cover WHERE id=?", UUID.class, currentCover);
            jdbc.update("UPDATE trip SET deleted_at=clock_timestamp(),deleted_by_user_id=? WHERE id=?", f.admin, trip);
            service.reconcileDaily();
            assertTrue(Files.exists(root.resolve("covers").resolve(currentStorage.toString()).resolve("display.jpg")));
            assertError(file(f.admin, trip, currentCover, false), 404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE");
            f.deleteFields.add(trip);
        }
    }

    @Test
    void reconciliationProgressesBeyondOneHundredReferencedDirectories() throws Exception {
        try (Fixture f = new Fixture(jdbc, storage)) {
            storage.closeDirectoryScans();
            Path covers = Path.of(BasketSavePostgresIntegrationTest.required("STOG_MEDIA_ROOT")).resolve("covers");
            Set<UUID> allKeys = new HashSet<>();
            for (int index = 0; index < 101; index++) {
                UUID key = UUID.randomUUID();
                allKeys.add(key);
                Path directory = covers.resolve(key.toString());
                Files.createDirectory(directory);
                Files.writeString(directory.resolve(".committed"), "COMMITTED");
                Files.setLastModifiedTime(directory, java.nio.file.attribute.FileTime.fromMillis(0));
            }
            List<TripCoverStorage.StoredDirectory> firstBatch = storage.finalDirectories();
            assertEquals(100, firstBatch.size());
            Set<UUID> referenced = new HashSet<>();
            for (TripCoverStorage.StoredDirectory directory : firstBatch) {
                UUID trip = f.trip();
                UUID cover = UUID.randomUUID();
                referenced.add(directory.storageKey());
                jdbc.update("INSERT INTO trip_cover(id,trip_id,created_by_user_id,media_type,storage_key,byte_size,width,height,"
                    + "thumbnail_byte_size,thumbnail_width,thumbnail_height,input_sha256,created_at) "
                    + "VALUES (?,?,?,'image/jpeg',?,1,1,1,1,1,1,?,clock_timestamp())",
                    cover, trip, f.admin, directory.storageKey(), new byte[32]);
                jdbc.update("UPDATE trip SET cover_id=? WHERE id=?", cover, trip);
            }
            Set<UUID> later = new HashSet<>(allKeys);
            later.removeAll(referenced);
            assertEquals(1, later.size());
            UUID orphan = later.iterator().next();

            service.reconcileDaily();

            assertFalse(Files.exists(covers.resolve(orphan.toString())),
                "the entry after a retained first batch must be reached on the next pass");
            for (UUID key : referenced) assertTrue(Files.exists(covers.resolve(key.toString())));
        } finally {
            storage.closeDirectoryScans();
        }
    }

    private MvcResult upload(UUID user, UUID trip, long version, UUID request, String mediaType, byte[] bytes) throws Exception {
        MockPart image = new MockPart("image", "untrusted-name", bytes);
        image.getHeaders().setContentType(MediaType.parseMediaType(mediaType));
        MockPart command = new MockPart("command",
            ("{\"expected_plan_version\":" + version + ",\"request_id\":\"" + request + "\"}").getBytes());
        command.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return mvc.perform(multipart("/api/trips/{trip}/cover", trip).part(image, command).with(auth(user))).andReturn();
    }

    private MvcResult remove(UUID user, UUID trip, long version, UUID request) throws Exception {
        return mvc.perform(delete("/api/trips/{trip}/cover", trip).with(auth(user)).contentType("application/json")
            .content("{\"expected_plan_version\":" + version + ",\"request_id\":\"" + request + "\"}"))
            .andReturn();
    }

    private MvcResult removeMember(UUID user, UUID trip, UUID target, long version, UUID request) throws Exception {
        return mvc.perform(delete("/api/trips/{trip}/members/{target}", trip, target).with(auth(user))
            .contentType("application/json").content("{\"expected_plan_version\":" + version
                + ",\"request_id\":\"" + request + "\"}")).andReturn();
    }

    private MvcResult file(UUID user, UUID trip, UUID cover, boolean thumbnail) throws Exception {
        return mvc.perform(get("/api/trips/{trip}/covers/{cover}/{kind}", trip, cover,
            thumbnail ? "thumbnail" : "file").with(auth(user))).andReturn();
    }

    private MvcResult detail(UUID user, UUID trip) throws Exception {
        return mvc.perform(get("/api/trips/{trip}", trip).with(auth(user))).andReturn();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(UUID user) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(user), null, List.of()));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(code, body(result).at("/error/code").asText());
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private int ownedFinalDirectoryCount() throws Exception {
        Path covers = Path.of(BasketSavePostgresIntegrationTest.required("STOG_MEDIA_ROOT")).resolve("covers");
        try (var children = Files.list(covers)) {
            return (int) children.filter(path -> Files.exists(path.resolve(".staging"))
                || Files.exists(path.resolve(".committed")) || Files.exists(path.resolve(".gc-pending"))).count();
        }
    }

    private static byte[] image(String format, int width, int height, boolean alpha) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
            alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(alpha ? new Color(0, 0, 0, 0) : Color.BLUE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.RED);
            graphics.fillRect(width / 2, 0, Math.max(1, width / 2), height);
        } finally { graphics.dispose(); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    static final class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc;
        final TripCoverStorage storage;
        final UUID admin = UUID.randomUUID(), member = UUID.randomUUID(), outsider = UUID.randomUUID();
        final List<UUID> trips = new ArrayList<>(), deleteFields = new ArrayList<>();
        Fixture(JdbcTemplate jdbc, TripCoverStorage storage) {
            this.jdbc = jdbc; this.storage = storage;
            jdbc.update("INSERT INTO stog_user(id) VALUES (?),(?),(?)", admin, member, outsider);
        }
        UUID trip() {
            UUID trip = UUID.randomUUID(); trips.add(trip);
            jdbc.update("INSERT INTO trip(id,name,region_label,start_date,end_date,plan_version) VALUES (?,?,?,?,?,0)",
                trip, "cover trip", "서울", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
            jdbc.update("INSERT INTO trip_member(trip_id,user_id,role) VALUES (?,?,'ADMIN'),(?,?,'MEMBER')",
                trip, admin, trip, member);
            return trip;
        }
        @Override public void close() {
            for (UUID trip : deleteFields) jdbc.update("UPDATE trip SET deleted_at=NULL,deleted_by_user_id=NULL WHERE id=?", trip);
            for (UUID trip : trips) {
                jdbc.update("UPDATE trip SET cover_id=NULL WHERE id=?", trip);
                List<UUID> keys = jdbc.query("SELECT storage_key FROM trip_cover WHERE trip_id=?",
                    (rs, ignored) -> rs.getObject(1, UUID.class), trip);
                jdbc.update("DELETE FROM trip_command_execution WHERE trip_id=?", trip);
                jdbc.update("DELETE FROM trip_cover WHERE trip_id=?", trip);
                keys.forEach(storage::deleteFinal);
                jdbc.update("DELETE FROM trip_member WHERE trip_id=?", trip);
                jdbc.update("DELETE FROM trip WHERE id=?", trip);
            }
            jdbc.update("DELETE FROM stog_user WHERE id IN (?,?,?)", admin, member, outsider);
        }
    }
}
