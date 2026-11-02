package com.stog.backend;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TripCoverService {
    static final String SET_COVER = "SET_TRIP_COVER_V1";
    static final String REMOVE_COVER = "REMOVE_TRIP_COVER_V1";
    private static final Logger logger = LoggerFactory.getLogger(TripCoverService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TripCoverStorage storage;
    private final TripPlanEventPublisher events;
    private final ObjectMapper json;
    private final ReentrantLock reconciliationLock;

    @Autowired
    public TripCoverService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            TripCoverStorage storage, TripPlanEventPublisher events, ObjectMapper json) {
        this(jdbc, transactionManager, storage, events, json, new ReentrantLock());
    }

    TripCoverService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            TripCoverStorage storage, TripPlanEventPublisher events, ObjectMapper json,
            ReentrantLock reconciliationLock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.storage = storage;
        this.events = events;
        this.json = json;
        this.reconciliationLock = reconciliationLock;
    }

    public UploadResult upload(UUID principal, UUID tripId, long expectedVersion, UUID requestId,
            String mediaType, long declaredSize, InputStream input) {
        requireAccessible(principal, tripId);
        TripCoverStorage.Stage stage = storage.stage(input, declaredSize, mediaType);
        byte[] fingerprint = fingerprint(tripId, expectedVersion, stage.inputSha256(), stage.format(),
            stage.inputWidth(), stage.inputHeight());
        StageOwner owner = new StageOwner(stage);
        try {
            try {
                return transactions.execute(status -> uploadInTransaction(principal, tripId, expectedVersion,
                    requestId, fingerprint, owner));
            } catch (DataIntegrityViolationException collision) {
                owner.rollback();
                UploadResult winner = transactions.execute(status -> readUploadWinner(
                    principal, tripId, requestId, fingerprint));
                if (winner != null) return winner;
                throw collision;
            } catch (RuntimeException exception) {
                throw exception;
            }
        } catch (TripCoverException exception) {
            throw exception;
        } catch (TransientDataAccessException exception) {
            throw new TripCoverException(503, "DATABASE_BUSY", "The request could not be completed");
        } finally {
            owner.discardUnmoved();
        }
    }

    public RemoveResult remove(UUID principal, UUID tripId, long expectedVersion, UUID requestId) {
        byte[] fingerprint = fingerprint(tripId, expectedVersion);
        try {
            return transactions.execute(status -> {
                AccessibleTrip trip = lockAccessibleTrip(principal, tripId);
                Execution prior = execution(principal, tripId, REMOVE_COVER, requestId);
                if (prior != null && prior.unexpired()) return removeReplay(prior, fingerprint);
                expire(prior, principal, tripId, REMOVE_COVER, requestId);
                requireVersion(trip.planVersion(), expectedVersion);
                if (trip.coverId() == null) {
                    RemoveResult result = new RemoveResult("UNCHANGED", trip.planVersion());
                    insertExecution(principal, tripId, REMOVE_COVER, requestId, fingerprint, 200,
                        Map.of("result", result.result(), "plan_version", result.planVersion()));
                    return result;
                }
                UUID retiredStorage = jdbc.queryForObject("SELECT storage_key FROM trip_cover WHERE id=?",
                    UUID.class, trip.coverId());
                long version = nextVersion(trip.planVersion());
                jdbc.update("UPDATE trip_cover SET retired_at=clock_timestamp() WHERE id=? AND retired_at IS NULL",
                    trip.coverId());
                jdbc.update("UPDATE trip SET cover_id=NULL,plan_version=? WHERE id=?", version, tripId);
                RemoveResult result = new RemoveResult("REMOVED", version);
                insertExecution(principal, tripId, REMOVE_COVER, requestId, fingerprint, 200,
                    Map.of("result", result.result(), "plan_version", result.planVersion()));
                afterCommit(tripId, version, retiredStorage);
                return result;
            });
        } catch (TripCoverException exception) {
            throw exception;
        } catch (TransientDataAccessException exception) {
            throw new TripCoverException(503, "DATABASE_BUSY", "The request could not be completed");
        }
    }

    public FileResult file(UUID principal, UUID tripId, UUID coverId, boolean thumbnail) {
        try {
            return transactions.execute(status -> {
                lockAccessibleTrip(principal, tripId);
                StoredCover cover = one("SELECT c.id,c.trip_id,c.storage_key,c.byte_size,c.thumbnail_byte_size "
                    + "FROM trip_cover c JOIN trip t ON t.id=c.trip_id WHERE c.id=? AND c.trip_id=? AND "
                    + "(t.cover_id=c.id OR EXISTS (SELECT 1 FROM trip_command_execution e WHERE e.trip_id=t.id "
                    + "AND e.command_type='SET_TRIP_COVER_V1' AND e.expires_at>clock_timestamp() "
                    + "AND e.result_json#>>'{cover,cover_id}'=?))",
                    TripCoverService::mapStoredCover, coverId, tripId, coverId.toString());
                if (cover == null) throw coverNotFound();
                long expected = thumbnail ? cover.thumbnailSize() : cover.displaySize();
                return new FileResult(storage.read(cover.storageKey(), thumbnail, expected));
            });
        } catch (TripCoverException exception) {
            throw exception;
        } catch (TransientDataAccessException exception) {
            throw new TripCoverException(503, "DATABASE_BUSY", "The request could not be completed");
        }
    }

    public CoverView currentCover(UUID tripId) {
        return one("SELECT c.id,c.media_type,c.byte_size,c.width,c.height,c.thumbnail_byte_size,"
            + "c.thumbnail_width,c.thumbnail_height FROM trip t JOIN trip_cover c ON c.id=t.cover_id WHERE t.id=?",
            (rs, ignored) -> mapCover(rs, tripId), tripId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileAtStartup() {
        reconcileSafely();
    }

    @Scheduled(fixedDelayString = "PT24H")
    public void reconcileDaily() {
        reconcileSafely();
    }

    private void requireAccessible(UUID principal, UUID tripId) {
        try {
            transactions.executeWithoutResult(status -> lockAccessibleTrip(principal, tripId));
        } catch (TripCoverException exception) {
            throw exception;
        } catch (TransientDataAccessException exception) {
            throw new TripCoverException(503, "DATABASE_BUSY", "The request could not be completed");
        }
    }

    private UploadResult uploadInTransaction(UUID principal, UUID tripId, long expectedVersion, UUID requestId,
            byte[] fingerprint, StageOwner owner) {
        AccessibleTrip trip = lockAccessibleTrip(principal, tripId);
        Execution prior = execution(principal, tripId, SET_COVER, requestId);
        if (prior != null && prior.unexpired()) return uploadReplay(prior, fingerprint);
        expire(prior, principal, tripId, SET_COVER, requestId);
        requireVersion(trip.planVersion(), expectedVersion);
        long version = nextVersion(trip.planVersion());
        UUID coverId = UUID.randomUUID();
        UUID storageKey = UUID.randomUUID();
        TripCoverStorage.Stage moved = storage.commitStage(owner.stage(), storageKey);
        owner.moved(moved, storageKey);
        registerRollback(owner);
        UUID retiredStorage = null;
        if (trip.coverId() != null) {
            retiredStorage = jdbc.queryForObject("SELECT storage_key FROM trip_cover WHERE id=?", UUID.class,
                trip.coverId());
            jdbc.update("UPDATE trip_cover SET retired_at=clock_timestamp() WHERE id=? AND retired_at IS NULL",
                trip.coverId());
        }
        jdbc.update("INSERT INTO trip_cover(id,trip_id,created_by_user_id,media_type,storage_key,byte_size,width,height,"
            + "thumbnail_byte_size,thumbnail_width,thumbnail_height,input_sha256,created_at) "
            + "VALUES (?,?,?,'image/jpeg',?,?,?,?,?,?,?,?,clock_timestamp())",
            coverId, tripId, principal, storageKey, moved.displaySize(), moved.displayWidth(), moved.displayHeight(),
            moved.thumbnailSize(), moved.thumbnailWidth(), moved.thumbnailHeight(), moved.inputSha256());
        jdbc.update("UPDATE trip SET cover_id=?,plan_version=? WHERE id=?", coverId, version, tripId);
        CoverView cover = new CoverView(coverId, "image/jpeg", moved.displaySize(), moved.displayWidth(),
            moved.displayHeight(), moved.thumbnailSize(), moved.thumbnailWidth(), moved.thumbnailHeight(),
            filePath(tripId, coverId), thumbnailPath(tripId, coverId));
        UploadResult result = new UploadResult("SET", cover, version, true);
        insertExecution(principal, tripId, SET_COVER, requestId, fingerprint, 201, uploadMap(result));
        registerFileCompletion(tripId, version, storageKey, retiredStorage, owner);
        return result;
    }

    private UploadResult readUploadWinner(UUID principal, UUID tripId, UUID requestId, byte[] fingerprint) {
        lockAccessibleTrip(principal, tripId);
        Execution winner = execution(principal, tripId, SET_COVER, requestId);
        return winner == null || !winner.unexpired() ? null : uploadReplay(winner, fingerprint);
    }

    private AccessibleTrip lockAccessibleTrip(UUID principal, UUID tripId) {
        jdbc.execute("SET LOCAL lock_timeout='5s'");
        AccessibleTrip row = one("SELECT t.plan_version,t.cover_id FROM trip t JOIN trip_member tm "
            + "ON tm.trip_id=t.id AND tm.user_id=? WHERE t.id=? AND t.deleted_at IS NULL FOR UPDATE OF t,tm",
            (rs, ignored) -> new AccessibleTrip(rs.getLong(1), rs.getObject(2, UUID.class)), principal, tripId);
        if (row == null) throw inaccessible();
        return row;
    }

    private Execution execution(UUID principal, UUID tripId, String command, UUID requestId) {
        Execution row = one("SELECT input_sha256,response_status,result_json::text,expires_at "
            + "FROM trip_command_execution WHERE principal_user_id=? AND trip_id=? AND command_type=? "
            + "AND request_id=? FOR UPDATE", (rs, ignored) -> new Execution(rs.getBytes(1), rs.getInt(2),
                rs.getString(3), rs.getObject(4, OffsetDateTime.class), false),
            principal, tripId, command, requestId);
        if (row == null) return null;
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        return row.withUnexpired(now.isBefore(row.expiresAt()));
    }

    private void expire(Execution prior, UUID principal, UUID tripId, String command, UUID requestId) {
        if (prior != null && !prior.unexpired()) jdbc.update("DELETE FROM trip_command_execution WHERE "
            + "principal_user_id=? AND trip_id=? AND command_type=? AND request_id=? "
            + "AND expires_at<=clock_timestamp()", principal, tripId, command, requestId);
    }

    private void insertExecution(UUID principal, UUID tripId, String command, UUID requestId, byte[] input,
            int responseStatus, Map<String, ?> result) {
        String resultJson;
        try { resultJson = json.writeValueAsString(result); }
        catch (Exception exception) { throw new IllegalStateException("Result serialization failed", exception); }
        jdbc.update("WITH sampled AS (SELECT clock_timestamp() completed_at) INSERT INTO trip_command_execution("
            + "principal_user_id,trip_id,command_type,request_id,input_sha256,response_status,result_json,completed_at,expires_at) "
            + "SELECT ?,?,?,?,?,?,?::jsonb,completed_at,completed_at+INTERVAL '24 hours' FROM sampled",
            principal, tripId, command, requestId, input, responseStatus, resultJson);
    }

    private UploadResult uploadReplay(Execution execution, byte[] fingerprint) {
        requireInput(execution, fingerprint);
        JsonNode root = parse(execution.resultJson());
        JsonNode cover = root.get("cover");
        CoverView view = new CoverView(UUID.fromString(cover.get("cover_id").asText()),
            cover.get("media_type").asText(), cover.get("byte_size").asLong(), cover.get("width").asInt(),
            cover.get("height").asInt(), cover.get("thumbnail_byte_size").asLong(),
            cover.get("thumbnail_width").asInt(), cover.get("thumbnail_height").asInt(),
            cover.get("file_path").asText(), cover.get("thumbnail_path").asText());
        return new UploadResult(root.get("result").asText(), view, root.get("plan_version").asLong(), false);
    }

    private RemoveResult removeReplay(Execution execution, byte[] fingerprint) {
        requireInput(execution, fingerprint);
        JsonNode value = parse(execution.resultJson());
        return new RemoveResult(value.get("result").asText(), value.get("plan_version").asLong());
    }

    private void registerFileCompletion(UUID tripId, long version, UUID storageKey, UUID retiredStorage,
            StageOwner owner) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                owner.committed();
                try { storage.markCommitted(storageKey); }
                catch (RuntimeException exception) {
                    logger.warn("Committed cover marker transition will be retried for trip {}", tripId);
                }
                if (retiredStorage != null) storage.markGcPending(retiredStorage);
                events.publishAfterCommit(new TripPlanChangedEvent(tripId, version, "COVER"));
            }
        });
    }

    private void registerRollback(StageOwner owner) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) owner.rollback();
            }
        });
    }

    private void afterCommit(UUID tripId, long version, UUID retiredStorage) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                if (retiredStorage != null) storage.markGcPending(retiredStorage);
                events.publishAfterCommit(new TripPlanChangedEvent(tripId, version, "COVER"));
            }
        });
    }

    private void reconcileSafely() {
        reconciliationLock.lock();
        try {
            Instant now = Instant.now();
            TripCoverStorage.ReconcileBatch batch = storage.reconciliationBatch(now.minusSeconds(3600), 100);
            for (TripCoverStorage.StoredDirectory directory : batch.finalDirectories()) {
                ReconcileRow row = one("SELECT c.retired_at,t.deleted_at IS NOT NULL,(t.cover_id=c.id),"
                    + "EXISTS(SELECT 1 FROM trip_command_execution e WHERE e.trip_id=c.trip_id "
                    + "AND e.command_type='SET_TRIP_COVER_V1' AND e.expires_at>clock_timestamp() "
                    + "AND e.result_json#>>'{cover,cover_id}'=c.id::text) FROM trip_cover c "
                    + "JOIN trip t ON t.id=c.trip_id WHERE c.storage_key=?",
                    (rs, ignored) -> new ReconcileRow(rs.getObject(1, OffsetDateTime.class), rs.getBoolean(2),
                        rs.getBoolean(3), rs.getBoolean(4)), directory.storageKey());
                if (row == null) {
                    if (directory.modifiedAt().toInstant().isBefore(now.minusSeconds(3600))) {
                        storage.deleteFinal(directory.storageKey());
                    }
                    continue;
                }
                if (row.deletedTrip() || row.current() || row.unexpiredExecution()) {
                    try { storage.markCommitted(directory.storageKey()); } catch (RuntimeException ignored) { }
                    continue;
                }
                if (row.retiredAt() != null && row.retiredAt().toInstant().isBefore(now.minusSeconds(86400))) {
                    storage.markGcPending(directory.storageKey());
                    storage.deleteFinal(directory.storageKey());
                }
            }
        } catch (RuntimeException exception) {
            logger.warn("Bounded private cover reconciliation did not complete; it will be retried");
        } finally {
            reconciliationLock.unlock();
        }
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("Stored result is invalid", exception); }
    }

    private void requireInput(Execution execution, byte[] fingerprint) {
        if (!MessageDigest.isEqual(execution.inputHash(), fingerprint)) throw new TripCoverException(409,
            "REQUEST_ID_REUSED", "The request id was already used");
    }

    private static void requireVersion(long current, long expected) {
        if (current != expected) throw new TripCoverException(409, "PLAN_VERSION_CONFLICT",
            "The trip plan has changed", current);
    }

    private static long nextVersion(long current) {
        if (current == Long.MAX_VALUE) throw new TripCoverException(409, "PLAN_VERSION_LIMIT_REACHED",
            "The trip plan version cannot be incremented");
        return current + 1;
    }

    private static byte[] fingerprint(Object... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (Object value : values) {
                    byte[] encoded;
                    if (value instanceof byte[] raw) encoded = raw;
                    else if (value instanceof UUID uuid) encoded = ByteBuffer.allocate(16)
                        .putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
                    else if (value instanceof Long number) encoded = ByteBuffer.allocate(8).putLong(number).array();
                    else if (value instanceof Integer number) encoded = ByteBuffer.allocate(4).putInt(number).array();
                    else encoded = value.toString().getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static CoverView mapCover(ResultSet rs, UUID tripId) throws SQLException {
        UUID coverId = rs.getObject(1, UUID.class);
        return new CoverView(coverId, rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5),
            rs.getLong(6), rs.getInt(7), rs.getInt(8), filePath(tripId, coverId), thumbnailPath(tripId, coverId));
    }

    private static StoredCover mapStoredCover(ResultSet rs, int ignored) throws SQLException {
        return new StoredCover(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class), rs.getLong(4), rs.getLong(5));
    }

    private static String filePath(UUID tripId, UUID coverId) {
        return "/api/trips/" + tripId + "/covers/" + coverId + "/file";
    }
    private static String thumbnailPath(UUID tripId, UUID coverId) {
        return "/api/trips/" + tripId + "/covers/" + coverId + "/thumbnail";
    }

    private static Map<String, Object> uploadMap(UploadResult value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", value.result());
        result.put("cover", coverMap(value.cover()));
        result.put("plan_version", value.planVersion());
        return result;
    }

    static Map<String, Object> coverMap(CoverView value) {
        Map<String, Object> cover = new LinkedHashMap<>();
        cover.put("cover_id", value.coverId().toString());
        cover.put("media_type", value.mediaType());
        cover.put("byte_size", value.byteSize());
        cover.put("width", value.width());
        cover.put("height", value.height());
        cover.put("thumbnail_byte_size", value.thumbnailByteSize());
        cover.put("thumbnail_width", value.thumbnailWidth());
        cover.put("thumbnail_height", value.thumbnailHeight());
        cover.put("file_path", value.filePath());
        cover.put("thumbnail_path", value.thumbnailPath());
        return cover;
    }

    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... arguments) {
        List<T> rows = jdbc.query(sql, mapper, arguments);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static TripCoverException inaccessible() { return new TripCoverException(404,
        "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE", "The trip was not found or is not accessible"); }
    private static TripCoverException coverNotFound() { return new TripCoverException(404,
        "COVER_NOT_FOUND", "The cover was not found"); }

    private final class StageOwner {
        private TripCoverStorage.Stage stage;
        private UUID storageKey;
        private boolean committed;
        StageOwner(TripCoverStorage.Stage stage) { this.stage = stage; }
        TripCoverStorage.Stage stage() { return stage; }
        void moved(TripCoverStorage.Stage moved, UUID key) { stage = moved; storageKey = key; }
        void committed() { committed = true; }
        void rollback() {
            if (!committed && storageKey != null) storage.deleteFinal(storageKey);
        }
        void discardUnmoved() {
            if (stage.finalDirectory() == null) storage.discard(stage);
        }
    }

    private record AccessibleTrip(long planVersion, UUID coverId) {}
    private record Execution(byte[] inputHash, int responseStatus, String resultJson, OffsetDateTime expiresAt,
        boolean unexpired) {
        Execution withUnexpired(boolean value) {
            return new Execution(inputHash, responseStatus, resultJson, expiresAt, value);
        }
    }
    private record StoredCover(UUID coverId, UUID tripId, UUID storageKey, long displaySize, long thumbnailSize) {}
    private record ReconcileRow(OffsetDateTime retiredAt, boolean deletedTrip, boolean current,
        boolean unexpiredExecution) {}

    public record CoverView(@JsonProperty("cover_id") UUID coverId,
        @JsonProperty("media_type") String mediaType, @JsonProperty("byte_size") long byteSize,
        int width, int height, @JsonProperty("thumbnail_byte_size") long thumbnailByteSize,
        @JsonProperty("thumbnail_width") int thumbnailWidth,
        @JsonProperty("thumbnail_height") int thumbnailHeight,
        @JsonProperty("file_path") String filePath,
        @JsonProperty("thumbnail_path") String thumbnailPath) {}
    public record UploadResult(String result, CoverView cover, long planVersion, boolean created) {}
    public record RemoveResult(String result, long planVersion) {}
    public record FileResult(byte[] bytes) {}
}
