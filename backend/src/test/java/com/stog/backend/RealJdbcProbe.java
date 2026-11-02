package com.stog.backend;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class RealJdbcProbe {
    @Bean
    Probe jdbcProbe() {
        return new Probe();
    }

    @Bean
    BeanPostProcessor observedDataSource(Probe probe) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof DataSource dataSource) || Proxy.isProxyClass(bean.getClass())) {
                    return bean;
                }
                return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                    (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(dataSource, arguments);
                            if (result instanceof Connection connection) {
                                probe.connectionOpened(connection);
                                return observedConnection(connection, probe);
                            }
                            return result;
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
            }
        };
    }

    private static Connection observedConnection(Connection delegate, Probe probe) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
                try {
                    if ("commit".equals(method.getName())) {
                        probe.beforeCommit();
                    }
                    Object result = method.invoke(delegate, arguments);
                    if (result instanceof java.sql.PreparedStatement statement && arguments != null
                            && arguments.length > 0 && arguments[0] instanceof String sql) {
                        return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {java.sql.PreparedStatement.class}, (preparedProxy, preparedMethod, preparedArguments) -> {
                                try {
                                    if (preparedMethod.getName().startsWith("execute")) {
                                        probe.beforeSql(sql);
                                    }
                                    Object executionResult = preparedMethod.invoke(statement, preparedArguments);
                                    if (preparedMethod.getName().startsWith("execute")) probe.afterSql(sql);
                                    return executionResult;
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }
                            });
                    }
                    return result;
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            });
    }

    static final class Probe {
        private volatile Gate requestInsert;
        private volatile Gate tripCreateInsert;
        private volatile Gate tripSelect;
        private volatile Gate executionSelect;
        private volatile Gate commit;
        private volatile Gate executionSelectReturned;
        private volatile Gate clockQuery;
        private volatile Gate currentItemSelect;
        private volatile Gate coverBytesSelect;
        private volatile Gate collaborationTripSelect;
        private final Set<Integer> backendPids = ConcurrentHashMap.newKeySet();

        Gate pauseRequestInserts(int parties) {
            return requestInsert = new Gate(parties, true);
        }

        Gate pauseTripCreateInserts(int parties) {
            return tripCreateInsert = new Gate(parties, true);
        }

        Gate observeTripSelect() {
            return observeTripSelect(1);
        }

        Gate observeTripSelect(int parties) {
            return tripSelect = new Gate(parties, false);
        }

        Gate observeExecutionSelect() {
            return executionSelect = new Gate(1, false);
        }

        Gate observeExecutionSelectReturned() {
            return executionSelectReturned = new Gate(1, false);
        }

        Gate observeClockQuery() {
            return clockQuery = new Gate(1, false);
        }

        Gate pauseCurrentItemSelect() {
            return currentItemSelect = new Gate(1, true);
        }

        Gate pauseCoverBytesSelect() {
            return coverBytesSelect = new Gate(1, true);
        }

        Gate observeCollaborationTripSelect() {
            return collaborationTripSelect = new Gate(1, false);
        }

        void assertIndependentTransactions(int count) {
            if (backendPids.size() < count) throw new AssertionError("Expected distinct PostgreSQL backend PIDs: " + backendPids);
        }

        Gate pauseCommit() {
            return commit = new Gate(1, true);
        }

        void reset() {
            release(requestInsert);
            release(tripCreateInsert);
            release(tripSelect);
            release(executionSelect);
            release(executionSelectReturned);
            release(clockQuery);
            release(currentItemSelect);
            release(coverBytesSelect);
            release(collaborationTripSelect);
            release(commit);
            requestInsert = tripCreateInsert = tripSelect = executionSelect = executionSelectReturned = clockQuery = currentItemSelect = null;
            coverBytesSelect = collaborationTripSelect = commit = null;
            backendPids.clear();
        }

        private void beforeSql(String sql) throws SQLException {
            String normalized = sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (normalized.contains("INSERT INTO REQUEST_EXECUTION")) arrive(requestInsert);
            if (normalized.contains("INSERT INTO TRIP_CREATE_EXECUTION")) arrive(tripCreateInsert);
            if (normalized.startsWith("SELECT PLAN_VERSION, START_DATE, END_DATE FROM TRIP")) arrive(tripSelect);
            if (normalized.startsWith("SELECT INPUT_TRIP_ID, INPUT_PLACE_ID")) arrive(executionSelect);
            if (normalized.startsWith("SELECT T.PLAN_VERSION, TI.ID, TI.PLACE_ID")) arrive(currentItemSelect);
            if (normalized.startsWith("SELECT C.ID,C.TRIP_ID,C.STORAGE_KEY,C.BYTE_SIZE,C.THUMBNAIL_BYTE_SIZE")) arrive(coverBytesSelect);
            if (normalized.startsWith("SELECT ID,NAME,REGION_LABEL,START_DATE,END_DATE,PLAN_VERSION,DELETED_AT,COVER_ID FROM TRIP")) arrive(collaborationTripSelect);
            if (normalized.contains("CLOCK_TIMESTAMP()")) arrive(clockQuery);
        }

        private void afterSql(String sql) throws SQLException {
            String normalized = sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (normalized.startsWith("SELECT INPUT_TRIP_ID, INPUT_PLACE_ID")) arrive(executionSelectReturned);
        }

        private void connectionOpened(Connection connection) throws SQLException {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                backendPids.add(result.getInt(1));
            }
        }

        private void beforeCommit() throws SQLException {
            arrive(commit);
        }

        private static void arrive(Gate gate) throws SQLException {
            if (gate == null) return;
            gate.arrived.countDown();
            if (gate.pause) {
                try {
                    if (!gate.release.await(15, TimeUnit.SECONDS)) {
                        throw new SQLException("Timed out at deterministic JDBC test barrier");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted at deterministic JDBC test barrier", exception);
                }
            }
        }

        private static void release(Gate gate) {
            if (gate != null) gate.release.countDown();
        }
    }

    static final class Gate {
        final CountDownLatch arrived;
        final CountDownLatch release = new CountDownLatch(1);
        final boolean pause;

        Gate(int parties, boolean pause) {
            this.arrived = new CountDownLatch(parties);
            this.pause = pause;
        }

        void awaitArrival() throws InterruptedException {
            if (!arrived.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Expected real JDBC operation was not observed");
            }
        }

        void release() {
            release.countDown();
        }
    }
}
