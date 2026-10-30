package com.uav.inspection.evidence.verification;

import com.uav.inspection.blockchain.EvidenceLedgerClient.EvidenceQuery;
import com.uav.inspection.blockchain.EvidenceLedgerClient.EvidenceRecord;
import com.uav.inspection.blockchain.InstitutionLedgerClients;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.temp.TemporaryFileManager;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.evidence.verification.VerificationModels.*;
import com.uav.inspection.evidence.verification.VerificationTransactions.Prepared;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import jakarta.annotation.PreDestroy;
import org.hyperledger.fabric.client.GatewayException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded synchronous verification; only the requesting thread can persist or expose a result. */
@Service
@Profile("database & storage & ledger")
public class VerificationService implements AutoCloseable {
    private static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
    private final VerificationTransactions transactions;
    private final ObjectStorage storage;
    private final InstitutionLedgerClients clients;
    private final VerificationSettings settings;
    private final TemporaryFileManager temporaryFiles;
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor closers;
    private final Set<Guard> active=ConcurrentHashMap.newKeySet();

    @Autowired
    public VerificationService(VerificationTransactions transactions,ObjectStorage storage,
                               InstitutionLedgerClients clients,VerificationSettings settings,
                               TemporaryFileManager temporaryFiles) {
        this.transactions=transactions; this.storage=storage; this.clients=clients; this.settings=settings;
        this.temporaryFiles=temporaryFiles;
        workers=executor(settings.concurrency(),"inspection-verification-");
        closers=executor(settings.concurrency(),"inspection-verification-close-");
    }

    /** The controller calls this before parsing a multipart body. */
    public void authorize(CurrentUser actor,String versionId) {
        id(versionId);
        transactions.prepare(actor,versionId);
    }

    public VerificationView verifyStored(CurrentUser actor,String versionId) {
        id(versionId);
        Prepared prepared=transactions.prepare(actor,versionId);
        return execute(actor,versionId,"STORED",prepared,null);
    }

    public VerificationView verifyCopy(CurrentUser actor,String versionId,String filename,String contentType,long size,CopyInput input) {
        id(versionId);
        Prepared prepared=transactions.prepare(actor,versionId);
        validateCopy(filename,contentType,size,input);
        return execute(actor,versionId,"COPY",prepared,new Copy(contentType,size,input));
    }

    public VerificationPage history(CurrentUser actor,String versionId,int page,int pageSize) {
        id(versionId);
        if(page<1 || pageSize<1 || pageSize>100) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        return transactions.history(actor,versionId,page,pageSize);
    }

    private VerificationView execute(CurrentUser actor,String versionId,String source,Prepared prepared,Copy copy) {
        Guard guard=new Guard();
        Future<Computed> task;
        active.add(guard);
        try {
            task=workers.submit(()->{
                guard.worker=Thread.currentThread();
                try { return compute(prepared,copy,guard); }
                finally { active.remove(guard); }
            });
        } catch(RejectedExecutionException busy) {
            active.remove(guard);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        Computed outcome;
        try {
            outcome=task.get(settings.timeout().toMillis(),TimeUnit.MILLISECONDS);
        } catch(TimeoutException timeout) {
            guard.cancel(); task.cancel(true);
            FileDigest digest=guard.completedDigest.get();
            outcome=guard.querying.get() && digest!=null
                    ?Computed.chainFailure(digest.sha256(),digest.size(),"CHAIN_QUERY_TIMEOUT")
                    :Computed.fileFailure("FILE_PROCESSING_TIMEOUT");
        } catch(InterruptedException interrupted) {
            guard.cancel(); task.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch(ExecutionException failed) {
            if(failed.getCause() instanceof BusinessException business) throw business;
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        // No worker can write this row. Revoked membership or identity prevents both persistence and disclosure.
        return transactions.save(actor,versionId,source,outcome);
    }

    private Computed compute(Prepared prepared,Copy copy,Guard guard) {
        FileDigest digest;
        TemporaryFileManager.Handle temporary=null;
        try {
            guard.check();
            if(copy!=null) {
                long usable=Files.getFileStore(settings.tempDirectory()).getUsableSpace();
                if(usable<settings.minFreeSpace() || copy.size()>usable-settings.minFreeSpace())
                    return Computed.fileFailure("LOCAL_SPACE_UNAVAILABLE");
                temporary=temporaryFiles.create(settings.tempDirectory(),"verification-",".tmp");
            }
            try(InputStream input=guard.track(copy==null?storage.open(prepared.version().objectKey()):copy.input().open())) {
                guard.check();
                if(copy==null) digest=hash(input,null,null,guard);
                else try(OutputStream output=Files.newOutputStream(temporary.path())) { digest=hash(input,output,copy,guard); }
            } finally { guard.stream.set(null); }
        } catch(Cancelled ignored) {
            return Computed.fileFailure("FILE_PROCESSING_TIMEOUT");
        } catch(IOException | RuntimeException failure) {
            if(copy!=null && failure instanceof BusinessException business
                    && (business.errorCode()==ErrorCode.INVALID_ARGUMENT || business.errorCode()==ErrorCode.FILE_TOO_LARGE)) throw business;
            return Computed.fileFailure(copy==null?"STORED_FILE_READ_FAILED":"COPY_FILE_READ_FAILED");
        } finally {
            if(temporary!=null) temporary.close();
        }
        guard.completedDigest.set(digest);
        guard.querying.set(true);
        try {
            guard.check();
            EvidenceQuery query=clients.forOrganization(prepared.actor().orgId())
                    .queryEvidence(prepared.version().evidenceId(),prepared.version().versionNo());
            guard.check();
            if(query==null) return Computed.chainFailure(digest.sha256(),digest.size(),"LEDGER_RESPONSE_INVALID");
            if(!query.found()) return new Computed(digest.sha256(),null,digest.size(),null,"ANCHOR_UNCONFIRMED","LEDGER_RECORD_NOT_FOUND");
            EvidenceRecord record=query.record();
            if(!validRecord(prepared,record)) return Computed.chainFailure(digest.sha256(),digest.size(),"LEDGER_RESPONSE_INVALID");
            boolean match=digest.sha256().equals(record.fileHash());
            return new Computed(digest.sha256(),record.fileHash(),digest.size(),record.registeredTxId(),
                    match?"MATCH":"MISMATCH",match?"CONTENT_MATCH":"CONTENT_MISMATCH");
        } catch(Cancelled ignored) {
            return Computed.chainFailure(digest.sha256(),digest.size(),"CHAIN_QUERY_TIMEOUT");
        } catch(GatewayException | RuntimeException unavailable) {
            return Computed.chainFailure(digest.sha256(),digest.size(),"CHAIN_QUERY_UNAVAILABLE");
        }
    }

    private FileDigest hash(InputStream input,OutputStream output,Copy copy,Guard guard) throws IOException {
        MessageDigest digest=sha256();
        long size=0;
        if(copy!=null) {
            int length=switch(copy.contentType()) { case "image/jpeg"->3; case "application/pdf"->5; case "video/mp4"->12; default->8; };
            byte[] head=input.readNBytes(length);
            guard.check();
            if(head.length!=length || !magic(head,copy.contentType())) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            digest.update(head); output.write(head); size=head.length;
        }
        byte[] buffer=new byte[65536];
        int count;
        while((count=input.read(buffer))!=-1) {
            guard.check();
            if(count==0) continue;
            size=Math.addExact(size,count);
            if(size>settings.maxFileSize()) throw new IOException("Verification file limit exceeded");
            digest.update(buffer,0,count);
            if(output!=null) output.write(buffer,0,count);
        }
        guard.check();
        if(copy!=null && size!=copy.size()) throw new IOException("Verification copy length changed");
        return new FileDigest(HexFormat.of().formatHex(digest.digest()),size);
    }

    private boolean validRecord(Prepared prepared,EvidenceRecord record) {
        return record!=null && prepared.version().evidenceId().equals(record.evidenceId())
                && record.version()==prepared.version().versionNo()
                && ResourcePermissionService.OPERATOR.equals(prepared.version().submitterOrgId())
                && clients.mspId(ResourcePermissionService.OPERATOR).equals(record.submitterMsp())
                && record.fileHash()!=null && record.fileHash().matches("[0-9a-f]{64}")
                && record.registeredTxId()!=null && record.registeredTxId().matches("[0-9a-f]{64}");
    }

    private void validateCopy(String filename,String contentType,long size,CopyInput input) {
        if(size>settings.maxFileSize()) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        if(size<1 || input==null || filename==null || filename.isBlank() || filename.length()>255
                || filename.indexOf('/')>=0 || filename.indexOf('\\')>=0
                || filename.codePoints().anyMatch(Character::isISOControl)) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        int dot=filename.lastIndexOf('.');
        String extension=dot<0?"":filename.substring(dot+1).toLowerCase(Locale.ROOT);
        String expected=switch(extension) { case "png"->"image/png"; case "jpg","jpeg"->"image/jpeg";
            case "pdf"->"application/pdf"; case "mp4"->"video/mp4"; default->""; };
        if(!settings.allowedExtensions().contains(extension) || !expected.equals(contentType))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
    }

    private static boolean magic(byte[] head,String type) {
        return switch(type) {
            case "image/png"->Arrays.equals(head,PNG);
            case "image/jpeg"->head[0]==(byte)0xff && head[1]==(byte)0xd8 && head[2]==(byte)0xff;
            case "application/pdf"->new String(head,StandardCharsets.US_ASCII).equals("%PDF-");
            case "video/mp4"->new String(head,4,4,StandardCharsets.US_ASCII).equals("ftyp");
            default->false;
        };
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static void id(String id) {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
    }
    private static ThreadPoolExecutor executor(int concurrency,String prefix) {
        AtomicInteger sequence=new AtomicInteger();
        return new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),task->{
            Thread thread=new Thread(task,prefix+sequence.incrementAndGet()); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    @Override public void close() {
        active.forEach(Guard::cancel);
        workers.shutdownNow(); closers.shutdown();
    }

    @FunctionalInterface public interface CopyInput { InputStream open() throws IOException; }
    private record Copy(String contentType,long size,CopyInput input) {}
    private record FileDigest(String sha256,long size) {}
    private static final class Cancelled extends RuntimeException {}

    private final class Guard {
        private final AtomicBoolean cancelled=new AtomicBoolean();
        private final AtomicBoolean querying=new AtomicBoolean();
        private final AtomicReference<FileDigest> completedDigest=new AtomicReference<>();
        private final AtomicReference<InputStream> stream=new AtomicReference<>();
        private volatile Thread worker;
        InputStream track(InputStream input) { stream.set(input); return input; }
        void check() { if(cancelled.get() || Thread.currentThread().isInterrupted()) throw new Cancelled(); }
        void cancel() {
            if(!cancelled.compareAndSet(false,true)) return;
            if(worker!=null) worker.interrupt();
            InputStream input=stream.get();
            if(input!=null) {
                try { closers.execute(()->{ try { input.close(); } catch(IOException | RuntimeException ignored) {} }); }
                catch(RejectedExecutionException ignored) { /* Capacity remains occupied until the original worker exits. */ }
            }
        }
    }
}
