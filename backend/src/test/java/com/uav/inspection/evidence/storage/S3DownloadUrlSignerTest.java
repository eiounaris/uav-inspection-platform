package com.uav.inspection.evidence.storage;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3DownloadUrlSignerTest {
    private static final Instant DATABASE_TIME=Instant.parse("2026-10-25T00:00:00Z");
    private static final String KEY="evidence-files/原始 文件/version";

    @Test
    void actualSdkUsesDatabaseTimeAndAnExplicitDurationWithoutAnyStorageRequest() {
        try(S3Presigner sdk=localPresigner()) {
            var signer=new S3DownloadUrlSigner(sdk,"private-evidence");
            for(long seconds:new long[]{1,17,300}) {
                var result=signer.sign(KEY,DATABASE_TIME,seconds);
                URI uri=URI.create(result.url()); Map<String,String> query=query(uri);
                assertEquals("http",uri.getScheme()); assertEquals("127.0.0.1",uri.getHost());
                assertEquals(9000,uri.getPort()); assertEquals("/private-evidence/"+KEY,uri.getPath());
                assertEquals("20261025T000000Z",query.get("X-Amz-Date"));
                assertEquals(Long.toString(seconds),query.get("X-Amz-Expires"));
                assertEquals("host",query.get("X-Amz-SignedHeaders"));
                assertEquals(DATABASE_TIME.plusSeconds(seconds),result.expiresAt());
                assertFalse(result.toString().contains("Signature")); assertFalse(result.toString().contains(KEY));
            }
        }
    }

    @Test
    void invalidBoundsOrNonSecondSigningTimeNeverReachTheSdk() {
        S3Presigner sdk=mock(S3Presigner.class);
        var signer=new S3DownloadUrlSigner(sdk,"private-evidence");
        for(long seconds:new long[]{0,-1,301}) assertUnavailable(()->signer.sign(KEY,DATABASE_TIME,seconds));
        assertUnavailable(()->signer.sign(KEY,DATABASE_TIME.plusMillis(1),20));
        verifyNoInteractions(sdk);
    }

    @Test
    void sdkFailureCannotExposeSignedUrlOrCredentialDiagnostics() {
        S3Presigner sdk=mock(S3Presigner.class);
        when(sdk.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(SdkClientException.create("synthetic-private-secret https://storage/path?X-Amz-Signature=secret"));
        BusinessException error=assertThrows(BusinessException.class,
                ()->new S3DownloadUrlSigner(sdk,"private-evidence").sign(KEY,DATABASE_TIME,30));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());
        assertFalse(error.toString().contains("synthetic-private-secret")); assertNull(error.getCause());
    }

    private static void assertUnavailable(Runnable action) {
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(BusinessException.class,action::run).errorCode());
    }
    private static S3Presigner localPresigner() {
        return S3Presigner.builder().endpointOverride(URI.create("http://127.0.0.1:9000"))
                .region(Region.of("garage")).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("synthetic-access-key","synthetic-private-secret")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                        .checksumValidationEnabled(false).build()).build();
    }
    private static Map<String,String> query(URI uri) {
        Map<String,String> result=new HashMap<>();
        for(String pair:uri.getRawQuery().split("&")) {
            String[] pieces=pair.split("=",2);
            result.put(URLDecoder.decode(pieces[0],StandardCharsets.UTF_8),URLDecoder.decode(pieces[1],StandardCharsets.UTF_8));
        }
        return result;
    }
}
