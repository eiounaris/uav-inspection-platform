package com.uav.inspection.evidence.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Profile("storage")
public final class UploadSettings {
    private final long maxFileSize;
    private final int partSize;
    private final Duration sessionTtl;
    private final Path tempDirectory;
    private final Set<String> allowedExtensions;
    private final long minFreeSpace;

    public UploadSettings(Environment environment) {
        try {
            maxFileSize = environment.getRequiredProperty("inspection.upload.max-file-size", Long.class);
            partSize = environment.getRequiredProperty("inspection.upload.part-size", Integer.class);
            long ttl = environment.getRequiredProperty("inspection.upload.session-ttl-seconds", Long.class);
            minFreeSpace = environment.getRequiredProperty("inspection.upload.min-free-space", Long.class);
            if (maxFileSize < 1 || partSize < 8 || ttl < 1 || ttl > 604800 || minFreeSpace < 0
                    || 1 + (maxFileSize - 1) / partSize > 10000) {
                throw new IllegalArgumentException();
            }
            sessionTtl = Duration.ofSeconds(ttl);
            allowedExtensions = Arrays.stream(environment.getRequiredProperty("inspection.upload.allowed-extensions")
                            .split(",")).map(String::strip).map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            if (allowedExtensions.isEmpty() || !Set.of("jpg", "jpeg", "png", "pdf", "mp4")
                    .containsAll(allowedExtensions)) {
                throw new IllegalArgumentException();
            }
            tempDirectory = Path.of(environment.getRequiredProperty("inspection.upload.temp-directory"))
                    .toAbsolutePath().normalize();
            Files.createDirectories(tempDirectory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Invalid upload limits or unavailable private temporary directory");
        }
    }

    public long maxFileSize() { return maxFileSize; }
    public int partSize() { return partSize; }
    public Duration sessionTtl() { return sessionTtl; }
    public Path tempDirectory() { return tempDirectory; }
    public Set<String> allowedExtensions() { return allowedExtensions; }
    public long minFreeSpace() { return minFreeSpace; }
}
