package com.uav.inspection.common.lease;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessingLeaseKeeperTest {
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final AtomicLong clock = new AtomicLong();
    private final ArrayList<Runnable> callbacks = new ArrayList<>();
    private ProcessingLeaseKeeper keeper;

    @BeforeEach
    void configure() {
        doAnswer(call -> { callbacks.add(call.getArgument(0)); return future; }).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), eq(30_000L), eq(30_000L), eq(TimeUnit.MILLISECONDS));
        keeper = new ProcessingLeaseKeeper(scheduler, 30_000, clock::get);
    }

    @Test
    void verifiesImmediatelyThenRenewsEveryThirtySecondsAndCancelsWithoutInterruptingTransactions() {
        AtomicInteger renewed = new AtomicInteger();
        try (var lease = keeper.track(() -> { renewed.incrementAndGet(); return true; })) {
            assertEquals(1, renewed.get());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            callbacks.getFirst().run();
            assertEquals(2, renewed.get());
            lease.check();
            verify(future, never()).cancel(anyBoolean());
        }
        verify(future).cancel(false);
        callbacks.getFirst().run();
        assertEquals(2, renewed.get());
    }

    @Test
    void losingDatabaseOwnershipStopsHeartbeatAndCannotBeReversedByAnotherCallback() {
        AtomicInteger attempts = new AtomicInteger();
        try (var lease = keeper.track(() -> attempts.incrementAndGet() == 1)) {
            callbacks.getFirst().run();
            assertThrows(ProcessingLeaseLostException.class, lease::check);
            callbacks.getFirst().run();
            assertEquals(2, attempts.get());
            verify(future).cancel(false);
        }
    }

    @Test
    void aRenewalExceptionIsFailClosedAndDoesNotExposeProviderMessages() {
        AtomicInteger attempts = new AtomicInteger();
        try (var lease = keeper.track(() -> {
            if (attempts.incrementAndGet() > 1) throw new IllegalStateException("private database detail");
            return true;
        })) {
            callbacks.getFirst().run();
            var lost = assertThrows(ProcessingLeaseLostException.class, lease::check);
            assertEquals("Processing lease is no longer verified", lost.getMessage());
            assertNull(lost.getCause());
            verify(future).cancel(false);
        }
    }

    @Test
    void anInitialFailedOrUncertainRenewalNeverRegistersAHeartbeat() {
        assertThrows(ProcessingLeaseLostException.class, () -> keeper.track(() -> false));
        var lost = assertThrows(ProcessingLeaseLostException.class,
                () -> keeper.track(() -> { throw new IllegalStateException("private connection detail"); }));
        assertNull(lost.getCause());
        assertTrue(callbacks.isEmpty());
    }

    @Test
    void aBlockedSchedulerDoesNotLetTheOwnerContinuePastTheLocalLeaseBound() {
        try (var lease = keeper.track(() -> true)) {
            clock.set(TimeUnit.SECONDS.toNanos(120));
            assertThrows(ProcessingLeaseLostException.class, lease::check);
            callbacks.getFirst().run();
            clock.set(0);
            assertThrows(ProcessingLeaseLostException.class, lease::check);
            verify(future).cancel(false);
        }
    }

    @Test
    void delayedCommitResponsesDoNotStartAnOptimisticNewDeadline() {
        AtomicInteger attempts = new AtomicInteger();
        try (var lease = keeper.track(() -> {
            if (attempts.incrementAndGet() > 1) clock.addAndGet(TimeUnit.SECONDS.toNanos(110));
            return true;
        })) {
            clock.set(TimeUnit.SECONDS.toNanos(30));
            callbacks.getFirst().run();
            // Renewal began at t=30; its delayed response at t=140 cannot grant until t=260.
            clock.set(TimeUnit.SECONDS.toNanos(150));
            assertThrows(ProcessingLeaseLostException.class, lease::check);
        }
    }

    @Test
    void anInitialRenewalTakingAnEntireLeaseCannotStartBusinessIo() {
        assertThrows(ProcessingLeaseLostException.class, () -> keeper.track(() -> {
            clock.set(TimeUnit.SECONDS.toNanos(120));
            return true;
        }));
        assertTrue(callbacks.isEmpty());
    }

    @Test
    void interruptedOwnersKeepTheirInterruptFlagAndLosePermissionToContinue() {
        try (var lease = keeper.track(() -> true)) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(ProcessingLeaseLostException.class, lease::check);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test
    void shutdownRejectsNewWorkAndInvalidatesTrackedWork() {
        try (var lease = keeper.track(() -> true)) {
            keeper.shutdown();
            keeper.shutdown();
            assertThrows(ProcessingLeaseLostException.class, lease::check);
            assertThrows(ProcessingLeaseLostException.class, () -> keeper.track(() -> true));
            verify(scheduler).shutdownNow();
        }
    }

    @Test
    void registrationFailureIsSafeAndDoesNotLeakTheCapacitySlot() {
        doThrow(new java.util.concurrent.RejectedExecutionException("private executor detail")).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        for (int i = 0; i < 100; i++) {
            var lost = assertThrows(ProcessingLeaseLostException.class, () -> keeper.track(() -> true));
            assertNull(lost.getCause());
        }
    }

    @Test
    void activeHeartbeatRegistrationsAreBoundedAndDoubleCloseCannotReleaseExtraCapacity() {
        var leases = new ArrayList<ProcessingLeaseKeeper.Lease>();
        try {
            for (int i = 0; i < 64; i++) leases.add(keeper.track(() -> true));
            assertThrows(ProcessingLeaseLostException.class, () -> keeper.track(() -> true));
            var first = leases.removeFirst();
            first.close(); first.close();
            leases.add(keeper.track(() -> true));
            assertThrows(ProcessingLeaseLostException.class, () -> keeper.track(() -> true));
        } finally { leases.forEach(ProcessingLeaseKeeper.Lease::close); }
    }
}
