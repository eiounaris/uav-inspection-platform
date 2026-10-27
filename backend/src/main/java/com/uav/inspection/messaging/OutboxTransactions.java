package com.uav.inspection.messaging;

import com.uav.inspection.messaging.persistence.OutboxMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

@Service
@Profile("database & storage & messaging")
public class OutboxTransactions {
    private final OutboxMapper mapper;
    public OutboxTransactions(OutboxMapper mapper) { this.mapper = mapper; }

    @Transactional
    public Delivery claim() {
        var row = mapper.lockDue();
        if (row == null) return null;
        String token = UUID.randomUUID().toString();
        if (mapper.claim(row.eventId(), token) != 1) return null;
        return new Delivery(row.eventId(),row.eventType(),row.bizId(),row.payload(),token,Math.addExact(row.attemptCount(),1));
    }

    @Transactional
    public boolean sent(Delivery delivery) {
        return mapper.sent(delivery.eventId(), delivery.token())==1;
    }

    @Transactional
    public boolean retry(Delivery delivery, String code) {
        safeCode(code);
        int[] seconds = {2, 10, 30, 60, 300};
        return mapper.retry(delivery.eventId(), delivery.token(), code,
                seconds[Math.min(Math.max(delivery.attemptCount() - 1, 0), seconds.length - 1)])==1;
    }

    @Transactional
    public boolean failed(Delivery delivery,String code) {
        if(!"INVALID_ENVELOPE".equals(code)) throw new IllegalArgumentException("Invalid permanent outbox error");
        return mapper.failed(delivery.eventId(),delivery.token(),code)==1;
    }

    private static void safeCode(String code) {
        if(code==null || !code.matches("[A-Z][A-Z0-9_]{0,199}")) throw new IllegalArgumentException("Invalid outbox error code");
    }

    public record Delivery(String eventId,String eventType,String bizId,String payload,String token,int attemptCount) {
        /** Compatibility for local probes; production claims always bind the independent durable columns. */
        public Delivery(String eventId,String payload,String token,int count) {
            this(eventId,legacy(payload),payload,token,count);
        }
        private Delivery(String eventId,MessageEvent event,String payload,String token,int count) {
            this(eventId,event==null?null:event.eventType(),event==null?null:event.bizId(),payload,token,count);
        }
        private static MessageEvent legacy(String payload) {
            try { return MessageEvent.decode(payload.getBytes(StandardCharsets.UTF_8)); }
            catch(RuntimeException invalid) { return null; }
        }
    }
}
