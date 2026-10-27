package com.uav.inspection.evidence.cleanup;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Profile("database & storage & cleanup")
public final class CleanupSettings {
    private final long graceSeconds;
    private final int batchSize;
    private final boolean schedulerEnabled;

    public CleanupSettings(Environment environment) {
        graceSeconds=environment.getProperty("inspection.cleanup.grace-seconds",Long.class,3600L);
        batchSize=environment.getProperty("inspection.cleanup.batch-size",Integer.class,100);
        schedulerEnabled=environment.getProperty("inspection.cleanup.scheduler-enabled",Boolean.class,true);
        long delay=environment.getProperty("inspection.cleanup.scan-delay-ms",Long.class,60000L);
        if(graceSeconds<1 || graceSeconds>604800 || batchSize<1 || batchSize>100 || delay<1000)
            throw new IllegalStateException("Invalid cleanup limits");
    }

    public long graceSeconds() { return graceSeconds; }
    public Duration grace() { return Duration.ofSeconds(graceSeconds); }
    public int batchSize() { return batchSize; }
    public boolean schedulerEnabled() { return schedulerEnabled; }
}
