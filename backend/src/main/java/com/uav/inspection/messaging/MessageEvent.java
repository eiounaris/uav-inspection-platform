package com.uav.inspection.messaging;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The wire contract deliberately excludes object keys, file bytes and credentials. */
public record MessageEvent(String eventId, String eventType, String bizId, String traceId) {
    public static final String FILE_FINALIZE_REQUESTED = "FILE_FINALIZE_REQUESTED";
    public static final String EVIDENCE_ANCHOR_REQUESTED = "EVIDENCE_ANCHOR_REQUESTED";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final JsonMapper JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

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

    /** The same bounded, strict envelope applies to both durable payloads and broker deliveries. */
    public static MessageEvent decode(byte[] body) {
        if(body==null || body.length==0 || body.length>4096) throw new InvalidMessageException();
        try {
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            var node=JSON.readTree(value);
            if(node==null || !node.isObject() || node.size()!=4) throw new InvalidMessageException();
            for(String field:List.of("eventId","eventType","bizId","traceId")) {
                if(!node.has(field) || !node.get(field).isString()) throw new InvalidMessageException();
            }
            return new MessageEvent(node.get("eventId").stringValue(),node.get("eventType").stringValue(),
                    node.get("bizId").stringValue(),node.get("traceId").stringValue());
        } catch(Exception failure) {
            // Parsing errors can contain the source bytes. Never attach them to a business exception.
            throw new InvalidMessageException();
        }
    }
}
