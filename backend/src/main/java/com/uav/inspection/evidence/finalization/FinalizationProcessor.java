package com.uav.inspection.evidence.finalization;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.evidence.finalization.FinalizationTransactions.Claim;
import com.uav.inspection.evidence.finalization.FileFormatValidator.InvalidFile;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.evidence.storage.UploadSettings;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.PartRow;
import com.uav.inspection.messaging.MessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@Profile("database & storage & messaging")
public class FinalizationProcessor {
    private static final Logger LOG=LoggerFactory.getLogger(FinalizationProcessor.class);
    private final FinalizationTransactions phases;
    private final ObjectStorage storage;
    private final UploadSettings settings;
    private final FileFormatValidator formats=new FileFormatValidator();

    public FinalizationProcessor(FinalizationTransactions phases,ObjectStorage storage,UploadSettings settings) {
        this.phases=phases; this.storage=storage; this.settings=settings;
    }

    public void process(MessageEvent event) { process(event,false); }

    /** Independent durable recovery deliberately bypasses consumed_event.DONE. */
    public void recoverDue() {
        for(MessageEvent event:phases.due()) {
            try { process(event,true); }
            catch(RuntimeException failure) { LOG.warn("Finalization recovery failed: {}",failure.getClass().getSimpleName()); }
        }
    }

    private void process(MessageEvent event,boolean recovery) {
        Claim claim=phases.claim(event,recovery);
        if(claim==null) return;
        Path temporary=null;
        try {
            if(Files.getFileStore(settings.tempDirectory()).getUsableSpace()
                    <Math.addExact(claim.session().expectedSize(),settings.minFreeSpace())) {
                phases.checkpoint(claim,false,"LOCAL_SPACE_UNAVAILABLE"); return;
            }
            temporary=Files.createTempFile(settings.tempDirectory(),"finalize-",".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            String digest=merge(claim,temporary);
            formats.validate(temporary,claim.session().contentType());
            String objectKey="evidence-files/"+claim.session().id()+"/"+claim.token();
            storage.put(objectKey,temporary,claim.session().contentType());
            // Never delete this candidate if the DB result is lost or another lease has taken over.
            phases.finish(claim,objectKey,claim.session().expectedSize(),digest);
        } catch(InvalidFile failure) {
            phases.checkpoint(claim,true,failure.code());
        } catch(BusinessException failure) {
            phases.checkpoint(claim,false,"STORAGE_UNAVAILABLE");
        } catch(IOException failure) {
            phases.checkpoint(claim,false,"FILE_IO_UNAVAILABLE");
        } finally {
            if(temporary!=null) {
                try { Files.deleteIfExists(temporary); }
                catch(IOException failure) { LOG.warn("Finalization temporary cleanup failed: {}",failure.getClass().getSimpleName()); }
            }
        }
    }

    private String merge(Claim claim,Path file) throws IOException {
        MessageDigest full=digest(); long total=0;
        if(claim.parts().size()!=claim.session().partCount()) throw new InvalidFile("PART_COUNT_MISMATCH");
        byte[] buffer=new byte[65536];
        try(var output=Files.newOutputStream(file)) {
            for(int i=0;i<claim.parts().size();i++) {
                PartRow part=claim.parts().get(i);
                long expected=Math.min(claim.session().partSize(),claim.session().expectedSize()-(long)i*claim.session().partSize());
                if(part.partNo()!=i+1 || part.size()!=expected) throw new InvalidFile("PART_SIZE_MISMATCH");
                MessageDigest partDigest=digest(); long size=0;
                try(var input=storage.open(part.objectKey())) {
                    int count;
                    while((count=input.read(buffer))!=-1) {
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
}
