package com.uav.inspection.messaging;

/** The wire contract deliberately excludes object keys, file bytes and credentials. */
public record MessageEvent(String eventId, String eventType, String bizId, String traceId) {
    public static final String FILE_FINALIZE_REQUESTED = "FILE_FINALIZE_REQUESTED";
    public static final String EVIDENCE_ANCHOR_REQUESTED = "EVIDENCE_ANCHOR_REQUESTED";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    public MessageEvent {
        if (eventId == null || !eventId.matches(UUID) || bizId == null || !bizId.matches(UUID)
                || !(FILE_FINALIZE_REQUESTED.equals(eventType) || EVIDENCE_ANCHOR_REQUESTED.equals(eventType))
                || traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid message envelope");
        }
    }

    public String routingKey() {
        return FILE_FINALIZE_REQUESTED.equals(eventType) ? "file.finalize" : "evidence.anchor";
    }
}
