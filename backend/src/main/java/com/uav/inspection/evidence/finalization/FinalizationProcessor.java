package com.uav.inspection.evidence.finalization;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.lease.ProcessingLeaseKeeper;
import com.uav.inspection.common.temp.TemporaryFileManager;
import com.uav.inspection.evidence.finalization.FinalizationTransactions.Claim;
import com.uav.inspection.evidence.finalization.FileFormatValidator.InvalidFile;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.evidence.storage.UploadSettings;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.PartRow;
import com.uav.inspection.messaging.MessageEvent;
import com.uav.inspection.messaging.BusinessRecoveryDispatcher;
import com.uav.inspection.messaging.DurableRecovery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@Profile("database & storage & messaging")
public class FinalizationProcessor {
    private final FinalizationTransactions phases;
    private final ObjectStorage storage;
    private final UploadSettings settings;
    private final ProcessingLeaseKeeper leases;
    private final TemporaryFileManager temporaryFiles;
    private final FileFormatValidator formats=new FileFormatValidator();

    public FinalizationProcessor(FinalizationTransactions phases,ObjectStorage storage,UploadSettings settings) {
        this(phases,storage,settings,ProcessingLeaseKeeper.unmonitored());
    }

    public FinalizationProcessor(FinalizationTransactions phases,ObjectStorage storage,UploadSettings settings,
                                 ProcessingLeaseKeeper leases) {
        this(phases,storage,settings,leases,new TemporaryFileManager());
    }

    @Autowired
    public FinalizationProcessor(FinalizationTransactions phases,ObjectStorage storage,UploadSettings settings,
                                 ProcessingLeaseKeeper leases,TemporaryFileManager temporaryFiles) {
        this.phases=phases; this.storage=storage; this.settings=settings; this.leases=leases;
        this.temporaryFiles=temporaryFiles;
    }

    public void process(MessageEvent event) {
        process(event,false);
        phases.verifyAcknowledgement(event);
    }

    /** Independent durable recovery deliberately bypasses consumed_event.DONE. */
    public void recoverDue() {
        DurableRecovery.run(BusinessRecoveryDispatcher.Kind.FILE_FINALIZE,
                phases::due,event->process(event,true));
    }

    private void process(MessageEvent event,boolean recovery) {
        Claim claim=phases.claim(event,recovery);
        if(claim==null) return;
        try(ProcessingLeaseKeeper.Lease lease=leases.track(()->phases.renew(claim))) {
            lease.check();
            TemporaryFileManager.Handle temporary=null;
            try {
                Candidate candidate;
                try {
                    temporary=temporaryFile(claim);
                    candidate=storeCandidate(claim,temporary.path(),lease);
                } catch(InvalidFile failure) {
                    lease.check(); phases.checkpoint(claim,true,failure.code()); return;
                } catch(LocalSpaceUnavailable failure) {
                    lease.check(); phases.checkpoint(claim,false,"LOCAL_SPACE_UNAVAILABLE"); return;
                } catch(BusinessException failure) {
                    lease.check(); phases.checkpoint(claim,false,"STORAGE_UNAVAILABLE"); return;
                } catch(IOException failure) {
                    lease.check(); phases.checkpoint(claim,false,"FILE_IO_UNAVAILABLE"); return;
                }
                // Database failures escape; uncertain candidates remain available for reference-aware cleanup.
                lease.check();
                phases.finish(claim,candidate.objectKey(),claim.session().expectedSize(),candidate.digest());
            } finally {
                if(temporary!=null) temporary.close();
            }
        }
    }

    private TemporaryFileManager.Handle temporaryFile(Claim claim) throws IOException {
        long usable=Files.getFileStore(settings.tempDirectory()).getUsableSpace();
        if(usable<settings.minFreeSpace() || claim.session().expectedSize()>usable-settings.minFreeSpace())
            throw new LocalSpaceUnavailable();
        return temporaryFiles.create(settings.tempDirectory(),"finalize-",".tmp");
    }

    /** Only file and object-storage I/O belongs in the processor's retry classification. */
    private Candidate storeCandidate(Claim claim,Path temporary,ProcessingLeaseKeeper.Lease lease) throws IOException {
        lease.check();
        String digest=merge(claim,temporary,lease);
        lease.check();
        formats.validate(temporary,claim.session().contentType());
        lease.check();
        String objectKey="evidence-files/"+claim.session().id()+"/"+claim.token();
        storage.put(objectKey,temporary,claim.session().contentType());
        lease.check();
        return new Candidate(objectKey,digest);
    }

    private String merge(Claim claim,Path file,ProcessingLeaseKeeper.Lease lease) throws IOException {
        MessageDigest full=digest(); long total=0;
        if(claim.parts().size()!=claim.session().partCount()) throw new InvalidFile("PART_COUNT_MISMATCH");
        byte[] buffer=new byte[65536];
        try(var output=Files.newOutputStream(file)) {
            for(int i=0;i<claim.parts().size();i++) {
                lease.check();
                PartRow part=claim.parts().get(i);
                long expected=Math.min(claim.session().partSize(),claim.session().expectedSize()-(long)i*claim.session().partSize());
                if(part.partNo()!=i+1 || part.size()!=expected) throw new InvalidFile("PART_SIZE_MISMATCH");
                MessageDigest partDigest=digest(); long size=0;
                try(var input=storage.open(part.objectKey())) {
                    lease.check();
                    int count;
                    while(true) {
                        lease.check();
                        count=input.read(buffer);
                        lease.check();
                        if(count==-1) break;
                        if(count==0) continue;
                        size=Math.addExact(size,count);
                        if(size>part.size()) throw new InvalidFile("PART_SIZE_MISMATCH");
                        output.write(buffer,0,count); partDigest.update(buffer,0,count); full.update(buffer,0,count);
                    }
                }
                if(size!=part.size()) throw new InvalidFile("PART_SIZE_MISMATCH");
                if(!HexFormat.of().formatHex(partDigest.digest()).equals(part.sha256())) throw new InvalidFile("PART_HASH_MISMATCH");
                total=Math.addExact(total,size);
            }
        }
        if(total!=claim.session().expectedSize()) throw new InvalidFile("FILE_SIZE_MISMATCH");
        return HexFormat.of().formatHex(full.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private record Candidate(String objectKey,String digest) {}
    private static final class LocalSpaceUnavailable extends IOException {}
}
