package com.uav.inspection.evidence.storage;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.signer.Presigner;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** S3Presigner handles URL encoding; the per-request signer fixes SigV4 time to database UTC. */
@SuppressWarnings("deprecation")
public final class S3DownloadUrlSigner implements DownloadUrlSigner {
    private static final DateTimeFormatter SIGNED_DATE=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final S3Presigner presigner;
    private final String bucket;

    public S3DownloadUrlSigner(S3Presigner presigner,String bucket) { this.presigner=presigner; this.bucket=bucket; }

    @Override
    public SignedDownload sign(String objectKey,Instant signingInstant,long ttlSeconds) {
        try {
            if(objectKey==null || objectKey.isBlank() || signingInstant==null || signingInstant.getNano()!=0
                    || ttlSeconds<1 || ttlSeconds>300) throw new IllegalArgumentException();
            Instant expiresAt=signingInstant.plusSeconds(ttlSeconds);
            GetObjectRequest request=GetObjectRequest.builder().bucket(bucket).key(objectKey)
                    .overrideConfiguration(config->config.signer(new FixedTimeSigner(signingInstant,expiresAt))).build();
            var signed=presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .getObjectRequest(request).signatureDuration(Duration.ofSeconds(ttlSeconds)).build());
            Map<String,String> query=new HashMap<>();
            for(String pair:signed.url().getQuery().split("&")) {
                String[] parts=pair.split("=",2);
                if(parts.length!=2 || query.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),
                        URLDecoder.decode(parts[1],StandardCharsets.UTF_8))!=null) throw new IllegalStateException();
            }
            // SDK metadata uses its system clock; the actual browser credential is defined by these signed fields.
            Instant actualSignedAt=Instant.from(SIGNED_DATE.parse(query.get("X-Amz-Date")));
            long actualSeconds=Long.parseLong(query.get("X-Amz-Expires"));
            if(!signed.isBrowserExecutable() || !actualSignedAt.equals(signingInstant) || actualSeconds!=ttlSeconds
                    || !actualSignedAt.plusSeconds(actualSeconds).equals(expiresAt)) throw new IllegalStateException();
            return new SignedDownload(signed.url().toExternalForm(),expiresAt);
        } catch(RuntimeException failure) {
            // SDK diagnostics can include the signed URL, credentials or canonical request. Do not propagate them.
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static final class FixedTimeSigner implements Signer,Presigner {
        private final AwsS3V4Signer delegate=AwsS3V4Signer.create();
        private final Instant at,expiry;
        private FixedTimeSigner(Instant at,Instant expiry) { this.at=at; this.expiry=expiry; }
        private ExecutionAttributes attributes(ExecutionAttributes original) {
            return original.copy().putAttribute(AwsSignerExecutionAttribute.SIGNING_CLOCK,Clock.fixed(at,ZoneOffset.UTC))
                    .putAttribute(AwsSignerExecutionAttribute.PRESIGNER_EXPIRATION,expiry);
        }
        @Override public SdkHttpFullRequest sign(SdkHttpFullRequest request,ExecutionAttributes attributes) {
            return delegate.sign(request,attributes(attributes));
        }
        @Override public SdkHttpFullRequest presign(SdkHttpFullRequest request,ExecutionAttributes attributes) {
            return delegate.presign(request,attributes(attributes));
        }
    }
}
