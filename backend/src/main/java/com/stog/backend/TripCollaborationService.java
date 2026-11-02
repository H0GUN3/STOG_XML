package com.stog.backend;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TripCollaborationService {
    static final String UPDATE_TRIP = "UPDATE_TRIP_V1";
    static final String UPDATE_ROLE = "UPDATE_MEMBER_ROLE_V1";
    static final String REMOVE_MEMBER = "REMOVE_MEMBER_V1";
    static final String LEAVE_TRIP = "LEAVE_TRIP_V1";
    static final String ISSUE_INVITE = "ISSUE_TRIP_INVITE_V1";
    static final String REVOKE_INVITE = "REVOKE_TRIP_INVITE_V1";
    static final String JOIN_TRIP = "JOIN_TRIP_V1";
    static final String DELETE_TRIP = "DELETE_TRIP_V1";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TripCursorCodec cursors;
    private final TripPlanEventPublisher events;
    private final ObjectMapper json;

    public TripCollaborationService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            TripCursorCodec cursors, TripPlanEventPublisher events, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.cursors = cursors;
        this.events = events;
        this.json = json;
    }

    public TripView updateTrip(UUID principal, UUID tripId, String name, String region, LocalDate start,
            LocalDate end, long expectedVersion, UUID requestId) {
        byte[] input = fingerprint(tripId, name, region, start, end, expectedVersion);
        return execute(() -> transactions.execute(status -> {
            Trip trip = lockAccessibleTrip(principal, tripId);
            Execution prior = execution(principal, tripId, UPDATE_TRIP, requestId);
            if (prior != null && prior.unexpired()) return tripViewReplay(prior, input);
            expire(prior, principal, tripId, UPDATE_TRIP, requestId);
            requireVersion(trip, expectedVersion);
            long outside = jdbc.queryForObject("SELECT count(*) FROM trip_item WHERE trip_id=? AND schedule_date IS NOT NULL "
                + "AND (schedule_date<? OR schedule_date>?)", Long.class, tripId, start, end);
            if (outside > 0) throw new TripCollaborationException(409, "TRIP_DATE_RANGE_CONFLICT",
                "Scheduled items are outside the requested date range", null, outside, null);
            boolean changed = !name.equals(trip.name()) || !region.equals(trip.region())
                || !start.equals(trip.start()) || !end.equals(trip.end());
            long version = changed ? nextVersion(trip.planVersion()) : trip.planVersion();
            if (changed) jdbc.update("UPDATE trip SET name=?,region_label=?,start_date=?,end_date=?,plan_version=? WHERE id=?",
                name, region, start, end, version, tripId);
            LocalDate asOf = jdbc.queryForObject("SELECT clock_timestamp()::date", LocalDate.class);
            long members = memberCount(tripId);
            TripView result = new TripView(tripId, name, region, start, end,
                TripProductService.status(start, end, asOf), asOf, trip.role(), members, version,
                currentCover(tripId, trip.coverId()));
            insertExecution(principal, tripId, UPDATE_TRIP, requestId, input, 200, tripViewMap(result));
            if (changed) afterCommit(tripId, version, "TRIP_METADATA");
            return result;
        }));
    }

    public MemberPage members(UUID principal, UUID tripId, int requestedLimit, String cursorToken) {
        return execute(() -> transactions.execute(status -> {
            TripCursorCodec.MemberCursor cursor = cursorToken == null ? null
                : cursors.decodeMember(cursorToken, principal, requestedLimit, tripId);
            int limit = cursor == null ? requestedLimit : cursor.limit();
            Trip trip = lockAccessibleTrip(principal, tripId);
            if (cursor != null && cursor.planVersion() != trip.planVersion()) {
                throw versionConflict(trip.planVersion());
            }
            int bucket = cursor == null ? -1 : cursor.roleBucket();
            UUID last = cursor == null ? null : cursor.memberUserId();
            String sql = "SELECT user_id,role,member_label FROM trip_member WHERE trip_id=? "
                + (cursor == null ? "" : "AND ((CASE role WHEN 'ADMIN' THEN 0 ELSE 1 END)>? OR "
                    + "((CASE role WHEN 'ADMIN' THEN 0 ELSE 1 END)=? AND user_id>?)) ")
                + "ORDER BY CASE role WHEN 'ADMIN' THEN 0 ELSE 1 END,user_id LIMIT ?";
            Object[] args = cursor == null ? new Object[] {tripId, limit + 1}
                : new Object[] {tripId, bucket, bucket, last, limit + 1};
            List<MemberView> rows = jdbc.query(sql, (rs, ignored) -> new MemberView(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                principal.equals(rs.getObject(1, UUID.class))), args);
            boolean more = rows.size() > limit;
            if (more) rows = new ArrayList<>(rows.subList(0, limit));
            String next = null;
            if (more) {
                MemberView tail = rows.get(rows.size() - 1);
                next = cursors.encodeMember(principal, limit, tripId, trip.planVersion(),
                    "ADMIN".equals(tail.role()) ? 0 : 1, tail.memberUserId());
            }
            return new MemberPage(tripId, trip.planVersion(), memberCount(tripId), rows, next);
        }));
    }

    public RoleResult updateRole(UUID principal, UUID tripId, UUID target, String role, long expectedVersion,
            UUID requestId) {
        byte[] input = fingerprint(tripId, target, role, expectedVersion);
        return execute(() -> transactions.execute(status -> {
            Trip trip = lockAccessibleTrip(principal, tripId);
            requireAdmin(trip);
            Execution prior = execution(principal, tripId, UPDATE_ROLE, requestId);
            if (prior != null && prior.unexpired()) return roleReplay(prior, input);
            expire(prior, principal, tripId, UPDATE_ROLE, requestId);
            Member targetMember = lockMember(tripId, target);
            if (targetMember == null) throw memberNotFound();
            requireVersion(trip, expectedVersion);
            if (targetMember.role().equals(role)) {
                RoleResult result = new RoleResult("UNCHANGED", target, role, trip.planVersion());
                insertExecution(principal, tripId, UPDATE_ROLE, requestId, input, 200, roleMap(result));
                return result;
            }
            lockAdmins(tripId);
            if ("ADMIN".equals(targetMember.role()) && "MEMBER".equals(role) && adminCount(tripId) == 1) {
                throw lastAdmin();
            }
            long version = nextVersion(trip.planVersion());
            jdbc.update("UPDATE trip_member SET role=? WHERE trip_id=? AND user_id=?", role, tripId, target);
            jdbc.update("UPDATE trip SET plan_version=? WHERE id=?", version, tripId);
            RoleResult result = new RoleResult("UPDATED", target, role, version);
            insertExecution(principal, tripId, UPDATE_ROLE, requestId, input, 200, roleMap(result));
            afterCommit(tripId, version, "MEMBERS");
            return result;
        }));
    }

    public void removeMember(UUID principal, UUID tripId, UUID target, long expectedVersion, UUID requestId) {
        byte[] input = fingerprint(tripId, target, expectedVersion);
        execute(() -> {
            transactions.executeWithoutResult(status -> {
            Trip trip = lockAccessibleTrip(principal, tripId);
            requireAdmin(trip);
            if (principal.equals(target)) throw new TripCollaborationException(409,
                "ADMIN_SELF_REMOVAL_FORBIDDEN", "An administrator cannot remove themselves");
            Execution prior = execution(principal, tripId, REMOVE_MEMBER, requestId);
            if (prior != null && prior.unexpired()) { requireInput(prior, input); return; }
            expire(prior, principal, tripId, REMOVE_MEMBER, requestId);
            Member targetMember = lockMember(tripId, target);
            if (targetMember == null) throw memberNotFound();
            requireVersion(trip, expectedVersion);
            lockAdmins(tripId);
            if ("ADMIN".equals(targetMember.role()) && adminCount(tripId) == 1) throw lastAdmin();
            long version = nextVersion(trip.planVersion());
            jdbc.update("DELETE FROM trip_member WHERE trip_id=? AND user_id=?", tripId, target);
            jdbc.update("UPDATE trip SET plan_version=? WHERE id=?", version, tripId);
            insertExecution(principal, tripId, REMOVE_MEMBER, requestId, input, 204, Map.of());
            afterCommit(tripId, version, "MEMBERS");
            });
            return null;
        });
    }

    public void leave(UUID principal, UUID tripId, long expectedVersion, UUID requestId) {
        byte[] input = fingerprint(tripId, expectedVersion);
        execute(() -> {
            transactions.executeWithoutResult(status -> {
            Execution early = execution(principal, tripId, LEAVE_TRIP, requestId);
            if (early != null && early.unexpired()) { requireInput(early, input); return; }
            Trip trip = lockAccessibleTrip(principal, tripId);
            if ("ADMIN".equals(trip.role())) throw new TripCollaborationException(403,
                "ADMIN_CANNOT_LEAVE", "An administrator cannot leave the trip");
            expire(early, principal, tripId, LEAVE_TRIP, requestId);
            requireVersion(trip, expectedVersion);
            long version = nextVersion(trip.planVersion());
            jdbc.update("DELETE FROM trip_member WHERE trip_id=? AND user_id=?", tripId, principal);
            jdbc.update("UPDATE trip SET plan_version=? WHERE id=?", version, tripId);
            insertExecution(principal, tripId, LEAVE_TRIP, requestId, input, 204, Map.of());
            afterCommit(tripId, version, "MEMBERS");
            });
            return null;
        });
    }

    public InviteResult issue(UUID principal, UUID tripId, byte[] tokenHash, String token, UUID requestId) {
        byte[] input = fingerprint(tripId, tokenHash);
        try {
            return execute(() -> transactions.execute(status -> {
                Trip trip = lockAccessibleTrip(principal, tripId);
                requireAdmin(trip);
                Execution prior = execution(principal, tripId, ISSUE_INVITE, requestId);
                if (prior != null && prior.unexpired()) {
                    requireInput(prior, input);
                    JsonNode result = parse(prior.resultJson());
                    return new InviteResult("ISSUED", uri(token), OffsetDateTime.parse(result.get("expires_at").asText()), false);
                }
                expire(prior, principal, tripId, ISSUE_INVITE, requestId);
                rateLimitIssue(tripId);
                jdbc.update("UPDATE trip_invitation SET revoked_at=clock_timestamp() WHERE trip_id=? AND revoked_at IS NULL", tripId);
                UUID invitationId = UUID.randomUUID();
                Map<String, Object> times = jdbc.queryForMap("SELECT clock_timestamp() issued_at,clock_timestamp()+INTERVAL '10 minutes' expires_at");
                OffsetDateTime issued = offset(times.get("issued_at"));
                OffsetDateTime expires = issued.plusMinutes(10);
                jdbc.update("INSERT INTO trip_invitation(id,trip_id,token_hash,created_by_user_id,issued_at,expires_at) VALUES (?,?,?,?,?,?)",
                    invitationId, tripId, tokenHash, principal, issued, expires);
                insertExecution(principal, tripId, ISSUE_INVITE, requestId, input, 201,
                    Map.of("result", "ISSUED", "expires_at", expires.toInstant().toString()));
                return new InviteResult("ISSUED", uri(token), expires, true);
            }));
        } catch (DataIntegrityViolationException collision) {
            Boolean tokenExists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM trip_invitation WHERE token_hash=?)",
                Boolean.class, tokenHash);
            if (Boolean.TRUE.equals(tokenExists)) {
                throw new TripCollaborationException(409, "INVITATION_TOKEN_UNAVAILABLE",
                    "The invitation token is unavailable");
            }
            throw collision;
        }
    }

    public SimpleResult revoke(UUID principal, UUID tripId, UUID requestId) {
        byte[] input = fingerprint(tripId);
        return execute(() -> transactions.execute(status -> {
            Trip trip = lockAccessibleTrip(principal, tripId);
            requireAdmin(trip);
            Execution prior = execution(principal, tripId, REVOKE_INVITE, requestId);
            if (prior != null && prior.unexpired()) return simpleReplay(prior, input);
            expire(prior, principal, tripId, REVOKE_INVITE, requestId);
            int changed = jdbc.update("UPDATE trip_invitation SET revoked_at=clock_timestamp() WHERE trip_id=? AND revoked_at IS NULL", tripId);
            SimpleResult result = new SimpleResult(changed == 0 ? "UNCHANGED" : "REVOKED");
            insertExecution(principal, tripId, REVOKE_INVITE, requestId, input, 200, Map.of("result", result.result()));
            return result;
        }));
    }

    public JoinResult join(UUID principal, byte[] tokenHash, String label, UUID requestId) {
        byte[] input = fingerprint(tokenHash, label);
        JoinResult outcome = execute(() -> transactions.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            UUID tripId = one("SELECT trip_id FROM trip_invitation WHERE token_hash=?",
                (rs, ignored) -> rs.getObject(1, UUID.class), tokenHash);
            if (tripId == null) {
                lockJoinRateKeys(principal, tokenHash);
                rateLimitJoin(principal, tokenHash);
                recordAttempt(principal, tokenHash, false);
                return null;
            }
            TripRow trip = lockTrip(tripId);
            Invitation invitation = one("SELECT revoked_at,expires_at FROM trip_invitation WHERE token_hash=? AND trip_id=? FOR UPDATE",
                (rs, ignored) -> new Invitation(rs.getObject(1, OffsetDateTime.class), rs.getObject(2, OffsetDateTime.class)),
                tokenHash, tripId);
            jdbc.queryForObject("SELECT id FROM stog_user WHERE id=? FOR UPDATE", UUID.class, principal);
            if (trip == null || trip.deletedAt() != null || invitation == null) {
                lockJoinRateKeys(principal, tokenHash);
                rateLimitJoin(principal, tokenHash);
                recordAttempt(principal, tokenHash, false);
                return null;
            }
            Member current = lockMember(tripId, principal);
            Execution prior = execution(principal, tripId, JOIN_TRIP, requestId);
            if (prior != null && prior.unexpired()) {
                if (current == null) throw inaccessible();
                return joinReplay(prior, input);
            }
            OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            if (invitation.revokedAt() != null || !now.isBefore(invitation.expiresAt())) {
                lockJoinRateKeys(principal, tokenHash);
                rateLimitJoin(principal, tokenHash);
                recordAttempt(principal, tokenHash, false);
                return null;
            }
            if (current != null) {
                JoinResult result = new JoinResult("ALREADY_MEMBER", tripId, current.role(), current.label(), trip.planVersion(), false);
                insertExecution(principal, tripId, JOIN_TRIP, requestId, input, 200, joinMap(result));
                return result;
            }
            lockJoinRateKeys(principal, tokenHash);
            rateLimitJoin(principal, tokenHash);
            long version = nextVersion(trip.planVersion());
            jdbc.update("INSERT INTO trip_member(trip_id,user_id,role,member_label) VALUES (?,?,'MEMBER',?)", tripId, principal, label);
            jdbc.update("UPDATE trip SET plan_version=? WHERE id=?", version, tripId);
            JoinResult result = new JoinResult("JOINED", tripId, "MEMBER", label, version, true);
            insertExecution(principal, tripId, JOIN_TRIP, requestId, input, 201, joinMap(result));
            recordAttempt(principal, tokenHash, true);
            afterCommit(tripId, version, "MEMBERS");
            return result;
        }));
        if (outcome == null) throw unusableInvitation();
        return outcome;
    }

    public void deleteTrip(UUID principal, UUID tripId, long expectedVersion, UUID requestId) {
        byte[] input = fingerprint(tripId, expectedVersion);
        execute(() -> {
            transactions.executeWithoutResult(status -> {
            Execution early = execution(principal, tripId, DELETE_TRIP, requestId);
            if (early != null && early.unexpired()) { requireInput(early, input); return; }
            Trip trip = lockAccessibleTrip(principal, tripId);
            requireAdmin(trip);
            expire(early, principal, tripId, DELETE_TRIP, requestId);
            requireVersion(trip, expectedVersion);
            long version = nextVersion(trip.planVersion());
            List<UUID> recipients = jdbc.query("SELECT user_id FROM trip_member WHERE trip_id=? ORDER BY user_id",
                (rs, ignored) -> rs.getObject(1, UUID.class), tripId);
            jdbc.update("UPDATE trip SET deleted_at=clock_timestamp(),deleted_by_user_id=?,plan_version=? WHERE id=?",
                principal, version, tripId);
            jdbc.update("UPDATE trip_invitation SET revoked_at=clock_timestamp() WHERE trip_id=? AND revoked_at IS NULL", tripId);
            jdbc.update("UPDATE trip_create_execution SET invalidated_at=clock_timestamp() WHERE result_trip_id=? "
                + "AND invalidated_at IS NULL AND expires_at>clock_timestamp()", tripId);
            insertExecution(principal, tripId, DELETE_TRIP, requestId, input, 204, Map.of());
            afterCommit(tripId, version, "TRIP_DELETED", recipients);
            });
            return null;
        });
    }

    private Trip lockAccessibleTrip(UUID principal, UUID tripId) {
        jdbc.execute("SET LOCAL lock_timeout='5s'");
        TripRow row = lockTrip(tripId);
        if (row == null || row.deletedAt() != null) throw inaccessible();
        Member member = lockMember(tripId, principal);
        if (member == null) throw inaccessible();
        return new Trip(row.id(), row.name(), row.region(), row.start(), row.end(), row.planVersion(), member.role(),
            row.coverId());
    }

    private TripRow lockTrip(UUID tripId) {
        return one("SELECT id,name,region_label,start_date,end_date,plan_version,deleted_at,cover_id FROM trip WHERE id=? FOR UPDATE",
            (rs, ignored) -> new TripRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getLong(6),
                rs.getObject(7, OffsetDateTime.class), rs.getObject(8, UUID.class)), tripId);
    }

    private Member lockMember(UUID tripId, UUID userId) {
        return one("SELECT role,member_label FROM trip_member WHERE trip_id=? AND user_id=? FOR UPDATE",
            (rs, ignored) -> new Member(rs.getString(1), rs.getString(2)), tripId, userId);
    }

    private void lockAdmins(UUID tripId) {
        jdbc.query("SELECT user_id FROM trip_member WHERE trip_id=? AND role='ADMIN' ORDER BY user_id FOR UPDATE",
            (rs, ignored) -> rs.getObject(1, UUID.class), tripId);
    }

    private long adminCount(UUID tripId) {
        return jdbc.queryForObject("SELECT count(*) FROM trip_member WHERE trip_id=? AND role='ADMIN'", Long.class, tripId);
    }

    private long memberCount(UUID tripId) {
        return jdbc.queryForObject("SELECT count(*) FROM trip_member WHERE trip_id=?", Long.class, tripId);
    }

    private void requireAdmin(Trip trip) {
        if (!"ADMIN".equals(trip.role())) throw new TripCollaborationException(403, "ADMIN_REQUIRED",
            "Administrator permission is required");
    }

    private void requireVersion(Trip trip, long expected) {
        if (trip.planVersion() != expected) throw versionConflict(trip.planVersion());
    }

    private long nextVersion(long current) {
        if (current == Long.MAX_VALUE) throw new TripCollaborationException(409, "PLAN_VERSION_LIMIT_REACHED",
            "The trip plan version cannot be incremented");
        return current + 1;
    }

    private Execution execution(UUID principal, UUID tripId, String command, UUID requestId) {
        Execution row = one("SELECT input_sha256,response_status,result_json::text,expires_at FROM trip_command_execution "
            + "WHERE principal_user_id=? AND trip_id=? AND command_type=? AND request_id=? FOR UPDATE",
            (rs, ignored) -> new Execution(rs.getBytes(1), rs.getInt(2), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class), false), principal, tripId, command, requestId);
        if (row == null) return null;
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        return row.withUnexpired(now.isBefore(row.expiresAt()));
    }

    private void expire(Execution prior, UUID principal, UUID tripId, String command, UUID requestId) {
        if (prior != null && !prior.unexpired()) jdbc.update("DELETE FROM trip_command_execution WHERE principal_user_id=? "
            + "AND trip_id=? AND command_type=? AND request_id=? AND expires_at<=clock_timestamp()",
            principal, tripId, command, requestId);
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

    private TripView tripViewReplay(Execution execution, byte[] input) {
        requireInput(execution, input);
        JsonNode value = parse(execution.resultJson());
        JsonNode cover = value.get("cover");
        return new TripView(UUID.fromString(value.get("trip_id").asText()), value.get("name").asText(),
            value.get("region_label").asText(), LocalDate.parse(value.get("start_date").asText()),
            LocalDate.parse(value.get("end_date").asText()), value.get("status").asText(),
            LocalDate.parse(value.get("as_of_date").asText()), value.get("role").asText(),
            value.get("member_count").asLong(), value.get("plan_version").asLong(),
            cover == null || cover.isNull() ? null : coverFromJson(cover));
    }

    private RoleResult roleReplay(Execution execution, byte[] input) {
        requireInput(execution, input);
        JsonNode value = parse(execution.resultJson());
        return new RoleResult(value.get("result").asText(), UUID.fromString(value.get("member_user_id").asText()),
            value.get("role").asText(), value.get("plan_version").asLong());
    }

    private SimpleResult simpleReplay(Execution execution, byte[] input) {
        requireInput(execution, input);
        return new SimpleResult(parse(execution.resultJson()).get("result").asText());
    }

    private JoinResult joinReplay(Execution execution, byte[] input) {
        requireInput(execution, input);
        JsonNode value = parse(execution.resultJson());
        JsonNode label = value.get("member_label");
        return new JoinResult(value.get("result").asText(), UUID.fromString(value.get("trip_id").asText()),
            value.get("role").asText(), label.isNull() ? null : label.asText(), value.get("plan_version").asLong(), false);
    }

    private void requireInput(Execution execution, byte[] input) {
        if (!MessageDigest.isEqual(execution.inputHash(), input)) throw new TripCollaborationException(409,
            "REQUEST_ID_REUSED", "The request id was already used");
    }

    private JsonNode parse(String source) {
        try { return json.readTree(source); }
        catch (Exception exception) { throw new IllegalStateException("Stored result is invalid", exception); }
    }

    private void rateLimitIssue(UUID tripId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM trip_invitation WHERE trip_id=? AND issued_at>clock_timestamp()-INTERVAL '1 hour'",
            Long.class, tripId);
        if (count < 5) return;
        OffsetDateTime oldest = jdbc.queryForObject("SELECT min(issued_at) FROM trip_invitation WHERE trip_id=? "
            + "AND issued_at>clock_timestamp()-INTERVAL '1 hour'", OffsetDateTime.class, tripId);
        throw rateLimited(secondsUntil(oldest.plusHours(1), 3600));
    }

    private void lockJoinRateKeys(UUID principal, byte[] hash) {
        long key = ByteBuffer.wrap(hash, 0, 8).getLong();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, key);
        jdbc.queryForObject("SELECT id FROM stog_user WHERE id=? FOR UPDATE", UUID.class, principal);
        jdbc.update("DELETE FROM trip_invitation_attempt WHERE id IN (SELECT id FROM trip_invitation_attempt "
            + "WHERE attempted_at<clock_timestamp()-INTERVAL '1 hour' ORDER BY id LIMIT 1000)");
    }

    private void rateLimitJoin(UUID principal, byte[] hash) {
        Long principalCount = jdbc.queryForObject("SELECT count(*) FROM trip_invitation_attempt WHERE principal_user_id=? "
            + "AND attempted_at>clock_timestamp()-INTERVAL '10 minutes'", Long.class, principal);
        Long tokenCount = jdbc.queryForObject("SELECT count(*) FROM trip_invitation_attempt WHERE token_hash=? "
            + "AND attempted_at>clock_timestamp()-INTERVAL '10 minutes'", Long.class, hash);
        if (principalCount < 10 && tokenCount < 50) return;
        OffsetDateTime oldest = principalCount >= 10
            ? jdbc.queryForObject("SELECT min(attempted_at) FROM trip_invitation_attempt WHERE principal_user_id=? "
                + "AND attempted_at>clock_timestamp()-INTERVAL '10 minutes'", OffsetDateTime.class, principal)
            : jdbc.queryForObject("SELECT min(attempted_at) FROM trip_invitation_attempt WHERE token_hash=? "
                + "AND attempted_at>clock_timestamp()-INTERVAL '10 minutes'", OffsetDateTime.class, hash);
        throw rateLimited(secondsUntil(oldest.plusMinutes(10), 600));
    }

    private long secondsUntil(OffsetDateTime end, long max) {
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        long millis = ChronoUnit.MILLIS.between(now, end);
        return Math.max(1, Math.min(max, (millis + 999) / 1000));
    }

    private void recordAttempt(UUID principal, byte[] hash, boolean success) {
        jdbc.update("INSERT INTO trip_invitation_attempt(principal_user_id,token_hash,attempted_at,succeeded) "
            + "VALUES (?,?,clock_timestamp(),?)", principal, hash, success);
    }

    private void afterCommit(UUID tripId, long version, String scope) {
        afterCommit(tripId, version, scope, List.of());
    }

    private void afterCommit(UUID tripId, long version, String scope, List<UUID> recipients) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                events.publishAfterCommit(new TripPlanChangedEvent(tripId, version, scope, recipients));
            }
        });
    }

    private <T> T execute(Action<T> action) {
        try { return action.run(); }
        catch (TripCollaborationException exception) { throw exception; }
        catch (TransientDataAccessException exception) {
            throw new TripCollaborationException(503, "DATABASE_BUSY", "The request could not be completed");
        }
    }

    private static byte[] fingerprint(Object... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                for (Object value : values) {
                    byte[] encoded;
                    if (value instanceof byte[] raw) encoded = raw;
                    else if (value instanceof UUID uuid) encoded = ByteBuffer.allocate(16)
                        .putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
                    else if (value instanceof Long number) encoded = ByteBuffer.allocate(8).putLong(number).array();
                    else encoded = value.toString().getBytes(StandardCharsets.UTF_8);
                    out.writeInt(encoded.length);
                    out.write(encoded);
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static byte[] tokenHash(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private static String uri(String token) { return "stog://trip-invitations/" + token; }
    private static OffsetDateTime offset(Object value) {
        if (value instanceof OffsetDateTime offset) return offset;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected database timestamp");
    }
    private static Map<String, Object> tripViewMap(TripView value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trip_id", value.tripId().toString()); result.put("name", value.name());
        result.put("region_label", value.regionLabel()); result.put("start_date", value.startDate().toString());
        result.put("end_date", value.endDate().toString()); result.put("status", value.status());
        result.put("as_of_date", value.asOfDate().toString()); result.put("role", value.role());
        result.put("member_count", value.memberCount()); result.put("plan_version", value.planVersion());
        result.put("cover", value.cover() == null ? null : TripCoverService.coverMap(value.cover()));
        return result;
    }
    private static Map<String, Object> roleMap(RoleResult value) {
        return Map.of("result", value.result(), "member_user_id", value.memberUserId().toString(),
            "role", value.role(), "plan_version", value.planVersion());
    }
    private static Map<String, Object> joinMap(JoinResult value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", value.result()); result.put("trip_id", value.tripId().toString());
        result.put("role", value.role()); result.put("member_label", value.memberLabel());
        result.put("plan_version", value.planVersion()); return result;
    }
    private static TripCollaborationException inaccessible() { return new TripCollaborationException(404,
        "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE", "The trip was not found or is not accessible"); }
    private static TripCollaborationException memberNotFound() { return new TripCollaborationException(404,
        "MEMBER_NOT_FOUND", "The member was not found"); }
    private static TripCollaborationException unusableInvitation() { return new TripCollaborationException(404,
        "INVITATION_NOT_FOUND_OR_NOT_USABLE", "The invitation was not found or is not usable"); }
    private static TripCollaborationException lastAdmin() { return new TripCollaborationException(409,
        "LAST_ADMIN_REQUIRED", "At least one administrator is required"); }
    private static TripCollaborationException versionConflict(long version) { return new TripCollaborationException(409,
        "PLAN_VERSION_CONFLICT", "The trip plan has changed", version, null, null); }
    private static TripCollaborationException rateLimited(long seconds) { return new TripCollaborationException(429,
        "INVITATION_RATE_LIMITED", "Invitation requests are temporarily limited", null, null, seconds); }

    private TripCoverService.CoverView currentCover(UUID tripId, UUID coverId) {
        if (coverId == null) return null;
        return one("SELECT id,media_type,byte_size,width,height,thumbnail_byte_size,thumbnail_width,thumbnail_height "
            + "FROM trip_cover WHERE id=? AND trip_id=?", (rs, ignored) -> TripCoverService.mapCover(rs, tripId),
            coverId, tripId);
    }

    private static TripCoverService.CoverView coverFromJson(JsonNode cover) {
        return new TripCoverService.CoverView(UUID.fromString(cover.get("cover_id").asText()),
            cover.get("media_type").asText(), cover.get("byte_size").asLong(), cover.get("width").asInt(),
            cover.get("height").asInt(), cover.get("thumbnail_byte_size").asLong(),
            cover.get("thumbnail_width").asInt(), cover.get("thumbnail_height").asInt(),
            cover.get("file_path").asText(), cover.get("thumbnail_path").asText());
    }

    @FunctionalInterface private interface Action<T> { T run(); }
    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        List<T> rows = jdbc.query(sql, mapper, args); return rows.isEmpty() ? null : rows.get(0);
    }
    private record Trip(UUID id, String name, String region, LocalDate start, LocalDate end, long planVersion,
        String role, UUID coverId) {}
    private record TripRow(UUID id, String name, String region, LocalDate start, LocalDate end, long planVersion,
        OffsetDateTime deletedAt, UUID coverId) {}
    private record Member(String role, String label) {}
    private record Invitation(OffsetDateTime revokedAt, OffsetDateTime expiresAt) {}
    private record Execution(byte[] inputHash, int responseStatus, String resultJson, OffsetDateTime expiresAt,
        boolean unexpired) { Execution withUnexpired(boolean value) { return new Execution(inputHash, responseStatus,
            resultJson, expiresAt, value); } }

    public record TripView(UUID tripId, String name, String regionLabel, LocalDate startDate, LocalDate endDate,
        String status, LocalDate asOfDate, String role, long memberCount, long planVersion,
        TripCoverService.CoverView cover) {
        public TripView(UUID tripId, String name, String regionLabel, LocalDate startDate, LocalDate endDate,
                String status, LocalDate asOfDate, String role, long memberCount, long planVersion) {
            this(tripId, name, regionLabel, startDate, endDate, status, asOfDate, role, memberCount, planVersion, null);
        }
    }
    public record MemberView(UUID memberUserId, String role, String memberLabel, boolean self) {}
    public record MemberPage(UUID tripId, long planVersion, long memberCount, List<MemberView> items, String nextCursor) {}
    public record RoleResult(String result, UUID memberUserId, String role, long planVersion) {}
    public record InviteResult(String result, String inviteUri, OffsetDateTime expiresAt, boolean created) {}
    public record SimpleResult(String result) {}
    public record JoinResult(String result, UUID tripId, String role, String memberLabel, long planVersion, boolean joined) {}
}
