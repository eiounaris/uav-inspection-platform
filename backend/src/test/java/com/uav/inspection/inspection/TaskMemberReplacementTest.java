package com.uav.inspection.inspection;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.inspection.TaskModels.*;
import com.uav.inspection.inspection.persistence.TaskMapper;
import com.uav.inspection.inspection.persistence.TaskMapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Account locks needed by membership FK writes must precede the actor's final authorization lock. */
class TaskMemberReplacementTest {
    private static final String OWNER=ResourcePermissionService.OWNER,OPERATOR=ResourcePermissionService.OPERATOR;
    private static final String TASK=id(100),CREATOR=id(1),ACTOR=id(2),OLD=id(3),FOREIGN=id(4),NEW=id(5);
    private static final Instant NOW=Instant.parse("2026-10-27T00:00:00Z");
    private static final CurrentUser ADMIN=new CurrentUser(ACTOR,OWNER,"owner-admin",List.of(Role.OWNER_TASK_ADMIN),0,0);
    private final TaskMapper tasks=mock(TaskMapper.class);
    private final ResourcePermissionService permissions=mock(ResourcePermissionService.class);
    private final AuditMapper audit=mock(AuditMapper.class);
    private final Transactions transactions=new Transactions();
    private final TaskService service=service();

    @BeforeEach void configure(){
        when(tasks.lockTask(TASK)).thenReturn(task(7));when(tasks.findTask(TASK)).thenReturn(task(8));
        when(permissions.currentActor(ADMIN,false)).thenReturn(ADMIN);when(permissions.currentActor(ADMIN,true)).thenReturn(ADMIN);
        when(tasks.listMembers(TASK)).thenReturn(List.of(member(FOREIGN,OPERATOR),member(OLD,OWNER),member(ACTOR,OWNER),member(CREATOR,OWNER)));
        for(String user:List.of(CREATOR,ACTOR,OLD,NEW)){
            when(tasks.findCandidate(user)).thenReturn(candidate(user,OWNER,true));
            when(tasks.lockCandidate(user)).thenReturn(candidate(user,OWNER,true));
        }
        when(tasks.deleteManagedMember(eq(TASK),anyString(),eq(CREATOR))).thenReturn(1);
        when(tasks.addMember(eq(TASK),anyString())).thenReturn(1);when(tasks.touchTask(TASK,7)).thenReturn(1);
        when(tasks.listSubtasks(TASK)).thenReturn(List.of());when(tasks.listRequirements(TASK)).thenReturn(List.of());
        when(audit.insertResourceSuccess(anyString(),eq(ACTOR),eq(OWNER),eq("TASK_MEMBERS_UPDATED"),eq("INSPECTION_TASK"),eq(TASK),anyString())).thenReturn(1);
    }

    @Test void relatedAccountsAreSortedBeforeFinalAuthorizationAndOnlyOwnNonCreatorMembershipsAreReplaced(){
        TaskDetail result=service.replaceMembers(ADMIN,TASK,new ReplaceMembersRequest(List.of(NEW,ACTOR),7L));
        assertEquals("COMPLETED",result.status());assertEquals(NOW,result.completedAt());
        var order=inOrder(tasks,permissions,audit);
        order.verify(tasks).lockTask(TASK);order.verify(permissions).currentActor(ADMIN,false);
        order.verify(tasks).listMembers(TASK);order.verify(tasks).findCandidate(NEW);order.verify(tasks).findCandidate(ACTOR);
        for(String user:List.of(CREATOR,ACTOR,OLD,NEW))order.verify(tasks).lockCandidate(user);
        order.verify(permissions).currentActor(ADMIN,true);
        order.verify(permissions).requireTask(ADMIN,TASK,OWNER,OPERATOR,Role.OWNER_TASK_ADMIN);
        order.verify(tasks).deleteManagedMember(TASK,ACTOR,CREATOR);order.verify(tasks).deleteManagedMember(TASK,OLD,CREATOR);
        order.verify(tasks).addMember(TASK,ACTOR);order.verify(tasks).addMember(TASK,NEW);
        order.verify(tasks).touchTask(TASK,7);
        verify(tasks,never()).lockCandidate(FOREIGN);verify(tasks,never()).deleteManagedMember(TASK,CREATOR,CREATOR);
        verify(tasks,never()).deleteManagedMember(TASK,FOREIGN,CREATOR);
        assertEquals(1,transactions.commits);assertEquals(0,transactions.rollbacks);
    }

    @Test void aTokenInvalidatedAfterThePreviewCannotCommitMembershipChanges(){
        when(permissions.currentActor(ADMIN,true)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        assertCode(ErrorCode.UNAUTHORIZED,()->service.replaceMembers(ADMIN,TASK,new ReplaceMembersRequest(List.of(NEW),7L)));
        verify(tasks).lockCandidate(NEW);verifyNoWrites();assertEquals(1,transactions.rollbacks);
    }

    @Test void revokedManagerRoleIsCheckedAgainAfterAllRelatedAccountLocks(){
        CurrentUser demoted=new CurrentUser(ACTOR,OWNER,"owner-admin",List.of(Role.OWNER_REVIEWER),0,1);
        when(permissions.currentActor(ADMIN,true)).thenReturn(demoted);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(permissions).requireTask(demoted,TASK,OWNER,OPERATOR,Role.OWNER_TASK_ADMIN);
        assertCode(ErrorCode.FORBIDDEN,()->service.replaceMembers(ADMIN,TASK,new ReplaceMembersRequest(List.of(NEW),7L)));
        verify(tasks).lockCandidate(NEW);verifyNoWrites();assertEquals(1,transactions.rollbacks);
    }

    @Test void candidateDisabledDuringThePreviewIsRejectedUsingItsLockedState(){
        when(tasks.lockCandidate(NEW)).thenReturn(candidate(NEW,OWNER,false));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.replaceMembers(ADMIN,TASK,new ReplaceMembersRequest(List.of(NEW),7L)));
        verify(permissions).currentActor(ADMIN,true);verifyNoWrites();assertEquals(1,transactions.rollbacks);
    }

    @Test void aForeignOrganizationCandidateNeverEntersTheAccountLockSet(){
        when(tasks.findCandidate(FOREIGN)).thenReturn(candidate(FOREIGN,OPERATOR,true));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.replaceMembers(ADMIN,TASK,new ReplaceMembersRequest(List.of(FOREIGN),7L)));
        verify(tasks,never()).lockCandidate(anyString());verifyNoWrites();assertEquals(1,transactions.rollbacks);
    }

    private void verifyNoWrites(){verify(tasks,never()).deleteManagedMember(anyString(),anyString(),anyString());verify(tasks,never()).addMember(anyString(),anyString());verify(tasks,never()).touchTask(anyString(),anyLong());verifyNoInteractions(audit);}
    private static void assertCode(ErrorCode code,Runnable action){assertEquals(code,assertThrows(BusinessException.class,action::run).errorCode());}
    private static CandidateRow candidate(String id,String org,boolean enabled){return new CandidateRow(id,org,"candidate","[\"OWNER_TASK_ADMIN\"]",enabled);}
    private static MemberRow member(String id,String org){return new MemberRow(id,org,"member",OWNER.equals(org)?"[\"OWNER_TASK_ADMIN\"]":"[\"OPERATOR_TASK_ADMIN\"]");}
    private static TaskSummary task(long version){return new TaskSummary(TASK,OWNER,OPERATOR,"Inspection","North","COMPLETED",CREATOR,version,NOW,NOW,NOW);}
    private static String id(int n){return "10000000-0000-4000-8000-"+String.format("%012d",n);}
    private TaskService service(){var proxy=new ProxyFactory(new TaskService(tasks,permissions,audit,Clock.systemUTC()));proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));return (TaskService)proxy.getProxy();}
    private static final class Transactions extends AbstractPlatformTransactionManager{
        int commits,rollbacks;protected Object doGetTransaction(){return new Object();}protected void doBegin(Object tx,TransactionDefinition def){}protected void doCommit(DefaultTransactionStatus status){commits++;}protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
