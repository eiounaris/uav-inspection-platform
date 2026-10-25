package com.uav.inspection.evidence.storage;

import java.time.Instant;

/** Local signing only. Implementations must not resolve credentials or contact storage over the network. */
public interface DownloadUrlSigner {
    SignedDownload sign(String objectKey,Instant signingInstant,long ttlSeconds);

    record SignedDownload(String url,Instant expiresAt) {
        @Override public String toString() { return "SignedDownload[expiresAt="+expiresAt+"]"; }
    }
}
