package com.uav.inspection.evidence.finalization;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.evidence.finalization.persistence.FinalizationMapper;
import com.uav.inspection.evidence.upload.UploadTransactions;
import com.uav.inspection.evidence.upload.persistence.UploadMapper;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.PartRow;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.SessionRow;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.Role;
import com.uav.inspection.device.CurrentDevice;
import com.uav.inspection.messaging.OutboxWriter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadCompletionServiceTest {
    private static final String ID="80000000-0000-4000-8000-000000000001";
    private static final Instant NOW=Instant.parse("2026-10-22T00:00:00Z");
    private final UploadTransactions uploads=mock(UploadTransactions.class);
    private final UploadMapper parts=mock(UploadMapper.class);
    private final FinalizationMapper finalizations=mock(FinalizationMapper.class);
    private final OutboxWriter outbox=mock(OutboxWriter.class);
    private final AuditMapper audit=mock(AuditMapper.class);
    private final CurrentUser actor=new CurrentUser("user","org","operator",List.of(Role.OPERATOR_USER),0,0);
    private final UploadCompletionService service=new UploadCompletionService(uploads,parts,finalizations,outbox,audit);

    @Test
    void missingOrNonContiguousPartsCannotFreezeTheSessionOrWriteAnEvent() {
        when(uploads.lockForCompletion(actor,ID)).thenReturn(session("UPLOADING"));
        for(List<PartRow> registered:List.of(List.<PartRow>of(),List.of(part(1,8)),List.of(part(1,8),part(3,4)),List.of(part(1,8),part(2,3)))) {
            when(parts.listParts(ID)).thenReturn(registered);
            var error=assertThrows(BusinessException.class,()->service.complete(actor,ID));
            assertEquals(ErrorCode.UPLOAD_PARTS_INCOMPLETE,error.errorCode());
        }
        verifyNoInteractions(finalizations,outbox,audit);
    }

    @Test
    void repeatedCompletionForAFrozenSessionDoesNotPublishAnotherEvent() {
        when(uploads.lockForCompletion(actor,ID)).thenReturn(session("VERIFYING"));
        service.complete(actor,ID);
        verifyNoInteractions(finalizations,outbox,audit,parts);
        verify(uploads).get(actor,ID);
    }

    @Test
    void aConditionalFreezeFailureCannotPublishTheEvent() {
        when(uploads.lockForCompletion(actor,ID)).thenReturn(session("UPLOADING"));
        when(parts.listParts(ID)).thenReturn(List.of(part(1,8),part(2,4)));
        when(finalizations.freeze(ID,0)).thenReturn(0);
        when(parts.databaseNow()).thenReturn(NOW);
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,()->service.complete(actor,ID)).errorCode());
        verifyNoInteractions(outbox,audit);
    }

    @Test
    void expiryBetweenAuthorizationAndFreezeIsReportedAsExpiryWithoutPublishingAnything() {
        when(uploads.lockForCompletion(actor,ID)).thenReturn(session("UPLOADING"));
        when(parts.listParts(ID)).thenReturn(List.of(part(1,8),part(2,4)));
        when(finalizations.freeze(ID,0)).thenReturn(0);
        when(parts.databaseNow()).thenReturn(NOW.plusSeconds(3600));

        assertEquals(ErrorCode.UPLOAD_SESSION_EXPIRED,assertThrows(BusinessException.class,()->service.complete(actor,ID)).errorCode());

        verifyNoInteractions(outbox,audit);
        verify(uploads,never()).get(any(),anyString());
    }

    @Test
    void successfulCompletionFollowedByARefreshWritesOnlyOneEventAndKeepsTheSameSession() {
        when(uploads.lockForCompletion(actor,ID)).thenReturn(session("UPLOADING"),session("VERIFYING"),session("COMPLETED"));
        when(parts.listParts(ID)).thenReturn(List.of(part(1,8),part(2,4)));
        when(finalizations.freeze(ID,0)).thenReturn(1);
        when(audit.insertResourceSuccess(anyString(),eq("user"),eq("org"),eq("UPLOAD_COMPLETION_REQUESTED"),eq("UPLOAD_SESSION"),eq(ID),anyString())).thenReturn(1);

        service.complete(actor,ID);
        service.complete(actor,ID);
        service.complete(actor,ID);

        verify(finalizations).freeze(ID,0);
        verify(outbox).enqueue(eq("FILE_FINALIZE_REQUESTED"),eq(ID),anyString());
        verify(audit).insertResourceSuccess(anyString(),eq("user"),eq("org"),eq("UPLOAD_COMPLETION_REQUESTED"),eq("UPLOAD_SESSION"),eq(ID),anyString());
        verify(uploads,times(3)).lockForCompletion(actor,ID);
        verify(uploads,times(3)).get(actor,ID);
    }

    @Test
    void deviceCompletionKeepsDeviceActorAndPublishesOnlyAfterFreeze() {
        var device=new CurrentDevice("device","org","drone","d".repeat(64));
        var session=new SessionRow(ID,"task","subtask","requirement","evidence","execution","org","DEVICE","device",
                "client","hash","file.png","image/png",12,8,2,"UPLOADING",NOW.plusSeconds(3600),0,NOW,NOW);
        when(uploads.lockForCompletionDevice(device,ID)).thenReturn(session);
        when(parts.listParts(ID)).thenReturn(List.of(part(1,8),part(2,4)));
        when(finalizations.freeze(ID,0)).thenReturn(1);
        when(audit.insertDeviceResourceSuccess(anyString(),eq("device"),eq("org"),eq("UPLOAD_COMPLETION_REQUESTED"),eq("UPLOAD_SESSION"),eq(ID),anyString())).thenReturn(1);

        service.completeDevice(device,ID);

        var order=inOrder(finalizations,outbox,audit);
        order.verify(finalizations).freeze(ID,0);
        order.verify(outbox).enqueue(eq("FILE_FINALIZE_REQUESTED"),eq(ID),anyString());
        order.verify(audit).insertDeviceResourceSuccess(anyString(),eq("device"),eq("org"),eq("UPLOAD_COMPLETION_REQUESTED"),eq("UPLOAD_SESSION"),eq(ID),anyString());
        verify(audit,never()).insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        verify(uploads).getDevice(device,ID);
    }

    @Test
    void rejectedDeviceAtCompletionCannotFreezeOrEnqueueAnything() {
        var device=new CurrentDevice("device","org","drone","d".repeat(64));
        when(uploads.lockForCompletionDevice(device,ID)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

        assertEquals(ErrorCode.UNAUTHORIZED,assertThrows(BusinessException.class,()->service.completeDevice(device,ID)).errorCode());

        verifyNoInteractions(parts,finalizations,outbox,audit);
    }

    private static PartRow part(int number,long size) { return new PartRow(ID,number,size,"a".repeat(64),"key-"+number); }
    private static SessionRow session(String status) {
        return new SessionRow(ID,"task","subtask","requirement","evidence","execution","org","USER","user",
                "client","hash","file.png","image/png",12,8,2,status,NOW.plusSeconds(3600),0,NOW,NOW);
    }
}
