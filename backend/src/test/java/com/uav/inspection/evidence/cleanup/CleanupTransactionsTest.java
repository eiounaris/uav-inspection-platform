package com.uav.inspection.evidence.cleanup;

import com.uav.inspection.evidence.cleanup.CleanupTransactions.Claim;
import com.uav.inspection.evidence.cleanup.persistence.CleanupMapper;
import com.uav.inspection.evidence.cleanup.persistence.CleanupMapper.SessionRow;
import com.uav.inspection.evidence.cleanup.persistence.CleanupMapper.TaskRow;
import com.uav.inspection.evidence.storage.ObjectInventory.Entry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CleanupTransactionsTest {
    static final String UPLOAD="10000000-0000-4000-8000-000000000001",TASK="20000000-0000-4000-8000-000000000001";
    static final String JOB="30000000-0000-4000-8000-000000000001",TOKEN="40000000-0000-4000-8000-000000000001";
    static final String OLD="50000000-0000-4000-8000-000000000001",ORG="00000000-0000-4000-8000-000000000002";
    static final Instant NOW=Instant.parse("2026-10-27T00:00:00Z");
    static final String KEY="evidence-files/"+UPLOAD+"/"+OLD;
    private final CleanupMapper mapper=mock(CleanupMapper.class);
    private final CleanupTransactions phases=new CleanupTransactions(mapper,new CleanupSettings(new MockEnvironment()));

    @BeforeEach void setup() {
        when(mapper.now()).thenReturn(NOW);session("COMPLETED",null,null);
        when(mapper.insert(any())).thenReturn(1);when(mapper.skipLocked(anyString(),anyString())).thenReturn(1);
        when(mapper.skipOwned(anyString(),anyString(),anyString())).thenReturn(1);
        when(mapper.audit(anyString(),nullable(String.class),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
    }

    @Test void openUploadsProtectAllUnregisteredObjectsEvenAfterTheDeadlineUntilExpiryIsCommitted() {
        session("UPLOADING",null,null);
        assertFalse(phases.discover(old(KEY)));
        verify(mapper,never()).insert(any());
    }

    @Test void exactFormalAndPartReferencesAlwaysProtectObjects() {
        when(mapper.formalReferences(KEY)).thenReturn(1);
        assertFalse(phases.discover(old(KEY)));
        when(mapper.formalReferences(KEY)).thenReturn(0);when(mapper.partReferences(KEY)).thenReturn(1);
        assertFalse(phases.discover(old(KEY)));verify(mapper,never()).insert(any());
    }

    @Test void liveFinalizerTokenIsProtectedWhileAnOldUnreferencedTokenCanBeCollected() {
        session("VERIFYING",TOKEN,NOW.plusSeconds(120));
        assertFalse(phases.discover(old("evidence-files/"+UPLOAD+"/"+TOKEN)));
        assertTrue(phases.discover(old(KEY)));
        verify(mapper).insert(argThat(job->KEY.equals(job.objectKey())&&UPLOAD.equals(job.uploadId())));
    }

    @Test void unrecognizedOrYoungObjectsNeverCreateCleanupIntents() {
        for(String value:List.of("other/"+UPLOAD,"evidence-files/"+UPLOAD+"/../private",
                "upload-parts/"+UPLOAD+"/01/"+OLD,"upload-parts/"+UPLOAD+"/10001/"+OLD))
            assertFalse(phases.discover(old(value)));
        assertFalse(phases.discover(new Entry(KEY,NOW.minusSeconds(3599),1)));
        verify(mapper,never()).insert(any());
    }

    @Test void claimLocksParentBeforeCleanupTaskAndRechecksReferences() {
        TaskRow job=job("PENDING",null,null);when(mapper.task(JOB)).thenReturn(job);when(mapper.lockCleanup(JOB)).thenReturn(job);
        when(mapper.formalReferences(KEY)).thenReturn(1);
        assertNull(phases.claim(JOB));
        var order=inOrder(mapper);
        order.verify(mapper).lockTask(TASK);order.verify(mapper).lockSession(UPLOAD);order.verify(mapper).lockCleanup(JOB);
        verify(mapper).skipLocked(JOB,"OBJECT_REFERENCED");verify(mapper,never()).claim(anyString(),anyString());
    }

    @Test void aReferenceCommittedDuringHeadPreventsDeletionDespiteAnExistingIntent() {
        when(mapper.lockCleanup(JOB)).thenReturn(job("SENDING",TOKEN,NOW.plusSeconds(120)));
        when(mapper.formalReferences(KEY)).thenReturn(1);
        assertFalse(phases.verifyDeletion(claim(),old(KEY)));
        verify(mapper).skipOwned(JOB,TOKEN,"OBJECT_REFERENCED");
    }

    @Test void freshReplacementMetadataAndLostTokensAreRejectedBeforeDeletion() {
        when(mapper.lockCleanup(JOB)).thenReturn(job("SENDING",TOKEN,NOW.plusSeconds(120)));
        assertFalse(phases.verifyDeletion(claim(),new Entry(KEY,NOW,1)));
        verify(mapper).skipOwned(JOB,TOKEN,"GRACE_PERIOD");
        when(mapper.lockCleanup(JOB)).thenReturn(job("SENDING",OLD,NOW.plusSeconds(120)));
        assertFalse(phases.verifyDeletion(claim(),old(KEY)));
        verify(mapper,times(1)).skipOwned(anyString(),anyString(),anyString());
    }

    @Test void expiryCannotInvalidateAnActiveProcessingLeaseOrAnExistingVersion() {
        when(mapper.expiredSessions()).thenReturn(List.of(UPLOAD));session("UPLOADING",TOKEN,NOW.plusSeconds(1));
        assertEquals(0,phases.expireDue());
        session("UPLOADING",null,null);when(mapper.sessionVersions(UPLOAD)).thenReturn(1);
        assertEquals(0,phases.expireDue());verify(mapper,never()).expire(anyString());
    }

    @Test void completedAndVerifyingPartsAreNeverRetired() {
        when(mapper.retiredSessions(3600)).thenReturn(List.of(UPLOAD));
        for(String status:List.of("COMPLETED","VERIFYING")) { session(status,null,null);assertEquals(0,phases.retireDue()); }
        verify(mapper,never()).parts(anyString(),anyInt());verify(mapper,never()).retirePart(anyString(),anyInt(),anyString());
    }

    @Test void terminalPartRetirementAndDurableIntentShareTheSameTransactionPhase() {
        String key="upload-parts/"+UPLOAD+"/1/"+OLD;session("EXPIRED",null,null);
        when(mapper.retiredSessions(3600)).thenReturn(List.of(UPLOAD));
        when(mapper.parts(UPLOAD,100)).thenReturn(List.of(new CleanupMapper.PartRow(UPLOAD,1,key,1,NOW.minusSeconds(7200))));
        when(mapper.retirePart(UPLOAD,1,key)).thenReturn(1);
        assertEquals(1,phases.retireDue());
        var order=inOrder(mapper);order.verify(mapper).retirePart(UPLOAD,1,key);order.verify(mapper).insert(any());
        verify(mapper).audit(anyString(),eq(ORG),eq("UPLOAD_PARTS_RETIRED"),eq("UPLOAD_SESSION"),eq(UPLOAD),anyString());
    }

    @Test void rediscoveredDoneObjectsCanBeRearmedWithoutResettingAnActiveLeaseOrRetryPlan() {
        when(mapper.byHash(CleanupKey.hash(KEY))).thenReturn(job("DONE",null,null));
        assertTrue(phases.discover(old(KEY)));
        verify(mapper).rearm(CleanupKey.hash(KEY),NOW.minusSeconds(7200),1);verify(mapper,never()).insert(any());
    }

    @Test void staleCompletionCannotWriteAuditAndRetryUsesBoundedBackoff() {
        assertFalse(phases.done(claim()));verify(mapper,never()).audit(anyString(),nullable(String.class),anyString(),anyString(),anyString(),anyString());
        when(mapper.retry(JOB,TOKEN,2)).thenReturn(1);
        assertTrue(phases.retry(claim()));
        var oldClaim=new Claim(JOB,KEY,UPLOAD,TOKEN,Integer.MAX_VALUE,ORG);when(mapper.retry(JOB,TOKEN,300)).thenReturn(1);
        assertTrue(phases.retry(oldClaim));
        verify(mapper,times(2)).audit(anyString(),eq(ORG),eq("STORAGE_OBJECT_CLEANUP_RETRY"),eq("CLEANUP_TASK"),eq(JOB),anyString());
    }

    private void session(String status,String token,Instant until) {
        var row=new SessionRow(UPLOAD,TASK,ORG,status,NOW.minusSeconds(7200),token,until,NOW.minusSeconds(7200));
        when(mapper.session(UPLOAD)).thenReturn(row);when(mapper.lockTask(TASK)).thenReturn(TASK);when(mapper.lockSession(UPLOAD)).thenReturn(row);
    }
    private static Entry old(String key) { return new Entry(key,NOW.minusSeconds(7200),1); }
    private static Claim claim() { return new Claim(JOB,KEY,UPLOAD,TOKEN,1,ORG); }
    private static TaskRow job(String state,String token,Instant until) {
        return new TaskRow(JOB,KEY,CleanupKey.hash(KEY),UPLOAD,NOW.minusSeconds(7200),1,state,1,
                "PENDING".equals(state)?NOW:null,token,until,null,NOW.minusSeconds(7200),NOW.minusSeconds(7200));
    }
}
