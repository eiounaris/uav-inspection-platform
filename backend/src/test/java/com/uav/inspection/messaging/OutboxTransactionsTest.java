package com.uav.inspection.messaging;

import com.uav.inspection.messaging.OutboxTransactions.Delivery;
import com.uav.inspection.messaging.persistence.OutboxMapper;
import com.uav.inspection.messaging.persistence.OutboxMapper.PendingRow;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxTransactionsTest {
    private static final MessageEvent EVENT=new MessageEvent("11000000-0000-4000-8000-000000000001",
            MessageEvent.FILE_FINALIZE_REQUESTED,"22000000-0000-4000-8000-000000000001","tx-test");
    private static final String TOKEN="33000000-0000-4000-8000-000000000001";
    private static final String PAYLOAD=JsonMapper.builder().build().writeValueAsString(EVENT);
    private final OutboxMapper mapper=mock(OutboxMapper.class);
    private final RecordingManager manager=new RecordingManager();
    private final OutboxTransactions service=transactional();

    @Test
    void claimBindsIndependentDurableIdentityAndPayloadAndCommitsBeforeDelivery() {
        when(mapper.lockDue()).thenReturn(new PendingRow(EVENT.eventId(),EVENT.eventType(),EVENT.bizId(),PAYLOAD,4));
        when(mapper.claim(eq(EVENT.eventId()),anyString())).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return 1;
        });
        Delivery delivery=service.claim();
        assertEquals(EVENT.eventId(),delivery.eventId()); assertEquals(EVENT.eventType(),delivery.eventType());
        assertEquals(EVENT.bizId(),delivery.bizId()); assertEquals(PAYLOAD,delivery.payload());
        assertEquals(5,delivery.attemptCount()); assertTrue(delivery.token().matches("[0-9a-f-]{36}"));
        verify(mapper).claim(EVENT.eventId(),delivery.token()); assertEquals(1,manager.commits);
    }

    @Test
    void aConditionalClaimRejectionDoesNotReturnASendingLease() {
        when(mapper.lockDue()).thenReturn(new PendingRow(EVENT.eventId(),EVENT.eventType(),EVENT.bizId(),PAYLOAD,4));
        when(mapper.claim(eq(EVENT.eventId()),anyString())).thenReturn(0);
        assertNull(service.claim());
        verify(mapper,never()).sent(anyString(),anyString()); verify(mapper,never()).retry(anyString(),anyString(),anyString(),anyInt());
    }

    @Test
    void noDueRowDoesNotAttemptAClaimOrCreateAnEvent() {
        assertNull(service.claim());
        verify(mapper,never()).claim(anyString(),anyString()); verify(mapper,never()).insert(anyString(),anyString(),anyString(),anyString());
    }

    @Test
    void expiredOrReplacedLeasesCannotBeReportedAsSuccessfulCheckpoints() {
        Delivery delivery=delivery(1);
        assertFalse(service.sent(delivery)); assertFalse(service.retry(delivery,"PUBLISH_NACK"));
        assertFalse(service.failed(delivery,"INVALID_ENVELOPE"));
        verify(mapper).sent(EVENT.eventId(),TOKEN); verify(mapper).retry(EVENT.eventId(),TOKEN,"PUBLISH_NACK",2);
        verify(mapper).failed(EVENT.eventId(),TOKEN,"INVALID_ENVELOPE");
    }

    @Test
    void retriesKeepTheOriginalIdentityAndUseTheCappedDatabaseBackoff() {
        when(mapper.retry(anyString(),anyString(),anyString(),anyInt())).thenReturn(1);
        int[] expected={2,10,30,60,300,300,300};
        for(int index=0;index<expected.length;index++) {
            assertTrue(service.retry(delivery(index+1),"PUBLISH_UNCONFIRMED"));
        }
        var delays=org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(mapper,times(expected.length)).retry(eq(EVENT.eventId()),eq(TOKEN),eq("PUBLISH_UNCONFIRMED"),delays.capture());
        assertArrayEquals(expected,delays.getAllValues().stream().mapToInt(Integer::intValue).toArray());
        verify(mapper,never()).insert(anyString(),anyString(),anyString(),anyString());
    }

    @Test
    void databaseCommitOrUpdateFailurePropagatesWithoutAnAlternativeRetryOrANewEvent() {
        var failure=new DataAccessResourceFailureException("database unavailable");
        when(mapper.sent(EVENT.eventId(),TOKEN)).thenThrow(failure);
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->service.sent(delivery(1))));
        verify(mapper,never()).retry(anyString(),anyString(),anyString(),anyInt());
        verify(mapper,never()).insert(anyString(),anyString(),anyString(),anyString());
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
    }

    @Test
    void unsafeFailureTextAndUnexpectedPermanentCodesCannotEnterTheCheckpoint() {
        assertThrows(IllegalArgumentException.class,()->service.retry(delivery(1),"raw broker reply"));
        assertThrows(IllegalArgumentException.class,()->service.failed(delivery(1),"PUBLISH_NACK"));
        verifyNoInteractions(mapper);
    }

    @Test
    void legacyLocalConstructorsRemainCompatibleWhileProductionUsesFullRowFields() {
        Delivery old=new Delivery(EVENT.eventId(),PAYLOAD,TOKEN,1);
        PendingRow row=new PendingRow(EVENT.eventId(),PAYLOAD,0);
        assertEquals(EVENT.eventType(),old.eventType()); assertEquals(EVENT.bizId(),old.bizId());
        assertEquals(EVENT.eventType(),row.eventType()); assertEquals(EVENT.bizId(),row.bizId());
        assertEquals(PAYLOAD,old.payload());
    }

    @Test
    void mapperCheckpointsParseAndUseBoundLeaseIdentifiers() {
        var configuration=new Configuration(); configuration.addMapper(OutboxMapper.class);
        Map<String,Object> parameters=Map.of("id",EVENT.eventId(),"token",TOKEN,"code","INVALID_ENVELOPE","delay",2);
        for(String name:new String[]{"claim","sent","retry","failed"}) {
            var bound=configuration.getMappedStatement(OutboxMapper.class.getName()+"."+name).getBoundSql(parameters);
            assertFalse(bound.getSql().contains(TOKEN)); assertFalse(bound.getSql().contains(EVENT.eventId()));
            assertTrue(bound.getParameterMappings().stream().anyMatch(mapping->"token".equals(mapping.getProperty())));
        }
    }

    private static Delivery delivery(int attempt) { return new Delivery(EVENT.eventId(),EVENT.eventType(),EVENT.bizId(),PAYLOAD,TOKEN,attempt); }
    private OutboxTransactions transactional() {
        var proxy=new ProxyFactory(new OutboxTransactions(mapper)); proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return (OutboxTransactions)proxy.getProxy();
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        private int commits,rollbacks;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
