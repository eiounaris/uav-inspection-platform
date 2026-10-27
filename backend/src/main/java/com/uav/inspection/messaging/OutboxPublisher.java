package com.uav.inspection.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@Profile("database & storage & messaging")
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxTransactions transactions;
    private final RabbitTemplate rabbit;
    private final String exchange;
    private final boolean enabled;

    public OutboxPublisher(OutboxTransactions transactions, RabbitTemplate rabbit, Environment environment) {
        this.transactions = transactions;
        this.rabbit = rabbit;
        this.exchange = environment.getRequiredProperty("inspection.messaging.exchange");
        this.enabled = environment.getProperty("inspection.messaging.publisher-enabled", Boolean.class, true);
    }

    @Scheduled(fixedDelayString = "${inspection.messaging.publish-delay-ms:1000}", initialDelay = 1000)
    public void scheduledPublish() {
        if (enabled) {
            try { publishBatch(); }
            catch (RuntimeException failure) { LOG.warn("Outbox scan deferred: {}", failure.getClass().getSimpleName()); }
        }
    }

    public void publishBatch() {
        for (int i = 0; i < 20; i++) {
            var delivery = transactions.claim();
            if (delivery == null) return;
            String outcome = publish(delivery);
            // A failed database update leaves the same event recoverable after its sending lease.
            boolean saved;
            if (outcome == null) saved=transactions.sent(delivery);
            else if("INVALID_ENVELOPE".equals(outcome)) saved=transactions.failed(delivery,outcome);
            else saved=transactions.retry(delivery, outcome);
            if(!saved) {
                LOG.info("Outbox result discarded after sending lease changed");
                return;
            }
        }
    }

    private String publish(OutboxTransactions.Delivery delivery) {
        MessageEvent event;
        byte[] body;
        try {
            if(delivery.payload()==null || delivery.payload().length()>4096) return "INVALID_ENVELOPE";
            body=delivery.payload().getBytes(StandardCharsets.UTF_8);
            event=MessageEvent.decode(body);
            if(!event.eventId().equals(delivery.eventId()) || !event.eventType().equals(delivery.eventType())
                    || !event.bizId().equals(delivery.bizId())) return "INVALID_ENVELOPE";
        } catch(RuntimeException invalid) {
            return "INVALID_ENVELOPE";
        }
        try {
            MessageProperties properties = new MessageProperties();
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding("UTF-8");
            properties.setMessageId(event.eventId());
            CorrelationData correlation = new CorrelationData(event.eventId() + ":" + delivery.token());
            rabbit.send(exchange, event.routingKey(),
                    new Message(body, properties), correlation);
            var confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (correlation.getReturned() != null) return "UNROUTABLE";
            return confirm.ack() ? null : "PUBLISH_NACK";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "PUBLISH_INTERRUPTED";
        } catch (Exception failure) {
            // Never log broker replies, full payloads, connection credentials or exception messages.
            LOG.warn("Outbox delivery deferred: {}", failure.getClass().getSimpleName());
            return "PUBLISH_UNCONFIRMED";
        }
    }
}
