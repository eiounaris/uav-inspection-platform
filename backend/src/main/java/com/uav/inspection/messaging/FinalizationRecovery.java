package com.uav.inspection.messaging;

import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("database & storage & messaging")
public class FinalizationRecovery {
    private final FinalizationProcessor processor;
    private final boolean enabled;
    public FinalizationRecovery(FinalizationProcessor processor, Environment environment) {
        this.processor = processor;
        enabled = environment.getProperty("inspection.messaging.recovery-enabled", Boolean.class, true);
    }

    @Scheduled(fixedDelayString = "${inspection.messaging.recovery-delay-ms:10000}", initialDelay = 10000)
    public void recover() {
        if (!enabled) return;
        try { processor.recoverDue(); }
        catch (RuntimeException failure) {
            LoggerFactory.getLogger(FinalizationRecovery.class)
                    .warn("Finalization recovery deferred: {}", failure.getClass().getSimpleName());
        }
    }
}
