package com.uav.inspection.evidence.finalization;

import com.uav.inspection.evidence.finalization.FinalizationTransactions.Claim;
import com.uav.inspection.evidence.finalization.persistence.FinalizationMapper;
import com.uav.inspection.evidence.finalization.persistence.FinalizationMapper.FixedVersion;
import com.uav.inspection.evidence.finalization.persistence.FinalizationMapper.WorkState;
import com.uav.inspection.evidence.upload.persistence.UploadMapper;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.*;
import com.uav.inspection.messaging.MessageEvent;
import com.uav.inspection.messaging.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinalizationTransactionsTest {
    private static final String UPLOAD="80000000-0000-4000-8000-000000000001";
    private static final String TOKEN="90000000-0000-4000-8000-000000000001";
    private static final MessageEvent EVENT=new MessageEvent("90000000-0000-4000-8000-000000000002","FILE_FINALIZE_REQUESTED",UPLOAD,"tx-finalize");
    private static final Instant NOW=Instant.parse("2026-10-22T00:00:00Z");
    private final UploadMapper uploads=mock(UploadMapper.class);
    private final FinalizationMapper work=mock(FinalizationMapper.class);
    private final OutboxWriter outbox=mock(OutboxWriter.class);
    private final FinalizationTransactions transactions=new FinalizationTransactions(uploads,work,outbox);
    private final SessionRow session=new SessionRow(UPLOAD,"task","subtask","requirement","evidence","execution","org",
            "USER","user","client","hash","image.png","image/png",12,1048576,1,"VERIFYING",NOW.plusSeconds(3600),0,NOW,NOW);
    private final Claim claim=new Claim(EVENT,session,List.of(),TOKEN);

    @BeforeEach
    void scope() {
        when(uploads.findSession(UPLOAD)).thenReturn(session);
        when(uploads.lockSession(UPLOAD)).thenReturn(session);
        when(uploads.lockTask("task")).thenReturn(new TaskRow("task","owner","org","WAITING_ACCEPTANCE"));
        when(uploads.lockSubtask("subtask")).thenReturn(new SubtaskRow("subtask","task","execution"));
        when(uploads.lockRequirement("requirement")).thenReturn(new RequirementRow("requirement","evidence","task","subtask"));
        when(uploads.lockExecution("execution")).thenReturn(new ExecutionRow("execution","subtask","RUNNING"));
        when(uploads.databaseNow()).thenReturn(NOW);
        when(work.state(UPLOAD)).thenReturn(new WorkState(TOKEN,NOW.plusSeconds(120),0,null));
        when(work.findEvent(EVENT.eventId(),UPLOAD)).thenReturn(EVENT);
        when(work.audit(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
    }

    @Test
    void expiredLeaseAndReplacedTokenCannotWriteTheVersionOrAcknowledgeCompletion() {
        for(WorkState state:List.of(new WorkState(TOKEN,NOW,0,null),new WorkState("replacement",NOW.plusSeconds(120),0,null))) {
            when(work.state(UPLOAD)).thenReturn(state);
            assertFalse(transactions.finish(claim,"evidence-files/"+UPLOAD+"/"+TOKEN,12,"a".repeat(64)));
        }
        verify(work,never()).insertVersion(any());
        verify(work,never()).advanceVersion(anyString(),anyInt());
        verify(work,never()).done(anyString());
        verifyNoInteractions(outbox);
    }

    @Test
    void finalVersionUsesTheLockedSequenceAndPersistsOnePendingSubmissionAndNextEvent() {
        when(work.lockVersionNumber("requirement")).thenReturn(7);
        when(work.insertVersion(any())).thenReturn(1);
        when(work.advanceVersion("requirement",7)).thenReturn(1);
        when(work.insertSubmission(anyString(),anyString())).thenReturn(1);
        when(work.complete(UPLOAD,TOKEN)).thenReturn(1);

        assertTrue(transactions.finish(claim,"evidence-files/"+UPLOAD+"/"+TOKEN,12,"b".repeat(64)));

        var version=ArgumentCaptor.forClass(FixedVersion.class);
        verify(work).insertVersion(version.capture());
        assertEquals(7,version.getValue().versionNo());
        assertEquals("user",version.getValue().uploaderUserId());
        assertNull(version.getValue().uploaderDroneId());
        verify(work).insertSubmission(anyString(),eq(version.getValue().id()));
        verify(outbox).enqueue(eq("EVIDENCE_ANCHOR_REQUESTED"),anyString(),eq(EVENT.traceId()));
        verify(work).complete(UPLOAD,TOKEN);
        verify(work).reopenTask("task");
        verify(work).done(EVENT.eventId());
    }

    @Test
    void aSessionWithAnExistingVersionNeverAllocatesAnotherNumberOrSubmission() {
        when(uploads.findVersionId(UPLOAD)).thenReturn("existing-version");
        when(work.complete(UPLOAD,TOKEN)).thenReturn(1);

        assertTrue(transactions.finish(claim,"unused-candidate",12,"b".repeat(64)));

        verify(work).complete(UPLOAD,TOKEN);
        verify(work).done(EVENT.eventId());
        verify(work,never()).lockVersionNumber(anyString());
        verify(work,never()).insertVersion(any());
        verify(work,never()).advanceVersion(anyString(),anyInt());
        verify(work,never()).insertSubmission(anyString(),anyString());
        verifyNoInteractions(outbox);
    }

    @Test
    void aConcurrentDeliveryCannotTakeOverAnAlreadyClaimedSession() {
        when(work.claim(eq(UPLOAD),anyString())).thenReturn(1,0);
        when(uploads.listParts(UPLOAD)).thenReturn(List.of());

        assertNotNull(transactions.claim(EVENT,false));
        assertNull(transactions.claim(EVENT,false));

        verify(work).processing(EVENT.eventId());
        verify(uploads).listParts(UPLOAD);
        verify(work,never()).insertVersion(any());
        verify(work,never()).done(anyString());
    }

    @Test
    void lateDeviceVersionRetainsItsClosedExecutionAndDoesNotReopenReplacementTask() {
        var historical = new SessionRow(UPLOAD,"task","subtask","requirement","evidence","execution","org",
                "DEVICE","old-drone","client","hash","image.png","image/png",12,1048576,1,"VERIFYING",NOW.plusSeconds(3600),0,NOW,NOW);
        when(uploads.findSession(UPLOAD)).thenReturn(historical);
        when(uploads.lockSession(UPLOAD)).thenReturn(historical);
        when(uploads.lockExecution("execution")).thenReturn(new ExecutionRow("execution","subtask","CLOSED"));
        when(uploads.lockSubtask("subtask")).thenReturn(new SubtaskRow("subtask","task","replacement-execution"));
        when(work.lockVersionNumber("requirement")).thenReturn(8);
        when(work.insertVersion(any())).thenReturn(1);
        when(work.advanceVersion("requirement",8)).thenReturn(1);
        when(work.insertSubmission(anyString(),anyString())).thenReturn(1);
        when(work.complete(UPLOAD,TOKEN)).thenReturn(1);

        assertTrue(transactions.finish(new Claim(EVENT,historical,List.of(),TOKEN),"evidence-files/"+UPLOAD+"/"+TOKEN,12,"c".repeat(64)));

        var version = ArgumentCaptor.forClass(FixedVersion.class);
        verify(work).insertVersion(version.capture());
        assertEquals("execution",version.getValue().executionId());
        assertEquals("old-drone",version.getValue().uploaderDroneId());
        assertNull(version.getValue().uploaderUserId());
        assertEquals(8,version.getValue().versionNo());
        verify(work,never()).reopenTask(anyString());
        verify(outbox).enqueue(eq("EVIDENCE_ANCHOR_REQUESTED"),anyString(),eq(EVENT.traceId()));
    }

    @Test
    void doneMessageIsIgnoredButScannerCanReclaimTheStillVerifyingBusinessWork() {
        when(work.consumption(EVENT.eventId())).thenReturn("DONE");
        when(work.claim(eq(UPLOAD),anyString())).thenReturn(1);
        when(uploads.listParts(UPLOAD)).thenReturn(List.of());
        assertNull(transactions.claim(EVENT,false));
        verify(work,never()).claim(anyString(),anyString());

        Claim recovery=transactions.claim(EVENT,true);

        assertNotNull(recovery);
        assertEquals(session,recovery.session());
        verify(work).processing(EVENT.eventId());
    }

    @Test
    void temporaryFailureCommitsItsNextActionBeforeMarkingTheMessageDone() {
        when(work.checkpoint(eq(UPLOAD),eq(TOKEN),eq("VERIFYING"),any(),eq("STORAGE_UNAVAILABLE"))).thenReturn(1);
        assertTrue(transactions.checkpoint(claim,false,"STORAGE_UNAVAILABLE"));
        var order=inOrder(work);
        order.verify(work).checkpoint(UPLOAD,TOKEN,"VERIFYING",NOW.plusSeconds(2),"STORAGE_UNAVAILABLE");
        order.verify(work).done(EVENT.eventId());
    }

    @Test
    void aFailedCheckpointNeverMarksTheMessageDone() {
        when(work.checkpoint(eq(UPLOAD),eq(TOKEN),anyString(),any(),anyString())).thenReturn(0);
        assertThrows(IllegalStateException.class,()->transactions.checkpoint(claim,false,"STORAGE_UNAVAILABLE"));
        verify(work,never()).done(anyString());
    }
}
