package com.uav.inspection.evidence.upload;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.evidence.storage.UploadSettings;
import com.uav.inspection.evidence.upload.UploadModels.CreateUploadRequest;
import com.uav.inspection.evidence.upload.UploadModels.PartCommit;
import com.uav.inspection.evidence.upload.UploadModels.PartPlan;
import com.uav.inspection.evidence.upload.UploadModels.PartResult;
import com.uav.inspection.evidence.upload.UploadModels.UploadProgress;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.device.CurrentDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Orchestrates streams and object storage outside the short transactions of UploadTransactions. */
@Service
@Profile("database & storage")
public class UploadService {
    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private final UploadSettings settings;
    private final ObjectStorage storage;
    private final UploadTransactions transactions;

    public UploadService(UploadSettings settings, ObjectStorage storage, UploadTransactions transactions) {
        this.settings = settings;
        this.storage = storage;
        this.transactions = transactions;
    }

    public UploadProgress create(CurrentUser actor, CreateUploadRequest request) {
        UploadValidation.request(request, settings);
        return transactions.create(actor, request, UploadValidation.requestHash(request));
    }

    public UploadProgress get(CurrentUser actor, String uploadId) {
        UploadValidation.id(uploadId);
        return transactions.get(actor, uploadId);
    }

    public PartResult uploadPart(CurrentUser actor, String uploadId, int partNo,
                                 InputStream input, long contentLength) {
        return uploadPart(uploadId, partNo, input, contentLength, new PartAccess() {
            public PartPlan prepare() { return transactions.preparePart(actor, uploadId, partNo); }
            public PartResult known(long size, String hash) { return transactions.findIdenticalPart(actor, uploadId, partNo, size, hash); }
            public PartCommit commit(long size, String hash, String key) { return transactions.storePart(actor, uploadId, partNo, size, hash, key); }
        });
    }

    public UploadProgress createDevice(CurrentDevice actor, CreateUploadRequest request) {
        UploadValidation.request(request, settings);
        return transactions.createDevice(actor, request, UploadValidation.requestHash(request));
    }

    public UploadProgress getDevice(CurrentDevice actor, String uploadId) {
        UploadValidation.id(uploadId);
        return transactions.getDevice(actor, uploadId);
    }

    public PartResult uploadDevicePart(CurrentDevice actor, String uploadId, int partNo, InputStream input, long contentLength) {
        return uploadPart(uploadId, partNo, input, contentLength, new PartAccess() {
            public PartPlan prepare() { return transactions.preparePartDevice(actor, uploadId, partNo); }
            public PartResult known(long size, String hash) { return transactions.findIdenticalPartDevice(actor, uploadId, partNo, size, hash); }
            public PartCommit commit(long size, String hash, String key) { return transactions.storePartDevice(actor, uploadId, partNo, size, hash, key); }
        });
    }

    private PartResult uploadPart(String uploadId, int partNo, InputStream input, long contentLength, PartAccess access) {
        UploadValidation.id(uploadId);
        UploadValidation.invalid(partNo < 1 || input == null);
        PartPlan plan = access.prepare();
        if (contentLength >= 0) {
            UploadValidation.exactLength(contentLength, plan.expectedSize());
        }
        Path temporary = null;
        try {
            temporary = temporaryFile(plan.expectedSize());
            SpooledPart part = spool(input, temporary, plan);
            PartResult known = access.known(part.size(), part.sha256());
            if (known != null) {
                return known;
            }
            String candidate = "upload-parts/" + uploadId + "/" + partNo + "/" + UUID.randomUUID();
            storage.put(candidate, temporary, plan.contentType());

            // A thrown final phase may mean the commit outcome is unknown. Its candidate must remain
            // available for reference-aware recovery/collection; only a normal return can prove a loser.
            PartCommit committed = access.commit(part.size(), part.sha256(), candidate);
            if (committed == null || committed.result() == null || committed.storedObjectKey() == null
                    || committed.storedObjectKey().isBlank()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            if (!candidate.equals(committed.storedObjectKey())) {
                deleteLosingObject(candidate);
            }
            PartResult result = committed.result();
            if (result.size() != part.size() || !result.sha256().equals(part.sha256())) {
                throw new BusinessException(ErrorCode.PART_CONTENT_CONFLICT);
            }
            return result;
        } catch (IOException exception) {
            // I/O details may include local paths. HTTP errors use only the stable public error code.
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } finally {
            deleteTemporaryFile(temporary);
        }
    }

    private Path temporaryFile(long expectedSize) throws IOException {
        Path directory = settings.tempDirectory();
        Files.createDirectories(directory);
        long usable = Files.getFileStore(directory).getUsableSpace();
        if (usable < settings.minFreeSpace() || expectedSize > usable - settings.minFreeSpace()) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return Files.createTempFile(directory, "upload-", ".part",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    private static SpooledPart spool(InputStream input, Path target, PartPlan plan) throws IOException {
        MessageDigest digest = UploadValidation.digest();
        byte[] buffer = new byte[64 * 1024];
        byte[] header = new byte[16];
        int headerLength = 0;
        long size = 0;
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            while (true) {
                // Reading at most one excess byte bounds disk use even for a stream without Content-Length.
                int limit = (int) Math.min(buffer.length, plan.expectedSize() - size + 1);
                int read = input.read(buffer, 0, limit);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    int single = input.read();
                    if (single < 0) {
                        break;
                    }
                    buffer[0] = (byte) single;
                    read = 1;
                }
                if (read > plan.expectedSize() - size) {
                    throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
                }
                int headerBytes = Math.min(read, header.length - headerLength);
                System.arraycopy(buffer, 0, header, headerLength, headerBytes);
                headerLength += headerBytes;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                size += read;
            }
        }
        UploadValidation.exactLength(size, plan.expectedSize());
        if (plan.partNo() == 1) {
            UploadValidation.magic(plan.contentType(), header, headerLength);
        }
        return new SpooledPart(size, HexFormat.of().formatHex(digest.digest()));
    }

    private void deleteLosingObject(String candidate) {
        try {
            storage.delete(candidate);
        } catch (RuntimeException exception) {
            // Cleanup can be retried by the later reference-aware collector. Do not expose keys or SDK errors.
            log.warn("An unused upload attempt could not be removed; deferred collection is required.");
        }
    }

    private static void deleteTemporaryFile(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException exception) {
            log.warn("An upload temporary file could not be removed.");
        }
    }

    private interface PartAccess {
        PartPlan prepare();
        PartResult known(long size, String hash);
        PartCommit commit(long size, String hash, String key);
    }

    private record SpooledPart(long size, String sha256) { }
}
