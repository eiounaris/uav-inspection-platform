package com.uav.inspection.evidence.verification;

import java.time.Instant;
import java.util.List;

public final class VerificationModels {
    private VerificationModels() {}

    public record VerificationView(String id,String versionId,String userId,String source,String actualSha256,
                                   String ledgerSha256,Long actualSize,String ledgerTxId,String result,String reason,
                                   Instant verifiedAt) {}
    public record VerificationPage(List<VerificationView> items,int page,int pageSize,long total) {}

    /** Internal outcome; partial reads must never manufacture a completed digest. */
    public record Computed(String actualSha256,String ledgerSha256,Long actualSize,String ledgerTxId,
                           String result,String reason) {
        static Computed fileFailure(String reason) {
            return new Computed(null,null,null,null,"FILE_READ_FAILED",reason);
        }
        static Computed chainFailure(String sha256,long size,String reason) {
            return new Computed(sha256,null,size,null,"CHAIN_QUERY_FAILED",reason);
        }
    }
}
