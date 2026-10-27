package com.uav.inspection.evidence.cleanup;

import com.uav.inspection.evidence.storage.ObjectInventory;
import com.uav.inspection.evidence.storage.ObjectInventory.Entry;
import com.uav.inspection.evidence.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded inventory and idempotent deletion. Database commit failures are never classified as storage failures. */
@Service
@Profile("database & storage & cleanup")
public final class CleanupProcessor {
    private static final Logger LOG=LoggerFactory.getLogger(CleanupProcessor.class);
    private final CleanupTransactions phases;
    private final ObjectInventory inventory;
    private final ObjectStorage storage;
    private final CleanupSettings settings;
    private final Map<String,String> continuations=new HashMap<>();

    public CleanupProcessor(CleanupTransactions phases,ObjectInventory inventory,ObjectStorage storage,CleanupSettings settings) {
        this.phases=phases;this.inventory=inventory;this.storage=storage;this.settings=settings;
    }

    public synchronized void runOnce() {
        if(Thread.currentThread().isInterrupted()) return;
        phases.expireDue();phases.retireDue();
        for(String prefix:List.of("upload-parts/","evidence-files/")) {
            if(Thread.currentThread().isInterrupted()) return;
            ObjectInventory.Page page;
            try { page=inventory.list(prefix,continuations.get(prefix),settings.batchSize()); }
            catch(RuntimeException unavailable) {
                continuations.remove(prefix);
                LOG.warn("Cleanup inventory deferred: {}",unavailable.getClass().getSimpleName());continue;
            }
            int seen=0;
            for(Entry entry:page.entries()) {
                if(Thread.currentThread().isInterrupted()) return;
                if(seen++>=settings.batchSize()) break;
                phases.discover(entry);
            }
            if(page.continuation()==null) continuations.remove(prefix);
            else continuations.put(prefix,page.continuation());
        }
        for(String id:phases.due()) {
            if(Thread.currentThread().isInterrupted()) return;
            var claim=phases.claim(id);
            if(claim==null) continue;
            Entry current;
            try { current=inventory.head(claim.objectKey()); }
            catch(RuntimeException unavailable) { phases.retry(claim);return; }
            if(Thread.currentThread().isInterrupted()) return;
            if(!phases.verifyDeletion(claim,current)) continue;
            try { if(current!=null) storage.delete(claim.objectKey()); }
            catch(RuntimeException unavailable) { phases.retry(claim);return; }
            // Never reclassify a DB update/commit failure as an external delete failure.
            if(!phases.done(claim)) return;
        }
    }
}
