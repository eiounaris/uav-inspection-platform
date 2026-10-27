package com.uav.inspection.evidence.cleanup;

import com.uav.inspection.evidence.cleanup.CleanupTransactions.Claim;
import com.uav.inspection.evidence.storage.ObjectInventory;
import com.uav.inspection.evidence.storage.ObjectInventory.Entry;
import com.uav.inspection.evidence.storage.ObjectInventory.Page;
import com.uav.inspection.evidence.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CleanupProcessorTest {
    private final CleanupTransactions phases=mock(CleanupTransactions.class);
    private final ObjectInventory inventory=mock(ObjectInventory.class);
    private final ObjectStorage storage=mock(ObjectStorage.class);
    private final CleanupProcessor processor=new CleanupProcessor(phases,inventory,storage,new CleanupSettings(new MockEnvironment()));
    private final Claim claim=new Claim("job","key","upload","token",1,null);
    private final Entry current=new Entry("key",Instant.EPOCH,1);

    @BeforeEach void setup() {
        when(inventory.list(anyString(),nullable(String.class),eq(100))).thenReturn(new Page(List.of(),null));
        when(phases.due()).thenReturn(List.of("job","next"));when(phases.claim("job")).thenReturn(claim);
        when(inventory.head("key")).thenReturn(current);when(phases.verifyDeletion(claim,current)).thenReturn(true);
        when(phases.done(claim)).thenReturn(true);
    }

    @Test void realDeletionOccursOnlyBetweenCommittedClaimVerificationAndCompletion() {
        processor.runOnce();var order=inOrder(phases,inventory,storage);
        order.verify(phases).claim("job");order.verify(inventory).head("key");order.verify(phases).verifyDeletion(claim,current);
        order.verify(storage).delete("key");order.verify(phases).done(claim);
    }

    @Test void headFailureSchedulesRetryWithoutDeletingOrHammeringNextJob() {
        when(inventory.head("key")).thenThrow(new IllegalStateException("private provider detail"));
        processor.runOnce();verify(phases).retry(claim);verifyNoInteractions(storage);verify(phases,never()).claim("next");
    }

    @Test void deleteFailureSchedulesRetryWithoutPretendingCompletion() {
        doThrow(new IllegalStateException("private provider detail")).when(storage).delete("key");
        processor.runOnce();verify(phases).retry(claim);verify(phases,never()).done(any());verify(phases,never()).claim("next");
    }

    @Test void aCompletionCommitFailureEscapesWithoutBeingReclassifiedAsAnotherDeleteFailure() {
        doThrow(new IllegalStateException("uncertain database commit")).when(phases).done(claim);
        assertThrows(IllegalStateException.class,processor::runOnce);verify(storage).delete("key");verify(phases,never()).retry(any());
    }

    @Test void referencesDetectedAfterHeadAndExpiredClaimOutcomesPreventDeletion() {
        when(phases.verifyDeletion(claim,current)).thenReturn(false);
        processor.runOnce();verifyNoInteractions(storage);verify(phases,never()).done(any());
    }

    @Test void alreadyAbsentObjectsCompleteIdempotentlyWithoutADeleteCall() {
        when(inventory.head("key")).thenReturn(null);when(phases.verifyDeletion(claim,null)).thenReturn(true);
        processor.runOnce();verifyNoInteractions(storage);verify(phases).done(claim);
    }

    @Test void shutdownInterruptionAfterHeadLeavesTheDurableLeaseForRecovery() {
        when(inventory.head("key")).thenAnswer(call->{Thread.currentThread().interrupt();return current;});
        try {processor.runOnce();assertTrue(Thread.currentThread().isInterrupted());verifyNoInteractions(storage);verify(phases,never()).verifyDeletion(any(),any());}
        finally {Thread.interrupted();}
    }

    @Test void boundedInventoryContinuesAfterProtectedPagesAndWrapsAtTheEnd() {
        when(phases.due()).thenReturn(List.of());
        when(inventory.list(eq("upload-parts/"),isNull(),eq(100))).thenReturn(new Page(List.of(),"next-page"));
        when(inventory.list("upload-parts/","next-page",100)).thenReturn(new Page(List.of(),null));
        processor.runOnce();processor.runOnce();processor.runOnce();
        verify(inventory,times(2)).list("upload-parts/",null,100);verify(inventory).list("upload-parts/","next-page",100);
    }

    @Test void expiredInventoryCursorResetsWithoutStarvingTheOtherPrefixOrDurableDeleteWork() {
        when(phases.due()).thenReturn(List.of("job"));
        when(inventory.list("upload-parts/",null,100)).thenReturn(new Page(List.of(),"expired-cursor"));
        when(inventory.list("upload-parts/","expired-cursor",100)).thenThrow(new IllegalStateException("opaque provider detail"));
        processor.runOnce();processor.runOnce();processor.runOnce();
        verify(inventory,times(2)).list("upload-parts/",null,100);
        verify(inventory,times(3)).list("evidence-files/",null,100);
        verify(storage,times(3)).delete("key");verify(phases,times(3)).done(claim);
    }
}
