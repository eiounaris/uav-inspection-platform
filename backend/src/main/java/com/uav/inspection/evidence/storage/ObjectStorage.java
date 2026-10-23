package com.uav.inspection.evidence.storage;

import java.io.InputStream;
import java.nio.file.Path;

/** Private objects; callers own and close returned streams. Keys never come from HTTP clients. */
public interface ObjectStorage {
    void put(String objectKey, Path file, String contentType);
    InputStream open(String objectKey);
    void delete(String objectKey);
}
