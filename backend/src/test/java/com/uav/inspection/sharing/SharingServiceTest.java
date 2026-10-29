package com.uav.inspection.sharing;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.IdentityService;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.TaskScopeMapper;
import com.uav.inspection.sharing.SharingModels.*;
import com.uav.inspection.sharing.persistence.SharingMapper;
import com.uav.inspection.sharing.persistence.SharingMapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SharingServiceTest {
    private static final String TASK="30000000-0000-4000-8000-000000000001";
    private static final String OTHER_TASK="30000000-0000-4000-8000-000000000002";
    private static final String VERSION="80000000-0000-4000-8000-000000000001";
    private static final String OTHER_VERSION="80000000-0000-4000-8000-000000000002";
    private static final String GRANT="90000000-0000-4000-8000-000000000001";
    private static final String REQUEST="90000000-0000-4000-8000-000000000002";
    private static final String OWNER=ResourcePermissionService.OWNER,OPERATOR=ResourcePermissionService.OPERATOR;
    private static final Instant NOW=Instant.parse("2026-10-25T01:02:03.456Z");
    private static final CurrentUser APPLICANT=actor("10000000-0000-4000-8000-000000000003",OWNER,Role.ORG_ADMIN);
    private static final CurrentUser OPERATOR_APPLICANT=actor("10000000-0000-4000-8000-000000000006",OPERATOR,Role.ORG_ADMIN);
    private static final CurrentUser APPROVER=actor("10000000-0000-4000-8000-000000000002",OWNER,Role.OWNER_DATA_APPROVER);
    private static final CurrentUser OWNER_ADMIN=actor("10000000-0000-4000-8000-000000000001",OWNER,Role.OWNER_TASK_ADMIN);
    private static final CurrentUser REVIEWER=actor("10000000-0000-4000-8000-000000000005",OWNER,Role.OWNER_REVIEWER);
    private final SharingMapper mapper=mock(SharingMapper.class);
    private final AuditMapper audit=mock(AuditMapper.class);
    private final IdentityMapper identities=mock(IdentityMapper.class);
    private final IdentityService authentication=mock(IdentityService.class);
    private final TaskScopeMapper memberships=mock(TaskScopeMapper.class);
    private final Map<String,CurrentUser> users=new HashMap<>();
    private final Map<String,GrantRow> grants=new HashMap<>();
    private final RecordingManager manager=new RecordingManager();
    private final SharingService service=transactional();

    @BeforeEach
    void configure() {
        for(CurrentUser actor:List.of(APPLICANT,OPERATOR_APPLICANT,APPROVER,OWNER_ADMIN,REVIEWER)) users.put(actor.id(),actor);
        when(authentication.authenticate(anyString(),anyLong())).thenAnswer(call->users.get(call.getArgument(0)));
        when(memberships.countMembership(anyString(),anyString())).thenReturn(0);
        when(memberships.countMembership(TASK,APPROVER.id())).thenReturn(1);
        when(mapper.databaseNow()).thenReturn(NOW);
        when(mapper.version(VERSION)).thenReturn(version("APPROVED","CONFIRMED"));
        when(mapper.version(OTHER_VERSION)).thenReturn(new VersionScope(OTHER_VERSION,OTHER_TASK,OWNER,OPERATOR,"APPROVED","CONFIRMED"));
        when(mapper.lockTask(anyString())).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return new TaskRow(call.getArgument(0),OWNER,OPERATOR);
        });
        when(mapper.request(anyString(),anyString())).thenAnswer(call->grants.values().stream().filter(row->
                row.applicantId().equals(call.getArgument(0)) && row.clientRequestId().equals(call.getArgument(1))).findFirst().orElse(null));
        when(mapper.grant(anyString())).thenAnswer(call->grants.get(call.getArgument(0)));
        when(mapper.lockGrant(anyString())).thenAnswer(call->grants.get(call.getArgument(0)));
        when(mapper.insert(any())).thenAnswer(call->{
            GrantRow row=call.getArgument(0);
            grants.put(row.id(),new GrantRow(row.id(),row.versionId(),row.applicantId(),row.clientRequestId(),row.requestHash(),"PENDING",
                    row.reason(),row.requestedValidUntil(),null,null,null,null,null,null,null,0,NOW,NOW));
            return 1;
        });
        when(mapper.decide(anyString(),anyLong(),anyString(),anyString(),nullable(String.class),nullable(Instant.class))).thenAnswer(call->{
            GrantRow row=grants.get(call.getArgument(0));
            grants.put(row.id(),new GrantRow(row.id(),row.versionId(),row.applicantId(),row.clientRequestId(),row.requestHash(),call.getArgument(2),
                    row.reason(),row.requestedValidUntil(),call.getArgument(3),call.getArgument(4),NOW,call.getArgument(5),
                    null,null,null,row.lockVersion()+1,row.createdAt(),NOW));
            return 1;
        });
        when(mapper.revoke(anyString(),anyLong(),anyString(),anyString())).thenAnswer(call->{
            GrantRow row=grants.get(call.getArgument(0));
            grants.put(row.id(),new GrantRow(row.id(),row.versionId(),row.applicantId(),row.clientRequestId(),row.requestHash(),"REVOKED",row.reason(),
                    row.requestedValidUntil(),row.approverId(),row.decisionReason(),row.decidedAt(),row.validUntil(),call.getArgument(2),call.getArgument(3),NOW,
                    row.lockVersion()+1,row.createdAt(),NOW));
            return 1;
        });
        when(mapper.countMine(anyString())).thenAnswer(call->grants.values().stream().filter(row->row.applicantId().equals(call.getArgument(0))).count());
        when(mapper.mine(anyString(),anyInt(),anyLong())).thenAnswer(call->grants.values().stream().filter(row->row.applicantId().equals(call.getArgument(0)))
                .skip(call.getArgument(2,Long.class)).limit(call.getArgument(1,Integer.class)).toList());
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
    }

    @Test
    void aRegisteredNonmemberCanApplyWithoutReceivingFileOrTaskMetadata() {
        when(mapper.version(VERSION)).thenReturn(version("PENDING","UNKNOWN"));

        GrantView result=service.apply(APPLICANT,application());

        assertEquals(VERSION,result.versionId()); assertEquals(APPLICANT.id(),result.applicantId());
        assertEquals("PENDING",result.status()); assertEquals(NOW,result.createdAt());
        assertEquals(1,grants.size()); assertTrue(grants.get(result.id()).requestHash().matches("[0-9a-f]{64}"));
        String json=JsonMapper.builder().build().writeValueAsString(result);
        for(String hidden:List.of("requestHash","taskId","filename","objectKey","sha256","url")) assertFalse(json.contains('"'+hidden+'"'));
        var order=inOrder(mapper,identities,authentication,audit);
        order.verify(mapper).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(APPLICANT.id());
        order.verify(authentication).authenticate(APPLICANT.id(),0);
        order.verify(mapper).insert(any());
        order.verify(audit).insertResourceSuccess(anyString(),eq(APPLICANT.id()),eq(OWNER),eq("ACCESS_GRANT_REQUESTED"),eq("ACCESS_GRANT"),eq(result.id()),anyString());
        verifyNoInteractions(memberships);
        assertEquals(1,manager.commits);
    }

    @Test
    void bothOrganizationsCanApplyEvenWithOnlyOrganizationAdministratorRole() {
        GrantView owner=service.apply(APPLICANT,application());
        GrantView operator=service.apply(OPERATOR_APPLICANT,application());
        assertNotEquals(owner.id(),operator.id());
        assertEquals(2,grants.size()); verifyNoInteractions(memberships);
    }

    @Test
    void identicalRequestRetriesReuseTheOriginalGrantWithoutExtendingTimeOrRepeatingAudit() {
        GrantView first=service.apply(APPLICANT,new ApplyRequest(REQUEST,VERSION,"  inspection  ",NOW.plusSeconds(3600).plusNanos(123456)));
        GrantView retry=service.apply(APPLICANT,application());
        assertEquals(first.id(),retry.id()); assertEquals(first.requestedValidUntil(),retry.requestedValidUntil());
        assertEquals("inspection",retry.reason());
        verify(mapper).insert(any());
        verify(audit).insertResourceSuccess(anyString(),eq(APPLICANT.id()),eq(OWNER),eq("ACCESS_GRANT_REQUESTED"),anyString(),eq(first.id()),anyString());
        var order=inOrder(mapper,identities);
        order.verify(mapper,times(2)).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(APPLICANT.id());
        order.verify(mapper).lockGrant(first.id());
    }

    @Test
    void anExpiredApprovedRequestCanStillBeRetriedButCannotBeRenewedByChangingTheSameKey() {
        GrantView first=service.apply(APPLICANT,application());
        service.decide(APPROVER,first.id(),new DecisionRequest("APPROVED",NOW.plusSeconds(60),null,0L));
        when(mapper.databaseNow()).thenReturn(NOW.plusSeconds(7200));

        GrantView retry=service.apply(APPLICANT,application());

        assertEquals(first.id(),retry.id()); assertEquals("EXPIRED",retry.status());
        assertEquals(NOW.plusSeconds(60),retry.validUntil());
        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT,()->service.apply(APPLICANT,new ApplyRequest(REQUEST,VERSION,"inspection",NOW.plusSeconds(10000))));
        verify(mapper).insert(any()); assertEquals(1,grants.size());
    }

    @Test
    void changingVersionOrReasonWithTheSameRequestKeyCannotLockOrExposeAnotherTasksGrant() {
        GrantView first=service.apply(APPLICANT,application());
        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT,()->service.apply(APPLICANT,new ApplyRequest(REQUEST,OTHER_VERSION,"inspection",NOW.plusSeconds(3600))));
        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT,()->service.apply(APPLICANT,new ApplyRequest(REQUEST,VERSION,"different reason",NOW.plusSeconds(3600))));
        verify(mapper,never()).lockTask(OTHER_TASK);
        verify(mapper,never()).lockGrant(first.id()); verify(mapper).insert(any());
    }

    @Test
    void unknownVersionsAndStaleAuthenticationDoNotCreateAnApplication() {
        String unknown="80000000-0000-4000-8000-000000000099";
        assertCode(ErrorCode.FORBIDDEN,()->service.apply(APPLICANT,new ApplyRequest(REQUEST,unknown,"inspection",NOW.plusSeconds(3600))));
        when(authentication.authenticate(APPLICANT.id(),0)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        assertCode(ErrorCode.UNAUTHORIZED,()->service.apply(APPLICANT,application()));
        verify(mapper,never()).insert(any()); verifyNoInteractions(audit);
    }

    @Test
    void invalidApplicationContentAndDatabaseTimeExpiryAreRejectedWithoutAudit() {
        for(String bad:List.of(""," ","\u0085\u00a0","字".repeat(1001))) {
            assertCode(ErrorCode.INVALID_ARGUMENT,()->service.apply(APPLICANT,new ApplyRequest(REQUEST,VERSION,bad,NOW.plusSeconds(3600))));
        }
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.apply(APPLICANT,new ApplyRequest(REQUEST,VERSION,"inspection",NOW)));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.apply(APPLICANT,new ApplyRequest(REQUEST,VERSION,"inspection",Instant.parse("+10000-01-01T00:00:00Z"))));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.apply(APPLICANT,null));
        verify(mapper,never()).insert(any()); verifyNoInteractions(audit);
    }

    @Test
    void anApplicationThatExpiresAtItsInsertBoundaryDoesNotPretendToSucceed() {
        doReturn(0).when(mapper).insert(any());
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.apply(APPLICANT,application()));
        verifyNoInteractions(audit); assertEquals(1,manager.rollbacks); assertEquals(0,manager.commits);
    }

    @Test
    void onlyAnExplicitOwnerApproverMemberCanDecideRevokeOrSeeTaskApplications() {
        seed("PENDING");
        for(CurrentUser forbidden:List.of(APPLICANT,OPERATOR_APPLICANT,OWNER_ADMIN,REVIEWER)) {
            assertCode(ErrorCode.FORBIDDEN,()->service.decide(forbidden,GRANT,approval()));
            assertCode(ErrorCode.FORBIDDEN,()->service.revoke(forbidden,GRANT,new RevokeRequest("withdraw",0L)));
            assertCode(ErrorCode.FORBIDDEN,()->service.list(forbidden,false,TASK,1,20));
        }
        when(memberships.countMembership(TASK,APPROVER.id())).thenReturn(0);
        assertCode(ErrorCode.FORBIDDEN,()->service.decide(APPROVER,GRANT,approval()));
        verify(mapper,never()).lockGrant(anyString());
        verify(mapper,never()).decide(anyString(),anyLong(),anyString(),anyString(),any(),any());
        verify(mapper,never()).countTask(anyString()); verifyNoInteractions(audit);
    }

    @Test
    void approvalChecksTheExactVersionsReadinessAndStoresOneDecisionWithAtomicAudit() {
        seed("PENDING");

        GrantView approved=service.decide(APPROVER,GRANT,approval());

        assertEquals("APPROVED",approved.status()); assertEquals(1,approved.lockVersion());
        assertEquals(NOW.plusSeconds(300),approved.validUntil()); assertEquals(APPROVER.id(),approved.approverId());
        assertEquals(NOW,approved.decidedAt());
        var order=inOrder(mapper,identities,authentication,memberships,audit);
        order.verify(mapper).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(APPROVER.id());
        order.verify(authentication).authenticate(APPROVER.id(),0);
        order.verify(memberships).countMembership(TASK,APPROVER.id());
        order.verify(mapper).lockGrant(GRANT);
        order.verify(mapper).decide(GRANT,0,"APPROVED",APPROVER.id(),null,NOW.plusSeconds(300));
        order.verify(audit).insertResourceSuccess(anyString(),eq(APPROVER.id()),eq(OWNER),eq("ACCESS_GRANT_APPROVED"),eq("ACCESS_GRANT"),eq(GRANT),anyString());
        assertEquals(1,manager.commits);
    }

    @ParameterizedTest
    @ValueSource(strings={"PENDING","RETURNED"})
    void aVersionWithoutApprovedReviewCannotReceiveAnApprovedGrant(String review) {
        seed("PENDING"); when(mapper.version(VERSION)).thenReturn(version(review,"CONFIRMED"));
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.decide(APPROVER,GRANT,approval()));
        verify(mapper,never()).decide(anyString(),anyLong(),anyString(),anyString(),any(),any()); verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings={"PENDING","UNKNOWN","FAILED","SUBMITTED"})
    void aVersionWithoutConfirmedAnchorCannotReceiveAnApprovedGrant(String anchor) {
        seed("PENDING"); when(mapper.version(VERSION)).thenReturn(version("APPROVED",anchor));
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.decide(APPROVER,GRANT,approval()));
        verify(mapper,never()).decide(anyString(),anyLong(),anyString(),anyString(),any(),any()); verifyNoInteractions(audit);
    }

    @Test
    void rejectionNeedsAReasonButCanBeDecidedBeforeTheVersionIsReady() {
        seed("PENDING"); when(mapper.version(VERSION)).thenReturn(version("PENDING","UNKNOWN"));
        GrantView rejected=service.decide(APPROVER,GRANT,new DecisionRequest("REJECTED",null,"  not required  ",0L));
        assertEquals("REJECTED",rejected.status()); assertEquals("not required",rejected.decisionReason()); assertNull(rejected.validUntil());
        verify(audit).insertResourceSuccess(anyString(),eq(APPROVER.id()),eq(OWNER),eq("ACCESS_GRANT_REJECTED"),anyString(),eq(GRANT),anyString());
    }

    @Test
    void approvedDeadlinesMustBeFutureAndWithinTheRequestedPeriod() {
        seed("PENDING");
        for(Instant invalid:List.of(NOW,NOW.minusMillis(1),NOW.plusSeconds(3601))) {
            assertCode(ErrorCode.INVALID_ARGUMENT,()->service.decide(APPROVER,GRANT,new DecisionRequest("APPROVED",invalid,null,0L)));
        }
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.decide(APPROVER,GRANT,new DecisionRequest("APPROVED",null,null,0L)));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.decide(APPROVER,GRANT,new DecisionRequest("REJECTED",NOW.plusSeconds(1),"no",0L)));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.decide(APPROVER,GRANT,new DecisionRequest("REJECTED",null,"\u0085",0L)));
        verify(mapper,never()).decide(anyString(),anyLong(),anyString(),anyString(),any(),any()); verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings={"APPROVED","REJECTED","REVOKED"})
    void anExistingFormalDecisionCannotBeOverwritten(String status) {
        seed(status);
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.decide(APPROVER,GRANT,approval()));
        verify(mapper,never()).decide(anyString(),anyLong(),anyString(),anyString(),any(),any()); verifyNoInteractions(audit);
    }

    @Test
    void revocationPreservesTheOriginalDecisionAndWritesItsOwnActorReasonAndTime() {
        GrantRow original=seed("APPROVED");
        GrantView revoked=service.revoke(APPROVER,GRANT,new RevokeRequest("  access withdrawn  ",0L));
        assertEquals("REVOKED",revoked.status()); assertEquals(1,revoked.lockVersion());
        assertEquals(original.validUntil(),revoked.validUntil()); assertEquals(original.approverId(),revoked.approverId());
        assertEquals(original.decidedAt(),revoked.decidedAt()); assertEquals(APPROVER.id(),revoked.revokedBy());
        assertEquals("access withdrawn",revoked.revokeReason()); assertEquals(NOW,revoked.revokedAt());
        verify(audit).insertResourceSuccess(anyString(),eq(APPROVER.id()),eq(OWNER),eq("ACCESS_GRANT_REVOKED"),eq("ACCESS_GRANT"),eq(GRANT),anyString());
    }

    @Test
    void expiryAtTheExactDatabaseMillisecondIsDerivedWithoutChangingStoredStatus() {
        GrantRow original=seed("APPROVED");
        when(mapper.databaseNow()).thenReturn(original.validUntil());

        GrantPage page=service.list(APPLICANT,true,null,1,20);

        assertEquals("EXPIRED",page.items().getFirst().status()); assertEquals("APPROVED",grants.get(GRANT).status());
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.revoke(APPROVER,GRANT,new RevokeRequest("expired",0L)));
        verify(mapper,never()).revoke(anyString(),anyLong(),anyString(),anyString()); verifyNoInteractions(audit);
    }

    @Test
    void staleOrMissingCasVersionsNeverWriteADecisionOrRevocation() {
        seed("PENDING");
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.decide(APPROVER,GRANT,new DecisionRequest("APPROVED",NOW.plusSeconds(10),null,1L)));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.decide(APPROVER,GRANT,new DecisionRequest("APPROVED",NOW.plusSeconds(10),null,null)));
        seed("APPROVED");
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.revoke(APPROVER,GRANT,new RevokeRequest("withdraw",1L)));
        verify(mapper,never()).decide(anyString(),anyLong(),anyString(),anyString(),any(),any());
        verify(mapper,never()).revoke(anyString(),anyLong(),anyString(),anyString()); verifyNoInteractions(audit);
    }

    @Test
    void databaseTimeAndCasRechecksCanRejectAPreviouslyValidDecisionWithoutSuccessAudit() {
        seed("PENDING");
        doReturn(0).when(mapper).decide(anyString(),anyLong(),anyString(),anyString(),any(),any());
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.decide(APPROVER,GRANT,approval()));
        seed("APPROVED"); doReturn(0).when(mapper).revoke(anyString(),anyLong(),anyString(),anyString());
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.revoke(APPROVER,GRANT,new RevokeRequest("withdraw",0L)));
        verifyNoInteractions(audit); assertEquals(0,manager.commits); assertEquals(2,manager.rollbacks);
    }

    @Test
    void auditFailureRollsBackTheDecisionRatherThanReturningAnUnauditedGrant() {
        seed("PENDING");
        var failure=new DataIntegrityViolationException("audit unavailable");
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenThrow(failure);
        assertSame(failure,assertThrows(DataIntegrityViolationException.class,()->service.decide(APPROVER,GRANT,approval())));
        verify(mapper).decide(GRANT,0,"APPROVED",APPROVER.id(),null,NOW.plusSeconds(300));
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
    }

    @Test
    void listsAreScopedBeforePaginationAndTaskListsRecheckApproverMembership() {
        seed("PENDING");
        doReturn(9L).when(mapper).countMine(APPLICANT.id());
        doReturn(List.of()).when(mapper).mine(APPLICANT.id(),100,214748364600L);
        GrantPage own=service.list(APPLICANT,true,null,Integer.MAX_VALUE,100);
        assertEquals(9,own.total()); assertEquals(Integer.MAX_VALUE,own.page());
        verify(mapper).countMine(APPLICANT.id()); verify(mapper).mine(APPLICANT.id(),100,214748364600L);
        when(mapper.countTask(TASK)).thenReturn(1L); when(mapper.task(TASK,1,1)).thenReturn(List.of());
        GrantPage managed=service.list(APPROVER,false,TASK,2,1);
        assertEquals(1,managed.total());
        var order=inOrder(mapper,identities,authentication,memberships);
        order.verify(mapper).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(APPROVER.id());
        order.verify(authentication).authenticate(APPROVER.id(),0);
        order.verify(memberships).countMembership(TASK,APPROVER.id());
        order.verify(mapper).countTask(TASK); order.verify(mapper).task(TASK,1,1);
        verifyNoInteractions(audit);
    }

    @Test
    void invalidListModesAndUnknownGrantsDoNotExposeOtherUsersRequests() {
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.list(APPLICANT,null,null,1,20));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.list(APPLICANT,true,TASK,1,20));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.list(APPLICANT,true,null,0,20));
        assertCode(ErrorCode.FORBIDDEN,()->service.decide(APPROVER,GRANT,approval()));
        verify(mapper,never()).countMine(anyString()); verify(mapper,never()).countTask(anyString()); verifyNoInteractions(audit);
    }

    @Test
    void databaseUniquenessStillResolvesAnIdenticalApplicationWithoutRepeatingItsAudit() {
        GrantView first=service.apply(APPLICANT,application());
        GrantRow existing=grants.remove(first.id());
        clearInvocations(audit);
        doAnswer(call->{grants.put(existing.id(),existing); throw new DuplicateKeyException("concurrent request");}).when(mapper).insert(any());

        GrantView repeated=service.apply(APPLICANT,application());

        assertEquals(first.id(),repeated.id()); assertEquals(1,grants.size()); verifyNoInteractions(audit);
    }

    private GrantRow seed(String status) {
        boolean decided=!"PENDING".equals(status),approved="APPROVED".equals(status)||"REVOKED".equals(status),revoked="REVOKED".equals(status);
        GrantRow row=new GrantRow(GRANT,VERSION,APPLICANT.id(),REQUEST,"a".repeat(64),status,"inspection",NOW.plusSeconds(3600),
                decided?APPROVER.id():null,"REJECTED".equals(status)?"rejected":null,decided?NOW.minusSeconds(10):null,approved?NOW.plusSeconds(300):null,
                revoked?APPROVER.id():null,revoked?"withdrawn":null,revoked?NOW:null,0,NOW.minusSeconds(20),NOW);
        grants.put(GRANT,row); return row;
    }
    private SharingService transactional() {
        var permissions=new ResourcePermissionService(identities,authentication,memberships);
        var proxy=new ProxyFactory(new SharingService(mapper,permissions,audit)); proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return (SharingService)proxy.getProxy();
    }
    private static CurrentUser actor(String id,String org,Role role) { return new CurrentUser(id,org,"test",List.of(role),0,0); }
    private static VersionScope version(String review,String anchor) { return new VersionScope(VERSION,TASK,OWNER,OPERATOR,review,anchor); }
    private static ApplyRequest application() { return new ApplyRequest(REQUEST,VERSION,"inspection",NOW.plusSeconds(3600)); }
    private static DecisionRequest approval() { return new DecisionRequest("APPROVED",NOW.plusSeconds(300),null,0L); }
    private static void assertCode(ErrorCode expected,org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected,assertThrows(BusinessException.class,action).errorCode());
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        private int commits,rollbacks;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
