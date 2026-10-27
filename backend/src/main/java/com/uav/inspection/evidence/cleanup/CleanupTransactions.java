package com.uav.inspection.evidence.cleanup;

import com.uav.inspection.evidence.cleanup.persistence.CleanupMapper;
import com.uav.inspection.evidence.cleanup.persistence.CleanupMapper.SessionRow;
import com.uav.inspection.evidence.cleanup.persistence.CleanupMapper.TaskRow;
import com.uav.inspection.evidence.storage.ObjectInventory.Entry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Database-only cleanup phases. Eligibility seals off all supported future adoption paths. */
@Service
@Profile("database & storage & cleanup")
public class CleanupTransactions {
    private final CleanupMapper mapper;
    private final CleanupSettings settings;

    public CleanupTransactions(CleanupMapper mapper,CleanupSettings settings) { this.mapper=mapper;this.settings=settings; }

    @Transactional
    public int expireDue() {
        int changed=0;
        for(String id:mapper.expiredSessions()) {
            SessionRow session=lockSession(id);Instant now=mapper.now();
            if(session==null || !"UPLOADING".equals(session.status()) || session.expiresAt().isAfter(now)
                    || active(session,now) || mapper.sessionVersions(id)>0) continue;
            if(mapper.expire(id)==1) { changed++;audit(session.submitterOrgId(),"UPLOAD_SESSION_EXPIRED","UPLOAD_SESSION",id); }
        }
        return changed;
    }

    @Transactional
    public int retireDue() {
        int changed=0;
        for(String id:mapper.retiredSessions(settings.graceSeconds())) {
            SessionRow session=lockSession(id);Instant now=mapper.now();
            if(!retirable(session,now) || mapper.sessionVersions(id)>0) continue;
            int retired=0;
            for(var part:mapper.parts(id,settings.batchSize())) {
                var parsed=CleanupKey.parse(part.objectKey());
                if(parsed==null || !id.equals(parsed.uploadId()) || mapper.formalReferences(part.objectKey())>0) continue;
                if(mapper.retirePart(id,part.partNo(),part.objectKey())!=1) throw new IllegalStateException("Part retirement conflict");
                enqueue(new Entry(part.objectKey(),part.createdAt(),part.size()),parsed);
                retired++;changed++;
            }
            if(retired>0) audit(session.submitterOrgId(),"UPLOAD_PARTS_RETIRED","UPLOAD_SESSION",id);
        }
        return changed;
    }

    @Transactional
    public boolean discover(Entry entry) {
        var parsed=entry==null?null:CleanupKey.parse(entry.key());
        if(parsed==null || entry.lastModified()==null || entry.size()<0) return false;
        SessionRow session=lockSession(parsed.uploadId());Instant now=mapper.now();
        if(!oldEnough(entry,now) || ineligible(entry.key(),parsed,session,now)!=null) return false;
        enqueue(entry,parsed);return true;
    }

    @Transactional(readOnly=true)
    public List<String> due() { return mapper.due(settings.batchSize()); }

    @Transactional
    public Claim claim(String id) {
        TaskRow seed=mapper.task(id);
        if(seed==null) return null;
        SessionRow session=lockSession(seed.uploadId());
        TaskRow job=mapper.lockCleanup(id);Instant now=mapper.now();
        if(job==null || !due(job,now)) return null;
        if(!seed.uploadId().equals(job.uploadId()) || !seed.objectKey().equals(job.objectKey())) return null;
        var parsed=CleanupKey.parse(job.objectKey());
        String code=validJob(job,parsed)?ineligible(job.objectKey(),parsed,session,now):"INVALID_OBJECT_KEY";
        if(code==null && !oldEnough(new Entry(job.objectKey(),job.objectLastModified(),job.objectSize()),now)) code="GRACE_PERIOD";
        if(code!=null) { skip(job,session,code);return null; }
        String token=UUID.randomUUID().toString();
        if(mapper.claim(id,token)!=1) return null;
        return new Claim(job.id(),job.objectKey(),job.uploadId(),token,
                job.attemptCount()==Integer.MAX_VALUE?Integer.MAX_VALUE:job.attemptCount()+1,
                session==null?null:session.submitterOrgId());
    }

    @Transactional
    public Claim claim(String id,Entry current) {
        Claim claim=claim(id);
        return claim!=null && verifyDeletion(claim,current)?claim:null;
    }

    /** Runs after HEAD, before DELETE. No S3 call belongs in this transaction. */
    @Transactional
    public boolean verifyDeletion(Claim claim,Entry current) {
        SessionRow session=lockSession(claim.uploadId());
        TaskRow job=mapper.lockCleanup(claim.id());Instant now=mapper.now();
        if(!owned(job,claim,now)) return false;
        var parsed=CleanupKey.parse(job.objectKey());
        String code=validJob(job,parsed)?ineligible(job.objectKey(),parsed,session,now):"INVALID_OBJECT_KEY";
        if(code==null && current!=null && (!job.objectKey().equals(current.key()) || current.lastModified()==null
                || current.size()<0 || !oldEnough(current,now))) code="GRACE_PERIOD";
        if(code!=null) {
            if(mapper.skipOwned(job.id(),claim.token(),code)==1)
                audit(session==null?null:session.submitterOrgId(),"STORAGE_OBJECT_CLEANUP_SKIPPED","CLEANUP_TASK",job.id());
            return false;
        }
        return true;
    }

    @Transactional
    public boolean done(Claim claim) {
        if(mapper.done(claim.id(),claim.token())!=1) return false;
        audit(claim.orgId(),"STORAGE_OBJECT_CLEANUP_COMPLETED","CLEANUP_TASK",claim.id());return true;
    }

    @Transactional
    public boolean retry(Claim claim) {
        int[] backoff={2,10,30,60,300};
        if(mapper.retry(claim.id(),claim.token(),backoff[Math.min(Math.max(claim.attemptCount()-1,0),backoff.length-1)])!=1) return false;
        audit(claim.orgId(),"STORAGE_OBJECT_CLEANUP_RETRY","CLEANUP_TASK",claim.id());return true;
    }

    @Transactional
    public boolean renew(Claim claim) { return mapper.renew(claim.id(),claim.token())==1; }

    private void enqueue(Entry entry,CleanupKey.Parsed parsed) {
        String hash=CleanupKey.hash(entry.key());TaskRow existing=mapper.byHash(hash);
        if(existing!=null && !entry.key().equals(existing.objectKey())) throw new IllegalStateException("Cleanup identity collision");
        if(existing==null) mapper.insert(new TaskRow(UUID.randomUUID().toString(),entry.key(),hash,parsed.uploadId(),
                entry.lastModified(),entry.size(),"PENDING",0,null,null,null,null,null,null));
        mapper.rearm(hash,entry.lastModified(),entry.size());
    }

    private String ineligible(String key,CleanupKey.Parsed parsed,SessionRow session,Instant now) {
        if(mapper.formalReferences(key)>0 || mapper.partReferences(key)>0) return "OBJECT_REFERENCED";
        if(session==null) return null;
        // UPLOADING writers have no processing token. Keep all their unregistered candidates until expiry is sealed.
        if("UPLOADING".equals(session.status())) return "UPLOAD_WRITABLE";
        if(parsed.finalToken()!=null && parsed.finalToken().equals(session.leaseToken()) && active(session,now))
            return "PROCESSING_LEASE";
        return null;
    }

    private boolean oldEnough(Entry entry,Instant now) { return !entry.lastModified().isAfter(now.minus(settings.grace())); }
    private static boolean active(SessionRow session,Instant now) { return session.leaseUntil()!=null && session.leaseUntil().isAfter(now); }
    private boolean retirable(SessionRow session,Instant now) {
        return session!=null && !active(session,now) && ("EXPIRED".equals(session.status())
                && !session.expiresAt().isAfter(now.minus(settings.grace())) || "FAILED".equals(session.status())
                && !session.updatedAt().isAfter(now.minus(settings.grace())));
    }
    private static boolean due(TaskRow row,Instant now) {
        return "PENDING".equals(row.status()) && row.nextActionAt()!=null && !row.nextActionAt().isAfter(now)
                || "SENDING".equals(row.status()) && row.leaseUntil()!=null && !row.leaseUntil().isAfter(now);
    }
    private static boolean validJob(TaskRow row,CleanupKey.Parsed parsed) {
        return parsed!=null && parsed.uploadId().equals(row.uploadId()) && CleanupKey.hash(row.objectKey()).equals(row.objectKeyHash());
    }
    private static boolean owned(TaskRow row,Claim claim,Instant now) {
        return row!=null && "SENDING".equals(row.status()) && claim.token().equals(row.leaseToken())
                && row.leaseUntil()!=null && row.leaseUntil().isAfter(now)
                && row.objectKey().equals(claim.objectKey()) && row.uploadId().equals(claim.uploadId());
    }
    private SessionRow lockSession(String id) {
        SessionRow seed=mapper.session(id);
        if(seed==null) return null;
        if(mapper.lockTask(seed.taskId())==null) throw new IllegalStateException("Missing cleanup task scope");
        SessionRow current=mapper.lockSession(id);
        if(current==null || !seed.taskId().equals(current.taskId())) throw new IllegalStateException("Cleanup scope changed");
        return current;
    }
    private void skip(TaskRow job,SessionRow session,String code) {
        if(mapper.skipLocked(job.id(),code)!=1) throw new IllegalStateException("Cleanup skip conflict");
        audit(session==null?null:session.submitterOrgId(),"STORAGE_OBJECT_CLEANUP_SKIPPED","CLEANUP_TASK",job.id());
    }
    private void audit(String org,String action,String type,String id) {
        if(mapper.audit(UUID.randomUUID().toString(),org,action,type,id,"cleanup-"+id)!=1)
            throw new IllegalStateException("Cleanup audit conflict");
    }

    public record Claim(String id,String objectKey,String uploadId,String token,int attemptCount,String orgId) {}
}
