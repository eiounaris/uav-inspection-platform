package com.uav.inspection.messaging;

import com.rabbitmq.client.Channel;
import com.uav.inspection.blockchain.anchor.AnchorProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** A broker acknowledgement follows a committed anchor outcome or durable recovery checkpoint. */
public class AnchorMessageListener implements ChannelAwareMessageListener {
    private static final Logger LOG = LoggerFactory.getLogger(AnchorMessageListener.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AnchorProcessor processor;
    public AnchorMessageListener(AnchorProcessor processor) { this.processor = processor; }

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        MessageEvent event;
        try {
            if (message.getBody().length > 4096) throw new IllegalArgumentException();
            event = JSON.readValue(message.getBody(), MessageEvent.class);
            if (!MessageEvent.EVIDENCE_ANCHOR_REQUESTED.equals(event.eventType())) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            LOG.warn("Anchor message rejected: INVALID_ENVELOPE");
            channel.basicReject(tag, false);
            return;
        }
        String previous = MDC.get("traceId");
        try {
            MDC.put("traceId", event.traceId());
            processor.process(event);
            channel.basicAck(tag, false);
        } catch (InvalidMessageException invalid) {
            LOG.warn("Anchor message rejected: UNKNOWN_EVENT");
            channel.basicReject(tag, false);
        } catch (RuntimeException unavailable) {
            LOG.warn("Anchor delivery remains unacknowledged: {}", unavailable.getClass().getSimpleName());
            channel.abort();
        } finally {
            if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
        }
    }
}
