package com.uav.inspection.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.uav.inspection.messaging.BusinessRecoveryDispatcher.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class DurableRecoveryTest {
    @AfterEach
    void clearTrace() { MDC.remove("traceId"); }

    @Test
    void interruptedShutdownDoesNotSelectNewWork() {
        try {
            Thread.currentThread().interrupt();
            DurableRecovery.run(FILE_FINALIZE,
                    () -> { fail("shutdown must not select new work"); return List.of(); },
                    event -> fail("shutdown must not claim new work"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test
    void interruptionDuringOneWorkItemPreventsClaimingTheNext() {
        List<MessageEvent> handled = new ArrayList<>();
        MessageEvent first = event("first"), second = event("second");
        try {
            DurableRecovery.run(FILE_FINALIZE, () -> List.of(first, second), event -> {
                handled.add(event);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted I/O");
            });
            assertEquals(List.of(first), handled);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test
    void oneFailedBusinessDoesNotStarveOtherDueWorkAndEachUsesItsPersistedTrace() {
        MessageEvent first = event("first"), second = event("second");
        List<MessageEvent> handled = new ArrayList<>();
        MDC.put("traceId", "scheduler");
        DurableRecovery.run(FILE_FINALIZE, () -> List.of(first, second), event -> {
            assertEquals(event.traceId(), MDC.get("traceId"));
            handled.add(event);
            if (event.equals(first)) throw new IllegalStateException("controlled failure");
        });
        assertEquals(List.of(first, second), handled);
        assertEquals("scheduler", MDC.get("traceId"));
    }

    @Test
    void scanDoesNotLeakTraceToTheNextBackgroundTask() {
        DurableRecovery.run(EVIDENCE_ANCHOR, () -> List.of(event("durable")), event -> {
            assertEquals("durable", MDC.get("traceId"));
        });
        assertNull(MDC.get("traceId"));
    }

    @Test
    void failedDatabaseSelectionRemainsVisibleForTheNextScheduledTick() {
        assertThrows(IllegalStateException.class, () -> DurableRecovery.run(FILE_FINALIZE,
                () -> { throw new IllegalStateException("database selection unavailable"); },
                event -> fail("selection did not yield any durable work")));
    }

    private static MessageEvent event(String trace) {
        return new MessageEvent(UUID.randomUUID().toString(), MessageEvent.FILE_FINALIZE_REQUESTED,
                UUID.randomUUID().toString(), trace);
    }
}
