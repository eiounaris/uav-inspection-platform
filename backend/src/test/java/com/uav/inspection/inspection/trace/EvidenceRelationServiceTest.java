package com.uav.inspection.inspection.trace;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.inspection.persistence.EvidenceRelationMapper;
import com.uav.inspection.inspection.persistence.EvidenceRelationMapper.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvidenceRelationServiceTest {
    private static final String TASK="30000000-0000-4000-8000-000000000001",REQUIREMENT="60000000-0000-4000-8000-000000000001";
    private static final String EXECUTION="50000000-0000-4000-8000-000000000001",SUBTASK="40000000-0000-4000-8000-000000000001";
    private static final String VERSION="80000000-0000-4000-8000-000000000001",OTHER_VERSION="80000000-0000-4000-8000-000000000002";
    private static final String OWNER=ResourcePermissionService.OWNER,OPERATOR=ResourcePermissionService.OPERATOR;
    private static final CurrentUser ACTOR=new CurrentUser("10000000-0000-4000-8000-000000000004",OPERATOR,"operator-admin",List.of(Role.OPERATOR_TASK_ADMIN),0,0);
    private static final Instant NOW=Instant.parse("2026-10-25T12:00:00.123Z");
    private final EvidenceRelationMapper mapper=mock(EvidenceRelationMapper.class);
    private final ResourcePermissionService permissions=mock(ResourcePermissionService.class);
    private final RecordingManager manager=new RecordingManager();
    private final EvidenceRelationService service=transactional();

    @BeforeEach void setup() {
        when(mapper.lockTask(TASK)).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return new TaskRow(TASK,OWNER,OPERATOR);
        });
        when(permissions.currentActor(ACTOR,true)).thenReturn(ACTOR);
        when(mapper.now()).thenReturn(NOW);
        when(mapper.countVersions(TASK,null,null)).thenReturn(1L);
        when(mapper.versions(TASK,null,null,20,0)).thenReturn(List.of(version(VERSION,5,true,true)));
    }

    @Test void parentAndCurrentActorPermissionsPrecedeThePageAndAllAggregatesUseOnlyItsVersionIds() {
        List<String> ids=List.of(VERSION);
        when(mapper.countVersions(TASK,null,null)).thenReturn(7L);
        when(mapper.issues(ids)).thenReturn(List.of(new CountRow(VERSION,2)));
        when(mapper.reviews(ids)).thenReturn(List.of(new ReviewRow(VERSION,"review-id","APPROVED","reviewer-id",NOW)));
        when(mapper.anchors(ids)).thenReturn(List.of(new AnchorRow(VERSION,"submission-id","CONFIRMED","a".repeat(64),23L,0)));
        when(mapper.sharing(ids,NOW)).thenReturn(List.of(new SharingRow(VERSION,7,1,2,1,1,2)));
        when(mapper.verifications(ids)).thenReturn(List.of(new CountRow(VERSION,4)));

        var result=service.list(ACTOR,TASK,null,null,1,20);var item=result.items().getFirst();

        assertEquals(7,result.total());assertEquals(1,result.items().size());assertEquals(NOW,result.asOf());
        assertEquals(VERSION,item.versionId());assertTrue(item.currentExecution());assertTrue(item.latestInExecution());
        assertEquals(2,item.issueCount());assertEquals("review-id",item.review().reviewRecordId());
        assertEquals("APPROVED",item.review().decision());assertEquals(NOW,item.review().reviewedAt());
        assertEquals("submission-id",item.anchor().chainSubmissionId());assertEquals(23L,item.anchor().blockNumber());
        assertEquals(0,item.anchor().validationCode());assertEquals(7,item.sharing().grantCount());
        assertEquals(2,item.sharing().activeGrantCount());assertEquals(2,item.sharing().expiredGrantCount());assertEquals(4,item.verificationCount());
        var order=inOrder(mapper,permissions);
        order.verify(mapper).lockTask(TASK);order.verify(permissions).currentActor(ACTOR,true);
        order.verify(permissions).requireTaskHistory(ACTOR,TASK,OWNER,OPERATOR);order.verify(mapper).now();
        order.verify(mapper).countVersions(TASK,null,null);order.verify(mapper).versions(TASK,null,null,20,0);
        order.verify(mapper).issues(ids);order.verify(mapper).reviews(ids);order.verify(mapper).anchors(ids);
        order.verify(mapper).sharing(ids,NOW);order.verify(mapper).verifications(ids);
        verify(mapper,times(1)).now();assertEquals(1,manager.commits);
    }

    @Test void absentRelationsHaveExplicitZeroCountsAndNullReviewOrAnchorWithoutInventingStatuses() {
        var item=service.list(ACTOR,TASK,null,null,1,20).items().getFirst();
        assertEquals(0,item.issueCount());assertNull(item.review());assertNull(item.anchor());assertEquals(0,item.verificationCount());
        assertEquals(0,item.sharing().grantCount());assertEquals(0,item.sharing().activeGrantCount());assertEquals(0,item.sharing().expiredGrantCount());
    }

    @Test void historicalLargerVersionAndCurrentLatestFlagsRemainIndependentAndKeepPageOrdering() {
        when(mapper.countVersions(TASK,null,null)).thenReturn(2L);
        when(mapper.versions(TASK,null,null,20,0)).thenReturn(List.of(version(OTHER_VERSION,6,false,true),version(VERSION,5,true,true)));
        var result=service.list(ACTOR,TASK,null,null,1,20);
        assertEquals(List.of(OTHER_VERSION,VERSION),result.items().stream().map(EvidenceRelationModels.EvidenceRelation::versionId).toList());
        assertEquals(6,result.items().getFirst().versionNo());assertFalse(result.items().getFirst().currentExecution());
        assertEquals(5,result.items().get(1).versionNo());assertTrue(result.items().get(1).currentExecution());
        assertTrue(result.items().stream().allMatch(EvidenceRelationModels.EvidenceRelation::latestInExecution));
        verify(mapper).sharing(List.of(OTHER_VERSION,VERSION),NOW);
    }

    @Test void filtersAndVeryLargePagesUseAProperLongOffsetBeforeAnyRelationshipAggregation() {
        when(mapper.countVersions(TASK,REQUIREMENT,EXECUTION)).thenReturn(50L);
        when(mapper.versions(TASK,REQUIREMENT,EXECUTION,100,214748364600L)).thenReturn(List.of());
        var result=service.list(ACTOR,TASK,REQUIREMENT,EXECUTION,Integer.MAX_VALUE,100);
        assertEquals(50,result.total());assertTrue(result.items().isEmpty());
        verify(mapper).versions(TASK,REQUIREMENT,EXECUTION,100,214748364600L);
        verify(mapper,never()).issues(anyList());verify(mapper,never()).sharing(anyList(),any());
    }

    @Test void anEmptyFilteredScopeSkipsTheVersionPageAndEveryAggregateQuery() {
        when(mapper.countVersions(TASK,REQUIREMENT,EXECUTION)).thenReturn(0L);
        var result=service.list(ACTOR,TASK,REQUIREMENT,EXECUTION,1,20);
        assertEquals(0,result.total());assertTrue(result.items().isEmpty());assertEquals(NOW,result.asOf());
        verify(mapper,never()).versions(anyString(),any(),any(),anyInt(),anyLong());
        verify(mapper,never()).issues(anyList());verify(mapper,never()).reviews(anyList());
        verify(mapper,never()).anchors(anyList());verify(mapper,never()).sharing(anyList(),any());verify(mapper,never()).verifications(anyList());
    }

    @Test void lostTaskHistoryPermissionNeverReachesVersionCountsOrBodiesAndHasNoSharedFallback() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(permissions).requireTaskHistory(ACTOR,TASK,OWNER,OPERATOR);
        assertCode(ErrorCode.FORBIDDEN,()->service.list(ACTOR,TASK,null,null,1,20));
        verify(mapper,never()).now();verify(mapper,never()).countVersions(anyString(),any(),any());
        verify(mapper,never()).versions(anyString(),any(),any(),anyInt(),anyLong());
        verify(permissions,never()).requireVersion(any(),anyString(),anyString(),anyString(),anyString(),anyBoolean());
        assertEquals(1,manager.rollbacks);
    }

    @Test void unknownTaskIsForbiddenAndBadIdsOrPaginationAreRejectedBeforeDatabaseAccess() {
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.list(ACTOR,"bad",null,null,1,20));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.list(ACTOR,TASK,"bad",null,1,20));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.list(ACTOR,TASK,null,"bad",1,20));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.list(ACTOR,TASK,null,null,0,20));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.list(ACTOR,TASK,null,null,1,101));
        verifyNoInteractions(mapper,permissions);
        doReturn(null).when(mapper).lockTask(TASK);
        assertCode(ErrorCode.FORBIDDEN,()->service.list(ACTOR,TASK,null,null,1,20));verifyNoInteractions(permissions);
    }

    @Test void responseSerializationOmitsReasonsApplicantsStorageLocationsAndRawPayloads() {
        when(mapper.reviews(List.of(VERSION))).thenReturn(List.of(new ReviewRow(VERSION,"review-id","RETURNED","reviewer-id",NOW)));
        String json=JsonMapper.builder().build().writeValueAsString(service.list(ACTOR,TASK,null,null,1,20));
        for(String forbidden:List.of("objectKey","requestHash","reason","description","applicantId","payload","transactionBytes","commitBytes"))
            assertFalse(json.contains(forbidden));
        assertTrue(json.contains("filename"));assertTrue(json.contains("issueCount"));assertTrue(json.contains("verificationCount"));
    }

    @Test void mapperDynamicQueriesParseForOptionalFiltersAndOneHundredBoundVersionIds() {
        Configuration configuration=new Configuration();configuration.addMapper(EvidenceRelationMapper.class);
        String prefix=EvidenceRelationMapper.class.getName()+".";
        Map<String,Object> parameters=new HashMap<>();parameters.put("taskId",TASK);parameters.put("requirementId",REQUIREMENT);
        parameters.put("executionId",EXECUTION);parameters.put("limit",100);parameters.put("offset",214748364600L);
        parameters.put("asOf",NOW);parameters.put("versionIds",IntStream.range(0,100).mapToObj(i->VERSION).toList());
        for(String statement:List.of("countVersions","versions","issues","reviews","anchors","sharing","verifications")) {
            var sql=configuration.getMappedStatement(prefix+statement).getBoundSql(parameters);
            assertFalse(sql.getSql().isBlank());
        }
        assertEquals(102,configuration.getMappedStatement(prefix+"sharing").getBoundSql(parameters).getParameterMappings().size());
        parameters.put("requirementId",null);parameters.put("executionId",null);
        assertEquals(3,configuration.getMappedStatement(prefix+"versions").getBoundSql(parameters).getParameterMappings().size());
    }

    private static VersionRow version(String id,int number,boolean current,boolean latest) {
        return new VersionRow(id,REQUIREMENT,"70000000-0000-4000-8000-000000000001",number,SUBTASK,EXECUTION,
                "90000000-0000-4000-8000-000000000001","inspection.png","image/png",79,"a".repeat(64),NOW,current,latest);
    }
    private static void assertCode(ErrorCode code,Runnable action) {
        assertEquals(code,assertThrows(BusinessException.class,action::run).errorCode());
    }
    private EvidenceRelationService transactional() {
        var advice=new TransactionInterceptor();advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy=new ProxyFactory(new EvidenceRelationService(mapper,permissions));proxy.setProxyTargetClass(true);proxy.addAdvice(advice);
        return (EvidenceRelationService)proxy.getProxy();
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
