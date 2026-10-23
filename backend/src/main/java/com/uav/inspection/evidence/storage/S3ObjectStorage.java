package com.uav.inspection.evidence.storage;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.nio.file.Path;

/** The SDK reopens a stable file for retries; application hashing happens before this call. */
public final class S3ObjectStorage implements ObjectStorage {
    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);
    private final S3Client s3;
    private final String bucket;

    public S3ObjectStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String objectKey, Path file, String contentType) {
        try {
            s3.putObject(request -> request.bucket(bucket).key(objectKey).contentType(contentType),
                    RequestBody.fromFile(file));
        } catch (SdkException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return s3.getObject(request -> request.bucket(bucket).key(objectKey));
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            throw unavailable(failure);
        } catch (SdkException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            s3.deleteObject(request -> request.bucket(bucket).key(objectKey));
        } catch (SdkException failure) {
            throw unavailable(failure);
        }
    }

    private static BusinessException unavailable(SdkException failure) {
        // SDK errors can embed Authorization, signed endpoints or request data. Keep only the type.
        log.warn("Object storage operation failed ({})", failure.getClass().getSimpleName());
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
