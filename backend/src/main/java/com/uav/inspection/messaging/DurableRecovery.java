package com.uav.inspection.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Common scan contract. Selection is durable business work, independent of broker consumption DONE. */
public final class DurableRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(DurableRecovery.class);

    private DurableRecovery() {}

    public static void run(BusinessRecoveryDispatcher.Kind kind, Supplier<List<MessageEvent>> due,
                           Consumer<MessageEvent> recover) {
        if (Thread.currentThread().isInterrupted()) return;
        for (MessageEvent event : due.get()) {
            if (Thread.currentThread().isInterrupted()) return;
            String previous = MDC.get("traceId");
            try {
                MDC.put("traceId", event.traceId());
                recover.accept(event);
            } catch (RuntimeException failure) {
                LOG.warn("Business recovery deferred: kind={} failure={}", kind,
                        failure.getClass().getSimpleName());
            } finally {
                if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
            }
        }
    }
}
