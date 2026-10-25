package com.uav.inspection.inspection.trace;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.inspection.persistence.EvidenceRelationMapper;
import com.uav.inspection.inspection.persistence.EvidenceRelationMapper.*;
import com.uav.inspection.inspection.trace.EvidenceRelationModels.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A business read: parent locking keeps all relation queries consistent with concurrent task writes. */
@Service
@Profile("database")
public class EvidenceRelationService {
    private static final SharingCounts NO_SHARING=new SharingCounts(0,0,0,0,0,0);
    private final EvidenceRelationMapper mapper;
    private final ResourcePermissionService permissions;

    public EvidenceRelationService(EvidenceRelationMapper mapper,ResourcePermissionService permissions) {
        this.mapper=mapper;this.permissions=permissions;
    }

    @Transactional
    public EvidenceRelationPage list(CurrentUser supplied,String taskId,String requirementId,String executionId,int page,int pageSize) {
        id(taskId);if(requirementId!=null) id(requirementId);if(executionId!=null) id(executionId);
        if(page<1 || pageSize<1 || pageSize>100) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        TaskRow task=mapper.lockTask(taskId);
        if(task==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        CurrentUser actor=permissions.currentActor(supplied,true);
        permissions.requireTaskHistory(actor,task.id(),task.ownerOrgId(),task.operatorOrgId());
        Instant asOf=mapper.now();
        long total=mapper.countVersions(taskId,requirementId,executionId);
        if(total==0) return new EvidenceRelationPage(taskId,List.of(),page,pageSize,0,asOf);
        List<VersionRow> rows=List.copyOf(mapper.versions(taskId,requirementId,executionId,pageSize,((long)page-1)*pageSize));
        if(rows.size()>pageSize) throw new IllegalStateException("Evidence relation page exceeded its bound");
        if(rows.isEmpty()) return new EvidenceRelationPage(taskId,List.of(),page,pageSize,total,asOf);
        List<String> ids=rows.stream().map(VersionRow::versionId).toList();
        if(ids.stream().distinct().count()!=ids.size()) throw new IllegalStateException("Duplicate evidence relation version");
        Map<String,CountRow> issues=index(mapper.issues(ids),CountRow::versionId);
        Map<String,ReviewRow> reviews=index(mapper.reviews(ids),ReviewRow::versionId);
        Map<String,AnchorRow> anchors=index(mapper.anchors(ids),AnchorRow::versionId);
        Map<String,SharingRow> grants=index(mapper.sharing(ids,asOf),SharingRow::versionId);
        Map<String,CountRow> checks=index(mapper.verifications(ids),CountRow::versionId);
        List<EvidenceRelation> items=rows.stream().map(row->{
            ReviewRow review=reviews.get(row.versionId());AnchorRow anchor=anchors.get(row.versionId());SharingRow sharing=grants.get(row.versionId());
            return new EvidenceRelation(row.versionId(),row.requirementId(),row.evidenceId(),row.versionNo(),row.subtaskId(),
                    row.executionId(),row.uploadId(),row.filename(),row.contentType(),row.size(),row.sha256(),row.createdAt(),
                    row.currentExecution(),row.latestInExecution(),count(issues,row.versionId()),
                    review==null?null:new ReviewRelation(review.reviewRecordId(),review.decision(),review.reviewerId(),review.reviewedAt()),
                    anchor==null?null:new AnchorRelation(anchor.chainSubmissionId(),anchor.anchorStatus(),anchor.confirmedTxId(),anchor.blockNumber(),anchor.validationCode()),
                    sharing==null?NO_SHARING:new SharingCounts(sharing.grantCount(),sharing.pendingGrantCount(),sharing.activeGrantCount(),
                            sharing.rejectedGrantCount(),sharing.revokedGrantCount(),sharing.expiredGrantCount()),count(checks,row.versionId()));
        }).toList();
        return new EvidenceRelationPage(taskId,items,page,pageSize,total,asOf);
    }

    private static long count(Map<String,CountRow> values,String id) { return values.containsKey(id)?values.get(id).total():0; }
    private static <T> Map<String,T> index(List<T> values,Function<T,String> id) {
        Map<String,T> result=new HashMap<>();
        for(T value:values) if(result.put(id.apply(value),value)!=null) throw new IllegalStateException("Duplicate evidence relation aggregate");
        return result;
    }
    private static void id(String id) {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
    }
}
