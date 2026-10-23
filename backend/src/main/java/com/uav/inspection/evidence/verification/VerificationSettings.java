package com.uav.inspection.evidence.verification;

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
@Profile("database & storage & ledger")
public final class VerificationSettings {
    private final long maxFileSize;
    private final int concurrency;
    private final Duration timeout;
    private final Path tempDirectory;
    private final long minFreeSpace;
    private final Set<String> allowedExtensions;

    public VerificationSettings(Environment environment) {
        try {
            maxFileSize=environment.getProperty("inspection.verification.max-file-size",Long.class,209715200L);
            concurrency=environment.getProperty("inspection.verification.concurrency",Integer.class,2);
            long seconds=environment.getProperty("inspection.verification.timeout-seconds",Long.class,60L);
            minFreeSpace=environment.getProperty("inspection.verification.min-free-space",Long.class,33554432L);
            if(maxFileSize<1 || concurrency<1 || concurrency>8 || seconds<1 || seconds>300 || minFreeSpace<0)
                throw new IllegalArgumentException();
            timeout=Duration.ofSeconds(seconds);
            allowedExtensions=Arrays.stream(environment.getProperty("inspection.verification.allowed-extensions","jpg,jpeg,png,pdf,mp4")
                    .split(",")).map(String::strip).map(value->value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
            if(allowedExtensions.isEmpty() || !Set.of("jpg","jpeg","png","pdf","mp4").containsAll(allowedExtensions))
                throw new IllegalArgumentException();
            tempDirectory=Path.of(environment.getProperty("inspection.verification.temp-directory",".local/uploads/verification"))
                    .toAbsolutePath().normalize();
            Files.createDirectories(tempDirectory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if(!Files.getPosixFilePermissions(tempDirectory).equals(PosixFilePermissions.fromString("rwx------")))
                throw new IllegalArgumentException();
        } catch(IOException | RuntimeException failure) {
            throw new IllegalStateException("Invalid verification limits or unavailable private temporary directory");
        }
    }

    public long maxFileSize(){return maxFileSize;}
    public int concurrency(){return concurrency;}
    public Duration timeout(){return timeout;}
    public Path tempDirectory(){return tempDirectory;}
    public long minFreeSpace(){return minFreeSpace;}
    public Set<String> allowedExtensions(){return allowedExtensions;}
}
