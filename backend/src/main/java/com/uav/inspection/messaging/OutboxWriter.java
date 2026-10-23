package com.uav.inspection.messaging;

import com.uav.inspection.messaging.persistence.OutboxMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Service
@Profile("database & storage & messaging")
public class OutboxWriter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final OutboxMapper mapper;

    public OutboxWriter(OutboxMapper mapper) { this.mapper = mapper; }

    /** The business result and its event must commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueue(String eventType, String bizId, String traceId) {
        String id = UUID.randomUUID().toString();
        MessageEvent event = new MessageEvent(id, eventType, bizId, traceId);
        if (mapper.insert(id, eventType, bizId, JSON.writeValueAsString(event)) != 1) {
            throw new IllegalStateException("Outbox insert failed");
        }
        return id;
    }
}
