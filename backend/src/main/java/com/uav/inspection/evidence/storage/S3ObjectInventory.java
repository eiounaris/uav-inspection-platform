package com.uav.inspection.evidence.storage;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Set;

@Component
@Profile("storage & cleanup")
public final class S3ObjectInventory implements ObjectInventory {
    private final S3Client s3;
    private final String bucket;

    public S3ObjectInventory(S3Client s3, Environment environment) {
        this.s3=s3; bucket=environment.getRequiredProperty("inspection.storage.bucket");
    }

    @Override public Page list(String prefix,String continuation,int limit) {
        if(!Set.of("upload-parts/","evidence-files/").contains(prefix) || limit<1 || limit>100)
            throw new IllegalArgumentException("Invalid cleanup inventory scope");
        try {
            var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
                    .continuationToken(continuation).maxKeys(limit).build());
            return new Page(page.contents().stream().map(o->new Entry(o.key(),o.lastModified(),o.size())).toList(),
                    Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null);
        } catch(SdkException unavailable) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE); }
    }

    @Override public Entry head(String key) {
        try {
            var value=s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return new Entry(key,value.lastModified(),value.contentLength());
        } catch(S3Exception unavailable) {
            if(unavailable.statusCode()==404) return null;
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch(SdkException unavailable) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
}
