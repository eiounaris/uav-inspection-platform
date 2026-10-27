package com.uav.inspection.messaging;

import com.rabbitmq.client.Channel;
import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.io.IOException;
import java.util.Objects;

public class FinalizationMessageListener implements ChannelAwareMessageListener {
    private final ManualMessageDelivery delivery;

    public FinalizationMessageListener(FinalizationProcessor processor, MessageRejectionRecorder rejections) {
        this(processor, rejections, ManualMessageDelivery::pauseBeforeRecovery);
    }

    FinalizationMessageListener(FinalizationProcessor processor, MessageRejectionRecorder rejections,
                                ManualMessageDelivery.RecoveryPause pause) {
        Objects.requireNonNull(processor);
        delivery = new ManualMessageDelivery("FILE_FINALIZE", MessageEvent.FILE_FINALIZE_REQUESTED,
                processor::process, rejections, pause);
    }

    @Override
    public void onMessage(Message message, Channel channel) throws IOException { delivery.onMessage(message, channel); }
}
