package com.uav.inspection.audit;

import com.uav.inspection.audit.persistence.AuditQueryMapper;
import com.uav.inspection.audit.persistence.AuditQueryMapper.AuditRow;
import com.uav.inspection.audit.persistence.AuditQueryMapper.TaskRow;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.IdentityService;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.TaskScopeMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditQueryServiceTest {
    private static final String TASK="30000000-0000-4000-8000-000000000001";
    private static final String USER="10000000-0000-4000-8000-000000000001";
    private static final String VERSION="80000000-0000-4000-8000-000000000001";
    private static final String OWNER=ResourcePermissionService.OWNER,OPERATOR=ResourcePermissionService.OPERATOR;
    private static final Instant NOW=Instant.parse("2026-10-25T03:04:05.678Z");
    private final AuditQueryMapper mapper=mock(AuditQueryMapper.class);
    private final IdentityMapper identities=mock(IdentityMapper.class);
    private final IdentityService authentication=mock(IdentityService.class);
    private final TaskScopeMapper scopes=mock(TaskScopeMapper.class);
    private final ResourcePermissionService permissions=new ResourcePermissionService(identities,authentication,scopes);
    private final AtomicReference<CurrentUser> current=new AtomicReference<>(actor(Role.OWNER_TASK_ADMIN));
    private final RecordingManager manager=new RecordingManager();
    private final AuditQueryService service=transactional();

    @BeforeEach
    void configure() {
        when(mapper.lockTask(TASK)).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return new TaskRow(TASK,OWNER,OPERATOR);
        });
        when(authentication.authenticate(USER,0)).thenAnswer(call->current.get());
        when(scopes.countMembership(TASK,USER)).thenReturn(1);
        when(mapper.page(eq(TASK),nullable(String.class),nullable(String.class),nullable(String.class),anyInt(),anyLong())).thenReturn(List.of());
    }

    @ParameterizedTest
    @ValueSource(strings={"OWNER_TASK_ADMIN","OWNER_REVIEWER","OWNER_DATA_APPROVER","OPERATOR_TASK_ADMIN"})
    void onlyCurrentTaskManagersAndReviewersMayReadAuditHistory(String role) {
        current.set(actor(Role.valueOf(role)));
        var page=service.page(current.get(),TASK,1,20,null,null,null);
        assertEquals(0,page.total()); assertTrue(page.items().isEmpty());
        var order=inOrder(mapper,identities,authentication,scopes);
        order.verify(mapper).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(USER);
        order.verify(authentication).authenticate(USER,0);
        order.verify(scopes).countMembership(TASK,USER);
        order.verify(mapper).count(TASK,null,null,null);
        order.verify(mapper).page(TASK,null,null,null,20,0);
        assertEquals(1,manager.commits);
    }

    @ParameterizedTest
    @ValueSource(strings={"ORG_ADMIN","OPERATOR_USER"})
    void organizationAdministrationOrOperationalMembershipDoesNotGrantAuditReadAccess(String role) {
        current.set(actor(Role.valueOf(role)));
        assertCode(ErrorCode.FORBIDDEN,()->service.page(current.get(),TASK,1,20,null,null,null));
        verify(mapper,never()).count(anyString(),any(),any(),any());
        verify(mapper,never()).page(anyString(),any(),any(),any(),anyInt(),anyLong());
    }

    @Test
    void aVersionGrantCannotBeUsedAsATaskAuditPermission() {
        CurrentUser actor=current.get();
        when(scopes.countMembership(TASK,USER)).thenReturn(0);
        when(scopes.grant(VERSION,USER)).thenReturn(new TaskScopeMapper.GrantScope("grant",NOW.plusSeconds(60)));
        when(scopes.now()).thenReturn(NOW);
        assertFalse(permissions.requireVersion(actor,VERSION,TASK,OWNER,OPERATOR,false).taskPath());
        clearInvocations(scopes);

        assertCode(ErrorCode.FORBIDDEN,()->service.page(actor,TASK,1,20,null,null,null));

        verify(scopes,never()).grant(anyString(),anyString());
        verify(scopes,never()).lockGrant(anyString(),anyString());
        verify(mapper,never()).count(anyString(),any(),any(),any());
    }

    @Test
    void changedRolesOrRemovedMembershipAreRecheckedUnderTheAccountLockBeforeReadingRows() {
        CurrentUser supplied=current.get();
        current.set(actor(Role.ORG_ADMIN));
        assertCode(ErrorCode.FORBIDDEN,()->service.page(supplied,TASK,1,20,null,null,null));
        current.set(supplied); when(scopes.countMembership(TASK,USER)).thenReturn(0);
        assertCode(ErrorCode.FORBIDDEN,()->service.page(supplied,TASK,1,20,null,null,null));
        verify(identities,times(2)).findUserByIdForUpdate(USER);
        verify(mapper,never()).count(anyString(),any(),any(),any());
        verify(mapper,never()).page(anyString(),any(),any(),any(),anyInt(),anyLong());
    }

    @Test
    void signedDownloadUrlIssuanceIsClearlyLabeledAndDoesNotClaimACompletedDownload() {
        var row=new AuditRow("audit", "USER",USER,OWNER,"URL_ISSUED","EVIDENCE_VERSION",VERSION,"trace","SUCCESS",NOW,
                TASK,VERSION,"subtask","execution");
        when(mapper.count(TASK,"URL_ISSUED","EVIDENCE_VERSION","trace")).thenReturn(1L);
        when(mapper.page(TASK,"URL_ISSUED","EVIDENCE_VERSION","trace",20,0)).thenReturn(List.of(row));

        var result=service.page(current.get(),TASK,1,20,"URL_ISSUED","EVIDENCE_VERSION","trace");

        var item=result.items().getFirst();
        assertEquals("URL_ISSUED",item.action()); assertEquals("下载地址签发",item.actionLabel());
        assertEquals("SUCCESS",item.result()); assertEquals(TASK,item.taskId());
        assertEquals(VERSION,item.versionId()); assertEquals(NOW,item.createdAt());
        assertEquals(1,result.total());
    }

    @Test
    void systemAndUnfixedUploadRecordsPreserveNullableIdentitiesAndVersionRelations() {
        var row=new AuditRow("audit","SYSTEM",null,OPERATOR,"UPLOAD_FINALIZATION_RETRY_SCHEDULED","UPLOAD_SESSION","upload","trace","SUCCESS",NOW,
                TASK,null,"subtask","execution");
        when(mapper.count(TASK,null,null,null)).thenReturn(1L);
        when(mapper.page(TASK,null,null,null,20,0)).thenReturn(List.of(row));
        var result=service.page(current.get(),TASK,1,20,null,null,null).items().getFirst();
        assertNull(result.actorId()); assertNull(result.versionId());
        assertEquals("UPLOAD_FINALIZATION_RETRY_SCHEDULED",result.actionLabel());
        assertEquals("subtask",result.subtaskId()); assertEquals("execution",result.executionId());
    }

    @Test
    void paginationAndExactFiltersAreForwardedWithoutChangingTheirScope() {
        when(mapper.count(TASK,"NOT_YET_USED","DEVICE_EVENT","mixed_Trace-123")).thenReturn(3L);
        var page=service.page(current.get(),TASK,Integer.MAX_VALUE,100,"NOT_YET_USED","DEVICE_EVENT","mixed_Trace-123");
        assertEquals(3,page.total()); assertEquals(Integer.MAX_VALUE,page.page());
        verify(mapper).count(TASK,"NOT_YET_USED","DEVICE_EVENT","mixed_Trace-123");
        verify(mapper).page(TASK,"NOT_YET_USED","DEVICE_EVENT","mixed_Trace-123",100,214748364600L);
    }

    @Test
    void malformedFiltersIdsAndPaginationAreBadRequestsBeforeTakingLocks() {
        for(String bad:List.of("","lowercase","BAD CODE","A".repeat(65),"SQL'--")) {
            assertCode(ErrorCode.INVALID_ARGUMENT,()->service.page(current.get(),TASK,1,20,bad,null,null));
            assertCode(ErrorCode.INVALID_ARGUMENT,()->service.page(current.get(),TASK,1,20,null,bad,null));
        }
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.page(current.get(),TASK,1,20,null,null," "));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.page(current.get(),TASK,1,20,null,null,"x".repeat(65)));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.page(current.get(),"bad-task",1,20,null,null,null));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.page(current.get(),TASK,0,20,null,null,null));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.page(current.get(),TASK,1,101,null,null,null));
        verifyNoInteractions(mapper,identities,authentication,scopes);
    }

    @Test
    void unknownTasksAndExpiredIdentitiesCannotExposeCountsOrRows() {
        doReturn(null).when(mapper).lockTask(TASK);
        assertCode(ErrorCode.FORBIDDEN,()->service.page(current.get(),TASK,1,20,null,null,null));
        doReturn(new TaskRow(TASK,OWNER,OPERATOR)).when(mapper).lockTask(TASK);
        when(authentication.authenticate(USER,0)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        assertCode(ErrorCode.UNAUTHORIZED,()->service.page(current.get(),TASK,1,20,null,null,null));
        verify(mapper,never()).count(anyString(),any(),any(),any());
        verify(mapper,never()).page(anyString(),any(),any(),any(),anyInt(),anyLong());
    }

    @Test
    void failedCountQueryCannotFallBackToAnUnfilteredPageOrAPartialSuccess() {
        var failure=new DataAccessResourceFailureException("database unavailable");
        when(mapper.count(TASK,null,null,null)).thenThrow(failure);
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->service.page(current.get(),TASK,1,20,null,null,null)));
        verify(mapper,never()).page(anyString(),any(),any(),any(),anyInt(),anyLong());
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
    }

    @Test
    void mapperDynamicStatementsParseAndBindFiltersRatherThanInterpolatingThem() {
        var configuration=new Configuration();
        configuration.addMapper(AuditQueryMapper.class);
        Map<String,Object> arguments=new HashMap<>(Map.of("taskId",TASK,"action","UNIQUE_TEST_ACTION","resourceType","TEST_RESOURCE",
                "traceId","trace-for-binding-check","size",20,"offset",0L));
        for(String method:List.of("count","page")) {
            var statement=configuration.getMappedStatement(AuditQueryMapper.class.getName()+"."+method);
            var bound=statement.getBoundSql(arguments);
            assertFalse(bound.getSql().contains("trace-for-binding-check"));
            assertFalse(bound.getSql().contains("UNIQUE_TEST_ACTION"));
            assertTrue(bound.getParameterMappings().stream().anyMatch(mapping->mapping.getProperty().equals("taskId")));
            assertTrue(bound.getParameterMappings().stream().anyMatch(mapping->mapping.getProperty().equals("action")));
            assertTrue(bound.getParameterMappings().stream().anyMatch(mapping->mapping.getProperty().equals("resourceType")));
            assertTrue(bound.getParameterMappings().stream().anyMatch(mapping->mapping.getProperty().equals("traceId")));
            arguments.put("action",null); arguments.put("resourceType",null); arguments.put("traceId",null);
            assertDoesNotThrow(()->statement.getBoundSql(arguments));
            arguments.put("action","UNIQUE_TEST_ACTION"); arguments.put("resourceType","TEST_RESOURCE"); arguments.put("traceId","trace-for-binding-check");
        }
    }

    private AuditQueryService transactional() {
        var proxy=new ProxyFactory(new AuditQueryService(mapper,permissions)); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (AuditQueryService)proxy.getProxy();
    }
    private static CurrentUser actor(Role role) {
        return new CurrentUser(USER,role==Role.OPERATOR_TASK_ADMIN || role==Role.OPERATOR_USER?OPERATOR:OWNER,"test",List.of(role),0,0);
    }
    private static void assertCode(ErrorCode code,org.junit.jupiter.api.function.Executable action) {
        assertEquals(code,assertThrows(BusinessException.class,action).errorCode());
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        private int commits,rollbacks;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
