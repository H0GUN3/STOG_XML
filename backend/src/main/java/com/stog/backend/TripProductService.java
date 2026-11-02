package com.stog.backend;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TripProductService {
    static final String COMMAND = "CREATE_TRIP_V1";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TripCursorCodec cursors;

    public TripProductService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            TripCursorCodec cursors) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.cursors = cursors;
    }

    public CreateResult create(UUID principal, String name, String region, LocalDate start, LocalDate end,
            UUID requestId) {
        CreateInput input = new CreateInput(principal, name, region, start, end, requestId);
        try {
            return transactions.execute(status -> createInTransaction(input));
        } catch (DataIntegrityViolationException collision) {
            CreateResult winner = transactions.execute(status -> readWinner(input));
            if (winner != null) return winner;
            throw collision;
        } catch (TransientDataAccessException exception) {
            throw new TripProductException(503, "DATABASE_BUSY", "The request could not be completed");
        }
    }

    public TripPage trips(UUID principal, int requestedLimit, String cursorToken) {
        TripCursorCodec.TripCursor cursor = cursorToken == null ? null
            : cursors.decodeTrip(cursorToken, principal, requestedLimit);
        int limit = cursor == null ? requestedLimit : cursor.limit();
        LocalDate asOf = cursor == null ? LocalDate.now(ZoneOffset.UTC) : cursor.asOfDate();
        List<TripRow> rows = queryTrips(principal, asOf, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, limit));
        String next = null;
        if (hasMore) {
            TripRow last = rows.get(rows.size() - 1);
            int bucket = bucket(last.startDate(), last.endDate(), asOf);
            next = cursors.encodeTrip(principal, limit, asOf, bucket,
                sortDate(bucket, last.startDate(), last.endDate()), last.tripId());
        }
        return new TripPage(asOf, rows.stream().map(row -> item(row, asOf)).toList(), next);
    }

    public TripView detail(UUID principal, UUID tripId) {
        TripRow row = one("SELECT t.id,t.name,t.region_label,t.start_date,t.end_date,t.plan_version,tm.role,"
                + "(SELECT count(*) FROM trip_member members WHERE members.trip_id=t.id),"
                + "c.id,c.media_type,c.byte_size,c.width,c.height,c.thumbnail_byte_size,c.thumbnail_width,c.thumbnail_height "
                + "FROM trip t JOIN trip_member tm ON tm.trip_id=t.id AND tm.user_id=? "
                + "LEFT JOIN trip_cover c ON c.id=t.cover_id WHERE t.id=? AND t.deleted_at IS NULL",
            TripProductService::mapTrip, principal, tripId);
        if (row == null) throw inaccessible();
        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
        return view(row, asOf);
    }

    public BasketPage basket(UUID principal, UUID tripId, int requestedLimit, String cursorToken) {
        TripCursorCodec.BasketCursor cursor = cursorToken == null ? null
            : cursors.decodeBasket(cursorToken, principal, requestedLimit, tripId);
        int limit = cursor == null ? requestedLimit : cursor.limit();
        String sql = "SELECT t.plan_version,ti.id,ti.place_id,ti.created_at "
            + "FROM trip t JOIN trip_member tm ON tm.trip_id=t.id AND tm.user_id=? "
            + "LEFT JOIN trip_item ti ON ti.trip_id=t.id AND ti.schedule_date IS NULL AND ti.sort_order IS NULL "
            + (cursor == null ? "" : "AND (ti.created_at < ? OR (ti.created_at = ? AND ti.id < ?)) ")
            + "WHERE t.id=? AND t.deleted_at IS NULL ORDER BY ti.created_at DESC NULLS LAST,ti.id DESC NULLS LAST LIMIT ?";
        Object[] arguments = cursor == null
            ? new Object[] {principal, tripId, limit + 1}
            : new Object[] {principal, Timestamp.from(cursor.createdAt()), Timestamp.from(cursor.createdAt()),
                cursor.itemId(), tripId, limit + 1};
        List<BasketRow> rows = jdbc.query(sql, TripProductService::mapBasket, arguments);
        if (rows.isEmpty()) throw inaccessible();
        long currentVersion = rows.get(0).planVersion();
        if (cursor != null && cursor.planVersion() != currentVersion) {
            throw new TripProductException(409, "PLAN_VERSION_CONFLICT", "The trip plan has changed", currentVersion);
        }
        List<BasketItem> items = rows.stream().filter(row -> row.itemId() != null)
            .map(row -> new BasketItem(row.itemId(), row.placeId(), "BASKET", null, null, row.createdAt()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean hasMore = items.size() > limit;
        if (hasMore) items = new ArrayList<>(items.subList(0, limit));
        String next = null;
        if (hasMore) {
            BasketItem last = items.get(items.size() - 1);
            next = cursors.encodeBasket(principal, limit, tripId, currentVersion, last.createdAt(), last.tripItemId());
        }
        return new BasketPage(tripId, currentVersion, items, next);
    }

    private CreateResult createInTransaction(CreateInput input) {
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        CreateExecution existing = execution(input);
        if (existing != null && existing.unexpired()) return replay(existing, input);
        if (existing != null) {
            jdbc.update("DELETE FROM trip_create_execution WHERE principal_user_id=? AND command_type=? "
                    + "AND request_id=? AND expires_at<=clock_timestamp()",
                input.principal(), COMMAND, input.requestId());
        }
        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
        String status = status(input.start(), input.end(), asOf);
        UUID tripId = UUID.randomUUID();
        jdbc.update("INSERT INTO trip(id,start_date,end_date,plan_version,name,region_label) VALUES (?,?,?,?,?,?)",
            tripId, input.start(), input.end(), 0L, input.name(), input.region());
        jdbc.update("INSERT INTO trip_member(trip_id,user_id,role) VALUES (?,?,'ADMIN')", tripId, input.principal());
        jdbc.update("WITH sampled AS (SELECT clock_timestamp() completed_at) "
                + "INSERT INTO trip_create_execution(principal_user_id,command_type,request_id,input_name,"
                + "input_region_label,input_start_date,input_end_date,result_trip_id,result_name,result_region_label,"
                + "result_start_date,result_end_date,result_status,result_as_of_date,result_role,result_member_count,"
                + "result_plan_version,completed_at,expires_at) "
                + "SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ADMIN',1,0,completed_at,completed_at+INTERVAL '24 hours' FROM sampled",
            input.principal(), COMMAND, input.requestId(), input.name(), input.region(), input.start(), input.end(),
            tripId, input.name(), input.region(), input.start(), input.end(), status, asOf);
        return new CreateResult(new TripView(tripId, input.name(), input.region(), input.start(), input.end(), status,
            asOf, "ADMIN", 1L, 0L, null), true);
    }

    private CreateResult readWinner(CreateInput input) {
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        CreateExecution execution = execution(input);
        return execution == null || !execution.unexpired() ? null : replay(execution, input);
    }

    private CreateExecution execution(CreateInput input) {
        CreateExecution row = one("SELECT input_name,input_region_label,input_start_date,input_end_date,result_trip_id,"
                + "result_name,result_region_label,result_start_date,result_end_date,result_status,result_as_of_date,"
                + "result_role,result_member_count,result_plan_version,expires_at,invalidated_at FROM trip_create_execution "
                + "WHERE principal_user_id=? AND command_type=? AND request_id=? FOR UPDATE",
            TripProductService::mapExecution, input.principal(), COMMAND, input.requestId());
        if (row == null) return null;
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        return row.withUnexpired(now.isBefore(row.expiresAt()));
    }

    private CreateResult replay(CreateExecution execution, CreateInput input) {
        if (execution.invalidated()) {
            throw new TripProductException(409, "REQUEST_RESULT_NO_LONGER_AVAILABLE",
                "The previous request result is no longer available");
        }
        if (!execution.inputName().equals(input.name()) || !execution.inputRegion().equals(input.region())
                || !execution.inputStart().equals(input.start()) || !execution.inputEnd().equals(input.end())) {
            throw new TripProductException(409, "REQUEST_ID_REUSED", "The request id was already used");
        }
        return new CreateResult(execution.result(), false);
    }

    private List<TripRow> queryTrips(UUID principal, LocalDate asOf, TripCursorCodec.TripCursor cursor, int fetch) {
        String bucket = "CASE WHEN t.start_date<=? AND t.end_date>=? THEN 0 WHEN t.start_date>? THEN 1 ELSE 2 END";
        String sql = "WITH visible AS (SELECT t.id,t.name,t.region_label,t.start_date,t.end_date,t.plan_version,tm.role,"
            + "(SELECT count(*) FROM trip_member members WHERE members.trip_id=t.id) member_count," + bucket
            + " bucket,CASE WHEN t.start_date<=? AND t.end_date>=? THEN t.end_date WHEN t.start_date>? THEN t.start_date ELSE t.end_date END sort_date,"
            + "c.id cover_id,c.media_type,c.byte_size,c.width,c.height,c.thumbnail_byte_size,c.thumbnail_width,c.thumbnail_height "
            + "FROM trip t JOIN trip_member tm ON tm.trip_id=t.id AND tm.user_id=? LEFT JOIN trip_cover c ON c.id=t.cover_id "
            + "WHERE t.deleted_at IS NULL) SELECT id,name,region_label,start_date,end_date,plan_version,role,member_count,"
            + "cover_id,media_type,byte_size,width,height,thumbnail_byte_size,thumbnail_width,thumbnail_height FROM visible ";
        List<Object> args = new ArrayList<>(List.of(asOf, asOf, asOf, asOf, asOf, asOf, principal));
        if (cursor != null) {
            sql += "WHERE bucket>? OR (bucket=? AND ((bucket IN (0,1) AND (sort_date>? OR (sort_date=? AND id>?))) "
                + "OR (bucket=2 AND (sort_date<? OR (sort_date=? AND id>?))))) ";
            args.addAll(List.of(cursor.bucket(), cursor.bucket(), cursor.sortDate(), cursor.sortDate(), cursor.tripId(),
                cursor.sortDate(), cursor.sortDate(), cursor.tripId()));
        }
        sql += "ORDER BY bucket,CASE WHEN bucket IN (0,1) THEN sort_date END ASC,"
            + "CASE WHEN bucket=2 THEN sort_date END DESC,id ASC LIMIT ?";
        args.add(fetch);
        return jdbc.query(sql, TripProductService::mapTrip, args.toArray());
    }

    private static TripRow mapTrip(ResultSet rs, int ignored) throws SQLException {
        UUID tripId = rs.getObject(1, UUID.class);
        UUID coverId = rs.getObject(9, UUID.class);
        TripCoverService.CoverView cover = coverId == null ? null : new TripCoverService.CoverView(coverId,
            rs.getString(10), rs.getLong(11), rs.getInt(12), rs.getInt(13), rs.getLong(14), rs.getInt(15),
            rs.getInt(16), "/api/trips/" + tripId + "/covers/" + coverId + "/file",
            "/api/trips/" + tripId + "/covers/" + coverId + "/thumbnail");
        return new TripRow(tripId, rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
            rs.getObject(5, LocalDate.class), rs.getLong(6), rs.getString(7), rs.getLong(8), cover);
    }

    private static CreateExecution mapExecution(ResultSet rs, int ignored) throws SQLException {
        TripView result = new TripView(rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7),
            rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class), rs.getString(10),
            rs.getObject(11, LocalDate.class), rs.getString(12), rs.getLong(13), rs.getLong(14), null);
        return new CreateExecution(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate.class),
            rs.getObject(4, LocalDate.class), result, rs.getObject(15, OffsetDateTime.class),
            rs.getObject(16, OffsetDateTime.class) != null, false);
    }

    private static BasketRow mapBasket(ResultSet rs, int ignored) throws SQLException {
        Timestamp created = rs.getTimestamp(4);
        return new BasketRow(rs.getLong(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
            created == null ? null : created.toInstant());
    }

    private static TripItem item(TripRow row, LocalDate asOf) {
        return new TripItem(row.tripId(), row.name(), row.region(), row.startDate(), row.endDate(),
            status(row.startDate(), row.endDate(), asOf), row.role(), row.memberCount(), row.planVersion(), row.cover());
    }

    private static TripView view(TripRow row, LocalDate asOf) {
        return new TripView(row.tripId(), row.name(), row.region(), row.startDate(), row.endDate(),
            status(row.startDate(), row.endDate(), asOf), asOf, row.role(), row.memberCount(), row.planVersion(), row.cover());
    }

    static String status(LocalDate start, LocalDate end, LocalDate asOf) {
        if (start.isAfter(asOf)) return "UPCOMING";
        if (end.isBefore(asOf)) return "PAST";
        return "ONGOING";
    }

    private static int bucket(LocalDate start, LocalDate end, LocalDate asOf) {
        return switch (status(start, end, asOf)) { case "ONGOING" -> 0; case "UPCOMING" -> 1; default -> 2; };
    }

    private static LocalDate sortDate(int bucket, LocalDate start, LocalDate end) {
        return bucket == 1 ? start : end;
    }

    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... arguments) {
        List<T> rows = jdbc.query(sql, mapper, arguments);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static TripProductException inaccessible() {
        return new TripProductException(404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE",
            "The trip was not found or is not accessible");
    }

    private record CreateInput(UUID principal, String name, String region, LocalDate start, LocalDate end,
        UUID requestId) {}
    private record CreateExecution(String inputName, String inputRegion, LocalDate inputStart, LocalDate inputEnd,
        TripView result, OffsetDateTime expiresAt, boolean invalidated, boolean unexpired) {
        CreateExecution withUnexpired(boolean value) {
            return new CreateExecution(inputName, inputRegion, inputStart, inputEnd, result, expiresAt, invalidated, value);
        }
    }
    private record BasketRow(long planVersion, UUID itemId, UUID placeId, Instant createdAt) {}

    public record CreateResult(TripView trip, boolean created) {}
    public record TripView(UUID tripId, String name, String regionLabel, LocalDate startDate, LocalDate endDate,
        String status, LocalDate asOfDate, String role, long memberCount, long planVersion,
        TripCoverService.CoverView cover) {
        public TripView(UUID tripId, String name, String regionLabel, LocalDate startDate, LocalDate endDate,
                String status, LocalDate asOfDate, String role, long memberCount, long planVersion) {
            this(tripId, name, regionLabel, startDate, endDate, status, asOfDate, role, memberCount, planVersion, null);
        }
    }
    public record TripItem(UUID tripId, String name, String regionLabel, LocalDate startDate, LocalDate endDate,
        String status, String role, long memberCount, long planVersion, TripCoverService.CoverView cover) {
        public TripItem(UUID tripId, String name, String regionLabel, LocalDate startDate, LocalDate endDate,
                String status, String role, long memberCount, long planVersion) {
            this(tripId, name, regionLabel, startDate, endDate, status, role, memberCount, planVersion, null);
        }
    }
    public record TripPage(LocalDate asOfDate, List<TripItem> items, String nextCursor) {}
    public record BasketItem(UUID tripItemId, UUID placeId, String state, LocalDate scheduleDate, Long sortOrder,
        Instant createdAt) {}
    public record BasketPage(UUID tripId, long planVersion, List<BasketItem> items, String nextCursor) {}
    private record TripRow(UUID tripId, String name, String region, LocalDate startDate, LocalDate endDate,
        long planVersion, String role, long memberCount, TripCoverService.CoverView cover) {}
}
