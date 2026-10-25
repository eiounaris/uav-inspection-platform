package com.uav.inspection.device;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceContext;
import com.uav.inspection.device.DeviceModels.*;
import com.uav.inspection.device.persistence.DeviceMapper;
import com.uav.inspection.device.persistence.DeviceMapper.*;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("database")
public class DeviceTransactions {
    private final DeviceMapper devices;
    private final ResourcePermissionService permissions;
    private final DeviceAuthenticationService authentication;
    private final AuditMapper audit;
    private final SecureRandom random=new SecureRandom();

    public DeviceTransactions(DeviceMapper devices,ResourcePermissionService permissions,
                               DeviceAuthenticationService authentication,AuditMapper audit) {
        this.devices=devices; this.permissions=permissions; this.authentication=authentication; this.audit=audit;
    }

    @Transactional
    public NewDevice create(CurrentUser supplied,CreateDeviceRequest request) {
        CurrentUser actor=manager(supplied,true);
        if(request==null || request.code()==null || !request.code().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) invalid();
        if(devices.findByCode(request.code())!=null) conflict();
        String id=UUID.randomUUID().toString(); byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=HexFormat.of().formatHex(bytes);
        try { one(devices.insertDevice(id,actor.orgId(),request.code(),hash(token))); }
        catch(DuplicateKeyException duplicate) { throw new BusinessException(ErrorCode.VERSION_CONFLICT); }
        appendAudit(actor,"DEVICE_CREATED",id);
        return new NewDevice(devices.findDevice(id),token,devices.databaseNow());
    }

    @Transactional(readOnly=true)
    public DeviceRows list(CurrentUser supplied,int page,int pageSize) {
        CurrentUser actor=manager(supplied,false);
        long total=devices.countDevices(actor.orgId());
        return new DeviceRows(List.copyOf(devices.listDevices(actor.orgId(),pageSize,(long)(page-1)*pageSize)),page,pageSize,total,devices.databaseNow());
    }

    @Transactional(readOnly=true)
    public TimedDevice get(CurrentUser supplied,String id) {
        CurrentUser actor=manager(supplied,false);
        return new TimedDevice(owned(devices.findDevice(id),actor),devices.databaseNow());
    }

    @Transactional
    public TimedDevice setEnabled(CurrentUser supplied,String id,EnableDeviceRequest request) {
        CurrentUser actor=manager(supplied,true);
        DeviceRow device=owned(devices.lockDevice(id),actor);
        if(request==null || request.enabled()==null || request.expectedLockVersion()==null || request.expectedLockVersion()<0) invalid();
        if(device.lockVersion()!=request.expectedLockVersion()) conflict();
        if(devices.setEnabled(id,actor.orgId(),request.enabled(),device.lockVersion())!=1) conflict();
        appendAudit(actor,request.enabled()?"DEVICE_ENABLED":"DEVICE_DISABLED",id);
        return new TimedDevice(devices.findDevice(id),devices.databaseNow());
    }

    @Transactional
    public ReportResult report(CurrentDevice supplied,ReportRequest request) {
        if(supplied==null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        validate(request);
        ExecutionRow located=devices.findExecution(request.executionId());
        if(located==null || !supplied.id().equals(located.droneId())) forbidden();
        TaskRow task=devices.lockTask(located.taskId());
        SubtaskRow subtask=devices.lockSubtask(located.subtaskId());
        ExecutionRow execution=devices.lockExecution(located.id());
        CurrentDevice actor=authentication.currentDevice(supplied,true);
        if(task==null || subtask==null || execution==null || !task.id().equals(subtask.taskId())
                || !task.id().equals(execution.taskId()) || !subtask.id().equals(execution.subtaskId())
                || !actor.id().equals(execution.droneId()) || !actor.orgId().equals(task.operatorOrgId())
                || !ResourcePermissionService.OPERATOR.equals(actor.orgId())) forbidden();
        String fingerprint=fingerprint(actor.id(),request);
        EventRow bySequence=devices.findBySequence(actor.id(),request.bootId(),request.seq());
        EventRow byId=devices.findByEventId(actor.id(),request.eventId());
        if(bySequence!=null || byId!=null) {
            EventRow previous=bySequence==null?byId:bySequence;
            if(!previous.fingerprint().equals(fingerprint) || bySequence!=null && byId!=null && !bySequence.id().equals(byId.id()))
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            return result(previous,true);
        }
        // A returning device remains visible online, but its late reports stay
        // with the closed batch and cannot advance either execution's progress.
        boolean current=execution.id().equals(subtask.currentExecutionId());
        boolean currentRunning=current && "RUNNING".equals(execution.status());
        boolean historicalClosed=!current && "CLOSED".equals(execution.status());
        if(!Set.of("RUNNING","WAITING_ACCEPTANCE").contains(task.status()) || !(currentRunning || historicalClosed)) conflict();
        int applied=currentRunning && "PROGRESS".equals(request.type())?Math.max(execution.progress(),request.progress()):execution.progress();
        Instant now=devices.databaseNow();
        EventRow event=new EventRow(UUID.randomUUID().toString(),actor.id(),request.bootId(),request.seq(),request.eventId(),
                execution.id(),request.type(),request.progress(),fingerprint,now,applied);
        one(devices.insertEvent(event));
        one(devices.touchLastSeen(actor.id(),now));
        if(currentRunning && "PROGRESS".equals(request.type())) one(devices.updateProgress(execution.id(),request.progress()));
        one(devices.auditReport(UUID.randomUUID().toString(),actor.id(),actor.orgId(),"DEVICE_"+request.type(),execution.id(),trace()));
        return result(event,false);
    }

    private CurrentUser manager(CurrentUser supplied,boolean locked) {
        CurrentUser actor=permissions.currentActor(supplied,locked);
        if(!ResourcePermissionService.OPERATOR.equals(actor.orgId()) || !actor.roles().contains(Role.OPERATOR_TASK_ADMIN)) forbidden();
        return actor;
    }
    private static DeviceRow owned(DeviceRow device,CurrentUser actor) {
        if(device==null || !actor.orgId().equals(device.operatorOrgId())) forbidden();
        return device;
    }
    private void appendAudit(CurrentUser actor,String action,String id) {
        one(audit.insertResourceSuccess(UUID.randomUUID().toString(),actor.id(),actor.orgId(),action,"DRONE",id,trace()));
    }
    static void validate(ReportRequest request) {
        if(request==null) invalid();
        id(request.bootId()); id(request.eventId()); id(request.executionId());
        if(request.seq()==null || request.seq()<0 || request.seq()>9007199254740991L
                || !Set.of("HEARTBEAT","PROGRESS").contains(request.type()==null?"":request.type())) invalid();
        if("HEARTBEAT".equals(request.type()) && request.progress()!=null || "PROGRESS".equals(request.type())
                && (request.progress()==null || request.progress()<0 || request.progress()>100)) invalid();
    }
    static void id(String id) {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) invalid();
    }
    static String fingerprint(String droneId,ReportRequest request) {
        MessageDigest digest=digest();
        for(String value:List.of(droneId,request.bootId(),Long.toString(request.seq()),request.eventId(),request.executionId(),request.type(),
                request.progress()==null?"NULL":request.progress().toString())) {
            byte[] bytes=value.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String hash(String value){return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception failure){throw new IllegalStateException("SHA-256 unavailable");}}
    private static ReportResult result(EventRow event,boolean duplicate) {
        return new ReportResult(event.id(),event.droneId(),event.eventId(),event.bootId(),event.seq(),event.executionId(),event.eventType(),
                event.progress(),event.appliedProgress(),event.receivedAt(),duplicate);
    }
    private static String trace(){String trace=TraceContext.currentTraceId();return trace!=null&&trace.matches("[A-Za-z0-9_-]{1,64}")?trace:UUID.randomUUID().toString();}
    private static void one(int rows){if(rows!=1)throw new IllegalStateException("Device transaction write failed");}
    private static void invalid(){throw new BusinessException(ErrorCode.INVALID_ARGUMENT);}
    private static void conflict(){throw new BusinessException(ErrorCode.VERSION_CONFLICT);}
    private static void forbidden(){throw new BusinessException(ErrorCode.FORBIDDEN);}
    public record TimedDevice(DeviceRow device,Instant now) {}
    public record DeviceRows(List<DeviceRow> devices,int page,int pageSize,long total,Instant now) {}
    public record NewDevice(DeviceRow device,String token,Instant now) {
        @Override public String toString(){return "NewDevice[device="+device+", token=<redacted>]";}
    }
}
