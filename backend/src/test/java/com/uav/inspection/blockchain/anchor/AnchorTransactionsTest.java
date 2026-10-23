package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.EvidenceLedgerClient.PreparedRegistration;
import com.uav.inspection.blockchain.anchor.AnchorTransactions.Claim;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper.SubmissionRow;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.messaging.MessageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private static SubmissionRow submission(String token,Instant expiry) {
        return new SubmissionRow(SUBMISSION,"version","UNKNOWN",null,null,true,0,NOW,token,expiry,null,NOW,NOW,
                "task","evidence",1,"b".repeat(64),ResourcePermissionService.OPERATOR);
    }
}
