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
import java.util.List;
import java.util.ArrayList;
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
        when(transactions.sent(any())).thenReturn(true);
        when(transactions.retry(any(),anyString())).thenReturn(true);
        when(transactions.failed(any(),anyString())).thenReturn(true);
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

    @Test
    void payloadIdentityMustMatchAllIndependentDurableIdentityColumns() {
        for(var tampered:List.of(
                new OutboxTransactions.Delivery("11000000-0000-4000-8000-000000000099",MessageEvent.FILE_FINALIZE_REQUESTED,BIZ,delivery.payload(),delivery.token(),1),
                new OutboxTransactions.Delivery(ID,MessageEvent.EVIDENCE_ANCHOR_REQUESTED,BIZ,delivery.payload(),delivery.token(),1),
                new OutboxTransactions.Delivery(ID,MessageEvent.FILE_FINALIZE_REQUESTED,"22000000-0000-4000-8000-000000000099",delivery.payload(),delivery.token(),1))) {
            when(transactions.claim()).thenReturn(tampered,(OutboxTransactions.Delivery)null);
            publisher.publishBatch();
            verify(transactions).failed(tampered,"INVALID_ENVELOPE");
        }
        verifyNoInteractions(rabbit);
        verify(transactions,never()).sent(any()); verify(transactions,never()).retry(any(),anyString());
    }

    @Test
    void invalidJsonUnknownFieldsDuplicatesTrailingDataAndOversizedBodiesFailPermanently() {
        String valid=delivery.payload();
        for(String payload:List.of("{",valid.substring(0,valid.length()-1)+",\"unknown\":\"value\"}",
                valid.substring(0,valid.length()-1)+",\"traceId\":\"test-trace\"}",valid+" {}"," ".repeat(4097))) {
            var invalid=new OutboxTransactions.Delivery(ID,MessageEvent.FILE_FINALIZE_REQUESTED,BIZ,payload,delivery.token(),1);
            when(transactions.claim()).thenReturn(invalid,(OutboxTransactions.Delivery)null);
            publisher.publishBatch();
            verify(transactions).failed(invalid,"INVALID_ENVELOPE");
        }
        verifyNoInteractions(rabbit);
        verify(transactions,never()).sent(any()); verify(transactions,never()).retry(any(),anyString());
    }

    @Test
    void expiredSendingLeaseCannotBeClaimedAsSuccessfullySentOrRewrittenAsARetry() {
        when(transactions.sent(delivery)).thenReturn(false);
        when(transactions.claim()).thenReturn(delivery,delivery,(OutboxTransactions.Delivery)null);
        doAnswer(call->{call.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(true,null)); return null;})
                .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));

        publisher.publishBatch();

        verify(transactions).sent(delivery); verify(transactions).claim();
        verify(transactions,never()).retry(any(),anyString()); verify(transactions,never()).failed(any(),anyString());
    }

    @Test
    void aRetryAfterLostConfirmPublishesTheSameEventIdWithANewSendingToken() {
        var retry=new OutboxTransactions.Delivery(ID,delivery.eventType(),BIZ,delivery.payload(),"33000000-0000-4000-8000-000000000002",2);
        when(transactions.claim()).thenReturn(delivery,retry,(OutboxTransactions.Delivery)null);
        List<String> correlations=new ArrayList<>(),messageIds=new ArrayList<>();
        doAnswer(call->{
            Message message=call.getArgument(2); CorrelationData correlation=call.getArgument(3);
            assertArrayEquals(delivery.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8),message.getBody());
            messageIds.add(message.getMessageProperties().getMessageId()); correlations.add(correlation.getId());
            if(messageIds.size()==1) correlation.getFuture().completeExceptionally(new TimeoutException());
            else correlation.getFuture().complete(new CorrelationData.Confirm(true,null));
            return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));

        publisher.publishBatch();

        assertEquals(List.of(ID,ID),messageIds); assertNotEquals(correlations.get(0),correlations.get(1));
        verify(transactions).retry(delivery,"PUBLISH_UNCONFIRMED"); verify(transactions).sent(retry);
    }

    @Test
    void interruptedConfirmationKeepsTheInterruptAndSavesOnlyTheRetryCheckpoint() {
        doAnswer(call->{Thread.currentThread().interrupt(); return null;})
                .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        try {
            publisher.publishBatch();
            assertTrue(Thread.currentThread().isInterrupted());
            verify(transactions).retry(delivery,"PUBLISH_INTERRUPTED");
            verify(transactions,never()).sent(any());
        } finally { Thread.interrupted(); }
    }
}
