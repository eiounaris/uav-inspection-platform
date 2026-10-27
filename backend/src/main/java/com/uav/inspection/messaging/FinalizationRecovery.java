package com.uav.inspection.messaging;

import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("database & storage & messaging")
public class FinalizationRecovery {
    private final FinalizationProcessor processor;
    private final BusinessRecoveryDispatcher dispatcher;
    private final boolean enabled;
    public FinalizationRecovery(FinalizationProcessor processor, BusinessRecoveryDispatcher dispatcher,
                                Environment environment) {
        this.processor = processor;
        this.dispatcher = dispatcher;
        enabled = environment.getProperty("inspection.messaging.recovery-enabled", Boolean.class, true);
    }

    @Scheduled(fixedDelayString = "${inspection.messaging.recovery-delay-ms:10000}", initialDelay = 10000)
    public void recover() {
        if (!enabled) return;
        dispatcher.submit(BusinessRecoveryDispatcher.Kind.FILE_FINALIZE, processor::recoverDue);
    }
}
