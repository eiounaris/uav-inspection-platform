package com.uav.inspection.messaging;

import com.rabbitmq.client.Channel;
import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinalizationMessageListenerTest {
    private final FinalizationProcessor processor = mock(FinalizationProcessor.class);
    private final Channel channel = mock(Channel.class);
    private final FinalizationMessageListener listener = new FinalizationMessageListener(processor);
    private final MessageEvent event = new MessageEvent("11000000-0000-4000-8000-000000000001",
            MessageEvent.FILE_FINALIZE_REQUESTED, "22000000-0000-4000-8000-000000000001", "listener-trace");

    @Test
    void acknowledgesOnlyAfterProcessorHasCommittedItsResultOrRecoveryCheckpoint() throws Exception {
        listener.onMessage(message(JsonMapper.builder().build().writeValueAsBytes(event)), channel);
        var ordered = inOrder(processor, channel);
        ordered.verify(processor).process(event);
        ordered.verify(channel).basicAck(17L, false);
    }

    @Test
    void databaseFailureKeepsUnackedDeliveryAndClosesConsumerChannelForDelayedRecovery() throws Exception {
        doThrow(new DataAccessResourceFailureException("unavailable")).when(processor).process(event);
        listener.onMessage(message(JsonMapper.builder().build().writeValueAsBytes(event)), channel);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        verify(channel).abort();
    }

    @Test
    void rejectsMalformedMessageIntoDeadLetterQueueWithoutCallingBusinessProcessor() throws Exception {
        listener.onMessage(message("{\"unexpected\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), channel);
        verify(channel).basicReject(17L, false);
        verifyNoInteractions(processor);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void rejectsEnvelopeWithoutMatchingDurableEventInsteadOfReplayingForever() throws Exception {
        doThrow(new InvalidMessageException()).when(processor).process(event);
        listener.onMessage(message(JsonMapper.builder().build().writeValueAsBytes(event)), channel);
        verify(channel).basicReject(17L, false);
        verify(channel, never()).abort();
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private Message message(byte[] bytes) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(17L);
        return new Message(bytes, properties);
    }
}
