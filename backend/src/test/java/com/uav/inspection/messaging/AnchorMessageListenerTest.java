package com.uav.inspection.messaging;

import com.rabbitmq.client.Channel;
import com.uav.inspection.blockchain.anchor.AnchorProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnchorMessageListenerTest {
    private final AnchorProcessor processor = mock(AnchorProcessor.class);
    private final Channel channel = mock(Channel.class);
    private final AnchorMessageListener listener = new AnchorMessageListener(processor);
    private final MessageEvent event = new MessageEvent("11000000-0000-4000-8000-000000000001",
            MessageEvent.EVIDENCE_ANCHOR_REQUESTED, "22000000-0000-4000-8000-000000000001", "anchor-trace");

    @Test
    void acknowledgesOnlyAfterCommittedBusinessOutcomeOrDurableUnknownCheckpoint() throws Exception {
        listener.onMessage(message(JsonMapper.builder().build().writeValueAsBytes(event)), channel);
        var ordered = inOrder(processor, channel);
        ordered.verify(processor).process(event);
        ordered.verify(channel).basicAck(19L, false);
    }

    @Test
    void unavailableDatabaseDoesNotAcknowledgeOrDeadLetterRecoverableWork() throws Exception {
        doThrow(new DataAccessResourceFailureException("unavailable")).when(processor).process(event);
        listener.onMessage(message(JsonMapper.builder().build().writeValueAsBytes(event)), channel);
        verify(channel).abort();
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void wrongEventTypeGoesToDeadLetterWithoutAnAnchorCall() throws Exception {
        var wrong = new MessageEvent(event.eventId(), MessageEvent.FILE_FINALIZE_REQUESTED, event.bizId(), event.traceId());
        listener.onMessage(message(JsonMapper.builder().build().writeValueAsBytes(wrong)), channel);
        verify(channel).basicReject(19L, false);
        verifyNoInteractions(processor);
    }

    @Test
    void unknownDurableEventIsRejectedWithoutAnImmediateRequeueLoop() throws Exception {
        doThrow(new InvalidMessageException()).when(processor).process(event);
        listener.onMessage(message(JsonMapper.builder().build().writeValueAsBytes(event)), channel);
        verify(channel).basicReject(19L, false);
        verify(channel, never()).abort();
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private Message message(byte[] bytes) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(19L);
        return new Message(bytes, properties);
    }
}
