package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.EvidenceLedgerClient.*;
import com.uav.inspection.blockchain.InstitutionLedgerClients;
import com.uav.inspection.blockchain.anchor.AnchorTransactions.Claim;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper.AttemptRow;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper.SubmissionRow;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.common.lease.ProcessingLeaseKeeper;
import com.uav.inspection.common.lease.ProcessingLeaseLostException;
import com.uav.inspection.messaging.MessageEvent;
import com.uav.inspection.messaging.RetryableDeliveryException;
import io.grpc.Status;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.protos.peer.TxValidationCode;
import org.hyperledger.fabric.protos.gateway.ErrorDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnchorProcessorTest {
    private static final String SUBMISSION="80000000-0000-4000-8000-000000000001";
    private static final String EVENT_ID="90000000-0000-4000-8000-000000000001";
    private static final String TX="a".repeat(64),FIRST_TX="f".repeat(64),HASH="b".repeat(64);
    private static final byte[] TRANSACTION_BYTES={1,2,3},COMMIT_BYTES={4,5,6};
    private static final Instant NOW=Instant.parse("2026-10-23T00:00:00Z");
    private static final MessageEvent EVENT=new MessageEvent(EVENT_ID,"EVIDENCE_ANCHOR_REQUESTED",SUBMISSION,"anchor-unit");
    private static final EvidenceQuery ABSENT=new EvidenceQuery(false,null);
    private static final EvidenceQuery MATCH=new EvidenceQuery(true,new EvidenceRecord("evidence",3,HASH,"Org2MSP",FIRST_TX));
    private final AnchorTransactions phases=mock(AnchorTransactions.class);
    private final EvidenceLedgerClient ledger=mock(EvidenceLedgerClient.class);
    private final EvidenceLedgerClient owner=mock(EvidenceLedgerClient.class);
    private final InstitutionLedgerClients clients=new InstitutionLedgerClients(owner,ledger,"Org1MSP","Org2MSP");
    private final PreparedRegistration prepared=new PreparedRegistration(TX,TRANSACTION_BYTES);
    private final CommitCheckpoint checkpoint=new CommitCheckpoint(TX,COMMIT_BYTES);
    private final AttemptRow attempt=attempt("PREPARED",null,null,null);
    private Claim claim;

    @Test
    void aBusyClaimCannotSilentlyAcknowledgeWorkWithoutACommittedHandoff() {
        when(phases.claim(EVENT,false)).thenReturn(null);
        doThrow(new RetryableDeliveryException()).when(phases).verifyAcknowledgement(EVENT);
        assertThrows(RetryableDeliveryException.class,()->processor().process(EVENT));
        var order=inOrder(phases);
        order.verify(phases).claim(EVENT,false);
        order.verify(phases).verifyAcknowledgement(EVENT);
        verifyNoInteractions(ledger,owner);
    }

    @BeforeEach
    void setup() throws Exception {
        claim=claim(null);
        when(phases.claim(EVENT,false)).thenReturn(claim);
        when(phases.prepared(claim,prepared)).thenReturn(attempt);
        when(phases.submitting(any(),any())).thenReturn(true);
        when(phases.submitted(any(),any(),any())).thenReturn(true);
        when(phases.outcome(any(),any(),any())).thenReturn(true);
        when(phases.retryAllowed(any())).thenReturn(true);
        when(phases.confirm(any(),anyString(),anyString())).thenReturn(true);
        when(phases.checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean())).thenReturn(true);
        when(ledger.queryEvidence("evidence",3)).thenAnswer(call->{ outsideTransaction(); return ABSENT; });
        when(ledger.prepareRegistration("evidence",3,HASH)).thenAnswer(call->{ outsideTransaction(); return prepared; });
        when(ledger.submitPrepared(any())).thenAnswer(call->{ outsideTransaction(); return checkpoint; });
        when(ledger.queryCommit(any())).thenAnswer(call->{ outsideTransaction(); return new CommitOutcome(TX,true,0,42); });
    }

    @Test
    void transactionAndCommitBytesAreDurableBeforeTheirRespectiveRpcCalls() throws Exception {
        when(ledger.queryEvidence("evidence",3)).thenReturn(ABSENT,MATCH);
        processor().process(EVENT);
        var order=inOrder(phases,ledger);
        order.verify(phases).prepared(claim,prepared);
        order.verify(phases).submitting(claim,attempt);
        order.verify(ledger).submitPrepared(any());
        order.verify(phases).submitted(claim,attempt,checkpoint);
        order.verify(ledger).queryCommit(any());
        order.verify(phases).outcome(eq(claim),any(),eq(new CommitOutcome(TX,true,0,42)));
        order.verify(ledger).queryEvidence("evidence",3);
        order.verify(phases).confirm(claim,FIRST_TX,"Org2MSP");
        verifyNoInteractions(owner);
    }

    @Test
    void anExistingMatchingLedgerRecordUsesItsFirstTransactionIdWithoutPreparingAnotherTransaction() throws Exception {
        when(ledger.queryEvidence("evidence",3)).thenReturn(MATCH);
        processor().process(EVENT);
        verify(phases).confirm(claim,FIRST_TX,"Org2MSP");
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
        verify(ledger,never()).submitPrepared(any());
    }

    @Test
    void aHashConflictIsDefinitiveAndCannotOverwriteTheLedger() throws Exception {
        when(ledger.queryEvidence("evidence",3)).thenReturn(new EvidenceQuery(true,new EvidenceRecord("evidence",3,"c".repeat(64),"Org2MSP",FIRST_TX)));
        processor().process(EVENT);
        verify(phases).checkpoint(claim,null,"FAILED",false,"EVIDENCE_HASH_CONFLICT",false);
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
    }

    @Test
    void allLedgerScopeFieldsAndTheFirstTransactionIdentifierAreChecked() throws Exception {
        List<EvidenceRecord> invalid=List.of(new EvidenceRecord("foreign",3,HASH,"Org2MSP",FIRST_TX),
                new EvidenceRecord("evidence",4,HASH,"Org2MSP",FIRST_TX),
                new EvidenceRecord("evidence",3,HASH,"Org1MSP",FIRST_TX),
                new EvidenceRecord("evidence",3,HASH,"Org2MSP","malformed"));
        for(EvidenceRecord record:invalid) {
            when(ledger.queryEvidence("evidence",3)).thenReturn(new EvidenceQuery(true,record));
            processor().process(EVENT);
        }
        verify(phases,times(invalid.size())).checkpoint(claim,null,"FAILED",false,"LEDGER_RECORD_MISMATCH",false);
        verify(phases,never()).confirm(any(),anyString(),anyString());
    }

    @Test
    void aLostSubmitResponseRestoresTheOriginalTransactionInsteadOfPreparingANewId() throws Exception {
        AttemptRow unknown=attempt("UNKNOWN",null,null,null);
        Claim recovery=claim(unknown);
        when(phases.claim(EVENT,false)).thenReturn(recovery);
        when(ledger.queryEvidence("evidence",3)).thenReturn(ABSENT,MATCH);
        processor().process(EVENT);
        var restored=org.mockito.ArgumentCaptor.forClass(PreparedRegistration.class);
        verify(ledger).submitPrepared(restored.capture());
        assertEquals(TX,restored.getValue().transactionId());
        assertArrayEquals(TRANSACTION_BYTES,restored.getValue().transactionBytes());
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
        verify(phases).confirm(recovery,FIRST_TX,"Org2MSP");
    }

    @Test
    void savedCommitBytesAreQueriedBeforeTheLedgerAndNeverResubmitted() throws Exception {
        AttemptRow unknown=attempt("UNKNOWN",COMMIT_BYTES,null,null);
        Claim recovery=claim(unknown);
        when(phases.claim(EVENT,false)).thenReturn(recovery);
        when(ledger.queryEvidence("evidence",3)).thenReturn(MATCH);
        processor().process(EVENT);
        var order=inOrder(ledger);
        order.verify(ledger).queryCommit(any());
        order.verify(ledger).queryEvidence("evidence",3);
        verify(ledger,never()).submitPrepared(any());
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
    }

    @Test
    void confirmationTimeoutRetainsTheAttemptAndPersistsUnknownForRecovery() throws Exception {
        when(ledger.queryCommit(any())).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
        processor().process(EVENT);
        verify(phases).checkpoint(eq(claim),argThat(value->TX.equals(value.txId())
                && java.util.Arrays.equals(COMMIT_BYTES,value.commitBytes())),eq("UNKNOWN"),eq(true),eq("COMMIT_STATUS_UNAVAILABLE"),eq(true));
        verify(phases,never()).confirm(any(),anyString(),anyString());
    }

    @Test
    void recoveryCanConfirmTheImmutableRecordEvenWhenCommitStatusRemainsUnavailable() throws Exception {
        Claim recovery=claim(attempt("UNKNOWN",COMMIT_BYTES,null,null));
        when(phases.due()).thenReturn(List.of(EVENT));
        when(phases.claim(EVENT,true)).thenReturn(recovery);
        when(ledger.queryCommit(any())).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
        when(ledger.queryEvidence("evidence",3)).thenReturn(MATCH);
        processor().recoverDue();
        verify(phases).confirm(recovery,FIRST_TX,"Org2MSP");
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
    }

    @Test
    void databaseFailureSavingPreparedBytesCannotBeFollowedBySubmissionOrAFakeFailureCheckpoint() throws Exception {
        var failure=new TransactionSystemException("Unknown prepared commit result");
        when(phases.prepared(claim,prepared)).thenThrow(failure);
        assertSame(failure,assertThrows(TransactionSystemException.class,()->processor().process(EVENT)));
        verify(ledger,never()).submitPrepared(any());
        verify(phases,never()).checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean());
    }

    @Test
    void databaseFailureSavingCommitBytesCannotDiscardThePreviouslyStoredTransaction() throws Exception {
        var failure=new TransactionSystemException("Unknown submitted commit result");
        when(phases.submitted(claim,attempt,checkpoint)).thenThrow(failure);
        assertSame(failure,assertThrows(TransactionSystemException.class,()->processor().process(EVENT)));
        verify(ledger).submitPrepared(any());
        verify(ledger,never()).queryCommit(any());
        verify(phases,never()).checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean());
    }

    @Test
    void anInvalidMvccResultStillConfirmsAnAlreadyRegisteredMatchingBusinessRecord() throws Exception {
        when(ledger.queryCommit(any())).thenReturn(new CommitOutcome(TX,false,TxValidationCode.MVCC_READ_CONFLICT_VALUE,42));
        when(ledger.queryEvidence("evidence",3)).thenReturn(ABSENT,MATCH);
        processor().process(EVENT);
        verify(phases).outcome(eq(claim),any(),eq(new CommitOutcome(TX,false,TxValidationCode.MVCC_READ_CONFLICT_VALUE,42)));
        verify(phases).confirm(claim,FIRST_TX,"Org2MSP");
    }

    @Test
    void anInvalidMvccResultWithoutARecordIsRetryableButNeverMarkedConfirmed() throws Exception {
        when(ledger.queryCommit(any())).thenReturn(new CommitOutcome(TX,false,TxValidationCode.MVCC_READ_CONFLICT_VALUE,42));
        processor().process(EVENT);
        verify(phases).checkpoint(eq(claim),argThat(value->"INVALID".equals(value.phase())),eq("FAILED"),eq(true),eq("COMMIT_CONFLICT"),eq(false));
        verify(phases,never()).confirm(any(),anyString(),anyString());
    }

    @Test
    void aDuplicateTransactionCodeWithoutABusinessRecordDoesNotAuthorizeANewTransaction() throws Exception {
        when(ledger.queryCommit(any())).thenReturn(new CommitOutcome(TX,false,TxValidationCode.DUPLICATE_TXID_VALUE,42));
        processor().process(EVENT);
        verify(phases).checkpoint(eq(claim),any(),eq("UNKNOWN"),eq(true),eq("DUPLICATE_TX_AWAITING_RECORD"),eq(false));
        verify(ledger,times(1)).prepareRegistration(anyString(),anyInt(),anyString());
    }

    @Test
    void losingTheLeaseBeforeSubmittingStopsTheExternalCall() throws Exception {
        when(phases.submitting(claim,attempt)).thenReturn(false);
        assertThrows(ProcessingLeaseLostException.class,()->processor().process(EVENT));
        verify(ledger,never()).submitPrepared(any());
        verify(phases,never()).verifyAcknowledgement(any());
    }

    @Test
    void definitePermissionDenialDuringEndorsementIsNotAutomaticallyRetried() throws Exception {
        when(ledger.prepareRegistration("evidence",3,HASH)).thenThrow(new EndorseException(TX,Status.PERMISSION_DENIED.asRuntimeException()));
        processor().process(EVENT);
        verify(phases).checkpoint(claim,null,"FAILED",false,"LEDGER_FORBIDDEN",false);
        verify(ledger,never()).submitPrepared(any());
    }

    @Test
    void exactPeerErrorDetailMapsARealHashRejectionButBareOrUnknownMessagesRemainRetryable() throws Exception {
        String reason="A different file hash is already registered for this evidence version.";
        EndorseException exact=mock(EndorseException.class);
        when(exact.getStatus()).thenReturn(Status.ABORTED);
        when(exact.getDetails()).thenReturn(List.of(ErrorDetail.newBuilder().setMspId("Org2MSP")
                .setAddress("peer0.org2.example.com:9051").setMessage("chaincode response 500, "+reason).build()));
        when(ledger.prepareRegistration("evidence",3,HASH)).thenThrow(exact);
        processor().process(EVENT);
        verify(phases).checkpoint(claim,null,"FAILED",false,"EVIDENCE_HASH_CONFLICT",false);

        for(String message:List.of(reason,"chaincode response 500, unknown rejection")) {
            EndorseException unknown=mock(EndorseException.class);
            when(unknown.getStatus()).thenReturn(Status.ABORTED);
            when(unknown.getDetails()).thenReturn(List.of(ErrorDetail.newBuilder().setMspId("Org2MSP")
                    .setAddress("peer0.org2.example.com:9051").setMessage(message).build()));
            doThrow(unknown).when(ledger).prepareRegistration("evidence",3,HASH);
            processor().process(EVENT);
        }
        verify(phases,times(2)).checkpoint(claim,null,"PENDING",true,"PREPARE_UNAVAILABLE",false);
        verify(ledger,never()).submitPrepared(any());
    }

    @Test
    void rejectingOriginalCheckpointIdentityKeepsTheUnknownAttemptRecoverable() throws Exception {
        Claim recovery=claim(attempt("UNKNOWN",COMMIT_BYTES,null,null));
        when(phases.claim(EVENT,false)).thenReturn(recovery);
        when(ledger.queryCommit(any())).thenThrow(new IllegalArgumentException("Original identity mismatch"));
        processor().process(EVENT);
        verify(phases).checkpoint(eq(recovery),eq(recovery.attempt()),eq("UNKNOWN"),eq(true),eq("CHECKPOINT_REJECTED"),eq(true));
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
        verify(ledger,never()).submitPrepared(any());
        verify(ledger,never()).queryEvidence(anyString(),anyInt());
    }

    @Test
    void failedInitialRenewalPreventsAnyGatewayCallOrAcknowledgement() {
        ProcessingLeaseKeeper leases=mock(ProcessingLeaseKeeper.class);
        when(leases.track(any())).thenThrow(new ProcessingLeaseLostException());
        assertThrows(ProcessingLeaseLostException.class,()->new AnchorProcessor(phases,clients,leases).process(EVENT));
        verifyNoInteractions(ledger,owner);
        verify(phases,never()).checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean());
        verify(phases,never()).verifyAcknowledgement(any());
    }

    @Test
    void renewalUsesThisClaimAndTheLeaseScopeClosesAfterASuccessfulTerminalCommit() throws Exception {
        ProcessingLeaseKeeper leases=mock(ProcessingLeaseKeeper.class);
        ProcessingLeaseKeeper.Lease lease=mock(ProcessingLeaseKeeper.Lease.class);
        AtomicBoolean completed=new AtomicBoolean();
        when(leases.track(any())).thenAnswer(call->{
            assertTrue(((BooleanSupplier)call.getArgument(0)).getAsBoolean());
            return lease;
        });
        when(phases.renew(claim)).thenReturn(true);
        doAnswer(call->{ if(completed.get()) throw new ProcessingLeaseLostException(); return null; }).when(lease).check();
        when(ledger.queryEvidence("evidence",3)).thenReturn(MATCH);
        when(phases.confirm(claim,FIRST_TX,"Org2MSP")).thenAnswer(call->{completed.set(true); return true;});
        new AnchorProcessor(phases,clients,leases).process(EVENT);
        var order=inOrder(phases,lease);
        order.verify(phases).renew(claim);
        order.verify(phases).confirm(claim,FIRST_TX,"Org2MSP");
        order.verify(lease).close();
        order.verify(phases).verifyAcknowledgement(EVENT);
    }

    @Test
    void losingRenewalDuringTheInitialQueryCannotPrepareAnotherTransaction() throws Exception {
        ProcessingLeaseKeeper leases=mock(ProcessingLeaseKeeper.class);
        ProcessingLeaseKeeper.Lease lease=mock(ProcessingLeaseKeeper.Lease.class);
        AtomicBoolean lost=guard(leases,lease);
        when(ledger.queryEvidence("evidence",3)).thenAnswer(call->{lost.set(true); return ABSENT;});
        assertThrows(ProcessingLeaseLostException.class,()->new AnchorProcessor(phases,clients,leases).process(EVENT));
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
        verify(phases,never()).checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean());
        verify(phases,never()).verifyAcknowledgement(any());
        verify(lease).close();
    }

    @Test
    void losingRenewalDuringPreparationDoesNotSaveOrSubmitTheReturnedTransaction() throws Exception {
        ProcessingLeaseKeeper leases=mock(ProcessingLeaseKeeper.class);
        ProcessingLeaseKeeper.Lease lease=mock(ProcessingLeaseKeeper.Lease.class);
        AtomicBoolean lost=guard(leases,lease);
        when(ledger.prepareRegistration("evidence",3,HASH)).thenAnswer(call->{lost.set(true); return prepared;});
        assertThrows(ProcessingLeaseLostException.class,()->new AnchorProcessor(phases,clients,leases).process(EVENT));
        verify(phases,never()).prepared(any(),any());
        verify(ledger,never()).submitPrepared(any());
        verify(phases,never()).checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean());
        verify(lease).close();
    }

    @Test
    void renewalFailureDuringAnUnavailablePreparationCannotBecomeANormalRetryCheckpoint() throws Exception {
        ProcessingLeaseKeeper leases=mock(ProcessingLeaseKeeper.class);
        ProcessingLeaseKeeper.Lease lease=mock(ProcessingLeaseKeeper.Lease.class);
        AtomicBoolean lost=guard(leases,lease);
        when(ledger.prepareRegistration("evidence",3,HASH)).thenAnswer(call->{
            lost.set(true); throw Status.UNAVAILABLE.asRuntimeException();
        });
        assertThrows(ProcessingLeaseLostException.class,()->new AnchorProcessor(phases,clients,leases).process(EVENT));
        verify(phases,never()).checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean());
        verify(phases,never()).verifyAcknowledgement(any());
        verify(lease).close();
    }

    @Test
    void losingRenewalDuringSubmissionRetainsThePreviouslyPreparedAttemptForRecovery() throws Exception {
        ProcessingLeaseKeeper leases=mock(ProcessingLeaseKeeper.class);
        ProcessingLeaseKeeper.Lease lease=mock(ProcessingLeaseKeeper.Lease.class);
        AtomicBoolean lost=guard(leases,lease);
        when(ledger.submitPrepared(any())).thenAnswer(call->{lost.set(true); return checkpoint;});
        assertThrows(ProcessingLeaseLostException.class,()->new AnchorProcessor(phases,clients,leases).process(EVENT));
        verify(phases).prepared(claim,prepared);
        verify(phases).submitting(claim,attempt);
        verify(phases,never()).submitted(any(),any(),any());
        verify(ledger,never()).queryCommit(any());
        verify(phases,never()).checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean());
        verify(lease).close();
    }

    @Test
    void losingRenewalDuringCommitQueryCannotSaveOutcomeOrConfirmTheSubmission() throws Exception {
        ProcessingLeaseKeeper leases=mock(ProcessingLeaseKeeper.class);
        ProcessingLeaseKeeper.Lease lease=mock(ProcessingLeaseKeeper.Lease.class);
        AtomicBoolean lost=guard(leases,lease);
        Claim recovery=claim(attempt("UNKNOWN",COMMIT_BYTES,null,null));
        when(phases.claim(EVENT,false)).thenReturn(recovery);
        when(ledger.queryCommit(any())).thenAnswer(call->{lost.set(true); return new CommitOutcome(TX,true,0,42);});
        assertThrows(ProcessingLeaseLostException.class,()->new AnchorProcessor(phases,clients,leases).process(EVENT));
        verify(phases,never()).outcome(any(),any(),any());
        verify(ledger,never()).queryEvidence(anyString(),anyInt());
        verify(phases,never()).confirm(any(),anyString(),anyString());
        verify(phases,never()).checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean());
        verify(lease).close();
    }

    @Test
    void aConditionalCheckpointMissCannotFallThroughToAcknowledgement() throws Exception {
        when(ledger.queryEvidence("evidence",3)).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        when(phases.checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean())).thenReturn(false);
        assertThrows(ProcessingLeaseLostException.class,()->processor().process(EVENT));
        verify(phases,never()).verifyAcknowledgement(any());
    }

    @Test
    void aLeaseLossExceptionCannotBeReclassifiedAsALedgerOutage() throws Exception {
        when(ledger.queryEvidence("evidence",3)).thenThrow(new ProcessingLeaseLostException());
        assertThrows(ProcessingLeaseLostException.class,()->processor().process(EVENT));
        verify(phases,never()).checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean());
        verify(phases,never()).verifyAcknowledgement(any());
    }

    @Test
    void anUncertainDatabaseCommitClosesHeartbeatWithoutCreatingAFakeCheckpoint() throws Exception {
        ProcessingLeaseKeeper leases=mock(ProcessingLeaseKeeper.class);
        ProcessingLeaseKeeper.Lease lease=mock(ProcessingLeaseKeeper.Lease.class);
        guard(leases,lease);
        when(phases.prepared(claim,prepared)).thenThrow(new TransactionSystemException("Unknown commit result"));
        assertThrows(TransactionSystemException.class,()->new AnchorProcessor(phases,clients,leases).process(EVENT));
        verify(lease).close();
        verify(phases,never()).checkpoint(any(),any(),anyString(),anyBoolean(),anyString(),anyBoolean());
        verify(phases,never()).verifyAcknowledgement(any());
    }

    @Test
    void aKnownConflictCanPrepareANewAttemptOnlyAfterTheBusinessRecordIsStillAbsent() throws Exception {
        Claim retry=claim(attempt("INVALID",COMMIT_BYTES,TxValidationCode.MVCC_READ_CONFLICT_VALUE,42L));
        when(phases.claim(EVENT,false)).thenReturn(retry);
        when(phases.prepared(retry,prepared)).thenReturn(attempt);
        when(ledger.queryEvidence("evidence",3)).thenReturn(ABSENT,MATCH);
        processor().process(EVENT);
        var order=inOrder(ledger,phases);
        order.verify(ledger).queryEvidence("evidence",3);
        order.verify(phases).retryAllowed(retry);
        order.verify(ledger).prepareRegistration("evidence",3,HASH);
        order.verify(phases).prepared(retry,prepared);
        verify(phases).confirm(retry,FIRST_TX,"Org2MSP");
    }

    @Test
    void anExhaustedKnownConflictCannotPrepareANewTransactionEvenFromUnknownBusinessState() throws Exception {
        Claim retry=claim(attempt("INVALID",COMMIT_BYTES,TxValidationCode.PHANTOM_READ_CONFLICT_VALUE,42L));
        when(phases.claim(EVENT,false)).thenReturn(retry);
        when(phases.retryAllowed(retry)).thenReturn(false);
        processor().process(EVENT);
        verify(phases).checkpoint(retry,retry.attempt(),"FAILED",true,"COMMIT_CONFLICT",false);
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
        verify(ledger,never()).submitPrepared(any());
    }

    @Test
    void aPreviouslyValidCommitWithAnAbsentRecordRemainsUnknownWithoutCreatingAnotherAttempt() throws Exception {
        Claim valid=claim(attempt("VALID",COMMIT_BYTES,0,42L));
        when(phases.claim(EVENT,false)).thenReturn(valid);
        processor().process(EVENT);
        verify(phases).checkpoint(valid,valid.attempt(),"UNKNOWN",true,"LEDGER_RECORD_NOT_VISIBLE",false);
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
        verify(ledger,never()).submitPrepared(any());
        verify(phases,never()).retryAllowed(any());
    }

    @Test
    void aPersistedDuplicateTransactionOutcomeRequiresTheBusinessRecordBeforeAnyNewAttempt() throws Exception {
        Claim duplicate=claim(attempt("INVALID",COMMIT_BYTES,TxValidationCode.DUPLICATE_TXID_VALUE,42L));
        when(phases.claim(EVENT,false)).thenReturn(duplicate);
        processor().process(EVENT);
        verify(phases).checkpoint(duplicate,duplicate.attempt(),"UNKNOWN",true,"DUPLICATE_TX_AWAITING_RECORD",false);
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
        verify(ledger,never()).submitPrepared(any());
        verify(phases,never()).retryAllowed(any());
    }

    @Test
    void aNonAllowlistedInvalidCommitIsPermanentWhenNoMatchingBusinessRecordExists() throws Exception {
        Claim rejected=claim(attempt("INVALID",COMMIT_BYTES,TxValidationCode.ENDORSEMENT_POLICY_FAILURE_VALUE,42L));
        when(phases.claim(EVENT,false)).thenReturn(rejected);
        processor().process(EVENT);
        verify(phases).checkpoint(rejected,rejected.attempt(),"FAILED",false,"COMMIT_INVALID",false);
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
        verify(ledger,never()).submitPrepared(any());
    }

    @Test
    void definiteQueryPermissionDenialBeforeSubmissionIsNotAutomaticallyRetried() throws Exception {
        when(ledger.queryEvidence("evidence",3)).thenThrow(Status.PERMISSION_DENIED.asRuntimeException());
        processor().process(EVENT);
        verify(phases).checkpoint(claim,null,"FAILED",false,"LEDGER_FORBIDDEN",false);
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
        verify(ledger,never()).submitPrepared(any());
    }

    @Test
    void aPermissionDeniedQueryCannotPretendAPossiblySubmittedOriginalTransactionWasRejected() throws Exception {
        Claim unknown=claim(attempt("UNKNOWN",COMMIT_BYTES,null,null));
        when(phases.due()).thenReturn(List.of(EVENT));
        when(phases.claim(EVENT,true)).thenReturn(unknown);
        when(ledger.queryCommit(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        when(ledger.queryEvidence("evidence",3)).thenThrow(Status.PERMISSION_DENIED.asRuntimeException());
        processor().recoverDue();
        verify(phases).checkpoint(unknown,unknown.attempt(),"UNKNOWN",true,"LEDGER_QUERY_UNAVAILABLE",true);
        verify(ledger,never()).prepareRegistration(anyString(),anyInt(),anyString());
        verify(ledger,never()).submitPrepared(any());
    }

    private static AtomicBoolean guard(ProcessingLeaseKeeper leases,ProcessingLeaseKeeper.Lease lease) {
        AtomicBoolean lost=new AtomicBoolean();
        when(leases.track(any())).thenReturn(lease);
        doAnswer(call->{if(lost.get()) throw new ProcessingLeaseLostException(); return null;}).when(lease).check();
        return lost;
    }

    private AnchorProcessor processor() {
        var proxy=new ProxyFactory(new AnchorProcessor(phases,clients));
        proxy.setProxyTargetClass(true);
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object transaction,TransactionDefinition definition){}
            protected void doCommit(DefaultTransactionStatus status){}
            protected void doRollback(DefaultTransactionStatus status){}
        };
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return (AnchorProcessor)proxy.getProxy();
    }

    private static Claim claim(AttemptRow attempt) {
        return new Claim(EVENT,new SubmissionRow(SUBMISSION,"version","PENDING",null,null,true,0,NOW,"token",NOW.plusSeconds(120),null,
                NOW,NOW,"task","evidence",3,HASH,ResourcePermissionService.OPERATOR),attempt,"token");
    }

    private static AttemptRow attempt(String phase,byte[] commitBytes,Integer code,Long block) {
        return new AttemptRow("attempt",SUBMISSION,1,TX,phase,TRANSACTION_BYTES,commitBytes,code,block,null,NOW,NOW);
    }

    private static void outsideTransaction() { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); }
}
