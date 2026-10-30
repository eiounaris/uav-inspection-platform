package com.uav.inspection.common.lease;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Independent heartbeat threads keep external I/O from starving short renewal transactions. */
@Component
@Profile("database & storage & messaging")
public class ProcessingLeaseKeeper {
    private static final long LEASE_NANOS = TimeUnit.SECONDS.toNanos(120);
    private static final long RENEWAL_MILLIS = 30_000;
    private static final int MAX_TRACKED_LEASES = 64;
    private final ScheduledExecutorService scheduler;
    private final long renewalMillis;
    private final LongSupplier clock;
    private final Semaphore slots = new Semaphore(MAX_TRACKED_LEASES);
    private final AtomicBoolean stopped = new AtomicBoolean();

    public ProcessingLeaseKeeper() { this(scheduler(), RENEWAL_MILLIS, System::nanoTime); }

    ProcessingLeaseKeeper(ScheduledExecutorService scheduler, long renewalMillis, LongSupplier clock) {
        if (renewalMillis <= 0) throw new IllegalArgumentException("Invalid processing renewal interval");
        this.scheduler = scheduler;
        this.renewalMillis = renewalMillis;
        this.clock = Objects.requireNonNull(clock);
    }

    public Lease track(BooleanSupplier renewal) {
        Objects.requireNonNull(renewal);
        if (stopped.get() || !slots.tryAcquire()) throw new ProcessingLeaseLostException();
        Lease lease = new Lease(renewal);
        try {
            // The initial renewal also checks the database lease immediately after a committed claim.
            lease.renew();
            lease.check();
            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                    lease::renew, renewalMillis, renewalMillis, TimeUnit.MILLISECONDS);
            lease.future.set(future);
            if (lease.closed.get() || lease.lost.get()) future.cancel(false);
            lease.check();
            return lease;
        } catch (RuntimeException unavailable) {
            lease.close();
            throw new ProcessingLeaseLostException();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (stopped.compareAndSet(false, true)) scheduler.shutdownNow();
    }

    private static ScheduledThreadPoolExecutor scheduler() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "processing-lease-heartbeat-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        var scheduler = new ScheduledThreadPoolExecutor(2, factory);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    public final class Lease implements AutoCloseable {
        private final BooleanSupplier renewal;
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        private volatile long verifiedAt;

        private Lease(BooleanSupplier renewal) {
            this.renewal = renewal;
        }

        private void renew() {
            if (closed.get() || lost.get() || stopped.get()) return;
            long started = clock.getAsLong();
            try {
                if (!renewal.getAsBoolean()) { lose(); return; }
                // Anchor at the start, never at a delayed commit response after the SQL UTC timestamp.
                if (clock.getAsLong() - started >= LEASE_NANOS) { lose(); return; }
                if (!closed.get() && !lost.get()) verifiedAt = started;
            } catch (RuntimeException unavailable) {
                // Provider errors and their messages do not escape through the lease or normal logs.
                lose();
            }
        }

        public void check() {
            if (Thread.currentThread().isInterrupted()) lose();
            if (stopped.get() || clock.getAsLong() - verifiedAt >= LEASE_NANOS) lose();
            if (closed.get() || lost.get()) throw new ProcessingLeaseLostException();
        }

        private void lose() {
            lost.set(true);
            ScheduledFuture<?> current = future.get();
            if (current != null) current.cancel(false);
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            ScheduledFuture<?> current = future.get();
            if (current != null) current.cancel(false);
            slots.release();
        }
    }
}
