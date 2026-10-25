package com.uav.inspection.device;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.device.DeviceModels.*;
import com.uav.inspection.device.persistence.DeviceMapper.DeviceRow;
import com.uav.inspection.identity.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Orchestrates short DB transactions and disposable cache calls on opposite sides of commit. */
@Service
@Profile("database")
public class DeviceService {
    private final DeviceTransactions transactions;
    private final DevicePresenceCache cache;
    private final DevicePresenceSettings settings;
    public DeviceService(DeviceTransactions transactions,DevicePresenceCache cache,DevicePresenceSettings settings) {
        this.transactions=transactions;this.cache=cache;this.settings=settings;
    }
    public CreatedDevice create(CurrentUser actor,CreateDeviceRequest request) {
        var created=transactions.create(actor,request);
        return new CreatedDevice(view(created.device(),created.now()),created.token());
    }
    public DevicePage list(CurrentUser actor,int page,int pageSize) {
        if(page<1 || pageSize<1 || pageSize>100) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        var rows=transactions.list(actor,page,pageSize);
        List<DeviceView> views=rows.devices().stream().map(row->view(row,rows.now())).toList();
        return new DevicePage(views,page,pageSize,rows.total());
    }
    public DeviceView get(CurrentUser actor,String id) {
        DeviceTransactions.id(id);
        var row=transactions.get(actor,id);
        return view(row.device(),row.now());
    }
    public DeviceView setEnabled(CurrentUser actor,String id,EnableDeviceRequest request) {
        DeviceTransactions.id(id);
        var row=transactions.setEnabled(actor,id,request);
        return view(row.device(),row.now());
    }
    public ReportResult report(CurrentDevice actor,ReportRequest request) {
        ReportResult result=transactions.report(actor,request);
        if(!result.duplicate()) remember(result.droneId(),result.receivedAt());
        return result;
    }
    private DeviceView view(DeviceRow row,Instant now) {
        Instant lastSeen=row.lastSeenAt();
        Instant cached=null;
        try { cached=cache.find(row.id()); } catch(RuntimeException ignored) { /* SQL remains authoritative. */ }
        // Only SQL proves reception. A newer cache value cannot manufacture a heartbeat absent from this read.
        if(lastSeen!=null && !lastSeen.equals(cached)) remember(row.id(),lastSeen);
        boolean online=row.enabled() && lastSeen!=null && !lastSeen.isAfter(now)
                && lastSeen.plus(settings.onlineThreshold()).isAfter(now);
        return new DeviceView(row.id(),row.operatorOrgId(),row.code(),row.enabled(),lastSeen,online,row.lockVersion(),row.createdAt(),row.updatedAt());
    }
    private void remember(String id,Instant seen) { try { cache.remember(id,seen); } catch(RuntimeException ignored) { /* Never undo a committed event. */ } }
}
