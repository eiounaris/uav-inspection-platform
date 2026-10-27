package com.uav.inspection.messaging;

import com.uav.inspection.messaging.persistence.OutboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxWriterTest {
    @Test
    void anExplicitNewDispatchUsesANewEventWithOnlyTheExistingSafeWireFields() {
        OutboxMapper mapper=mock(OutboxMapper.class);
        when(mapper.insertDispatch(anyString(),anyString(),anyString(),eq(3),anyString())).thenReturn(1);
        String biz=UUID.randomUUID().toString();
        String id=new OutboxWriter(mapper).enqueue("EVIDENCE_ANCHOR_REQUESTED",biz,"recheck",3);
        var body=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).insertDispatch(eq(id),eq("EVIDENCE_ANCHOR_REQUESTED"),eq(biz),eq(3),body.capture());
        assertEquals(new MessageEvent(id,"EVIDENCE_ANCHOR_REQUESTED",biz,"recheck"),
                MessageEvent.decode(body.getValue().getBytes(StandardCharsets.UTF_8)));
        verify(mapper,never()).insert(anyString(),anyString(),anyString(),anyString());
    }

    @Test
    void neitherInitialNorManualDispatchCanCommitOutsideItsCallingBusinessTransaction() {
        var mapper=mock(OutboxMapper.class);
        var proxy=new ProxyFactory(new OutboxWriter(mapper));proxy.setProxyTargetClass(true);
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object transaction,TransactionDefinition definition){}
            protected void doCommit(DefaultTransactionStatus status){}
            protected void doRollback(DefaultTransactionStatus status){}
        };
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        var writer=(OutboxWriter)proxy.getProxy();
        String biz=UUID.randomUUID().toString();
        assertThrows(IllegalTransactionStateException.class,()->writer.enqueue("EVIDENCE_ANCHOR_REQUESTED",biz,"trace"));
        assertThrows(IllegalTransactionStateException.class,()->writer.enqueue("EVIDENCE_ANCHOR_REQUESTED",biz,"trace",1));
        verifyNoInteractions(mapper);
    }

    @Test
    void negativeDispatchIsRejectedBeforePersistence() {
        var mapper=mock(OutboxMapper.class);
        assertThrows(IllegalArgumentException.class,()->new OutboxWriter(mapper)
                .enqueue("EVIDENCE_ANCHOR_REQUESTED",UUID.randomUUID().toString(),"trace",-1));
        verifyNoInteractions(mapper);
    }
}
