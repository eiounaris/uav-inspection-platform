package com.uav.inspection.messaging;

import com.rabbitmq.client.Channel;
import com.uav.inspection.blockchain.anchor.AnchorProcessor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.io.IOException;
import java.util.Objects;

public class AnchorMessageListener implements ChannelAwareMessageListener {
    private final ManualMessageDelivery delivery;

    public AnchorMessageListener(AnchorProcessor processor, MessageRejectionRecorder rejections) {
        this(processor, rejections, ManualMessageDelivery::pauseBeforeRecovery);
    }

    AnchorMessageListener(AnchorProcessor processor, MessageRejectionRecorder rejections,
                          ManualMessageDelivery.RecoveryPause pause) {
        Objects.requireNonNull(processor);
        delivery = new ManualMessageDelivery("EVIDENCE_ANCHOR", MessageEvent.EVIDENCE_ANCHOR_REQUESTED,
                processor::process, rejections, pause);
    }

    @Override
    public void onMessage(Message message, Channel channel) throws IOException { delivery.onMessage(message, channel); }
}
