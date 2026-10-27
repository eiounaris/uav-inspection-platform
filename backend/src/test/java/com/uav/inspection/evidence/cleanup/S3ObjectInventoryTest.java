package com.uav.inspection.evidence.cleanup;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.evidence.storage.S3ObjectInventory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3ObjectInventoryTest {
    private final S3Client s3=mock(S3Client.class);
    private final S3ObjectInventory inventory=new S3ObjectInventory(s3,
            new MockEnvironment().withProperty("inspection.storage.bucket","private-bucket"));

    @Test void listUsesOnlyKnownPrefixesAndExplicitBoundedContinuation() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("metadata-only").lastModified(Instant.EPOCH).size(7L).build())
                .isTruncated(true).nextContinuationToken("opaque-next").build());
        var page=inventory.list("upload-parts/","opaque-current",10);
        var request=ArgumentCaptor.forClass(ListObjectsV2Request.class);verify(s3).listObjectsV2(request.capture());
        assertEquals("private-bucket",request.getValue().bucket());assertEquals(10,request.getValue().maxKeys());
        assertEquals("opaque-current",request.getValue().continuationToken());assertEquals("opaque-next",page.continuation());
        assertEquals(7,page.entries().getFirst().size());
        assertThrows(IllegalArgumentException.class,()->inventory.list("",null,100));
        assertThrows(IllegalArgumentException.class,()->inventory.list("evidence-files/",null,101));
    }

    @Test void missingHeadIsIdempotentWhileOtherProviderDetailsAreDiscarded() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).message("private").build());
        assertNull(inventory.head("known-key"));
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(503).message("private").build());
        var failure=assertThrows(BusinessException.class,()->inventory.head("known-key"));assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private"));
    }

    @Test void headReturnsActualModificationTimeAndSize() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .lastModified(Instant.EPOCH).contentLength(17L).build());
        var entry=inventory.head("known-key");assertEquals(Instant.EPOCH,entry.lastModified());assertEquals(17,entry.size());
    }
}
