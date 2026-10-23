package com.uav.inspection.messaging;

import com.rabbitmq.client.Channel;
import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** Manual ACK follows the processor's committed result or durable recovery checkpoint. */
public class FinalizationMessageListener implements ChannelAwareMessageListener {
    private static final Logger LOG = LoggerFactory.getLogger(FinalizationMessageListener.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FinalizationProcessor processor;

    public FinalizationMessageListener(FinalizationProcessor processor) { this.processor = processor; }

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        MessageEvent event;
        try {
            if (message.getBody().length > 4096) throw new IllegalArgumentException();
            event = JSON.readValue(message.getBody(), MessageEvent.class);
            if (!MessageEvent.FILE_FINALIZE_REQUESTED.equals(event.eventType())) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            LOG.warn("Finalization message rejected: INVALID_ENVELOPE");
            channel.basicReject(tag, false); // Durable dead-letter queue; never an immediate requeue loop.
            return;
        }
        String previous = MDC.get("traceId");
        try {
            MDC.put("traceId", event.traceId());
            processor.process(event);
            channel.basicAck(tag, false);
        } catch (InvalidMessageException invalid) {
            LOG.warn("Finalization message rejected: UNKNOWN_EVENT");
            channel.basicReject(tag, false);
        } catch (RuntimeException unavailable) {
            LOG.warn("Finalization delivery remains unacknowledged: {}", unavailable.getClass().getSimpleName());
            // Closing this consumer channel preserves the unacked delivery. Container recovery waits
            // five seconds before redelivery, including when no database checkpoint can be committed.
            channel.abort();
        } finally {
            if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
        }
    }
}
