package com.uav.inspection.evidence.cleanup;

import com.uav.inspection.common.temp.TemporaryFileManager;
import com.uav.inspection.evidence.storage.UploadSettings;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** The explicit cleanup profile owns one non-queued worker; scheduler/startup threads never perform S3 I/O. */
@Component
@Profile("database & storage & cleanup")
@DependsOn("cleanupProcessor")
public final class CleanupScheduler implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(CleanupScheduler.class);
    private final CleanupProcessor processor;
    private final TemporaryFileManager temporary;
    private final CleanupSettings settings;
    private final LinkedHashSet<Path> directories=new LinkedHashSet<>();
    private final ExecutorService executor;
    private final AtomicBoolean running=new AtomicBoolean(),closed=new AtomicBoolean();

    public CleanupScheduler(CleanupProcessor processor,TemporaryFileManager temporary,UploadSettings uploads,
                            CleanupSettings settings,Environment environment) {
        this.processor=processor;this.temporary=temporary;this.settings=settings;
        directories.add(uploads.tempDirectory());
        directories.add(Path.of(environment.getProperty("inspection.verification.temp-directory",
                ".local/uploads/verification")).toAbsolutePath().normalize());
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),runnable->{
            Thread thread=new Thread(runnable,"inspection-storage-cleanup");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelayString="${inspection.cleanup.scan-delay-ms:60000}",initialDelay=1000)
    public void tick() {
        if(!settings.schedulerEnabled() || closed.get() || !running.compareAndSet(false,true)) return;
        try {
            executor.execute(()->{
                try { runCycle(); }
                catch(RuntimeException failure) { LOG.warn("Cleanup cycle deferred: {}",failure.getClass().getSimpleName()); }
                finally { running.set(false); }
            });
        } catch(RejectedExecutionException unavailable) { running.set(false); }
    }

    public void runCycle() {
        for(Path directory:directories) {
            if(Thread.currentThread().isInterrupted()) return;
            var result=temporary.sweep(directory,settings.grace(),settings.batchSize());
            if(result.deleted()>0 || result.failed()>0)
                LOG.info("Temporary cleanup: removed={} locked={} failed={}",result.deleted(),result.locked(),result.failed());
        }
        if(!Thread.currentThread().isInterrupted()) processor.runOnce();
    }

    @Override @PreDestroy public void close() {
        if(!closed.compareAndSet(false,true)) return;
        executor.shutdownNow();
        try { executor.awaitTermination(15,TimeUnit.SECONDS); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
