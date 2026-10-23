package com.uav.inspection.evidence.upload;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.evidence.storage.UploadSettings;
import com.uav.inspection.evidence.upload.UploadModels.PartCommit;
import com.uav.inspection.evidence.upload.UploadModels.PartPlan;
import com.uav.inspection.evidence.upload.UploadModels.PartResult;
import com.uav.inspection.evidence.upload.persistence.UploadMapper;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.ExecutionRow;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.RequirementRow;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.SessionRow;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.SubtaskRow;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.TaskRow;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Cross-system boundaries use real file I/O and Spring transaction advice, without network or a database. */
class UploadServiceTest {
    private static final String OWNER = ResourcePermissionService.OWNER;
    private static final String OPERATOR = ResourcePermissionService.OPERATOR;
    private static final String TASK = "30000000-0000-4000-8000-000000000001";
    private static final String SUBTASK = "40000000-0000-4000-8000-000000000001";
    private static final String EXECUTION = "50000000-0000-4000-8000-000000000001";
    private static final String REQUIREMENT = "60000000-0000-4000-8000-000000000001";
    private static final String EVIDENCE = "70000000-0000-4000-8000-000000000001";
    private static final String UPLOAD = "80000000-0000-4000-8000-000000000001";
    private static final String CLIENT_REQUEST = "90000000-0000-4000-8000-000000000001";
    private static final String WINNER_KEY = "upload-parts/" + UPLOAD
            + "/1/90000000-0000-4000-8000-000000000002";
    private static final Instant NOW = Instant.parse("2026-10-21T15:00:00.123Z");
    private static final CurrentUser ACTOR = new CurrentUser("10000000-0000-4000-8000-000000000005",
            OPERATOR, "operator-user", List.of(Role.OPERATOR_USER), 0, 0);

    @TempDir
    Path directory;
    private Path temporaryDirectory;
    private UploadSettings settings;
    private byte[] image;
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final UploadTransactions phases = mock(UploadTransactions.class);
    private final UploadMapper uploads = mock(UploadMapper.class);
    private final ResourcePermissionService permissions = mock(ResourcePermissionService.class);
    private final AuditMapper audit = mock(AuditMapper.class);
    private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();

    @BeforeEach
    void configurePrivateTemporaryFiles() throws IOException {
        temporaryDirectory = directory.resolve("parts");
        settings = new UploadSettings(new MockEnvironment()
                .withProperty("inspection.upload.max-file-size", "1048576")
                .withProperty("inspection.upload.part-size", "1048576")
                .withProperty("inspection.upload.session-ttl-seconds", "3600")
                .withProperty("inspection.upload.min-free-space", "0")
                .withProperty("inspection.upload.allowed-extensions", "png")
                .withProperty("inspection.upload.temp-directory", temporaryDirectory.toString()));
        try (var output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output));
            image = output.toByteArray();
        }
    }

    @Test
    void objectStorageRunsOutsideDatabaseTransactionsAndTheTemporaryFileIsRemoved() throws IOException {
        prepareMockPhases();
        AtomicReference<Path> savedFile = new AtomicReference<>();
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Path file = invocation.getArgument(1);
            assertArrayEquals(image, Files.readAllBytes(file));
            savedFile.set(file);
            return null;
        }).when(storage).put(anyString(), any(Path.class), eq("image/png"));
        when(phases.storePart(eq(ACTOR), eq(UPLOAD), eq(1), eq((long) image.length), anyString(), anyString()))
                .thenAnswer(invocation -> new PartCommit(result(invocation.getArgument(4)), invocation.getArgument(5)));

        PartResult result = orchestrator().uploadPart(ACTOR, UPLOAD, 1, body(), image.length);

        assertEquals(image.length, result.size());
        assertTrue(result.sha256().matches("[0-9a-f]{64}"));
        assertFalse(Files.exists(savedFile.get()));
        verify(storage, never()).delete(anyString());
        assertTemporaryDirectoryEmpty();
    }

    @Test
    void unknownFinalCommitOutcomePreservesTheUploadedCandidateAndStillRemovesTheTemporaryFile() throws IOException {
        prepareMockPhases();
        AtomicReference<String> candidate = new AtomicReference<>();
        doAnswer(invocation -> {
            candidate.set(invocation.getArgument(0));
            return null;
        }).when(storage).put(anyString(), any(Path.class), anyString());
        var commitFailure = new TransactionSystemException("Simulated unknown database commit outcome");
        when(phases.storePart(eq(ACTOR), eq(UPLOAD), eq(1), eq((long) image.length), anyString(), anyString()))
                .thenThrow(commitFailure);

        var failure = assertThrows(TransactionSystemException.class,
                () -> orchestrator().uploadPart(ACTOR, UPLOAD, 1, body(), image.length));

        assertSame(commitFailure, failure);
        assertTrue(candidate.get().startsWith("upload-parts/" + UPLOAD + "/1/"));
        verify(storage, never()).delete(anyString());
        assertTemporaryDirectoryEmpty();
    }

    @Test
    void aKnownDuplicateWinnerDeletesOnlyThisAttemptsLosingObject() throws IOException {
        prepareMockPhases();
        AtomicReference<String> candidate = new AtomicReference<>();
        doAnswer(invocation -> {
            candidate.set(invocation.getArgument(0));
            return null;
        }).when(storage).put(anyString(), any(Path.class), anyString());
        when(phases.storePart(eq(ACTOR), eq(UPLOAD), eq(1), eq((long) image.length), anyString(), anyString()))
                .thenAnswer(invocation -> new PartCommit(result(invocation.getArgument(4)), WINNER_KEY));

        PartResult result = orchestrator().uploadPart(ACTOR, UPLOAD, 1, body(), image.length);

        assertEquals(UPLOAD, result.uploadId());
        assertFalse(WINNER_KEY.equals(candidate.get()));
        verify(storage).delete(candidate.get());
        verify(storage, times(1)).delete(anyString());
        verify(storage, never()).delete(WINNER_KEY);
        assertTemporaryDirectoryEmpty();
    }

    @Test
    void interruptedBodyReadCleansItsTemporaryFileBeforeAnyStorageWrite() throws IOException {
        prepareMockPhases();
        try (InputStream interrupted = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Simulated disconnected request body");
            }
        }) {
            assertThrows(BusinessException.class,
                    () -> orchestrator().uploadPart(ACTOR, UPLOAD, 1, interrupted, image.length));
        }

        verifyNoInteractions(storage);
        verify(phases, never()).storePart(any(), anyString(), anyInt(), anyLong(), anyString(), anyString());
        assertTemporaryDirectoryEmpty();
    }

    @Test
    void finalRegistrationRechecksAFreezeThatHappenedAfterPreflight() {
        UploadTransactions actual = actualTransactionalPhases();
        actual.preparePart(ACTOR, UPLOAD, 1);
        when(uploads.lockSession(UPLOAD)).thenReturn(session("VERIFYING"));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> actual.storePart(ACTOR, UPLOAD, 1, image.length, "a".repeat(64), WINNER_KEY));

        assertEquals(ErrorCode.UPLOAD_NOT_WRITABLE, failure.errorCode());
        verify(uploads, never()).insertPart(any());
        verify(uploads, never()).touchWritableSession(anyString(), anyLong());
        verifyNoInteractions(audit);
        assertPreflightCommittedAndRegistrationRolledBack();
    }

    @Test
    void finalRegistrationRechecksPermissionsRevokedAfterPreflight() {
        UploadTransactions actual = actualTransactionalPhases();
        actual.preparePart(ACTOR, UPLOAD, 1);
        var revoked = new CurrentUser(ACTOR.id(), OPERATOR, ACTOR.username(),
                List.of(Role.OPERATOR_TASK_ADMIN), ACTOR.tokenVersion(), 1);
        when(permissions.currentActor(ACTOR, true)).thenReturn(revoked);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> actual.storePart(ACTOR, UPLOAD, 1, image.length, "a".repeat(64), WINNER_KEY));

        assertEquals(ErrorCode.FORBIDDEN, failure.errorCode());
        verify(permissions, times(2)).currentActor(ACTOR, true);
        verify(uploads, never()).insertPart(any());
        verify(uploads, never()).touchWritableSession(anyString(), anyLong());
        verifyNoInteractions(audit);
        assertPreflightCommittedAndRegistrationRolledBack();
    }

    private void prepareMockPhases() {
        when(phases.preparePart(ACTOR, UPLOAD, 1))
                .thenReturn(new PartPlan(UPLOAD, 1, image.length, "image/png"));
    }

    private UploadService orchestrator() {
        // Keep annotation advice around the orchestrator too: adding @Transactional must fail the S3 assertion.
        return proxy(new UploadService(settings, storage, phases));
    }

    private UploadTransactions actualTransactionalPhases() {
        when(uploads.findSession(UPLOAD)).thenReturn(session("UPLOADING"));
        when(uploads.lockSession(UPLOAD)).thenReturn(session("UPLOADING"));
        var requirement = new RequirementRow(REQUIREMENT, EVIDENCE, TASK, SUBTASK);
        when(uploads.findRequirement(REQUIREMENT)).thenReturn(requirement);
        when(uploads.lockRequirement(REQUIREMENT)).thenReturn(requirement);
        when(uploads.lockTask(TASK)).thenReturn(new TaskRow(TASK, OWNER, OPERATOR, "RUNNING"));
        when(permissions.currentActor(ACTOR, true)).thenReturn(ACTOR);
        when(uploads.lockSubtask(SUBTASK)).thenReturn(new SubtaskRow(SUBTASK, TASK, EXECUTION));
        var execution = new ExecutionRow(EXECUTION, SUBTASK, "RUNNING");
        when(uploads.findExecution(EXECUTION)).thenReturn(execution);
        when(uploads.lockExecution(EXECUTION)).thenReturn(execution);
        when(uploads.databaseNow()).thenReturn(NOW);
        return proxy(new UploadTransactions(uploads, permissions, audit, settings));
    }

    private SessionRow session(String status) {
        return new SessionRow(UPLOAD, TASK, SUBTASK, REQUIREMENT, EVIDENCE, EXECUTION, OPERATOR,
                "USER", ACTOR.id(), CLIENT_REQUEST, "b".repeat(64), "test.png", "image/png",
                image.length, 1048576, 1, status, NOW.plusSeconds(3600), 0, NOW, NOW);
    }

    private PartResult result(String sha256) {
        return new PartResult(UPLOAD, 1, image.length, sha256);
    }

    private InputStream body() {
        return new ByteArrayInputStream(image);
    }

    private void assertTemporaryDirectoryEmpty() throws IOException {
        try (var paths = Files.list(temporaryDirectory)) {
            assertEquals(0, paths.count());
        }
    }

    private void assertPreflightCommittedAndRegistrationRolledBack() {
        assertEquals(2, transactionManager.begins);
        assertEquals(1, transactionManager.commits);
        assertEquals(1, transactionManager.rollbacks);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int begins;
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() { return new Object(); }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { begins++; }

        @Override
        protected void doCommit(DefaultTransactionStatus status) { commits++; }

        @Override
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
