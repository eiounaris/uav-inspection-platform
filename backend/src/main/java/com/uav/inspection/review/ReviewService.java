package com.uav.inspection.review;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceContext;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.review.ReviewModels.*;
import com.uav.inspection.review.persistence.ReviewMapper;
import com.uav.inspection.review.persistence.ReviewMapper.TaskRow;
import com.uav.inspection.review.persistence.ReviewMapper.VersionRow;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Task locking serializes fixed versions, reassignment, membership changes and review decisions. */
@Service
@Profile("database")
public class ReviewService {
    private final ReviewMapper mapper;
    private final ResourcePermissionService permissions;
    private final AuditMapper audit;

    public ReviewService(ReviewMapper mapper,ResourcePermissionService permissions,AuditMapper audit) {
        this.mapper=mapper; this.permissions=permissions; this.audit=audit;
    }

    @Transactional
    public IssueView createIssue(CurrentUser supplied,CreateIssueRequest request) {
        invalidIf(request==null);
        validateId(request.versionId());
        String description=text(request.description(),4000,false);
        Scope scope=locked(supplied,request.versionId(),ResourcePermissionService.OPERATOR,Role.OPERATOR_USER);
        writable(scope.task());
        String id=UUID.randomUUID().toString();
        one(mapper.insertIssue(new IssueView(id,scope.task().id(),scope.version().id(),description,scope.actor().id(),null)));
        appendAudit(scope.actor(),"ISSUE_CREATED","ISSUE",id);
        IssueView result=mapper.issue(id);
        if(result==null) throw new IllegalStateException("Missing created issue");
        return result;
    }

    @Transactional
    public IssuePage issues(CurrentUser supplied,String versionId,int page,int pageSize) {
        validateId(versionId);
        invalidIf(page<1 || pageSize<1 || pageSize>100);
        locked(supplied,versionId,null,null);
        long total=mapper.countIssues(versionId);
        List<IssueView> items=mapper.issues(versionId,pageSize,((long)page-1)*pageSize);
        return new IssuePage(List.copyOf(items),page,pageSize,total);
    }

    @Transactional
    public ReviewState review(CurrentUser supplied,String versionId,ReviewRequest request) {
        validateId(versionId);
        invalidIf(request==null || !("APPROVED".equals(request.decision()) || "RETURNED".equals(request.decision()))
                || !"PENDING".equals(request.expectedReviewStatus()));
        String reason=text(request.reason(),1000,"APPROVED".equals(request.decision()));
        Scope scope=locked(supplied,versionId,ResourcePermissionService.OWNER,Role.OWNER_REVIEWER);
        writable(scope.task());
        if(!"PENDING".equals(scope.version().reviewStatus())) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        changed(mapper.decide(versionId,scope.task().id(),request.decision(),scope.version().lockVersion()));
        try {
            one(mapper.insertReview(new ReviewView(UUID.randomUUID().toString(),versionId,request.decision(),reason,scope.actor().id(),null)));
        } catch(DuplicateKeyException duplicate) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        if("RETURNED".equals(request.decision()) && "WAITING_ACCEPTANCE".equals(scope.task().status())
                && versionId.equals(mapper.currentVersionId(scope.task().id(),scope.version().subtaskId(),scope.version().requirementId()))) {
            changed(mapper.reopenTask(scope.task().id(),scope.task().lockVersion()));
        }
        appendAudit(scope.actor(),"EVIDENCE_REVIEWED","EVIDENCE_VERSION",versionId);
        ReviewView result=mapper.review(versionId);
        if(result==null) throw new IllegalStateException("Missing review conclusion");
        return new ReviewState(versionId,request.decision(),result);
    }

    @Transactional
    public ReviewState review(CurrentUser supplied,String versionId) {
        validateId(versionId);
        Scope scope=locked(supplied,versionId,null,null);
        return new ReviewState(versionId,scope.version().reviewStatus(),mapper.review(versionId));
    }

    private Scope locked(CurrentUser supplied,String versionId,String requiredOrg,Role requiredRole) {
        String taskId=mapper.taskIdForVersion(versionId);
        if(taskId==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        TaskRow task=mapper.lockTask(taskId);
        if(task==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        CurrentUser actor=permissions.currentActor(supplied,true);
        if(requiredOrg!=null && !requiredOrg.equals(actor.orgId())) throw new BusinessException(ErrorCode.FORBIDDEN);
        permissions.requireTask(actor,task.id(),task.ownerOrgId(),task.operatorOrgId(),requiredRole);
        VersionRow version=mapper.lockVersion(versionId);
        if(version==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        if(!task.id().equals(version.taskId())) throw new IllegalStateException("Review version scope changed");
        return new Scope(actor,task,version);
    }

    private void appendAudit(CurrentUser actor,String action,String type,String id) {
        String trace=TraceContext.currentTraceId();
        if(trace==null || !trace.matches("[A-Za-z0-9_-]{1,64}")) trace=UUID.randomUUID().toString();
        one(audit.insertResourceSuccess(UUID.randomUUID().toString(),actor.id(),actor.orgId(),action,type,id,trace));
    }

    private static void writable(TaskRow task) {
        if("COMPLETED".equals(task.status())) throw new BusinessException(ErrorCode.TASK_COMPLETED);
    }

    private static String text(String value,int maximum,boolean optional) {
        if(value==null && optional) return null;
        invalidIf(value==null || value.length()>maximum || value.codePoints().allMatch(ch->
                Character.isWhitespace(ch) || Character.isSpaceChar(ch) || ch==0x85));
        return value.strip();
    }

    private static void validateId(String id) {
        invalidIf(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }
    private static void invalidIf(boolean condition) { if(condition) throw new BusinessException(ErrorCode.INVALID_ARGUMENT); }
    private static void changed(int rows) { if(rows!=1) throw new BusinessException(ErrorCode.VERSION_CONFLICT); }
    private static void one(int rows) { if(rows!=1) throw new IllegalStateException("Review transaction write failed"); }
    private record Scope(CurrentUser actor,TaskRow task,VersionRow version) {}
}
