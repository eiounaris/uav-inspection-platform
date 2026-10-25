package com.uav.inspection.inspection.trace;

import java.time.Instant;
import java.util.List;

/** Safe relationships for a bounded task-history page; descriptions, reasons and storage keys are excluded. */
public final class EvidenceRelationModels {
    private EvidenceRelationModels() {}

    public record EvidenceRelationPage(String taskId,List<EvidenceRelation> items,int page,int pageSize,long total,Instant asOf) {}
    public record EvidenceRelation(String versionId,String requirementId,String evidenceId,int versionNo,String subtaskId,
                                   String executionId,String uploadId,String filename,String contentType,long size,String sha256,
                                   Instant createdAt,boolean currentExecution,boolean latestInExecution,long issueCount,
                                   ReviewRelation review,AnchorRelation anchor,SharingCounts sharing,long verificationCount) {}
    public record ReviewRelation(String reviewRecordId,String decision,String reviewerId,Instant reviewedAt) {}
    public record AnchorRelation(String chainSubmissionId,String anchorStatus,String confirmedTxId,Long blockNumber,Integer validationCode) {}
    public record SharingCounts(long grantCount,long pendingGrantCount,long activeGrantCount,long rejectedGrantCount,
                                long revokedGrantCount,long expiredGrantCount) {}
}
