package com.uav.inspection.device;

import com.uav.inspection.device.DeviceModels.*;
import com.uav.inspection.device.DeviceTransactions.TimedDevice;
import com.uav.inspection.device.persistence.DeviceMapper.DeviceRow;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceServiceTest {
    private static final String ID="20000000-0000-4000-8000-000000000001";
    private static final Instant NOW=Instant.parse("2026-10-23T10:00:00.123Z");
    private static final CurrentUser ADMIN=new CurrentUser("admin",ResourcePermissionService.OPERATOR,"admin",List.of(Role.OPERATOR_TASK_ADMIN),0,0);
    private static final ReportResult RESULT=new ReportResult("record",ID,"event","boot",1,"execution","PROGRESS",100,100,NOW,false);
    private final DevicePresenceCache cache=mock(DevicePresenceCache.class);
    private final DevicePresenceSettings settings=new DevicePresenceSettings(new MockEnvironment());

    @Test
    void cacheIoRunsAfterTheDatabaseTransactionHasCommittedAndItsFailureCannotUndoTheReport() {
        var manager=new RecordingManager();
        DeviceTransactions phases=proxy(new ReportPhase(RESULT),manager);
        DeviceService service=proxy(new DeviceService(phases,cache,settings),manager);
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1,manager.commits);
            throw new IllegalStateException("Unavailable Redis");
        }).when(cache).remember(ID,NOW);
        assertSame(RESULT,service.report(null,null));
        assertEquals(1,manager.commits);
        verify(cache).remember(ID,NOW);
    }

    @Test
    void unknownDatabaseCommitDoesNotPublishUncertainPresenceToRedis() {
        var manager=new RecordingManager(); manager.failCommit=true;
        DeviceService service=new DeviceService(proxy(new ReportPhase(RESULT),manager),cache,settings);
        assertThrows(TransactionSystemException.class,()->service.report(null,null));
        verifyNoInteractions(cache);
    }

    @Test
    void duplicateReportDoesNotRefreshTheCacheOrItsExpiry() {
        DeviceTransactions phases=mock(DeviceTransactions.class);
        ReportResult duplicate=new ReportResult(RESULT.id(),ID,"event","boot",1,"execution","PROGRESS",100,100,NOW.minusSeconds(10),true);
        when(phases.report(null,null)).thenReturn(duplicate);
        assertSame(duplicate,new DeviceService(phases,cache,settings).report(null,null));
        verifyNoInteractions(cache);
    }

    @Test
    void aCacheTimestampCannotManufacturePresenceWhenSqlHasNoReportOrAnOlderReport() {
        DeviceTransactions phases=mock(DeviceTransactions.class);
        DeviceService service=new DeviceService(phases,cache,settings);
        when(cache.find(ID)).thenReturn(NOW.minusSeconds(1));
        when(phases.get(ADMIN,ID)).thenReturn(new TimedDevice(row(null,true),NOW));
        DeviceView absent=service.get(ADMIN,ID);
        assertFalse(absent.online()); assertNull(absent.lastSeenAt());
        Instant old=NOW.minusSeconds(60);
        when(phases.get(ADMIN,ID)).thenReturn(new TimedDevice(row(old,true),NOW));
        DeviceView stale=service.get(ADMIN,ID);
        assertFalse(stale.online()); assertEquals(old,stale.lastSeenAt());
        verify(cache).remember(ID,old);
    }

    @Test
    void staleFutureOrUnavailableCacheFallsBackToSqlAndTheTwentySecondBoundaryIsOffline() {
        DeviceTransactions phases=mock(DeviceTransactions.class);
        DeviceService service=new DeviceService(phases,cache,settings);
        Instant boundary=NOW.minusSeconds(20);
        when(phases.get(ADMIN,ID)).thenReturn(new TimedDevice(row(boundary,true),NOW));
        for(Instant cached:List.of(NOW.minusSeconds(90),NOW.plusSeconds(60))) {
            when(cache.find(ID)).thenReturn(cached);
            assertFalse(service.get(ADMIN,ID).online());
            assertEquals(boundary,service.get(ADMIN,ID).lastSeenAt());
        }
        when(cache.find(ID)).thenThrow(new IllegalStateException("Cache unavailable"));
        Instant recent=NOW.minusSeconds(19);
        when(phases.get(ADMIN,ID)).thenReturn(new TimedDevice(row(recent,true),NOW));
        assertTrue(service.get(ADMIN,ID).online());
        when(phases.get(ADMIN,ID)).thenReturn(new TimedDevice(row(recent,false),NOW));
        assertFalse(service.get(ADMIN,ID).online());
    }

    private static DeviceRow row(Instant seen,boolean enabled) {
        return new DeviceRow(ID,ResourcePermissionService.OPERATOR,"UAV-TEST",enabled,seen,5,NOW.minusSeconds(3600),NOW);
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target,RecordingManager manager) {
        var proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (T)proxy.getProxy();
    }
    public static class ReportPhase extends DeviceTransactions {
        private final ReportResult result;
        ReportPhase(ReportResult result){super(null,null,null,null);this.result=result;}
        @Override @Transactional public ReportResult report(CurrentDevice actor,ReportRequest request) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return result;
        }
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        private int commits;
        private boolean failCommit;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;if(failCommit)throw new TransactionSystemException("Unknown commit outcome");}
        protected void doRollback(DefaultTransactionStatus status){}
    }
}
