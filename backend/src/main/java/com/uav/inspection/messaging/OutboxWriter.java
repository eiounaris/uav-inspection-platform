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
        return enqueue(eventType,bizId,traceId,0);
    }

    /** A manual intent creates a new dispatch; retrying publication still reuses its event ID. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueue(String eventType,String bizId,String traceId,int dispatchNo) {
        if(dispatchNo<0) throw new IllegalArgumentException("Invalid outbox dispatch number");
        String id = UUID.randomUUID().toString();
        MessageEvent event = new MessageEvent(id, eventType, bizId, traceId);
        int inserted=dispatchNo==0?mapper.insert(id,eventType,bizId,JSON.writeValueAsString(event))
                :mapper.insertDispatch(id,eventType,bizId,dispatchNo,JSON.writeValueAsString(event));
        if (inserted != 1) {
            throw new IllegalStateException("Outbox insert failed");
        }
        return id;
    }
}
