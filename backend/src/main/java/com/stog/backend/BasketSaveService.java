package com.stog.backend;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BasketSaveService {
    static final String COMMAND = "CREATE_TRIP_ITEM_BASKET_V1";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TripPlanEventPublisher eventPublisher;

    public BasketSaveService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            TripPlanEventPublisher eventPublisher) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.eventPublisher = eventPublisher;
    }

    public CurrentItem currentItem(UUID principalUserId, UUID tripId, UUID tripItemId) {
        CurrentItemRow row = one("SELECT t.plan_version, ti.id, ti.place_id, ti.schedule_date, ti.sort_order "
                + "FROM trip t JOIN trip_member tm ON tm.trip_id = t.id AND tm.user_id = ? "
                + "LEFT JOIN trip_item ti ON ti.trip_id = t.id AND ti.id = ? WHERE t.id = ? AND t.deleted_at IS NULL",
            (rs, ignored) -> new CurrentItemRow(rs.getLong(1), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class), rs.getObject(5, Long.class)),
            principalUserId, tripItemId, tripId);
        if (row == null) {
            throw inaccessible();
        }
        if (row.tripItemId() == null) {
            throw new BasketSaveException(404, "TRIP_ITEM_NOT_FOUND", "The trip item was not found");
        }
        String state = row.scheduleDate() == null ? "BASKET" : "SCHEDULED";
        return new CurrentItem(row.tripItemId(), row.placeId(), state, row.scheduleDate(), row.sortOrder(),
            row.planVersion());
    }

    public Result save(UUID principalUserId, UUID tripId, UUID placeId, long expectedPlanVersion,
            UUID requestId) {
        Input input = new Input(principalUserId, tripId, placeId, expectedPlanVersion, requestId);
        try {
            return transactions.execute(status -> process(input));
        } catch (DataIntegrityViolationException collision) {
            Result winner = transactions.execute(status -> processWinnerReadback(input));
            if (winner != null) {
                return winner;
            }
            throw collision;
        } catch (TransientDataAccessException operationalFailure) {
            throw new BasketSaveException(503, "DATABASE_BUSY", "The request could not be completed");
        }
    }

    private Result process(Input input) {
        setLocalLockTimeout();
        Trip trip = one("SELECT plan_version, start_date, end_date FROM trip WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
            (rs, row) -> new Trip(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class)),
            input.tripId());
        if (trip == null || !memberExists(input.tripId(), input.principalUserId())) {
            throw inaccessible();
        }

        Execution execution = execution(input);
        if (execution != null && execution.unexpired()) {
            return replay(execution, input);
        }
        if (execution != null) {
            jdbc.update("DELETE FROM request_execution WHERE principal_user_id = ? AND command_type = ? "
                    + "AND request_id = ? AND expires_at <= clock_timestamp()",
                input.principalUserId(), COMMAND, input.requestId());
        }

        LocalDate databaseToday = jdbc.queryForObject("SELECT clock_timestamp()::date", LocalDate.class);
        if (trip.endDate().isBefore(databaseToday)) {
            throw new BasketSaveException(409, "TRIP_NOT_ELIGIBLE", "The trip can no longer be changed");
        }
        if (!exists("SELECT 1 FROM place WHERE id = ? FOR KEY SHARE", input.placeId())) {
            throw new BasketSaveException(404, "PLACE_NOT_FOUND", "The place was not found");
        }
        if (trip.planVersion() != input.expectedPlanVersion()) {
            throw new BasketSaveException(409, "PLAN_VERSION_CONFLICT", "The trip plan has changed",
                trip.planVersion());
        }

        UUID existingItemId = one("SELECT id FROM trip_item WHERE trip_id = ? AND place_id = ? "
                + "AND schedule_date IS NULL AND sort_order IS NULL",
            (rs, row) -> rs.getObject(1, UUID.class), input.tripId(), input.placeId());
        if (existingItemId != null) {
            Result result = new Result("ALREADY_EXISTS", existingItemId, input.placeId(), trip.planVersion());
            insertExecution(input, result);
            return result;
        }
        if (trip.planVersion() == Long.MAX_VALUE) {
            throw new BasketSaveException(409, "PLAN_VERSION_LIMIT_REACHED", "The trip plan version cannot be incremented");
        }

        long nextVersion = Math.addExact(trip.planVersion(), 1L);
        int changed = jdbc.update("UPDATE trip SET plan_version = ? WHERE id = ? AND plan_version = ?",
            nextVersion, input.tripId(), trip.planVersion());
        if (changed != 1) {
            Long current = jdbc.queryForObject("SELECT plan_version FROM trip WHERE id = ?", Long.class, input.tripId());
            throw new BasketSaveException(409, "PLAN_VERSION_CONFLICT", "The trip plan has changed", current);
        }
        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO trip_item(id, trip_id, place_id, created_by_user_id, schedule_date, sort_order) "
                + "VALUES (?, ?, ?, ?, NULL, NULL)",
            itemId, input.tripId(), input.placeId(), input.principalUserId());
        Result result = new Result("CREATED", itemId, input.placeId(), nextVersion);
        insertExecution(input, result);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                eventPublisher.publishAfterCommit(new TripPlanChangedEvent(input.tripId(), nextVersion));
            }
        });
        return result;
    }

    private Result processWinnerReadback(Input input) {
        setLocalLockTimeout();
        Trip ignored = one("SELECT plan_version, start_date, end_date FROM trip WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
            (rs, row) -> new Trip(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class)),
            input.tripId());
        if (ignored == null || !memberExists(input.tripId(), input.principalUserId())) {
            throw inaccessible();
        }
        Execution execution = execution(input);
        return execution == null || !execution.unexpired() ? null : replay(execution, input);
    }

    private boolean memberExists(UUID tripId, UUID userId) {
        return exists("SELECT 1 FROM trip_member WHERE trip_id = ? AND user_id = ? FOR SHARE", tripId, userId);
    }

    private Execution execution(Input input) {
        Execution locked = one("SELECT input_trip_id, input_place_id, input_expected_plan_version, result_kind, "
                + "result_trip_item_id, result_place_id, result_plan_version, expires_at "
                + "FROM request_execution WHERE principal_user_id = ? AND command_type = ? AND request_id = ? FOR UPDATE",
            BasketSaveService::mapExecution, input.principalUserId(), COMMAND, input.requestId());
        if (locked == null) {
            return null;
        }
        OffsetDateTime databaseNow = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        return locked.withUnexpired(databaseNow.isBefore(locked.expiresAt()));
    }

    private static Execution mapExecution(ResultSet rs, int row) throws SQLException {
        return new Execution(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3),
            new Result(rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getLong(7)),
            rs.getObject(8, OffsetDateTime.class), false);
    }

    private Result replay(Execution execution, Input input) {
        if (!execution.tripId().equals(input.tripId()) || !execution.placeId().equals(input.placeId())
                || execution.expectedPlanVersion() != input.expectedPlanVersion()) {
            throw new BasketSaveException(409, "REQUEST_ID_REUSED", "The request id was already used");
        }
        return execution.result();
    }

    private void insertExecution(Input input, Result result) {
        jdbc.update("WITH sampled AS (SELECT clock_timestamp() AS completed_at) "
                + "INSERT INTO request_execution(principal_user_id, command_type, request_id, input_trip_id, "
                + "input_place_id, input_expected_plan_version, result_kind, result_trip_item_id, result_place_id, "
                + "result_plan_version, completed_at, expires_at) "
                + "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, completed_at, completed_at + INTERVAL '24 hours' FROM sampled",
            input.principalUserId(), COMMAND, input.requestId(), input.tripId(), input.placeId(),
            input.expectedPlanVersion(), result.result(), result.tripItemId(), result.placeId(), result.planVersion());
    }

    private void setLocalLockTimeout() {
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
    }

    private boolean exists(String sql, Object... arguments) {
        return !jdbc.query(sql, (rs, row) -> 1, arguments).isEmpty();
    }

    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... arguments) {
        List<T> rows = jdbc.query(sql, mapper, arguments);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static BasketSaveException inaccessible() {
        return new BasketSaveException(404, "TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE", "The trip was not found or is not accessible");
    }

    private record Input(UUID principalUserId, UUID tripId, UUID placeId, long expectedPlanVersion, UUID requestId) {}
    private record Trip(long planVersion, LocalDate startDate, LocalDate endDate) {}
    private record CurrentItemRow(long planVersion, UUID tripItemId, UUID placeId, LocalDate scheduleDate,
            Long sortOrder) {}
    private record Execution(UUID tripId, UUID placeId, long expectedPlanVersion, Result result,
            OffsetDateTime expiresAt, boolean unexpired) {
        Execution withUnexpired(boolean value) {
            return new Execution(tripId, placeId, expectedPlanVersion, result, expiresAt, value);
        }
    }
    public record Result(String result, UUID tripItemId, UUID placeId, long planVersion) {}
    public record CurrentItem(UUID tripItemId, UUID placeId, String state, LocalDate scheduleDate, Long sortOrder,
            long planVersion) {}
}
