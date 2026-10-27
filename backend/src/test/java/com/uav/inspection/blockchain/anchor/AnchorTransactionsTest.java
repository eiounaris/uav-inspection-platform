package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.EvidenceLedgerClient.PreparedRegistration;
import com.uav.inspection.blockchain.EvidenceLedgerClient.CommitCheckpoint;
import com.uav.inspection.blockchain.EvidenceLedgerClient.CommitOutcome;
import com.uav.inspection.blockchain.anchor.AnchorTransactions.Claim;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper.AttemptRow;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper.SubmissionRow;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.messaging.MessageEvent;
import com.uav.inspection.messaging.InvalidMessageException;
import com.uav.inspection.messaging.RetryableDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.hyperledger.fabric.protos.peer.TxValidationCode;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnchorTransactionsTest {
    private static final String SUBMISSION="80000000-0000-4000-8000-000000000001";
    private static final String TOKEN="90000000-0000-4000-8000-000000000002";
    private static final MessageEvent EVENT=new MessageEvent("90000000-0000-4000-8000-000000000001","EVIDENCE_ANCHOR_REQUESTED",SUBMISSION,"anchor-transactions");
    private static final Instant NOW=Instant.parse("2026-10-23T00:00:00Z");
    private final AnchorMapper mapper=mock(AnchorMapper.class);
    private final AnchorTransactions phases=new AnchorTransactions(mapper);
    private final Claim claim=new Claim(EVENT,submission(TOKEN,NOW.plusSeconds(120)),null,TOKEN);

    @BeforeEach
    void scope() {
        when(mapper.find(SUBMISSION)).thenReturn(claim.submission());
        when(mapper.lockTask("task")).thenReturn("task");
        when(mapper.lock(SUBMISSION)).thenReturn(claim.submission());
        when(mapper.now()).thenReturn(NOW);
        when(mapper.event(EVENT.eventId(),SUBMISSION)).thenReturn(EVENT);
        when(mapper.latestEventId(SUBMISSION)).thenReturn(EVENT.eventId());
        when(mapper.audit(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
    }

    @Test
    void expiredOrReplacedLeasesCannotSavePreparedTransactionsOrConfirmTheSubmission() {
        for(SubmissionRow current:new SubmissionRow[]{submission(TOKEN,NOW),submission("replacement",NOW.plusSeconds(120))}) {
            when(mapper.lock(SUBMISSION)).thenReturn(current);
            assertNull(phases.prepared(claim,new PreparedRegistration("a".repeat(64),new byte[]{1})));
            assertFalse(phases.confirm(claim,"f".repeat(64),"Org2MSP"));
        }
        verify(mapper,never()).insertPrepared(anyString(),anyString(),anyInt(),anyString(),any(),anyString());
        verify(mapper,never()).confirm(anyString(),anyString(),anyString(),anyString());
        verify(mapper,never()).consume(anyString(),anyString());
    }

    @Test
    void claimLocksTheParentTaskBeforeTheSubmissionAndScannerCanBypassADoneMessage() {
        when(mapper.consumption(EVENT.eventId())).thenReturn("DONE");
        when(mapper.claim(eq(SUBMISSION),anyString())).thenReturn(1);
        assertNull(phases.claim(EVENT,false));
        verify(mapper,never()).claim(anyString(),anyString());
        assertNotNull(phases.claim(EVENT,true));
        var order=inOrder(mapper);
        order.verify(mapper).find(SUBMISSION);
        order.verify(mapper).lockTask("task");
        order.verify(mapper).lock(SUBMISSION);
        verify(mapper).consume(EVENT.eventId(),"PROCESSING");
    }

    @Test
    void unknownCheckpointCommitsTheNextActionBeforeMarkingTheMessageDone() {
        when(mapper.checkpoint(SUBMISSION,TOKEN,"UNKNOWN",true,NOW.plusSeconds(2),"COMMIT_STATUS_UNAVAILABLE")).thenReturn(1);
        assertTrue(phases.checkpoint(claim,null,"UNKNOWN",true,"COMMIT_STATUS_UNAVAILABLE",true));
        var order=inOrder(mapper);
        order.verify(mapper).checkpoint(SUBMISSION,TOKEN,"UNKNOWN",true,NOW.plusSeconds(2),"COMMIT_STATUS_UNAVAILABLE");
        order.verify(mapper).consume(EVENT.eventId(),"DONE");
    }

    @Test
    void aConditionalCheckpointFailureCannotMarkTheMessageDone() {
        assertThrows(AnchorTransactions.LostLeaseException.class,
                ()->phases.checkpoint(claim,null,"UNKNOWN",true,"COMMIT_STATUS_UNAVAILABLE",true));
        verify(mapper,never()).consume(anyString(),eq("DONE"));
    }

    @Test
    void confirmingUsesTheLedgerFirstTransactionIdWithoutInventingAnAttemptOutcome() {
        when(mapper.confirm(SUBMISSION,TOKEN,"f".repeat(64),"Org2MSP")).thenReturn(1);
        assertTrue(phases.confirm(claim,"f".repeat(64),"Org2MSP"));
        verify(mapper).confirm(SUBMISSION,TOKEN,"f".repeat(64),"Org2MSP");
        verify(mapper).consume(EVENT.eventId(),"DONE");
        verify(mapper,never()).outcome(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt(),anyLong());
    }

    @Test
    void anActiveWorkerWithoutADoneCheckpointCannotTransferResponsibility() {
        when(mapper.consumption(EVENT.eventId())).thenReturn("PROCESSING");
        assertThrows(RetryableDeliveryException.class,()->phases.verifyAcknowledgement(EVENT));
    }

    @Test
    void aDoneFlagWithALiveOrExpiredLeaseDoesNotAllowAcknowledgement() {
        when(mapper.consumption(EVENT.eventId())).thenReturn("DONE");
        for(Instant expiry:new Instant[]{NOW.plusSeconds(120),NOW.minusSeconds(1)}) {
            when(mapper.lock(SUBMISSION)).thenReturn(submission(TOKEN,expiry));
            assertThrows(RetryableDeliveryException.class,()->phases.verifyAcknowledgement(EVENT));
        }
    }

    @Test
    void aLeaseFreeDoneFlagStillNeedsADurableWaitingPlan() {
        when(mapper.consumption(EVENT.eventId())).thenReturn("DONE");
        when(mapper.lock(SUBMISSION)).thenReturn(waiting("UNKNOWN",null,true,0));
        assertThrows(RetryableDeliveryException.class,()->phases.verifyAcknowledgement(EVENT));
    }

    @Test
    void durableWaitingPlansAreAcknowledgedForEveryRecoverableState() {
        when(mapper.consumption(EVENT.eventId())).thenReturn("DONE");
        for(String status:new String[]{"PENDING","SUBMITTED","UNKNOWN","FAILED"}) {
            when(mapper.lock(SUBMISSION)).thenReturn(waiting(status,NOW.plusSeconds(2),true,1));
            assertDoesNotThrow(()->phases.verifyAcknowledgement(EVENT));
        }
        verify(mapper,never()).consume(anyString(),anyString());
    }

    @Test
    void confirmedFirstTransactionOrTerminalFailureIsSufficientWithoutANewRpc() {
        when(mapper.consumption(EVENT.eventId())).thenReturn("DONE");
        for(SubmissionRow row:new SubmissionRow[]{waiting("CONFIRMED",null,false,0),
                waiting("FAILED",null,false,1),waiting("FAILED",null,true,10)}) {
            when(mapper.lock(SUBMISSION)).thenReturn(row);
            when(mapper.automaticFailureCount(SUBMISSION)).thenReturn(row.retryable()?10:0);
            assertDoesNotThrow(()->phases.verifyAcknowledgement(EVENT));
        }
        verify(mapper,never()).consume(anyString(),anyString());
    }

    @Test
    void missingSubmissionsNeverCreateAFictitiousDoneCheckpoint() {
        when(mapper.find(SUBMISSION)).thenReturn(null);
        assertThrows(InvalidMessageException.class,()->phases.claim(EVENT,false));
        assertThrows(InvalidMessageException.class,()->phases.verifyAcknowledgement(EVENT));
        verify(mapper,never()).consume(anyString(),anyString());
    }

    @Test
    void renewalLocksTheParentBeforeExtendingOnlyTheCurrentDatabaseLease() {
        when(mapper.renew(SUBMISSION,TOKEN)).thenReturn(1);
        assertTrue(phases.renew(claim));
        var order=inOrder(mapper);
        order.verify(mapper).find(SUBMISSION);
        order.verify(mapper).lockTask("task");
        order.verify(mapper).lock(SUBMISSION);
        order.verify(mapper).now();
        order.verify(mapper).renew(SUBMISSION,TOKEN);
        verify(mapper,never()).consume(anyString(),anyString());
        verify(mapper,never()).audit(anyString(),anyString(),anyString(),anyString(),anyString());
    }

    @Test
    void anExpiredReplacedOrMissingLeaseCannotBeRevivedByRenewal() {
        for(SubmissionRow current:new SubmissionRow[]{submission(TOKEN,NOW),
                submission(TOKEN,NOW.minusSeconds(1)),submission("replacement",NOW.plusSeconds(120))}) {
            when(mapper.lock(SUBMISSION)).thenReturn(current);
            assertFalse(phases.renew(claim));
        }
        when(mapper.find(SUBMISSION)).thenReturn(null);
        assertFalse(phases.renew(claim));
        verify(mapper,never()).renew(anyString(),anyString());
        verify(mapper,never()).consume(anyString(),anyString());
    }

    @Test
    void aRenewalConditionalMissOrDatabaseFailureIsNotReportedAsSuccess() {
        assertFalse(phases.renew(claim));
        when(mapper.renew(SUBMISSION,TOKEN)).thenThrow(new IllegalStateException("Unavailable database"));
        assertThrows(IllegalStateException.class,()->phases.renew(claim));
        verify(mapper,never()).consume(anyString(),anyString());
    }

    @Test
    void terminalStatesCannotRenewOrWriteCheckpointsEvenWithAnUnexpectedLiveToken() {
        for(SubmissionRow terminal:new SubmissionRow[]{terminal("CONFIRMED",false,0),
                terminal("FAILED",false,1),terminal("FAILED",true,10)}) {
            when(mapper.lock(SUBMISSION)).thenReturn(terminal);
            when(mapper.automaticFailureCount(SUBMISSION)).thenReturn(terminal.retryable()?10:0);
            assertFalse(phases.renew(claim));
            assertFalse(phases.confirm(claim,"f".repeat(64),"Org2MSP"));
            assertFalse(phases.checkpoint(claim,null,"UNKNOWN",true,"COMMIT_STATUS_UNAVAILABLE",true));
        }
        verify(mapper,never()).renew(anyString(),anyString());
        verify(mapper,never()).confirm(anyString(),anyString(),anyString(),anyString());
        verify(mapper,never()).checkpoint(anyString(),anyString(),anyString(),anyBoolean(),any(),anyString());
        verify(mapper,never()).consume(anyString(),anyString());
    }

    @Test
    void expiredWorkersCannotSaveSubmissionOutcomeOrRecoveryMetadata() {
        AttemptRow attempt=new AttemptRow("attempt",SUBMISSION,1,"a".repeat(64),"SUBMITTED",new byte[]{1},
                new byte[]{2},null,null,null,NOW,NOW);
        for(SubmissionRow current:new SubmissionRow[]{submission(TOKEN,NOW),submission("replacement",NOW.plusSeconds(120))}) {
            when(mapper.lock(SUBMISSION)).thenReturn(current);
            assertFalse(phases.submitting(claim,attempt));
            assertFalse(phases.submitted(claim,attempt,new CommitCheckpoint(attempt.txId(),new byte[]{3})));
            assertFalse(phases.outcome(claim,attempt,new CommitOutcome(attempt.txId(),true,0,42)));
            assertFalse(phases.checkpoint(claim,attempt,"UNKNOWN",true,"COMMIT_STATUS_UNAVAILABLE",true));
        }
        verify(mapper,never()).submitting(anyString(),anyString(),anyString());
        verify(mapper,never()).submitted(anyString(),anyString(),anyString(),anyString(),any());
        verify(mapper,never()).outcome(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt(),anyLong());
        verify(mapper,never()).attemptError(anyString(),anyString(),anyString(),anyBoolean(),anyString());
        verify(mapper,never()).checkpoint(anyString(),anyString(),anyString(),anyBoolean(),any(),anyString());
        verify(mapper,never()).consume(anyString(),anyString());
    }

    @Test
    void manyUnknownReconciliationsDoNotConsumeTheDefiniteFailureBudget() {
        when(mapper.lock(SUBMISSION)).thenReturn(terminal("UNKNOWN",true,100));
        when(mapper.automaticFailureCount(SUBMISSION)).thenReturn(10);
        when(mapper.checkpoint(SUBMISSION,TOKEN,"UNKNOWN",true,NOW.plusSeconds(300),"COMMIT_STATUS_UNAVAILABLE")).thenReturn(1);
        assertTrue(phases.checkpoint(claim,null,"UNKNOWN",true,"COMMIT_STATUS_UNAVAILABLE",true));
        verify(mapper).checkpoint(SUBMISSION,TOKEN,"UNKNOWN",true,NOW.plusSeconds(300),"COMMIT_STATUS_UNAVAILABLE");
        verify(mapper).consume(EVENT.eventId(),"DONE");
    }

    @Test
    void aHighReconciliationCounterCannotMakeTheFirstKnownConflictTerminal() {
        when(mapper.lock(SUBMISSION)).thenReturn(terminal("PENDING",true,100));
        when(mapper.automaticFailureCount(SUBMISSION)).thenReturn(1);
        when(mapper.checkpoint(SUBMISSION,TOKEN,"FAILED",true,NOW.plusSeconds(300),"COMMIT_CONFLICT")).thenReturn(1);
        assertTrue(phases.checkpoint(claim,null,"FAILED",true,"COMMIT_CONFLICT",false));
        verify(mapper).checkpoint(SUBMISSION,TOKEN,"FAILED",true,NOW.plusSeconds(300),"COMMIT_CONFLICT");
    }

    @Test
    void theTenthKnownConflictCommitsAStoppedPlanAndDoneReceipt() {
        when(mapper.lock(SUBMISSION)).thenReturn(terminal("PENDING",true,9));
        when(mapper.automaticFailureCount(SUBMISSION)).thenReturn(10);
        when(mapper.checkpoint(SUBMISSION,TOKEN,"FAILED",true,null,"COMMIT_CONFLICT")).thenReturn(1);
        assertTrue(phases.checkpoint(claim,null,"FAILED",true,"COMMIT_CONFLICT",false));
        var order=inOrder(mapper);
        order.verify(mapper).checkpoint(SUBMISSION,TOKEN,"FAILED",true,null,"COMMIT_CONFLICT");
        order.verify(mapper).consume(EVENT.eventId(),"DONE");
    }

    @Test
    void anExhaustedConflictCycleDoesNotClaimButANewManualBaselineCanReopenIt() {
        when(mapper.lock(SUBMISSION)).thenReturn(waiting("FAILED",NOW,true,100));
        when(mapper.automaticFailureCount(SUBMISSION)).thenReturn(10,0);
        when(mapper.claim(eq(SUBMISSION),anyString())).thenReturn(1);
        assertNull(phases.claim(EVENT,true));
        verify(mapper,never()).claim(anyString(),anyString());
        assertNotNull(phases.claim(EVENT,true));
        verify(mapper).claim(eq(SUBMISSION),anyString());
    }

    @Test
    void knownInvalidAttemptsCannotUseUnknownStateToBypassTheConflictBudget() {
        when(mapper.lock(SUBMISSION)).thenReturn(terminal("UNKNOWN",true,100));
        when(mapper.automaticFailureCount(SUBMISSION)).thenReturn(10,0);
        assertFalse(phases.retryAllowed(claim));
        assertTrue(phases.retryAllowed(claim));
        verify(mapper,never()).consume(anyString(),anyString());
    }

    @Test
    void aScanSelectedBeforeManualReschedulingCannotClaimTheSupersededDispatch() {
        when(mapper.latestEventId(SUBMISSION)).thenReturn("90000000-0000-4000-8000-000000000099");
        assertNull(phases.claim(EVENT,true));
        verify(mapper,never()).claim(anyString(),anyString());
        verify(mapper,never()).consume(anyString(),anyString());
    }

    @Test
    void anUnknownAttemptAtTheCounterLimitStillAllowsOriginalTransactionRecovery() {
        when(mapper.lock(SUBMISSION)).thenReturn(waiting("UNKNOWN",NOW,true,Integer.MAX_VALUE));
        when(mapper.automaticFailureCount(SUBMISSION)).thenReturn(10);
        when(mapper.claim(eq(SUBMISSION),anyString())).thenReturn(1);
        assertNotNull(phases.claim(EVENT,true));
        verify(mapper).consume(EVENT.eventId(),"PROCESSING");
    }

    @Test
    void preparingANewAttemptRequiresAPreviousAllowlistedInvalidOutcomeAndRemainingBudget() {
        PreparedRegistration prepared=new PreparedRegistration("c".repeat(64),new byte[]{1});
        for(Integer code:new Integer[]{null,0,7,TxValidationCode.DUPLICATE_TXID_VALUE}) {
            when(mapper.latest(SUBMISSION)).thenReturn(attempt("INVALID",code));
            assertThrows(IllegalStateException.class,()->phases.prepared(claim,prepared));
        }
        when(mapper.latest(SUBMISSION)).thenReturn(attempt("INVALID",11));
        when(mapper.automaticFailureCount(SUBMISSION)).thenReturn(10);
        assertThrows(IllegalStateException.class,()->phases.prepared(claim,prepared));
        verify(mapper,never()).insertPrepared(anyString(),anyString(),anyInt(),anyString(),any(),anyString());
    }

    private static AttemptRow attempt(String phase,Integer code) {
        return new AttemptRow("old",SUBMISSION,10,"a".repeat(64),phase,new byte[]{1},new byte[]{2},code,
                code==null?null:42L,null,NOW,NOW);
    }

    private static SubmissionRow terminal(String status,boolean retryable,int retries) {
        return new SubmissionRow(SUBMISSION,"version",status,null,null,retryable,retries,NOW,TOKEN,
                NOW.plusSeconds(120),null,NOW,NOW,"task","evidence",1,"b".repeat(64),ResourcePermissionService.OPERATOR);
    }

    private static SubmissionRow waiting(String status,Instant next,boolean retryable,int retries) {
        return new SubmissionRow(SUBMISSION,"version",status,"CONFIRMED".equals(status)?"f".repeat(64):null,
                null,retryable,retries,next,null,null,null,NOW,NOW,"task","evidence",1,
                "b".repeat(64),ResourcePermissionService.OPERATOR);
    }

    private static SubmissionRow submission(String token,Instant expiry) {
        return new SubmissionRow(SUBMISSION,"version","UNKNOWN",null,null,true,0,NOW,token,expiry,null,NOW,NOW,
                "task","evidence",1,"b".repeat(64),ResourcePermissionService.OPERATOR);
    }
}
