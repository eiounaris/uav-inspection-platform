package com.uav.inspection.messaging;

import com.uav.inspection.messaging.persistence.MessageRejectionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageRejectionRecorderTest {
    private final MessageRejectionMapper mapper = mock(MessageRejectionMapper.class);
    private final MessageRejectionRecorder recorder = new MessageRejectionRecorder(mapper);

    @Test
    void sqlReceivesOnlyBodyHashLengthFixedCodesAndCanonicalIdentity() {
        recorder.record("FILE_FINALIZE", "abc".getBytes(StandardCharsets.UTF_8), "INVALID_ENVELOPE",
                "11000000-0000-4000-8000-000000000001");
        verify(mapper).insertFirst(matches("[0-9a-f-]{36}"), eq("FILE_FINALIZE"),
                eq("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), eq(3L),
                eq("INVALID_ENVELOPE"), eq("11000000-0000-4000-8000-000000000001"));
    }

    @Test
    void arbitraryHeaderIsDiscardedAndZeroByteBodyHasAValidDigest() {
        recorder.record("EVIDENCE_ANCHOR", new byte[0], "MESSAGE_ID_MISMATCH", "private header/token");
        verify(mapper).insertFirst(anyString(), eq("EVIDENCE_ANCHOR"),
                eq("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), eq(0L),
                eq("MESSAGE_ID_MISMATCH"), isNull());
    }

    @Test
    void unrecognizedMetadataCannotReachSql() {
        assertThrows(IllegalArgumentException.class,
                () -> recorder.record("provider headers", new byte[0], "INVALID_ENVELOPE", null));
        assertThrows(IllegalArgumentException.class,
                () -> recorder.record("FILE_FINALIZE", new byte[0], "provider error body", null));
        verifyNoInteractions(mapper);
    }

    @Test
    void transactionalProxyCommitsIndependentTransactionBeforeReturningAndPropagatesCommitFailure() {
        class TestTransactions extends AbstractPlatformTransactionManager {
            int commits;
            boolean fail;
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, TransactionDefinition definition) {
                assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                commits++;
                if (fail) throw new CannotCreateTransactionException("commit unavailable");
            }
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        }
        var transactions = new TestTransactions();
        var proxy = new ProxyFactory(recorder);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var transactional = (MessageRejectionRecorder) proxy.getProxy();
        transactional.record("FILE_FINALIZE", new byte[0], "UNKNOWN_EVENT", null);
        assertEquals(1, transactions.commits);
        transactions.fail = true;
        assertThrows(CannotCreateTransactionException.class,
                () -> transactional.record("FILE_FINALIZE", new byte[0], "UNKNOWN_EVENT", null));
        assertEquals(2, transactions.commits);
    }
}
