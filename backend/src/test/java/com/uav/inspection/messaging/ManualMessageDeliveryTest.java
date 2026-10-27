package com.uav.inspection.messaging;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManualMessageDeliveryTest {
    private final MessageEvent event = new MessageEvent("11000000-0000-4000-8000-000000000001",
            MessageEvent.FILE_FINALIZE_REQUESTED, "22000000-0000-4000-8000-000000000001", "wire-trace");
    private final Channel channel = mock(Channel.class);
    private final MessageRejectionRecorder rejections = mock(MessageRejectionRecorder.class);
    @SuppressWarnings("unchecked")
    private final Consumer<MessageEvent> processor = mock(Consumer.class);
    private final ManualMessageDelivery delivery = new ManualMessageDelivery("FILE_FINALIZE",
            MessageEvent.FILE_FINALIZE_REQUESTED, processor, rejections, () -> {});

    @Test
    void matchedMessageIdentityIsProcessedWithoutARejectionAndRestoresCallerTrace() throws Exception {
        MDC.put("traceId", "caller-trace");
        try {
            doAnswer(invocation -> { assertEquals("wire-trace", MDC.get("traceId")); return null; })
                    .when(processor).accept(event);
            delivery.onMessage(message(bytes(), event.eventId()), channel);
            assertEquals("caller-trace", MDC.get("traceId"));
            verify(processor).accept(event);
            verify(channel).basicAck(31, false);
            verifyNoInteractions(rejections);
        } finally { MDC.remove("traceId"); }
    }

    @Test
    void suppliedDifferentOrNonCanonicalMessageIdentityIsRecordedBeforeDeadLettering() throws Exception {
        for (String id : new String[]{"33000000-0000-4000-8000-000000000001", "secret-arbitrary-header", ""}) {
            reset(rejections, channel);
            delivery.onMessage(message(bytes(), id), channel);
            var order = inOrder(rejections, channel);
            order.verify(rejections).record(eq("FILE_FINALIZE"), any(byte[].class), eq("MESSAGE_ID_MISMATCH"), eq(id));
            order.verify(channel).basicReject(31, false);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
        }
        verifyNoInteractions(processor);
    }

    @Test
    void malformedEnvelopeRecordingFailurePreservesUnackedDeliveryAndDoesNotReject() throws Exception {
        doThrow(new DataAccessResourceFailureException("sensitive diagnostics"))
                .when(rejections).record(anyString(), any(), anyString(), nullable(String.class));
        delivery.onMessage(message("not-json".getBytes(StandardCharsets.UTF_8), null), channel);
        verify(channel).abort();
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        verifyNoInteractions(processor);
    }

    @Test
    void unknownDurableEventRecordingFailureAlsoPreservesDeliveryAndRemovesTrace() throws Exception {
        doThrow(new InvalidMessageException()).when(processor).accept(event);
        doThrow(new DataAccessResourceFailureException("sensitive diagnostics"))
                .when(rejections).record(anyString(), any(), eq("UNKNOWN_EVENT"), nullable(String.class));
        MDC.remove("traceId");
        delivery.onMessage(message(bytes(), null), channel);
        assertNull(MDC.get("traceId"));
        verify(channel).abort();
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void rejectTransportFailureCannotTurnARecordedRejectionIntoAnAcknowledgement() throws Exception {
        doThrow(new java.io.IOException("transport")).when(channel).basicReject(31, false);
        assertThrows(java.io.IOException.class,
                () -> delivery.onMessage(message(new byte[0], null), channel));
        verify(rejections).record(eq("FILE_FINALIZE"), any(), eq("INVALID_ENVELOPE"), isNull());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void retryableProcessorFailureNeverWritesARejectionOrRequeuesImmediately() throws Exception {
        doThrow(new IllegalStateException("sensitive diagnostics")).when(processor).accept(event);
        delivery.onMessage(message(bytes(), event.eventId()), channel);
        verify(channel).abort();
        verifyNoInteractions(rejections);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void transactionFailurePausesWhileDeliveryIsUnackedThenAborts() throws Exception {
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(rejections).record(anyString(), any(), anyString(), nullable(String.class));
        var steps = new java.util.ArrayList<String>();
        var paused = new ManualMessageDelivery("FILE_FINALIZE", MessageEvent.FILE_FINALIZE_REQUESTED,
                processor, rejections, () -> {
                    verify(rejections).record(eq("FILE_FINALIZE"), any(), eq("INVALID_ENVELOPE"), isNull());
                    verifyNoInteractions(channel);
                    steps.add("pause");
                });
        doAnswer(invocation -> { steps.add("abort"); return null; }).when(channel).abort();
        paused.onMessage(message(new byte[0], null), channel);
        assertEquals(java.util.List.of("pause", "abort"), steps);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void interruptedPauseRestoresInterruptAndStillAbortsWithoutAckOrReject() throws Exception {
        doThrow(new IllegalStateException("unavailable")).when(processor).accept(event);
        var paused = new ManualMessageDelivery("FILE_FINALIZE", MessageEvent.FILE_FINALIZE_REQUESTED,
                processor, rejections, () -> { throw new InterruptedException(); });
        try {
            assertFalse(Thread.currentThread().isInterrupted());
            paused.onMessage(message(bytes(), event.eventId()), channel);
            assertTrue(Thread.currentThread().isInterrupted());
            verify(channel).abort();
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            verify(channel, never()).basicReject(anyLong(), anyBoolean());
            verifyNoInteractions(rejections);
        } finally { Thread.interrupted(); }
    }

    private byte[] bytes() { return JsonMapper.builder().build().writeValueAsBytes(event); }

    private Message message(byte[] bytes, String id) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(31); properties.setMessageId(id);
        return new Message(bytes, properties);
    }
}
