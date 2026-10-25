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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
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
    void multiplePartsAreStreamedInNumericOrderWithAShortLastPartAndOneFullDigest() throws Exception {
        var picture=new BufferedImage(320,256,BufferedImage.TYPE_INT_RGB);
        var random=new Random(17);
        for(int y=0;y<picture.getHeight();y++) for(int x=0;x<picture.getWidth();x++) picture.setRGB(x,y,random.nextInt());
        var bytes=new ByteArrayOutputStream();
        assertTrue(ImageIO.write(picture,"png",bytes));
        byte[] complete=bytes.toByteArray();
        int partSize=100_000;
        assertTrue(complete.length>partSize*2 && complete.length<partSize*3);
        var registered=new ArrayList<PartRow>();
        for(int offset=0,number=1;offset<complete.length;offset+=partSize,number++) {
            byte[] part=Arrays.copyOfRange(complete,offset,Math.min(offset+partSize,complete.length));
            String key="multipart-"+number;
            registered.add(new PartRow(UPLOAD,number,part.length,hash(part),key));
            when(storage.open(key)).thenAnswer(call->{
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return new ByteArrayInputStream(part) {
                    @Override public synchronized int read(byte[] buffer,int start,int length) {
                        return super.read(buffer,start,Math.min(length,997));
                    }
                };
            });
        }
        claim=new Claim(EVENT,session(UPLOAD,complete.length,partSize,registered.size()),registered,TOKEN);
        when(phases.claim(EVENT,false)).thenReturn(claim);
        doAnswer(call->{
            assertArrayEquals(complete,Files.readAllBytes(call.getArgument(1,Path.class)));
            return null;
        }).when(storage).put(anyString(),any(Path.class),eq("image/png"));

        processor().process(EVENT);

        var order=inOrder(storage,phases);
        for(PartRow part:registered) order.verify(storage).open(part.objectKey());
        order.verify(storage).put(eq("evidence-files/"+UPLOAD+"/"+TOKEN),any(),eq("image/png"));
        order.verify(phases).finish(claim,"evidence-files/"+UPLOAD+"/"+TOKEN,complete.length,hash(complete));
        assertEmptyTemporaryDirectory();
    }

    @Test
    void aNonFirstPartWithChangedBytesCannotBecomeACompleteVersion() throws Exception {
        int split=(image.length+1)/2;
        byte[] first=Arrays.copyOfRange(image,0,split),last=Arrays.copyOfRange(image,split,image.length);
        var registered=List.of(new PartRow(UPLOAD,1,first.length,hash(first),"first"),
                new PartRow(UPLOAD,2,last.length,hash(last),"last"));
        claim=new Claim(EVENT,session(UPLOAD,image.length,split,2),registered,TOKEN);
        when(phases.claim(EVENT,false)).thenReturn(claim);
        when(storage.open("first")).thenReturn(new ByteArrayInputStream(first));
        byte[] changed=last.clone(); changed[0]^=1;
        when(storage.open("last")).thenReturn(new ByteArrayInputStream(changed));

        processor().process(EVENT);

        verify(phases).checkpoint(claim,true,"PART_HASH_MISMATCH");
        verify(phases,never()).finish(any(),anyString(),anyLong(),anyString());
        verify(storage,never()).put(anyString(),any(),anyString());
        assertEmptyTemporaryDirectory();
    }

    @Test
    void aShortOrOverlongObjectStreamIsRejectedEvenWithPlausibleRegisteredMetadata() throws Exception {
        for(byte[] actual:List.of(Arrays.copyOf(image,image.length-1),Arrays.copyOf(image,image.length+1))) {
            when(storage.open("private-part")).thenReturn(new ByteArrayInputStream(actual));
            processor().process(EVENT);
        }
        verify(phases,times(2)).checkpoint(claim,true,"PART_SIZE_MISMATCH");
        verify(phases,never()).finish(any(),anyString(),anyLong(),anyString());
        verify(storage,never()).put(anyString(),any(),anyString());
        assertEmptyTemporaryDirectory();
    }

    @Test
    void matchingHashesDoNotMakeATruncatedFileAValidImage() throws Exception {
        byte[] truncated=Arrays.copyOf(image,8);
        claim=new Claim(EVENT,session(UPLOAD,truncated.length,1048576,1),
                List.of(new PartRow(UPLOAD,1,truncated.length,hash(truncated),"private-part")),TOKEN);
        when(phases.claim(EVENT,false)).thenReturn(claim);
        when(storage.open("private-part")).thenReturn(new ByteArrayInputStream(truncated));

        processor().process(EVENT);

        verify(phases).checkpoint(claim,true,"FILE_FORMAT_INVALID");
        verify(phases,never()).finish(any(),anyString(),anyLong(),anyString());
        verify(storage,never()).put(anyString(),any(),anyString());
        assertEmptyTemporaryDirectory();
    }

    @Test
    void interruptedPartReadSchedulesRecoveryWithoutUsingAPartialDigest() throws Exception {
        // The ObjectStorage stream contract exposes checked I/O failures, including after a partial read.
        when(storage.open("private-part")).thenReturn(new java.io.InputStream() {
            private boolean first=true;
            @Override public int read() throws IOException { throw new IOException("read interrupted"); }
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                if(!first) throw new IOException("read interrupted");
                first=false; System.arraycopy(image,0,bytes,offset,8); return 8;
            }
        });

        processor().process(EVENT);

        verify(phases).checkpoint(claim,false,"FILE_IO_UNAVAILABLE");
        verify(phases,never()).finish(any(),anyString(),anyLong(),anyString());
        verify(storage,never()).put(anyString(),any(),anyString());
        assertEmptyTemporaryDirectory();
    }

    @Test
    void separateUploadSessionsNeverOverwriteTheFirstFinalObject() throws Exception {
        String nextId="80000000-0000-4000-8000-000000000099";
        String nextToken="90000000-0000-4000-8000-000000000099";
        var nextEvent=new MessageEvent("90000000-0000-4000-8000-000000000098","FILE_FINALIZE_REQUESTED",nextId,"next-version");
        var picture=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB); picture.setRGB(0,0,0xabcdef);
        var bytes=new ByteArrayOutputStream(); assertTrue(ImageIO.write(picture,"png",bytes));
        byte[] replacement=bytes.toByteArray();
        var next=new Claim(nextEvent,session(nextId,replacement.length,1048576,1),
                List.of(new PartRow(nextId,1,replacement.length,hash(replacement),"next-part")),nextToken);
        when(phases.claim(nextEvent,false)).thenReturn(next);
        when(storage.open("next-part")).thenReturn(new ByteArrayInputStream(replacement));
        Map<String,byte[]> objects=new HashMap<>();
        doAnswer(call->{
            assertNull(objects.put(call.getArgument(0),Files.readAllBytes(call.getArgument(1,Path.class)))); return null;
        }).when(storage).put(anyString(),any(Path.class),eq("image/png"));

        var processor=processor();
        processor.process(EVENT);
        processor.process(nextEvent);

        assertEquals(2,objects.size());
        assertArrayEquals(image,objects.get("evidence-files/"+UPLOAD+"/"+TOKEN));
        assertArrayEquals(replacement,objects.get("evidence-files/"+nextId+"/"+nextToken));
        verify(phases).finish(next,"evidence-files/"+nextId+"/"+nextToken,replacement.length,hash(replacement));
        verify(storage,never()).delete(anyString());
        assertEmptyTemporaryDirectory();
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
    void aBusinessFailureFromTheDatabasePhaseIsNotMisclassifiedAsStorageFailure() throws Exception {
        var failure=new BusinessException(ErrorCode.VERSION_CONFLICT);
        when(phases.finish(any(),anyString(),anyLong(),anyString())).thenThrow(failure);

        assertSame(failure,assertThrows(BusinessException.class,()->processor().process(EVENT)));

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

    private static SessionRow session(String id,long size,int partSize,int count) {
        Instant now=Instant.parse("2026-10-22T00:00:00Z");
        return new SessionRow(id,"task","subtask","requirement","evidence","execution","org",
                "USER","user","client","hash","image.png","image/png",size,partSize,count,"VERIFYING",now.plusSeconds(3600),0,now,now);
    }

    private void assertEmptyTemporaryDirectory() throws Exception {
        try(var files=Files.list(settings.tempDirectory())) { assertEquals(0,files.count()); }
    }
}
