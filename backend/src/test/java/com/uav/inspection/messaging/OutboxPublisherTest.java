package com.uav.inspection.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    private static final String ID = "11000000-0000-4000-8000-000000000001";
    private static final String BIZ = "22000000-0000-4000-8000-000000000001";
    private OutboxTransactions transactions;
    private RabbitTemplate rabbit;
    private OutboxPublisher publisher;
    private OutboxTransactions.Delivery delivery;

    @BeforeEach
    void setup() {
        transactions = mock(OutboxTransactions.class);
        rabbit = mock(RabbitTemplate.class);
        Environment environment = new MockEnvironment().withProperty("inspection.messaging.exchange", "test.events");
        publisher = new OutboxPublisher(transactions, rabbit, environment);
        delivery = new OutboxTransactions.Delivery(ID,
                JsonMapper.builder().build().writeValueAsString(
                        new MessageEvent(ID, MessageEvent.FILE_FINALIZE_REQUESTED, BIZ, "test-trace")),
                "33000000-0000-4000-8000-000000000001", 1);
        when(transactions.claim()).thenReturn(delivery, (OutboxTransactions.Delivery) null);
    }

    @Test
    void marksSentOnlyAfterRoutableConfirmAndSendsPersistentMinimalEnvelope() {
        doAnswer(call -> {
            Message message = call.getArgument(2);
            assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
            assertEquals(ID, message.getMessageProperties().getMessageId());
            var json = JsonMapper.builder().build().readTree(message.getBody());
            assertEquals(4, json.size());
            for (String name : Set.of("eventId", "eventType", "bizId", "traceId")) assertTrue(json.has(name));
            verify(transactions, never()).sent(any());
            call.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq("test.events"), eq("file.finalize"), any(Message.class), any(CorrelationData.class));
        publisher.publishBatch();
        verify(transactions).sent(delivery);
        verify(transactions, never()).retry(any(), anyString());
    }

    @Test
    void positiveConfirmWithMandatoryReturnRemainsRetryable() {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "test.events", "file.finalize"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publishBatch();
        verify(transactions).retry(delivery, "UNROUTABLE");
        verify(transactions, never()).sent(any());
    }

    @Test
    void negativeConfirmDoesNotMarkSent() {
        doAnswer(call -> {
            call.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publishBatch();
        verify(transactions).retry(delivery, "PUBLISH_NACK");
        verify(transactions, never()).sent(any());
    }

    @Test
    void unknownConfirmKeepsSameDurableEventForRetry() {
        doAnswer(call -> {
            call.<CorrelationData>getArgument(3).getFuture().completeExceptionally(new TimeoutException());
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publishBatch();
        verify(transactions).retry(delivery, "PUBLISH_UNCONFIRMED");
        verify(transactions, never()).sent(any());
    }

    @Test
    void databaseFailureAfterConfirmLeavesRecoveryToOriginalSendingLease() {
        doAnswer(call -> {
            call.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        doThrow(new DataAccessResourceFailureException("unavailable")).when(transactions).sent(delivery);
        assertThrows(DataAccessResourceFailureException.class, publisher::publishBatch);
        verify(transactions, never()).retry(any(), anyString());
    }
}
