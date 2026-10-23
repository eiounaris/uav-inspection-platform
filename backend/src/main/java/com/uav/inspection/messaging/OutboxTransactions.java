package com.uav.inspection.messaging;

import com.uav.inspection.messaging.persistence.OutboxMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
        if (mapper.claim(row.eventId(), token) != 1) throw new IllegalStateException("Outbox claim failed");
        return new Delivery(row.eventId(), row.payload(), token, row.attemptCount() + 1);
    }

    @Transactional
    public void sent(Delivery delivery) {
        mapper.sent(delivery.eventId(), delivery.token());
    }

    @Transactional
    public void retry(Delivery delivery, String code) {
        int[] seconds = {2, 10, 30, 60, 300};
        mapper.retry(delivery.eventId(), delivery.token(), code,
                seconds[Math.min(Math.max(delivery.attemptCount() - 1, 0), seconds.length - 1)]);
    }

    public record Delivery(String eventId, String payload, String token, int attemptCount) {}
}
