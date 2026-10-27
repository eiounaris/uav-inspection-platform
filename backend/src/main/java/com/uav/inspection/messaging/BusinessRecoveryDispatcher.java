package com.uav.inspection.messaging;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Scheduled ticks never wait for external I/O; each business scan has at most one batch in flight. */
@Component
@Profile("database & storage & messaging")
@DependsOn("finalizationProcessor") // Stop scan workers before their lease keeper and database dependencies.
public final class BusinessRecoveryDispatcher implements AutoCloseable {
    public enum Kind { FILE_FINALIZE, EVIDENCE_ANCHOR }

    private static final Logger LOG = LoggerFactory.getLogger(BusinessRecoveryDispatcher.class);
    private final ExecutorService executor;
    private final AtomicBoolean[] running = {new AtomicBoolean(), new AtomicBoolean()};
    private final AtomicBoolean closed = new AtomicBoolean();

    public BusinessRecoveryDispatcher() { this(newExecutor()); }

    BusinessRecoveryDispatcher(ExecutorService executor) { this.executor = executor; }

    public void submit(Kind kind, Runnable recovery) {
        AtomicBoolean lane = running[kind.ordinal()];
        if (closed.get() || !lane.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { recovery.run(); }
                catch (RuntimeException failure) {
                    LOG.warn("Business recovery deferred: kind={} failure={}", kind,
                            failure.getClass().getSimpleName());
                } finally { lane.set(false); }
            });
        } catch (RejectedExecutionException unavailable) {
            lane.set(false);
            if (!closed.get()) LOG.warn("Business recovery capacity unavailable: kind={}", kind);
        }
    }

    private static ExecutorService newExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "inspection-recovery-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        try { executor.awaitTermination(15, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
