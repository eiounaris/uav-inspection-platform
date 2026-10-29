package com.uav.inspection.blockchain.probe;

import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.EvidenceLedgerClient.CommitCheckpoint;
import com.uav.inspection.blockchain.EvidenceLedgerClient.CommitOutcome;
import com.uav.inspection.blockchain.EvidenceLedgerClient.EvidenceQuery;
import com.uav.inspection.blockchain.EvidenceLedgerClient.EvidenceRecord;
import com.uav.inspection.blockchain.EvidenceLedgerClient.PreparedRegistration;
import com.uav.inspection.blockchain.FabricGatewaySettings;
import com.uav.inspection.blockchain.GatewayEvidenceLedgerClient;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.GatewayException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit test-scope CLI; never registered as a Spring bean or an HTTP endpoint. */
public final class FabricGatewayProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> PHASES = Set.of("prepare", "submit", "confirm", "verify");
    private static final String CONFLICT_MESSAGE = "chaincode response 500, "
            + "A different file hash is already registered for this evidence version.";
    private static final String FORBIDDEN_MESSAGE = "chaincode response 500, "
            + "The calling organization is not permitted to perform this operation.";
    private static final int VERSION = 1;

    private final Path directory;
    private final String phase;
    private final long pid = ProcessHandle.current().pid();
    private final Map<String, Object> run;

    private FabricGatewayProbe(String phase, Path directory) throws IOException {
        this.phase = phase;
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
        Path state = this.directory.resolve("run.json");
        this.run = Files.exists(state) ? readMap(state) : new LinkedHashMap<>();
    }

    public static void main(String[] args) {
        if (args.length != 2 || !PHASES.contains(args[0])) {
            System.err.println("Usage: FabricGatewayProbe <prepare|submit|confirm|verify> <runDirectory>");
            System.exit(2);
            return;
        }
        FabricGatewayProbe probe = null;
        try {
            probe = new FabricGatewayProbe(args[0], Path.of(args[1]));
            probe.execute();
            System.out.println(JSON.writeValueAsString(Map.of(
                    "phase", probe.phase, "pid", probe.pid, "status", probe.run.get("status"),
                    "evidenceId", probe.run.get("evidenceId"),
                    "transactionId", probe.run.get("expectedTransactionId"))));
        } catch (Exception exception) {
            if (probe != null) {
                try {
                    probe.recordFailure(exception);
                } catch (Exception checkpointFailure) {
                    System.err.println("Could not update probe failure metadata; existing files retained.");
                }
            }
            // Exception messages and stack traces can contain transport details; do not print them.
            System.err.println("Fabric Gateway probe failed (" + exception.getClass().getSimpleName()
                    + "); existing checkpoints retained.");
            System.exit(1);
        }
    }

    private void execute() throws Exception {
        switch (phase) {
            case "prepare" -> prepare();
            case "submit" -> submit();
            case "confirm" -> confirm();
            case "verify" -> verify();
            default -> throw new IllegalStateException("Unsupported probe phase");
        }
    }

    private void prepare() throws Exception {
        require(run.isEmpty(), "Prepare requires a new run directory");
        run.put("formatVersion", 1);
        run.put("evidenceId", UUID.randomUUID().toString());
        run.put("version", VERSION);
        run.put("fileHash", sha256("uav-inspection:T006:fixed-probe-payload:v1"
                .getBytes(StandardCharsets.UTF_8)));
        run.put("submitterMsp", "Org2MSP");
        run.put("status", "PREPARING");
        run.put("createdAt", Instant.now().toString());
        run.put("phaseProcesses", new LinkedHashMap<String, Object>());
        saveRun();
        try (EvidenceLedgerClient orgA = connect("ORGA"); EvidenceLedgerClient orgB = connect("ORGB")) {
            assertMissing(orgA.queryEvidence(evidenceId(), VERSION));
            assertMissing(orgB.queryEvidence(evidenceId(), VERSION));
            PreparedRegistration prepared = orgB.prepareRegistration(evidenceId(), VERSION, fileHash());
            require(prepared.transactionId() != null && !prepared.transactionId().isBlank(),
                    "Prepared transaction has no transaction ID");
            writeArtifact("transaction.bin", prepared.transactionBytes(), "transactionArtifact");
            run.put("expectedTransactionId", prepared.transactionId());
            run.put("status", "PREPARED");
            run.put("missingBeforePrepare", Map.of("Org1MSP", true, "Org2MSP", true));
            completePhase();
        }
    }

    private void submit() throws Exception {
        require("PREPARED".equals(run.get("status")), "Submit requires PREPARED state");
        requireDifferentProcess("prepare");
        PreparedRegistration prepared = new PreparedRegistration(transactionId(),
                readArtifact("transaction.bin", "transactionArtifact"));
        try (EvidenceLedgerClient orgA = connect("ORGA"); EvidenceLedgerClient orgB = connect("ORGB")) {
            assertMissing(orgA.queryEvidence(evidenceId(), VERSION));
            run.put("status", "SUBMITTING");
            run.put("submitAttemptPid", pid);
            saveRun();
            CommitCheckpoint commit = orgB.submitPrepared(prepared);
            require(transactionId().equals(commit.transactionId()), "Submit changed the transaction ID");
            writeArtifact("commit.bin", commit.commitBytes(), "commitArtifact");
            run.put("status", "SUBMITTED");
            run.put("commitTransactionId", commit.transactionId());
            run.put("missingBeforeSubmit", true);
            completePhase();
        }
    }

    private void confirm() throws Exception {
        require(Set.of("SUBMITTED", "UNKNOWN", "COMMIT_VALID", "CONFIRMED").contains(run.get("status")),
                "Confirm requires a saved commit checkpoint");
        requireDifferentProcess("prepare");
        requireDifferentProcess("submit");
        CommitCheckpoint commit = new CommitCheckpoint(transactionId(),
                readArtifact("commit.bin", "commitArtifact"));
        try (EvidenceLedgerClient orgA = connect("ORGA"); EvidenceLedgerClient orgB = connect("ORGB")) {
            CommitOutcome outcome = orgB.queryCommit(commit);
            require(transactionId().equals(outcome.transactionId()), "Commit query changed transaction ID");
            run.put("commitOutcome", outcome);
            if (!outcome.valid() || outcome.validationCode() != 0) {
                run.put("transactionStatus", "INVALID");
                run.put("status", "INVALID");
                saveRun();
                throw new IllegalStateException("Transaction did not commit with VALID status");
            }
            assertValid(outcome, transactionId());
            run.put("transactionStatus", "VALID");
            // Business confirmation also requires both organization reads to match the intended record.
            if (!"CONFIRMED".equals(run.get("status"))) {
                run.put("status", "COMMIT_VALID");
            }
            saveRun();
            EvidenceRecord recordA = assertExpected(orgA.queryEvidence(evidenceId(), VERSION));
            EvidenceRecord recordB = assertExpected(orgB.queryEvidence(evidenceId(), VERSION));
            require(recordA.equals(recordB), "Organization queries disagree");
            run.put("confirmedQueries", Map.of("Org1MSP", recordA, "Org2MSP", recordB));
            run.put("status", "CONFIRMED");
            completePhase();
        }
    }

    private void verify() throws Exception {
        require("CONFIRMED".equals(run.get("status")), "Verify requires CONFIRMED state");
        requireDifferentProcess("prepare");
        requireDifferentProcess("submit");
        requireDifferentProcess("confirm");
        Map<String, Object> checks = new LinkedHashMap<>();
        try (EvidenceLedgerClient orgA = connect("ORGA"); EvidenceLedgerClient orgB = connect("ORGB")) {
            CommitOutcome original = orgB.queryCommit(new CommitCheckpoint(transactionId(),
                    readArtifact("commit.bin", "commitArtifact")));
            assertValid(original, transactionId());
            checks.put("originalCommitRecoveredAgain", original);
            checks.put("orgAQueryBeforeDuplicate", assertExpected(orgA.queryEvidence(evidenceId(), VERSION)));
            checks.put("orgBQueryBeforeDuplicate", assertExpected(orgB.queryEvidence(evidenceId(), VERSION)));

            CommitOutcome duplicate = verifyDuplicate(orgB);
            checks.put("sameHashFreshTransaction", duplicate);
            checks.put("orgAQueryAfterDuplicate", assertExpected(orgA.queryEvidence(evidenceId(), VERSION)));
            checks.put("orgBQueryAfterDuplicate", assertExpected(orgB.queryEvidence(evidenceId(), VERSION)));

            String conflictingHash = sha256("uav-inspection:T006:conflicting-payload:v1"
                    .getBytes(StandardCharsets.UTF_8));
            require(!fileHash().equals(conflictingHash), "Conflict probe hashes must differ");
            checks.put("differentHashRejected", expectRejection(
                    () -> orgB.prepareRegistration(evidenceId(), VERSION, conflictingHash),
                    CONFLICT_MESSAGE, "EVIDENCE_HASH_CONFLICT"));

            String forbiddenEvidenceId = UUID.randomUUID().toString();
            run.put("forbiddenEvidenceId", forbiddenEvidenceId);
            saveRun();
            assertMissing(orgA.queryEvidence(forbiddenEvidenceId, VERSION));
            checks.put("orgARegistrationRejected", expectRejection(
                    () -> orgA.prepareRegistration(forbiddenEvidenceId, VERSION, fileHash()),
                    FORBIDDEN_MESSAGE, "FORBIDDEN"));
            assertMissing(orgA.queryEvidence(forbiddenEvidenceId, VERSION));
            assertMissing(orgB.queryEvidence(forbiddenEvidenceId, VERSION));
            checks.put("rejectedRegistrationRemainsAbsent", true);
            checks.put("orgAOriginalRecordUnchanged", assertExpected(orgA.queryEvidence(evidenceId(), VERSION)));
            checks.put("orgBOriginalRecordUnchanged", assertExpected(orgB.queryEvidence(evidenceId(), VERSION)));
            completePhase();

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("passed", true);
            report.put("verifiedAt", Instant.now().toString());
            report.put("verificationPid", pid);
            report.put("evidenceId", evidenceId());
            report.put("version", VERSION);
            report.put("fileHash", fileHash());
            report.put("originalTransactionId", transactionId());
            report.put("duplicateTransactionId", run.get("duplicateTransactionId"));
            report.put("phaseProcesses", run.get("phaseProcesses"));
            report.put("transactionArtifact", run.get("transactionArtifact"));
            report.put("commitArtifact", run.get("commitArtifact"));
            report.put("checks", checks);
            report.put("scope", "Real Java Gateway calls and file-backed process checkpoints; "
                    + "not DB/Outbox recovery or injected lost-response validation");
            writeJson(directory.resolve("verification.json"), report);
        }
    }

    private CommitOutcome verifyDuplicate(EvidenceLedgerClient orgB) throws Exception {
        // Preserve each artifact before the next network operation; never silently replace an unknown attempt.
        require(!run.containsKey("duplicateTransactionId"),
                "Duplicate attempt already exists; inspect its retained checkpoints before retrying verify");
        PreparedRegistration prepared = orgB.prepareRegistration(evidenceId(), VERSION, fileHash());
        require(!transactionId().equals(prepared.transactionId()), "Duplicate probe requires a fresh transaction ID");
        writeArtifact("duplicate-transaction.bin", prepared.transactionBytes(), "duplicateTransactionArtifact");
        run.put("duplicateTransactionId", prepared.transactionId());
        run.put("duplicateStatus", "PREPARED");
        saveRun();
        run.put("duplicateStatus", "SUBMITTING");
        saveRun();
        CommitCheckpoint commit = orgB.submitPrepared(prepared);
        require(prepared.transactionId().equals(commit.transactionId()), "Duplicate submit changed transaction ID");
        writeArtifact("duplicate-commit.bin", commit.commitBytes(), "duplicateCommitArtifact");
        run.put("duplicateStatus", "SUBMITTED");
        saveRun();
        CommitOutcome outcome = orgB.queryCommit(commit);
        assertValid(outcome, prepared.transactionId());
        run.put("duplicateStatus", "CONFIRMED");
        run.put("duplicateCommitOutcome", outcome);
        saveRun();
        return outcome;
    }

    private Map<String, Object> expectRejection(EndorseCall call, String expectedMessage, String expectedCode)
            throws Exception {
        try {
            call.run();
        } catch (EndorseException exception) {
            if (exception.getStatus().getCode() != Status.Code.ABORTED || exception.getDetails().isEmpty()) {
                throw exception;
            }
            boolean knownPeerReason = exception.getDetails().stream().anyMatch(detail ->
                    expectedMessage.equals(detail.getMessage())
                            && Set.of("Org1MSP", "Org2MSP").contains(detail.getMspId())
                            && !detail.getAddress().isBlank());
            if (!knownPeerReason) {
                // An unrecognized RPC error must never pass as an expected chaincode rejection.
                throw exception;
            }
            return Map.of("rejected", true, "exceptionType", "EndorseException",
                    "grpcStatus", "ABORTED", "observedFixedMessage", expectedMessage,
                    "mappedCode", expectedCode,
                    "codeSource", "Mapped from fixed ErrorDetail.message; SDK does not expose chaincode payload");
        }
        throw new IllegalStateException("Expected endorsement rejection did not occur");
    }

    private EvidenceRecord assertExpected(EvidenceQuery query) {
        require(query.found() && query.record() != null, "Expected registered evidence is absent");
        EvidenceRecord record = query.record();
        require(evidenceId().equals(record.evidenceId()) && VERSION == record.version()
                        && fileHash().equals(record.fileHash()) && "Org2MSP".equals(record.submitterMsp())
                        && transactionId().equals(record.registeredTxId()),
                "Evidence record does not match all five expected fields");
        return record;
    }

    private static void assertMissing(EvidenceQuery query) {
        require(!query.found() && query.record() == null, "Expected evidence to be absent");
    }

    private static void assertValid(CommitOutcome outcome, String transactionId) {
        require(transactionId.equals(outcome.transactionId()), "Commit query changed transaction ID");
        require(outcome.valid() && outcome.validationCode() == 0 && outcome.blockNumber() > 0,
                "Transaction did not commit with VALID status");
    }

    private static EvidenceLedgerClient connect(String organization) throws Exception {
        return GatewayEvidenceLedgerClient.connect(
                FabricGatewaySettings.fromEnvironment(System.getenv(), organization));
    }

    private void requireDifferentProcess(String completedPhase) {
        Map<String, Object> processes = objectMap(run.get("phaseProcesses"));
        Map<String, Object> prior = objectMap(processes.get(completedPhase));
        require(prior.get("pid") instanceof Number && ((Number) prior.get("pid")).longValue() != pid,
                "Each phase must execute in a new JVM process");
    }

    private void completePhase() throws IOException {
        objectMap(run.get("phaseProcesses")).put(phase,
                Map.of("pid", pid, "completedAt", Instant.now().toString()));
        run.put("lastPhase", phase);
        run.put("lastPhaseResult", "PASSED");
        run.remove("lastFailure");
        saveRun();
    }

    private void recordFailure(Exception exception) throws IOException {
        if (run.isEmpty()) {
            return;
        }
        boolean rpcFailure = false;
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof GatewayException || cause instanceof StatusRuntimeException) {
                rpcFailure = true;
                break;
            }
            cause = cause.getCause();
        }
        if (rpcFailure) {
            if (!"CONFIRMED".equals(run.get("status"))) {
                run.put("status", "UNKNOWN");
            }
            if (run.containsKey("duplicateStatus") && !"CONFIRMED".equals(run.get("duplicateStatus"))) {
                run.put("duplicateStatus", "UNKNOWN");
            }
        }
        run.put("lastPhase", phase);
        run.put("lastPhaseResult", rpcFailure ? "UNKNOWN" : "ERROR");
        run.put("lastFailure", Map.of("exceptionType", exception.getClass().getSimpleName(),
                "pid", pid, "time", Instant.now().toString(), "checkpointsRetained", true));
        saveRun();
    }

    private void writeArtifact(String name, byte[] bytes, String metadataKey) throws Exception {
        require(bytes != null && bytes.length > 0, "Serialized checkpoint must not be empty");
        atomicWrite(directory.resolve(name), bytes);
        run.put(metadataKey, Map.of("path", name, "size", bytes.length, "sha256", sha256(bytes)));
    }

    private byte[] readArtifact(String name, String metadataKey) throws Exception {
        Map<String, Object> metadata = objectMap(run.get(metadataKey));
        require(name.equals(metadata.get("path")), "Checkpoint path metadata does not match");
        byte[] bytes = Files.readAllBytes(directory.resolve(name));
        require(metadata.get("size") instanceof Number
                        && ((Number) metadata.get("size")).longValue() == bytes.length
                        && sha256(bytes).equals(metadata.get("sha256")),
                "Serialized checkpoint does not match its recorded size and SHA-256");
        return bytes;
    }

    private void saveRun() throws IOException {
        writeJson(directory.resolve("run.json"), run);
    }

    private static void writeJson(Path path, Object value) throws IOException {
        atomicWrite(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    private static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(Path path) throws IOException {
        return JSON.readValue(Files.readAllBytes(path), LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        require(value instanceof Map<?, ?>, "Required checkpoint metadata is absent");
        return (Map<String, Object>) value;
    }

    private String evidenceId() {
        return (String) run.get("evidenceId");
    }

    private String fileHash() {
        return (String) run.get("fileHash");
    }

    private String transactionId() {
        return (String) run.get("expectedTransactionId");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    @FunctionalInterface
    private interface EndorseCall {
        PreparedRegistration run() throws EndorseException;
    }
}
