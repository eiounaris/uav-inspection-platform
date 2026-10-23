package com.uav.inspection.evidence.finalization;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.evidence.finalization.FinalizationTransactions.Claim;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.evidence.storage.UploadSettings;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.PartRow;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.SessionRow;
import com.uav.inspection.messaging.MessageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinalizationProcessorTest {
    private static final String UPLOAD="80000000-0000-4000-8000-000000000001";
    private static final String TOKEN="90000000-0000-4000-8000-000000000001";
    private static final MessageEvent EVENT=new MessageEvent("90000000-0000-4000-8000-000000000002","FILE_FINALIZE_REQUESTED",UPLOAD,"unit-finalize");
    @TempDir Path directory;
    private final FinalizationTransactions phases=mock(FinalizationTransactions.class);
    private final ObjectStorage storage=mock(ObjectStorage.class);
    private UploadSettings settings;
    private byte[] image;
    private Claim claim;

    @BeforeEach
    void configure() throws Exception {
        settings=new UploadSettings(new MockEnvironment()
                .withProperty("inspection.upload.max-file-size","1048576")
                .withProperty("inspection.upload.part-size","1048576")
                .withProperty("inspection.upload.session-ttl-seconds","3600")
                .withProperty("inspection.upload.min-free-space","0")
                .withProperty("inspection.upload.allowed-extensions","png")
                .withProperty("inspection.upload.temp-directory",directory.resolve("private").toString()));
        var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",bytes);
        image=bytes.toByteArray();
        Instant now=Instant.parse("2026-10-22T00:00:00Z");
        SessionRow session=new SessionRow(UPLOAD,"task","subtask","requirement","evidence","execution","org",
                "USER","user","client","hash","image.png","image/png",image.length,1048576,1,"VERIFYING",now.plusSeconds(3600),0,now,now);
        claim=new Claim(EVENT,session,List.of(new PartRow(UPLOAD,1,image.length,hash(image),"private-part")),TOKEN);
        when(phases.claim(EVENT,false)).thenReturn(claim);
        when(storage.open("private-part")).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new ByteArrayInputStream(image);
        });
    }

    @Test
    void fullDigestUsesActualBytesAndAllStorageIoRunsOutsideATransaction() throws Exception {
        AtomicReference<Path> temporary=new AtomicReference<>();
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Path file=call.getArgument(1);
            assertArrayEquals(image,Files.readAllBytes(file));
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
            temporary.set(file); return null;
        }).when(storage).put(anyString(),any(Path.class),eq("image/png"));

        processor().process(EVENT);

        verify(phases).finish(claim,"evidence-files/"+UPLOAD+"/"+TOKEN,image.length,hash(image));
        assertFalse(Files.exists(temporary.get()));
        verify(storage,never()).delete(anyString());
    }

    @Test
    void tamperedPartIsARecordedDefinitiveFailureWithoutPublishingAVersion() throws Exception {
        byte[] tampered=image.clone(); tampered[20]^=1;
        when(storage.open("private-part")).thenReturn(new ByteArrayInputStream(tampered));
        processor().process(EVENT);
        verify(phases).checkpoint(claim,true,"PART_HASH_MISMATCH");
        verify(phases,never()).finish(any(),anyString(),anyLong(),anyString());
        verify(storage,never()).put(anyString(),any(),anyString());
        assertEmptyTemporaryDirectory();
    }

    @Test
    void temporaryStorageFailurePersistsRecoveryResponsibilityBeforeReturning() throws Exception {
        when(storage.open("private-part")).thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        processor().process(EVENT);
        verify(phases).checkpoint(claim,false,"STORAGE_UNAVAILABLE");
        verify(phases,never()).finish(any(),anyString(),anyLong(),anyString());
        assertEmptyTemporaryDirectory();
    }

    @Test
    void unknownCommitResultPreservesTheFinalCandidateAndDoesNotPretendToCheckpoint() throws Exception {
        var failure=new TransactionSystemException("Unknown commit result");
        when(phases.finish(any(),anyString(),anyLong(),anyString())).thenThrow(failure);
        assertSame(failure,assertThrows(TransactionSystemException.class,()->processor().process(EVENT)));
        verify(storage).put(eq("evidence-files/"+UPLOAD+"/"+TOKEN),any(),eq("image/png"));
        verify(storage,never()).delete(anyString());
        verify(phases,never()).checkpoint(any(),anyBoolean(),anyString());
        assertEmptyTemporaryDirectory();
    }

    @Test
    void scannerCallsTheRecoveryClaimPathEvenAfterACompletedMessageCheckpoint() {
        when(phases.due()).thenReturn(List.of(EVENT));
        when(phases.claim(EVENT,true)).thenReturn(claim);
        processor().recoverDue();
        verify(phases).claim(EVENT,true);
        verify(phases).finish(eq(claim),anyString(),eq((long)image.length),anyString());
    }

    private FinalizationProcessor processor() {
        var factory=new ProxyFactory(new FinalizationProcessor(phases,storage,settings));
        factory.setProxyTargetClass(true);
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object transaction,TransactionDefinition definition){}
            protected void doCommit(DefaultTransactionStatus status){}
            protected void doRollback(DefaultTransactionStatus status){}
        };
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (FinalizationProcessor)factory.getProxy();
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void assertEmptyTemporaryDirectory() throws Exception {
        try(var files=Files.list(settings.tempDirectory())) { assertEquals(0,files.count()); }
    }
}
