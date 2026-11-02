package com.stog.backend;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TripCoverRecoveryTest {
    @TempDir Path temporary;

    @Test
    void overlappingStartupAndScheduledPassesNeverEnterStorageTogether() throws Exception {
        BlockingStorage storage = new BlockingStorage(temporary);
        SignallingLock lock = new SignallingLock(2);
        TripCoverService service = new TripCoverService(mock(JdbcTemplate.class),
            mock(PlatformTransactionManager.class), storage, mock(TripPlanEventPublisher.class), new ObjectMapper(), lock);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var startup = executor.submit(service::reconcileAtStartup);
            assertTrue(storage.firstEntered.await(10, TimeUnit.SECONDS));
            var scheduled = executor.submit(service::reconcileDaily);
            assertTrue(lock.attempted.await(10, TimeUnit.SECONDS));

            storage.releaseFirst.countDown();
            startup.get(10, TimeUnit.SECONDS);
            scheduled.get(10, TimeUnit.SECONDS);
            assertEquals(2, storage.calls.get(), "both startup and scheduled passes must complete");
            assertEquals(1, storage.maximumActive.get(), "recovery passes must be serialized");
        } finally {
            storage.releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static final class BlockingStorage extends TripCoverStorage {
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximumActive = new AtomicInteger();

        BlockingStorage(Path root) {
            super(root.toString());
            initialize();
        }

        @Override ReconcileBatch reconciliationBatch(Instant cutoff, int maximumVisited) {
            int call = calls.incrementAndGet();
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            try {
                if (call == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("first recovery pass was not released");
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("recovery barrier interrupted", exception);
            } finally {
                active.decrementAndGet();
            }
            return new ReconcileBatch(List.of(), 0);
        }
    }

    private static final class SignallingLock extends ReentrantLock {
        final CountDownLatch attempted;
        SignallingLock(int attempts) { attempted = new CountDownLatch(attempts); }
        @Override public void lock() {
            attempted.countDown();
            super.lock();
        }
    }
}
