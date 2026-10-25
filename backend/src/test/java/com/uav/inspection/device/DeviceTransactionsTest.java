package com.uav.inspection.device;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.device.DeviceModels.*;
import com.uav.inspection.device.persistence.DeviceMapper;
import com.uav.inspection.device.persistence.DeviceMapper.*;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceTransactionsTest {
    private static final String DEVICE="20000000-0000-4000-8000-000000000001";
    private static final String TASK="30000000-0000-4000-8000-000000000001";
    private static final String SUBTASK="40000000-0000-4000-8000-000000000001";
    private static final String EXECUTION="50000000-0000-4000-8000-000000000001";
    private static final String BOOT="60000000-0000-4000-8000-000000000001";
    private static final String EVENT="70000000-0000-4000-8000-000000000001";
    private static final Instant NOW=Instant.parse("2026-10-23T10:00:00.123Z");
    private static final CurrentDevice PRINCIPAL=new CurrentDevice(DEVICE,ResourcePermissionService.OPERATOR,"UAV-TEST","a".repeat(64));
    private static final CurrentUser ADMIN=new CurrentUser("admin",ResourcePermissionService.OPERATOR,"operator-admin",List.of(Role.OPERATOR_TASK_ADMIN),0,0);
    private final DeviceMapper mapper=mock(DeviceMapper.class);
    private final ResourcePermissionService permissions=mock(ResourcePermissionService.class);
    private final DeviceAuthenticationService authentication=mock(DeviceAuthenticationService.class);
    private final AuditMapper audit=mock(AuditMapper.class);
    private final DeviceTransactions phases=new DeviceTransactions(mapper,permissions,authentication,audit);

    @BeforeEach
    void configure() {
        var execution=new ExecutionRow(EXECUTION,SUBTASK,TASK,DEVICE,"RUNNING",70,2);
        when(mapper.findExecution(EXECUTION)).thenReturn(execution);
        when(mapper.lockExecution(EXECUTION)).thenReturn(execution);
        when(mapper.lockTask(TASK)).thenReturn(new TaskRow(TASK,ResourcePermissionService.OPERATOR,"RUNNING"));
        when(mapper.lockSubtask(SUBTASK)).thenReturn(new SubtaskRow(SUBTASK,TASK,EXECUTION));
        when(authentication.currentDevice(PRINCIPAL,true)).thenReturn(PRINCIPAL);
        when(mapper.databaseNow()).thenReturn(NOW);
        when(mapper.insertEvent(any())).thenReturn(1);
        when(mapper.touchLastSeen(DEVICE,NOW)).thenReturn(1);
        when(mapper.updateProgress(eq(EXECUTION),anyInt())).thenReturn(1);
        when(mapper.auditReport(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        when(permissions.currentActor(ADMIN,true)).thenReturn(ADMIN);
        when(permissions.currentActor(ADMIN,false)).thenReturn(ADMIN);
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
    }

    @Test
    void progressIsMonotonicAndUsesDatabaseReceiveTimeWithTaskFirstDeviceLastLocks() {
        ReportResult result=phases.report(PRINCIPAL,report("PROGRESS",30));
        assertEquals(70,result.executionProgress());
        assertEquals(30,result.progress());
        assertEquals(NOW,result.receivedAt());
        assertFalse(result.duplicate());
        var order=inOrder(mapper,authentication);
        order.verify(mapper).lockTask(TASK);
        order.verify(mapper).lockSubtask(SUBTASK);
        order.verify(mapper).lockExecution(EXECUTION);
        order.verify(authentication).currentDevice(PRINCIPAL,true);
        order.verify(mapper).insertEvent(any());
        order.verify(mapper).touchLastSeen(DEVICE,NOW);
        order.verify(mapper).updateProgress(EXECUTION,30);
        verify(mapper).auditReport(anyString(),eq(DEVICE),eq(ResourcePermissionService.OPERATOR),eq("DEVICE_PROGRESS"),eq(EXECUTION),anyString());
        verifyNoInteractions(permissions);
    }

    @Test
    void exactDuplicateReturnsTheOriginalSnapshotWithoutRefreshingPresenceProgressOrAudit() {
        ReportRequest request=report("PROGRESS",30);
        Instant original=NOW.minusSeconds(10);
        var previous=new EventRow("original",DEVICE,BOOT,7,EVENT,EXECUTION,"PROGRESS",30,
                DeviceTransactions.fingerprint(DEVICE,request),original,30);
        when(mapper.findBySequence(DEVICE,BOOT,7)).thenReturn(previous);
        when(mapper.findByEventId(DEVICE,EVENT)).thenReturn(previous);
        ReportResult duplicate=phases.report(PRINCIPAL,request);
        assertTrue(duplicate.duplicate());
        assertEquals(original,duplicate.receivedAt());
        assertEquals(30,duplicate.executionProgress());
        verify(mapper,never()).insertEvent(any());
        verify(mapper,never()).touchLastSeen(anyString(),any());
        verify(mapper,never()).updateProgress(anyString(),anyInt());
        verify(mapper,never()).auditReport(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }

    @Test
    void reassignedBatchCanReplayAcknowledgementAndRecordLateProgressWithoutAdvancingEitherBatch() {
        var closed = new ExecutionRow(EXECUTION,SUBTASK,TASK,DEVICE,"CLOSED",70,3);
        when(mapper.findExecution(EXECUTION)).thenReturn(closed);
        when(mapper.lockExecution(EXECUTION)).thenReturn(closed);
        when(mapper.lockSubtask(SUBTASK)).thenReturn(new SubtaskRow(SUBTASK,TASK,"replacement-execution"));
        var request = report("PROGRESS",30);
        var previous = new EventRow("original",DEVICE,BOOT,7,EVENT,EXECUTION,"PROGRESS",30,
                DeviceTransactions.fingerprint(DEVICE,request),NOW.minusSeconds(10),30);
        when(mapper.findBySequence(DEVICE,BOOT,7)).thenReturn(previous);
        when(mapper.findByEventId(DEVICE,EVENT)).thenReturn(previous);

        var duplicate = phases.report(PRINCIPAL,request);
        assertTrue(duplicate.duplicate());
        assertEquals(previous.receivedAt(),duplicate.receivedAt());
        assertEquals(30,duplicate.executionProgress());
        var fresh = new ReportRequest(BOOT,8L,"70000000-0000-4000-8000-000000000002",EXECUTION,"PROGRESS",100);
        var historical = phases.report(PRINCIPAL,fresh);
        assertFalse(historical.duplicate());
        assertEquals(EXECUTION,historical.executionId());
        assertEquals(100,historical.progress());
        assertEquals(70,historical.executionProgress());
        assertEquals(NOW,historical.receivedAt());
        verify(mapper).insertEvent(any());
        verify(mapper).touchLastSeen(DEVICE,NOW);
        verify(mapper,never()).updateProgress(anyString(),anyInt());
        verify(mapper).auditReport(anyString(),eq(DEVICE),eq(PRINCIPAL.orgId()),eq("DEVICE_PROGRESS"),eq(EXECUTION),anyString());
    }

    @Test
    void eitherIdempotencyKeyRejectsChangedContent() {
        var prior=report("PROGRESS",30);
        var old=new EventRow("original",DEVICE,BOOT,7,EVENT,EXECUTION,"PROGRESS",30,
                DeviceTransactions.fingerprint(DEVICE,prior),NOW,70);
        when(mapper.findBySequence(DEVICE,BOOT,7)).thenReturn(old);
        BusinessException seqConflict=assertThrows(BusinessException.class,()->phases.report(PRINCIPAL,report("PROGRESS",40)));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT,seqConflict.errorCode());
        when(mapper.findBySequence(DEVICE,BOOT,7)).thenReturn(null);
        when(mapper.findByEventId(DEVICE,EVENT)).thenReturn(old);
        var reusedEvent=new ReportRequest(BOOT,8L,EVENT,EXECUTION,"PROGRESS",30);
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT,assertThrows(BusinessException.class,()->phases.report(PRINCIPAL,reusedEvent)).errorCode());
        verify(mapper,never()).insertEvent(any());
    }

    @Test
    void heartbeatCannotSupplyProgressAndAValidHeartbeatDoesNotAlterExecutionProgress() {
        assertEquals(ErrorCode.INVALID_ARGUMENT,assertThrows(BusinessException.class,()->phases.report(PRINCIPAL,report("HEARTBEAT",5))).errorCode());
        ReportResult heartbeat=phases.report(PRINCIPAL,report("HEARTBEAT",null));
        assertEquals(70,heartbeat.executionProgress());
        assertNull(heartbeat.progress());
        verify(mapper,never()).updateProgress(anyString(),anyInt());
    }

    @Test
    void disabledAfterFilterAndNonCurrentExecutionCannotInsertAnEvent() {
        when(authentication.currentDevice(PRINCIPAL,true)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        assertEquals(ErrorCode.UNAUTHORIZED,assertThrows(BusinessException.class,()->phases.report(PRINCIPAL,report("HEARTBEAT",null))).errorCode());
        doReturn(PRINCIPAL).when(authentication).currentDevice(PRINCIPAL,true);
        when(mapper.lockSubtask(SUBTASK)).thenReturn(new SubtaskRow(SUBTASK,TASK,"other-execution"));
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,()->phases.report(PRINCIPAL,report("HEARTBEAT",null))).errorCode());
        verify(mapper,never()).insertEvent(any());
    }

    @Test
    void anotherAssignedDroneAndACompletedTaskCannotReport() {
        when(mapper.findExecution(EXECUTION)).thenReturn(new ExecutionRow(EXECUTION,SUBTASK,TASK,"foreign-drone","RUNNING",0,0));
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->phases.report(PRINCIPAL,report("HEARTBEAT",null))).errorCode());
        when(mapper.findExecution(EXECUTION)).thenReturn(new ExecutionRow(EXECUTION,SUBTASK,TASK,DEVICE,"RUNNING",70,2));
        when(mapper.lockTask(TASK)).thenReturn(new TaskRow(TASK,ResourcePermissionService.OPERATOR,"COMPLETED"));
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,()->phases.report(PRINCIPAL,report("PROGRESS",100))).errorCode());
        verify(mapper,never()).insertEvent(any());
    }

    @Test
    void registrationReturnsARandomTokenButPersistsOnlyItsUtf8Sha256() throws Exception {
        when(mapper.insertDevice(anyString(),eq(ADMIN.orgId()),eq("UAV-NEW"),anyString())).thenReturn(1);
        var created=phases.create(ADMIN,new CreateDeviceRequest("UAV-NEW"));
        assertTrue(created.token().matches("[0-9a-f]{64}"));
        var digest=ArgumentCaptor.forClass(String.class);
        verify(mapper).insertDevice(anyString(),eq(ADMIN.orgId()),eq("UAV-NEW"),digest.capture());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(created.token().getBytes(StandardCharsets.UTF_8))),digest.getValue());
        assertNotEquals(created.token(),digest.getValue());
        assertFalse(created.toString().contains(created.token()));
    }

    @Test
    void organizationAdministrationAloneCannotManageDevicesAndStaleCasCannotChangeEnabled() {
        CurrentUser orgAdmin=new CurrentUser(ADMIN.id(),ADMIN.orgId(),ADMIN.username(),List.of(Role.ORG_ADMIN),0,0);
        when(permissions.currentActor(ADMIN,false)).thenReturn(orgAdmin);
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->phases.get(ADMIN,DEVICE)).errorCode());
        when(mapper.lockDevice(DEVICE)).thenReturn(new DeviceRow(DEVICE,ADMIN.orgId(),"UAV-TEST",true,NOW,5,NOW,NOW));
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,
                ()->phases.setEnabled(ADMIN,DEVICE,new EnableDeviceRequest(false,4L))).errorCode());
        verify(mapper,never()).setEnabled(anyString(),anyString(),anyBoolean(),anyLong());
    }

    private static ReportRequest report(String type,Integer progress){return new ReportRequest(BOOT,7L,EVENT,EXECUTION,type,progress);}
}
