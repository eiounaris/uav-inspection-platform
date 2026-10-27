package com.uav.inspection.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.uav.inspection.messaging.BusinessRecoveryDispatcher.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessRecoveryDispatcherTest {
    @Test
    void blockedIoDoesNotBlockTicksOrTheOtherBusinessAndDuplicateTicksDoNotQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(), transactionalCalls = new AtomicInteger();
        try (var dispatcher = new BusinessRecoveryDispatcher()) {
            Runnable work = () -> {
                calls.incrementAndGet();
                if (TransactionSynchronizationManager.isActualTransactionActive()) transactionalCalls.incrementAndGet();
                entered.countDown();
                await(release);
            };
            dispatcher.submit(FILE_FINALIZE, work);
            dispatcher.submit(EVIDENCE_ANCHOR, work);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                for (int i = 0; i < 20; i++) {
                    dispatcher.submit(FILE_FINALIZE, work);
                    dispatcher.submit(EVIDENCE_ANCHOR, work);
                }
                assertEquals(2, calls.get());
                assertEquals(0, transactionalCalls.get());
            } finally { release.countDown(); }
        }
        assertEquals(2, calls.get(), "busy ticks must not leave queued recovery batches");
    }

    @Test
    void aFailedBatchDoesNotPreventAnotherTickFromRecovering() throws Exception {
        CountDownLatch failed = new CountDownLatch(1), recovered = new CountDownLatch(1);
        try (var dispatcher = new BusinessRecoveryDispatcher()) {
            dispatcher.submit(FILE_FINALIZE, () -> {
                failed.countDown();
                throw new IllegalStateException("controlled failure");
            });
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (recovered.getCount() != 0 && System.nanoTime() < deadline) {
                dispatcher.submit(FILE_FINALIZE, recovered::countDown);
                recovered.await(10, TimeUnit.MILLISECONDS);
            }
            assertEquals(0, recovered.getCount());
        }
    }

    @Test
    void closingInterruptsOwnedWorkAndStopsFutureScans() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        var dispatcher = new BusinessRecoveryDispatcher();
        dispatcher.submit(FILE_FINALIZE, () -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        dispatcher.close();
        dispatcher.close();
        assertEquals(0, interrupted.getCount());
        AtomicInteger laterCalls = new AtomicInteger();
        dispatcher.submit(FILE_FINALIZE, laterCalls::incrementAndGet);
        dispatcher.submit(EVIDENCE_ANCHOR, laterCalls::incrementAndGet);
        assertEquals(0, laterCalls.get());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
