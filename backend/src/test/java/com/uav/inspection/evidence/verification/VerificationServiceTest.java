package com.uav.inspection.evidence.verification;

import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.EvidenceLedgerClient.*;
import com.uav.inspection.blockchain.InstitutionLedgerClients;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.temp.TemporaryFileManager;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.evidence.verification.VerificationModels.*;
import com.uav.inspection.evidence.verification.VerificationTransactions.Prepared;
import com.uav.inspection.evidence.verification.persistence.VerificationMapper.VersionRow;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import io.grpc.Status;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationServiceTest {
    private static final String VERSION="80000000-0000-4000-8000-000000000001";
    private static final String TX="f".repeat(64);
    private static final CurrentUser ACTOR=new CurrentUser("user",ResourcePermissionService.OWNER,"owner",List.of(Role.OWNER_REVIEWER),0,0);
    private static final VersionRow VERSION_ROW=new VersionRow(VERSION,"evidence",3,"task","private/object",
            ResourcePermissionService.OPERATOR,ResourcePermissionService.OWNER,ResourcePermissionService.OPERATOR);
    @TempDir Path directory;
    private final VerificationTransactions phases=mock(VerificationTransactions.class);
    private final ObjectStorage storage=mock(ObjectStorage.class);
    private final EvidenceLedgerClient owner=mock(EvidenceLedgerClient.class),operator=mock(EvidenceLedgerClient.class);
    private final InstitutionLedgerClients clients=new InstitutionLedgerClients(owner,operator,"Org1MSP","Org2MSP");
    private final byte[] original={(byte)137,80,78,71,13,10,26,10,1,2,3,4,5,6,7,8};

    @BeforeEach
    void setup() throws Exception {
        when(phases.prepare(ACTOR,VERSION)).thenAnswer(call->{ outsideTransaction(); return new Prepared(ACTOR,VERSION_ROW); });
        when(phases.save(eq(ACTOR),eq(VERSION),anyString(),any())).thenAnswer(call->{
            outsideTransaction();
            Computed result=call.getArgument(3);
            return new VerificationView("record",VERSION,ACTOR.id(),call.getArgument(2),result.actualSha256(),result.ledgerSha256(),
                    result.actualSize(),result.ledgerTxId(),result.result(),result.reason(),Instant.parse("2026-10-23T00:00:00Z"));
        });
        when(storage.open("private/object")).thenAnswer(call->{ outsideTransaction(); return new ByteArrayInputStream(original); });
        when(owner.queryEvidence("evidence",3)).thenAnswer(call->{ outsideTransaction(); return found(hash(original)); });
    }

    @Test
    void anUnattainableCopyFreeSpaceFloorIsReportedBeforeOpeningAnyInputOrTemporaryFile() throws Exception {
        var limits=new VerificationSettings(new MockEnvironment()
                .withProperty("inspection.verification.concurrency","1")
                .withProperty("inspection.verification.timeout-seconds","5")
                .withProperty("inspection.verification.min-free-space",Long.toString(Long.MAX_VALUE))
                .withProperty("inspection.verification.temp-directory",directory.resolve("space").toString()));
        var temporary=mock(TemporaryFileManager.class);
        var input=mock(VerificationService.CopyInput.class);
        try(var service=new VerificationService(phases,storage,clients,limits,temporary)) {
            var result=service.verifyCopy(ACTOR,VERSION,"copy.png","image/png",original.length,input);
            assertEquals("FILE_READ_FAILED",result.result());
            assertEquals("LOCAL_SPACE_UNAVAILABLE",result.reason());
            verifyNoInteractions(temporary,input,storage,owner,operator);
            try(var entries=Files.list(limits.tempDirectory())) { assertEquals(0,entries.count()); }
        }
    }

    @Test
    void aCleanupPassCannotDeleteTheCopyWhileVerificationIsReadingIt() throws Exception {
        var settings=settings(1,5);
        try(var service=new VerificationService(phases,storage,clients,settings,new TemporaryFileManager())) {
            var result=service.verifyCopy(ACTOR,VERSION,"copy.png","image/png",original.length,()->{
                Path temporary;
                try(var entries=Files.list(settings.tempDirectory())) {
                    temporary=entries.filter(p->p.getFileName().toString().endsWith(".tmp")).findFirst().orElseThrow();
                }
                Files.setLastModifiedTime(temporary,FileTime.from(Instant.now().minusSeconds(7200)));
                var sweep=new TemporaryFileManager().sweep(settings.tempDirectory(),Duration.ofSeconds(60),100);
                assertTrue(sweep.locked()>0);assertEquals(0,sweep.deleted());assertTrue(Files.exists(temporary));
                return new ByteArrayInputStream(original);
            });
            assertEquals("MATCH",result.result());assertTemporaryDirectoryEmpty();
        }
    }

    @Test
    void storedVerificationRecomputesActualBytesAndQueriesTheCurrentUsersInstitution() throws Exception {
        try(var service=service(settings(2,5))) {
            VerificationView view=service.verifyStored(ACTOR,VERSION);
            assertEquals("MATCH",view.result());
            assertEquals(hash(original),view.actualSha256());
            assertEquals((long)original.length,view.actualSize());
            assertEquals(TX,view.ledgerTxId());
            verifyNoInteractions(operator);
        }
    }

    @Test
    void changingStoredBytesChangesTheResultEvenThoughTheDatabaseVersionIsUnchanged() throws Exception {
        byte[] changed=java.util.Arrays.copyOf(original,original.length+1); changed[changed.length-1]=42;
        when(storage.open("private/object")).thenReturn(new ByteArrayInputStream(changed));
        try(var service=service(settings(2,5))) {
            VerificationView view=service.verifyStored(ACTOR,VERSION);
            assertEquals("MISMATCH",view.result());
            assertEquals(hash(changed),view.actualSha256());
            assertEquals(hash(original),view.ledgerSha256());
            assertEquals((long)changed.length,view.actualSize());
        }
    }

    @Test
    void modifiedCopyPayloadCanMismatchWithoutBeingReinterpretedAsAnUploadOrRejectedByAFullFormatDecoder() throws Exception {
        byte[] changed=original.clone(); changed[12]^=1;
        try(var service=service(settings(2,5))) {
            VerificationView view=service.verifyCopy(ACTOR,VERSION,"copy.png","image/png",changed.length,()->new ByteArrayInputStream(changed));
            assertEquals("COPY",view.source());
            assertEquals("MISMATCH",view.result());
            verifyNoInteractions(storage);
            assertTemporaryDirectoryEmpty();
        }
    }

    @Test
    void missingFilesAndPartialReadFailuresNeverExposeAPartialDigestOrQueryTheLedger() throws Exception {
        try(var service=service(settings(2,5))) {
            when(storage.open("private/object")).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            VerificationView missing=service.verifyStored(ACTOR,VERSION);
            assertEquals("FILE_READ_FAILED",missing.result());
            assertNull(missing.actualSha256()); assertNull(missing.actualSize());

            doReturn(new InputStream() {
                private int count;
                public int read() throws IOException { if(count++<3) return 7; throw new IOException("Disconnected object response"); }
            }).when(storage).open("private/object");
            VerificationView partial=service.verifyStored(ACTOR,VERSION);
            assertEquals("FILE_READ_FAILED",partial.result());
            assertNull(partial.actualSha256()); assertNull(partial.ledgerSha256()); assertNull(partial.actualSize());
            verifyNoInteractions(owner,operator);
        }
    }

    @Test
    void absentLedgerRecordAndUnavailableLedgerHaveDifferentUncertainResults() throws Exception {
        try(var service=service(settings(2,5))) {
            when(owner.queryEvidence("evidence",3)).thenReturn(new EvidenceQuery(false,null));
            VerificationView absent=service.verifyStored(ACTOR,VERSION);
            assertEquals("ANCHOR_UNCONFIRMED",absent.result());
            assertEquals(hash(original),absent.actualSha256()); assertNull(absent.ledgerSha256());
            doThrow(Status.DEADLINE_EXCEEDED.asRuntimeException()).when(owner).queryEvidence("evidence",3);
            VerificationView unavailable=service.verifyStored(ACTOR,VERSION);
            assertEquals("CHAIN_QUERY_FAILED",unavailable.result());
            assertEquals(hash(original),unavailable.actualSha256()); assertNull(unavailable.ledgerSha256());
        }
    }

    @Test
    void malformedLedgerScopeHashOrTransactionIdentifierCannotBecomeAContentMismatch() throws Exception {
        List<EvidenceRecord> invalid=List.of(new EvidenceRecord("foreign",3,hash(original),"Org2MSP",TX),
                new EvidenceRecord("evidence",4,hash(original),"Org2MSP",TX),
                new EvidenceRecord("evidence",3,hash(original),"Org1MSP",TX),
                new EvidenceRecord("evidence",3,"bad-hash","Org2MSP",TX),
                new EvidenceRecord("evidence",3,hash(original),"Org2MSP","bad-tx"));
        try(var service=service(settings(2,5))) {
            for(EvidenceRecord record:invalid) {
                when(owner.queryEvidence("evidence",3)).thenReturn(new EvidenceQuery(true,record));
                VerificationView result=service.verifyStored(ACTOR,VERSION);
                assertEquals("CHAIN_QUERY_FAILED",result.result());
                assertEquals("LEDGER_RESPONSE_INVALID",result.reason());
                assertNull(result.ledgerSha256()); assertNull(result.ledgerTxId());
            }
        }
    }

    @Test
    void authorizationPrecedesOpeningAnyCopyOrStoredObject() {
        when(phases.prepare(ACTOR,VERSION)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        AtomicInteger opens=new AtomicInteger();
        try(var service=service(settings(2,5))) {
            assertThrows(BusinessException.class,()->service.verifyCopy(ACTOR,VERSION,"copy.png","image/png",original.length,()->{
                opens.incrementAndGet(); return new ByteArrayInputStream(original);
            }));
            assertThrows(BusinessException.class,()->service.verifyStored(ACTOR,VERSION));
            assertEquals(0,opens.get());
            verifyNoInteractions(storage,owner,operator);
        }
    }

    @Test
    void invalidCopyMetadataAndMagicAreInputErrorsWithNoRecordedVerification() throws Exception {
        AtomicInteger opens=new AtomicInteger();
        try(var service=service(settings(2,5))) {
            BusinessException tooLarge=assertThrows(BusinessException.class,()->service.verifyCopy(ACTOR,VERSION,"copy.png","image/png",1048577,()->{
                opens.incrementAndGet(); return new ByteArrayInputStream(original);
            }));
            assertEquals(ErrorCode.FILE_TOO_LARGE,tooLarge.errorCode());
            assertEquals(0,opens.get());
            assertThrows(BusinessException.class,()->service.verifyCopy(ACTOR,VERSION,"copy.exe","application/octet-stream",16,()->new ByteArrayInputStream(original)));
            assertThrows(BusinessException.class,()->service.verifyCopy(ACTOR,VERSION,"copy.png","image/png",16,()->new ByteArrayInputStream(new byte[16])));
            verify(phases,never()).save(any(),anyString(),anyString(),any());
            verifyNoInteractions(owner,operator,storage);
            assertTemporaryDirectoryEmpty();
        }
    }

    @Test
    void finalPermissionRevocationPreventsReturningTheAlreadyComputedResult() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(phases).save(eq(ACTOR),eq(VERSION),anyString(),any());
        try(var service=service(settings(2,5))) {
            BusinessException forbidden=assertThrows(BusinessException.class,()->service.verifyStored(ACTOR,VERSION));
            assertEquals(ErrorCode.FORBIDDEN,forbidden.errorCode());
        }
    }

    @Test
    void databaseCommitFailureIsPropagatedWithoutRetryingOrReturningAnUnrecordedResult() {
        var failure=new TransactionSystemException("Unknown verification commit result");
        doThrow(failure).when(phases).save(eq(ACTOR),eq(VERSION),anyString(),any());
        try(var service=service(settings(2,5))) {
            assertSame(failure,assertThrows(TransactionSystemException.class,()->service.verifyStored(ACTOR,VERSION)));
            verify(phases,times(1)).save(eq(ACTOR),eq(VERSION),eq("STORED"),any());
        }
    }

    @Test
    void blockedReadsAndBlockedCloseRespectTheDeadlineAndKeepBothWorkerSlotsOccupied() throws Exception {
        CountDownLatch reading=new CountDownLatch(2),releaseRead=new CountDownLatch(1),closing=new CountDownLatch(1),releaseClose=new CountDownLatch(1);
        when(storage.open("private/object")).thenAnswer(call->new InputStream() {
            public int read(){ reading.countDown(); awaitIgnoringInterrupt(releaseRead); return -1; }
            public void close(){ closing.countDown(); awaitIgnoringInterrupt(releaseClose); }
        });
        var callers=Executors.newFixedThreadPool(2);
        try(var service=service(settings(2,1))) {
            Future<VerificationView> first=callers.submit(()->service.verifyStored(ACTOR,VERSION));
            Future<VerificationView> second=callers.submit(()->service.verifyStored(ACTOR,VERSION));
            assertTrue(reading.await(2,TimeUnit.SECONDS));
            BusinessException busy=assertThrows(BusinessException.class,()->service.verifyStored(ACTOR,VERSION));
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,busy.errorCode());
            assertEquals("FILE_PROCESSING_TIMEOUT",first.get(3,TimeUnit.SECONDS).reason());
            assertEquals("FILE_PROCESSING_TIMEOUT",second.get(3,TimeUnit.SECONDS).reason());
            assertTrue(closing.await(1,TimeUnit.SECONDS));
            // Future cancellation must not release capacity while uninterruptible code is still running.
            assertThrows(BusinessException.class,()->service.verifyStored(ACTOR,VERSION));
            verify(storage,times(2)).open("private/object");
            verifyNoInteractions(owner,operator);
        } finally { releaseRead.countDown(); releaseClose.countDown(); callers.shutdownNow(); }
    }

    @Test
    void aBlockedLedgerRpcHasAChainTimeoutAndRetainsItsWorkerUntilTheRpcActuallyReturns() throws Exception {
        CountDownLatch querying=new CountDownLatch(1),release=new CountDownLatch(1);
        when(owner.queryEvidence("evidence",3)).thenAnswer(call->{ querying.countDown(); awaitIgnoringInterrupt(release); return found(hash(original)); });
        try(var service=service(settings(1,1))) {
            long start=System.nanoTime();
            VerificationView timeout=service.verifyStored(ACTOR,VERSION);
            assertTrue(querying.await(1,TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2500);
            assertEquals("CHAIN_QUERY_FAILED",timeout.result());
            assertEquals("CHAIN_QUERY_TIMEOUT",timeout.reason());
            assertEquals(hash(original),timeout.actualSha256());
            assertThrows(BusinessException.class,()->service.verifyStored(ACTOR,VERSION));
            verify(storage,times(1)).open("private/object");
        } finally { release.countDown(); }
    }

    private VerificationSettings settings(int concurrency,int seconds) {
        return new VerificationSettings(new MockEnvironment().withProperty("inspection.verification.max-file-size","1048576")
                .withProperty("inspection.verification.concurrency",Integer.toString(concurrency))
                .withProperty("inspection.verification.timeout-seconds",Integer.toString(seconds))
                .withProperty("inspection.verification.min-free-space","0")
                .withProperty("inspection.verification.temp-directory",directory.resolve("private").toString()));
    }

    private VerificationService service(VerificationSettings settings) {
        var proxy=new ProxyFactory(new VerificationService(phases,storage,clients,settings));
        proxy.setProxyTargetClass(true);
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object transaction,TransactionDefinition definition){}
            protected void doCommit(DefaultTransactionStatus status){}
            protected void doRollback(DefaultTransactionStatus status){}
        };
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (VerificationService)proxy.getProxy();
    }

    private static EvidenceQuery found(String hash) { return new EvidenceQuery(true,new EvidenceRecord("evidence",3,hash,"Org2MSP",TX)); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void outsideTransaction(){ assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); }
    private void assertTemporaryDirectoryEmpty() throws Exception {
        try(var files=Files.list(directory.resolve("private"))) { assertEquals(0,files.count()); }
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean done=false;
        while(!done) { try { latch.await(); done=true; } catch(InterruptedException ignored) {} }
    }
}
