package com.uav.inspection.messaging;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

/** Shares the commit-before-ACK/reject contract across both manual-ack consumers. */
final class ManualMessageDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(ManualMessageDelivery.class);
    private final String consumer;
    private final String expectedType;
    private final Consumer<MessageEvent> processor;
    private final MessageRejectionRecorder rejections;
    private final RecoveryPause pause;

    ManualMessageDelivery(String consumer, String expectedType, Consumer<MessageEvent> processor,
                          MessageRejectionRecorder rejections) {
        this(consumer, expectedType, processor, rejections, ManualMessageDelivery::pauseBeforeRecovery);
    }

    ManualMessageDelivery(String consumer, String expectedType, Consumer<MessageEvent> processor,
                          MessageRejectionRecorder rejections, RecoveryPause pause) {
        this.consumer = consumer;
        this.expectedType = expectedType;
        this.processor = Objects.requireNonNull(processor);
        this.rejections = Objects.requireNonNull(rejections);
        this.pause = Objects.requireNonNull(pause);
    }

    void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        String suppliedId = message.getMessageProperties().getMessageId();
        MessageEvent event;
        try {
            event = MessageEvent.decode(message.getBody());
            if (!expectedType.equals(event.eventType())) throw new InvalidMessageException();
        } catch (RuntimeException invalid) {
            reject(message, channel, tag, "INVALID_ENVELOPE", suppliedId);
            return;
        }
        if (suppliedId != null && !event.eventId().equals(suppliedId)) {
            reject(message, channel, tag, "MESSAGE_ID_MISMATCH", suppliedId);
            return;
        }
        String previous = MDC.get("traceId");
        try {
            MDC.put("traceId", event.traceId());
            processor.accept(event);
            channel.basicAck(tag, false);
        } catch (InvalidMessageException invalid) {
            reject(message, channel, tag, "UNKNOWN_EVENT", suppliedId);
        } catch (RuntimeException unavailable) {
            unavailable(channel);
        } finally {
            if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
        }
    }

    private void reject(Message message, Channel channel, long tag, String reason, String suppliedId) throws IOException {
        try {
            rejections.record(consumer, message.getBody(), reason, suppliedId);
        } catch (RuntimeException unavailable) {
            unavailable(channel);
            return;
        }
        LOG.warn("Messaging delivery rejected: consumer={} reason={}", consumer, reason);
        channel.basicReject(tag, false);
    }

    private void unavailable(Channel channel) throws IOException {
        LOG.warn("Messaging delivery remains unacknowledged: consumer={}", consumer);
        // Processor/recorder transaction proxies have returned or thrown before this pause.
        // A running consumer's restart does not necessarily obey the container startup interval.
        // Keep the delivery unacked during the fixed pause, then close for safe redelivery.
        try { pause.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        channel.abort();
    }

    static void pauseBeforeRecovery() throws InterruptedException { Thread.sleep(5000); }

    @FunctionalInterface
    interface RecoveryPause { void await() throws InterruptedException; }
}
