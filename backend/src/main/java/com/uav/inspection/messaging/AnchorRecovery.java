package com.uav.inspection.messaging;

import com.uav.inspection.blockchain.anchor.AnchorProcessor;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("database & storage & messaging & ledger")
public class AnchorRecovery {
    private final AnchorProcessor processor;
    private final boolean enabled;
    public AnchorRecovery(AnchorProcessor processor, Environment environment) {
        this.processor = processor;
        enabled = environment.getProperty("inspection.ledger.recovery-enabled", Boolean.class, true);
    }

    @Scheduled(fixedDelayString = "${inspection.ledger.recovery-delay-ms:10000}", initialDelay = 10000)
    public void recover() {
        if (!enabled) return;
        try { processor.recoverDue(); }
        catch (RuntimeException failure) {
            LoggerFactory.getLogger(AnchorRecovery.class)
                    .warn("Anchor recovery deferred: {}", failure.getClass().getSimpleName());
        }
    }
}
