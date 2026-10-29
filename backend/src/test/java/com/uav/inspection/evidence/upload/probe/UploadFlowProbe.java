package com.uav.inspection.evidence.upload.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.evidence.storage.ObjectStorage;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/** Explicit real HTTP/MySQL/Garage probe; not a JUnit test or application JAR entry point. */
public final class UploadFlowProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private static final String API = "/api/v1";
    private static final Set<String> PROGRESS_FIELDS = Set.of("uploadId", "requirementId", "executionId",
            "filename", "contentType", "size", "partSize", "partCount", "processingStatus", "expiresAt",
            "uploadedParts", "uploadedSize", "versionId");
    private static final Set<String> UPLOAD_ACTIONS = Set.of("UPLOAD_SESSION_CREATED", "UPLOAD_PART_STORED");
    private static final Map<String, String> TABLE_ORDER = new LinkedHashMap<>();
    static {
        TABLE_ORDER.put("organization", "id");
        TABLE_ORDER.put("app_user", "id");
        TABLE_ORDER.put("inspection_task", "id");
        TABLE_ORDER.put("task_member", "task_id, user_id");
        TABLE_ORDER.put("drone", "id");
        TABLE_ORDER.put("inspection_subtask", "id");
        TABLE_ORDER.put("material_requirement", "id");
        TABLE_ORDER.put("task_execution", "id");
        TABLE_ORDER.put("upload_session", "id");
        TABLE_ORDER.put("upload_part", "session_id,part_no");
        TABLE_ORDER.put("evidence_version", "id");
    }

    private final Path reportPath;
    private final String runId = UUID.randomUUID().toString();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> checks = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Account> accounts = new ArrayList<>();
    private final List<String> secrets = new ArrayList<>();
    private final Set<String> uploadIds = new LinkedHashSet<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final SecureRandom random = new SecureRandom();
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private ObjectStorage storage;
    private S3Client s3;
    private HttpClient client;
    private String baseUrl;
    private String storageEndpoint;
    private String bucket;
    private String taskId;
    private String droneId;
    private String droneCode;
    private Scope firstScope;
    private Scope secondScope;

    private UploadFlowProbe(Path reportPath) {
        this.reportPath = reportPath.toAbsolutePath().normalize();
        report.put("formatVersion", 1);
        report.put("runId", runId);
        report.put("startedAt", Instant.now().toString());
        report.put("passed", false);
        report.put("checks", checks);
        report.put("requests", requests);
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: UploadFlowProbe <reportPath>");
            System.exit(2);
            return;
        }
        var probe = new UploadFlowProbe(Path.of(args[0]));
        try {
            probe.execute();
            probe.writeReport();
            System.out.println("[PASS] T010 real HTTP/MySQL/Garage upload probe: " + probe.reportPath);
        } catch (Exception failure) {
            probe.report.put("passed", false);
            probe.report.put("failureType", failure.getClass().getSimpleName());
            if (failure instanceof ProbeFailure) probe.report.put("failedCheck", failure.getMessage());
            try { probe.writeReport(); }
            catch (Exception ignored) { System.err.println("Could not write the upload probe failure report."); }
            // Never print SQL/HTTP/S3 exception messages, causes, URLs, keys or signed request data.
            System.err.println("[FAIL] T010 upload probe (" + failure.getClass().getSimpleName()
                    + "); inspect the safe report metadata.");
            System.exit(1);
        }
    }

    private void execute() throws Exception {
        report.put("stage", "application_startup");
        try (var context = new SpringApplicationBuilder(UavInspectionApplication.class)
                .web(WebApplicationType.SERVLET).profiles("local", "database", "storage").logStartupInfo(false)
                .run("--server.port=0", "--logging.level.com.uav.inspection=INFO")) {
            require(context instanceof ServletWebServerApplicationContext, "real_servlet_context_required");
            require(!List.of(context.getEnvironment().getActiveProfiles()).contains("demo"),
                    "probe_does_not_initialize_demo_data");
            jdbc = context.getBean(JdbcTemplate.class);
            dataSource = context.getBean(DataSource.class);
            storage = context.getBean(ObjectStorage.class);
            s3 = context.getBean(S3Client.class);
            storageEndpoint = context.getEnvironment().getRequiredProperty("inspection.storage.endpoint");
            bucket = context.getEnvironment().getRequiredProperty("inspection.storage.bucket");
            baseUrl = "http://127.0.0.1:"
                    + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            try (Connection connection = dataSource.getConnection()) {
                require("MySQL".equals(connection.getMetaData().getDatabaseProductName()), "real_mysql_required");
            }
            byte[] baseline = businessSnapshot();
            report.put("businessCountsBefore", businessCounts());
            try (HttpClient actualClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                client = actualClient;
                try {
                    report.put("stage", "isolated_task_setup");
                    createFixtures();
                    createRunningTask();
                    report.put("stage", "upload_http_and_private_storage_checks");
                    verifyUploadFlow();
                    verifyAuditSafety();
                } finally {
                    report.put("cleanupAttempted", true);
                    cleanupFixtures();
                    require(MessageDigest.isEqual(baseline, businessSnapshot()),
                            "original_eleven_business_tables_changed");
                    report.put("originalBusinessDataUnchanged", true);
                    report.put("businessCountsAfter", businessCounts());
                }
            }
        }
        report.put("stage", "complete");
        report.put("completedAt", Instant.now().toString());
        report.put("passed", true);
    }

    private void createFixtures() throws Exception {
        account(OWNER, "creator", List.of("OWNER_TASK_ADMIN"));
        account(OPERATOR, "admin", List.of("OPERATOR_TASK_ADMIN"));
        account(OPERATOR, "uploader", List.of("OPERATOR_USER"));
        account(OPERATOR, "peer-user", List.of("OPERATOR_USER"));
        account(OPERATOR, "outsider", List.of("OPERATOR_USER"));
        for (Account account : accounts) {
            account.token = string(expect("fixture_login", "POST", API + "/auth/login", null,
                    Map.of("username", account.username, "password", account.password), 200).data().get("accessToken"));
            secrets.add(account.token);
        }
        droneId = UUID.randomUUID().toString();
        droneCode = "T010-" + runId.substring(0, 12);
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        require(jdbc.update("""
                INSERT INTO drone (id,operator_org_id,code,token_hash) VALUES (?,?,?,?)
                """, droneId, OPERATOR, droneCode, sha256(entropy)) == 1, "fixture_drone_inserted");
        report.put("fixtureAccountIds", accounts.stream().map(a -> a.id).toList());
        report.put("fixtureDroneId", droneId);
    }

    private void account(String org, String suffix, List<String> roles) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        secrets.add(password);
        var account = new Account(UUID.randomUUID().toString(), org,
                "t010-" + runId.substring(0, 12) + "-" + suffix, password);
        accounts.add(account);
        String encoded = new BCryptPasswordEncoder(12).encode(password);
        require(jdbc.update("""
                INSERT INTO app_user (id,org_id,username,password_hash,roles)
                VALUES (?,?,?,?,CAST(? AS JSON))
                """, account.id, org, account.username, encoded, JSON.writeValueAsString(roles)) == 1,
                "fixture_account_inserted");
    }

    private void createRunningTask() throws Exception {
        Account creator = accounts.get(0), admin = accounts.get(1);
        Map<String, Object> task = expect("create_task", "POST", API + "/tasks", creator.token,
                Map.of("name", "T010-" + runId, "scope", "isolated real upload verification", "requirements", List.of(
                        Map.of("name", "first-photo", "required", true),
                        Map.of("name", "second-photo", "required", true))), 200).data();
        taskId = string(task.get("id"));
        task = expect("assign_task", "POST", taskPath() + "/assign", creator.token,
                Map.of("operatorOrgId", OPERATOR, "operatorAdminId", admin.id, "expectedLockVersion", version(task)), 200).data();
        task = expect("add_upload_members", "PUT", taskPath() + "/members", admin.token,
                Map.of("userIds", List.of(admin.id, accounts.get(2).id, accounts.get(3).id),
                        "expectedLockVersion", version(task)), 200).data();
        List<Map<String, Object>> requirements = objects(task.get("requirements"));
        List<Scope> scopes = new ArrayList<>();
        for (int index = 0; index < requirements.size(); index++) {
            String requirement = string(requirements.get(index).get("id"));
            String name = "flight-" + index;
            task = expect("create_subtask", "POST", taskPath() + "/subtasks", admin.token,
                    Map.of("name", name, "requirementIds", List.of(requirement), "expectedLockVersion", version(task)), 200).data();
            Map<String, Object> subtask = objects(task.get("subtasks")).stream()
                    .filter(row -> name.equals(row.get("name"))).findFirst().orElseThrow();
            String subtaskId = string(subtask.get("id"));
            Map<String, Object> execution = expect("create_execution", "POST", API + "/subtasks/" + subtaskId + "/executions",
                    admin.token, Map.of("droneId", droneId, "expectedLockVersion", version(subtask)), 200).data();
            scopes.add(new Scope(requirement, string(execution.get("id")), subtaskId));
            task = expect("refresh_task", "GET", taskPath(), admin.token, null, 200).data();
        }
        task = expect("start_task", "POST", taskPath() + "/start", admin.token,
                Map.of("expectedLockVersion", version(task)), 200).data();
        require("RUNNING".equals(task.get("status")), "fixture_task_started_through_real_t009_endpoints");
        firstScope = scopes.get(0);
        secondScope = scopes.get(1);
        report.put("fixtureTaskId", taskId);
    }

    private void verifyUploadFlow() throws Exception {
        Account admin = accounts.get(1), uploader = accounts.get(2), peer = accounts.get(3), outsider = accounts.get(4);
        byte[] red = png(0xff0000), blue = png(0x0000ff);
        int commonSize = Math.max(red.length, blue.length) + 64;
        red = padPng(red, commonSize);
        blue = padPng(blue, commonSize);
        require(ImageIO.read(new ByteArrayInputStream(red)).getRGB(0, 0)
                        != ImageIO.read(new ByteArrayInputStream(blue)).getRGB(0, 0), "two_real_png_images_have_different_pixels");
        Map<String, Object> initialBody = createBody(UUID.randomUUID().toString(), firstScope, "photo.png", "image/png", red.length);
        expect("anonymous_session_create", "POST", uploadPath(), null, initialBody, 401);
        expect("operator_admin_without_operator_user_cannot_upload", "POST", uploadPath(), admin.token, initialBody, 403);
        expect("nonmember_operator_user_cannot_upload", "POST", uploadPath(), outsider.token, initialBody, 403);
        expect("owner_cannot_upload", "POST", uploadPath(), accounts.get(0).token, initialBody, 403);
        expect("unsupported_file_type", "POST", uploadPath(), uploader.token,
                createBody(UUID.randomUUID().toString(), firstScope, "photo.exe", "application/octet-stream", red.length), 400);
        expect("zero_file_size", "POST", uploadPath(), uploader.token,
                createBody(UUID.randomUUID().toString(), firstScope, "photo.png", "image/png", 0), 400);
        expect("path_traversal_filename", "POST", uploadPath(), uploader.token,
                createBody(UUID.randomUUID().toString(), firstScope, "../photo.png", "image/png", red.length), 400);
        expect("malformed_client_request_uuid", "POST", uploadPath(), uploader.token,
                createBody("not-a-uuid", firstScope, "photo.png", "image/png", red.length), 400);
        expect("requirement_execution_scope_mismatch", "POST", uploadPath(), uploader.token,
                createBody(UUID.randomUUID().toString(), new Scope(secondScope.requirementId(), firstScope.executionId(),
                        firstScope.subtaskId()), "photo.png", "image/png", red.length), 400);

        long createAuditsBefore = actionCount("UPLOAD_SESSION_CREATED");
        List<Reply> createReplies = race("same_session_create", "POST", uploadPath(), uploader.token, initialBody, initialBody, false);
        require(createReplies.stream().allMatch(reply -> reply.status() == 200), "parallel_same_create_both_succeed");
        String upload = string(createReplies.getFirst().data().get("uploadId"));
        uploadIds.add(upload);
        require(upload.equals(createReplies.get(1).data().get("uploadId"))
                        && createReplies.getFirst().data().get("expiresAt").equals(createReplies.get(1).data().get("expiresAt")),
                "parallel_same_create_returns_one_original_session_and_expiry");
        Long sessionRows = jdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE actor_id=? AND client_request_id=?",
                Long.class, uploader.id, initialBody.get("clientRequestId"));
        require(sessionRows != null && sessionRows == 1 && actionCount("UPLOAD_SESSION_CREATED") == createAuditsBefore + 1,
                "parallel_same_create_has_one_session_and_one_audit");
        assertProgress(createReplies.getFirst().data(), upload, red.length, 0, List.of());
        Reply repeated = expect("repeat_create_keeps_expiry", "POST", uploadPath(), uploader.token, initialBody, 200);
        require(upload.equals(repeated.data().get("uploadId"))
                        && repeated.data().get("expiresAt").equals(createReplies.getFirst().data().get("expiresAt")),
                "repeat_create_does_not_extend_expiry");
        noUploadAudit(repeated);
        var conflictingBody = new LinkedHashMap<String, Object>(initialBody);
        conflictingBody.put("filename", "different.png");
        expect("same_request_different_metadata", "POST", uploadPath(), uploader.token, conflictingBody, 409,
                "IDEMPOTENCY_CONFLICT");

        expect("anonymous_progress", "GET", sessionPath(upload), null, null, 401);
        expect("other_member_cannot_read_session", "GET", sessionPath(upload), peer.token, null, 403);
        expect("unknown_session_progress", "GET", sessionPath(UUID.randomUUID().toString()), uploader.token, null, 403);
        part("anonymous_part", upload, 1, null, red, 401, "UNAUTHORIZED");
        part("other_member_cannot_write_session", upload, 1, peer.token, red, 403, "FORBIDDEN");
        part("zero_part_number", upload, 0, uploader.token, red, 400, "INVALID_ARGUMENT");
        part("part_number_beyond_file", upload, 2, uploader.token, red, 400, "INVALID_ARGUMENT");
        part("short_part_body", upload, 1, uploader.token, Arrays.copyOf(red, red.length - 1), 400, "INVALID_ARGUMENT");
        part("oversized_part_body", upload, 1, uploader.token, Arrays.copyOf(red, red.length + 1), 413, "FILE_TOO_LARGE");
        require(partCount(upload) == 0, "invalid_requests_do_not_create_parts");

        long partAuditsBefore = actionCount("UPLOAD_PART_STORED");
        List<Reply> sameParts = race("parallel_same_part", "PUT", partPath(upload, 1), uploader.token, red, red, true);
        require(sameParts.stream().allMatch(reply -> reply.status() == 200), "parallel_equal_part_both_succeed");
        verifyPartResponse(sameParts.get(0), upload, 1, red);
        verifyPartResponse(sameParts.get(1), upload, 1, red);
        require(partCount(upload) == 1 && actionCount("UPLOAD_PART_STORED") == partAuditsBefore + 1,
                "parallel_equal_part_has_one_record_and_one_audit");
        require(objectKeys(upload).size() == 1, "parallel_equal_part_loser_candidate_removed");
        verifyStoredPart(upload, 1, red, true);
        Reply sameAgain = part("repeat_same_part", upload, 1, uploader.token, red, 200, "OK");
        verifyPartResponse(sameAgain, upload, 1, red);
        noUploadAudit(sameAgain);
        part("different_part_content_conflict", upload, 1, uploader.token, blue, 409, "PART_CONTENT_CONFLICT");
        require(partCount(upload) == 1 && objectKeys(upload).size() == 1, "part_conflict_preserves_one_committed_object");
        assertProgress(progress(upload, uploader), upload, red.length, red.length, List.of(1));

        String differentUpload = createSession("different_part_session", uploader,
                createBody(UUID.randomUUID().toString(), firstScope, "concurrent.png", "image/png", red.length));
        long beforeDifferent = actionCount("UPLOAD_PART_STORED");
        List<Reply> differentParts = race("parallel_different_part", "PUT", partPath(differentUpload, 1),
                uploader.token, red, blue, true);
        require(Set.copyOf(differentParts.stream().map(Reply::status).toList()).equals(Set.of(200, 409)),
                "parallel_different_part_has_one_success_and_one_conflict");
        Reply winner = differentParts.stream().filter(reply -> reply.status() == 200).findFirst().orElseThrow();
        Reply loser = differentParts.stream().filter(reply -> reply.status() == 409).findFirst().orElseThrow();
        require("PART_CONTENT_CONFLICT".equals(loser.body().get("code")), "parallel_different_part_error_code");
        noUploadAudit(loser);
        byte[] winnerBytes = sha256(red).equals(winner.data().get("sha256")) ? red : blue;
        verifyPartResponse(winner, differentUpload, 1, winnerBytes);
        require(partCount(differentUpload) == 1 && actionCount("UPLOAD_PART_STORED") == beforeDifferent + 1,
                "parallel_different_part_has_one_record_and_one_audit");
        require(objectKeys(differentUpload).size() == 1, "parallel_different_part_loser_candidate_removed");
        verifyStoredPart(differentUpload, 1, winnerBytes, false);

        verifyMagicMismatch(uploader, red);
        verifyMultipartResume(uploader, red, number(createReplies.getFirst().data().get("partSize")));
        verifyUnavailableStorage(uploader, red);
        verifyLiveAuthorization(uploader, upload, red);
        verifyExpiredAndFrozenSessions(uploader, red);
        Long versions = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_version WHERE task_id=?", Long.class, taskId);
        require(versions != null && versions == 0, "t010_does_not_create_evidence_versions");
        Long processing = jdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE task_id=? AND status<>'UPLOADING'",
                Long.class, taskId);
        require(processing != null && processing == 0, "successful_sessions_remain_uploading_before_t011_finalization");
        report.put("parallelCreateStatuses", createReplies.stream().map(Reply::status).toList());
        report.put("parallelEqualPartStatuses", sameParts.stream().map(Reply::status).toList());
        report.put("parallelDifferentPartStatuses", differentParts.stream().map(Reply::status).toList());
        report.put("privateGarageBytesMatchDatabase", true);
        report.put("evidenceVersionsCreated", 0);
    }

    private void verifyMagicMismatch(Account uploader, byte[] png) throws Exception {
        String id = createSession("declared_jpeg_session", uploader,
                createBody(UUID.randomUUID().toString(), firstScope, "declared.jpg", "image/jpeg", png.length));
        part("png_bytes_rejected_when_declared_jpeg", id, 1, uploader.token, png, 400, "INVALID_ARGUMENT");
        require(partCount(id) == 0, "magic_mismatch_does_not_persist_a_part");
    }

    private void verifyUnavailableStorage(Account uploader, byte[] png) throws Exception {
        String id = createSession("unavailable_storage_session", uploader,
                createBody(UUID.randomUUID().toString(), firstScope, "storage-down.png", "image/png", png.length));
        String originalBaseUrl = baseUrl;
        // A second isolated application uses the same DB/JWT identity and a refused local S3 endpoint.
        // The healthy Garage service and the primary application stay available throughout this check.
        try (var unavailable = new SpringApplicationBuilder(UavInspectionApplication.class)
                .web(WebApplicationType.SERVLET).profiles("local", "database", "storage").logStartupInfo(false)
                .run("--server.port=0", "--inspection.storage.endpoint=http://127.0.0.1:1",
                        "--logging.level.com.uav.inspection=INFO")) {
            baseUrl = "http://127.0.0.1:"
                    + ((ServletWebServerApplicationContext) unavailable).getWebServer().getPort();
            part("storage_unavailable_returns_503", id, 1, uploader.token, png, 503, "DEPENDENCY_UNAVAILABLE");
        } finally {
            baseUrl = originalBaseUrl;
        }
        require(partCount(id) == 0 && objectKeys(id).isEmpty(), "storage_failure_does_not_create_part_or_object");
        require("UPLOADING".equals(progress(id, uploader).get("processingStatus")),
                "storage_failure_preserves_retryable_uploading_session");
        report.put("unavailableStorageReturns503WithoutPartOrSuccessAudit", true);
    }

    private void verifyMultipartResume(Account uploader, byte[] png, long partSize) throws Exception {
        require(partSize > 0 && partSize < 32 * 1024 * 1024, "configured_part_size_is_bounded_for_probe");
        byte[] full = padPng(png, Math.toIntExact(partSize + png.length));
        require(ImageIO.read(new ByteArrayInputStream(full)) != null, "multipart_test_file_is_a_real_png");
        String id = createSession("two_part_session", uploader,
                createBody(UUID.randomUUID().toString(), firstScope, "two-parts.png", "image/png", full.length));
        Map<String, Object> created = progress(id, uploader);
        require(number(created.get("partCount")) == 2, "multipart_geometry_has_two_parts");
        byte[] first = Arrays.copyOfRange(full, 0, Math.toIntExact(partSize));
        byte[] last = Arrays.copyOfRange(full, Math.toIntExact(partSize), full.length);
        Reply second = part("upload_last_part_first", id, 2, uploader.token, last, 200, "OK");
        successAudit(second, "UPLOAD_PART_STORED");
        assertProgress(progress(id, uploader), id, full.length, last.length, List.of(2));
        Reply resumed = part("resume_missing_first_part", id, 1, uploader.token, first, 200, "OK");
        successAudit(resumed, "UPLOAD_PART_STORED");
        assertProgress(progress(id, uploader), id, full.length, full.length, List.of(1, 2));
        verifyStoredPart(id, 1, first, false);
        verifyStoredPart(id, 2, last, false);
        require(objectKeys(id).size() == 2, "multipart_has_only_two_committed_private_objects");
        report.put("multipartUploadSize", full.length);
        report.put("multipartPartSize", partSize);
        report.put("resumeUploadedPartOrder", List.of(2, 1));
    }

    private void verifyLiveAuthorization(Account uploader, String id, byte[] bytes) throws Exception {
        require(jdbc.update("DELETE FROM task_member WHERE task_id=? AND user_id=?", taskId, uploader.id) == 1,
                "fixture_membership_removed_for_live_authorization_check");
        try {
            expect("removed_member_progress_forbidden", "GET", sessionPath(id), uploader.token, null, 403);
            part("removed_member_part_forbidden", id, 1, uploader.token, bytes, 403, "FORBIDDEN");
        } finally {
            jdbc.update("INSERT INTO task_member(task_id,user_id) VALUES (?,?)", taskId, uploader.id);
        }
        require(jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE id=?", firstScope.subtaskId()) == 1,
                "fixture_current_execution_temporarily_unset");
        try {
            expect("noncurrent_execution_session_rejected", "POST", uploadPath(), uploader.token,
                    createBody(UUID.randomUUID().toString(), firstScope, "noncurrent.png", "image/png", bytes.length),
                    409, "VERSION_CONFLICT");
            part("noncurrent_execution_part_rejected", id, 1, uploader.token, bytes, 409, "VERSION_CONFLICT");
        } finally {
            jdbc.update("UPDATE inspection_subtask SET current_execution_id=? WHERE id=?", firstScope.executionId(), firstScope.subtaskId());
        }
        require(jdbc.update("UPDATE inspection_task SET status='COMPLETED',completed_at=UTC_TIMESTAMP(3) WHERE id=?", taskId) == 1,
                "fixture_task_temporarily_completed_for_negative_check");
        try {
            expect("completed_task_session_rejected", "POST", uploadPath(), uploader.token,
                    createBody(UUID.randomUUID().toString(), firstScope, "completed.png", "image/png", bytes.length),
                    409, "TASK_COMPLETED");
            part("completed_task_part_rejected", id, 1, uploader.token, bytes, 409, "TASK_COMPLETED");
        } finally {
            jdbc.update("UPDATE inspection_task SET status='RUNNING',completed_at=NULL WHERE id=?", taskId);
        }
    }

    private void verifyExpiredAndFrozenSessions(Account uploader, byte[] png) throws Exception {
        String expired = createSession("expiry_session", uploader,
                createBody(UUID.randomUUID().toString(), firstScope, "expired.png", "image/png", png.length));
        Map<String, Object> original = jdbc.queryForMap("SELECT created_at,expires_at FROM upload_session WHERE id=?", expired);
        // Keep the database expiry>created constraint while making only this fixture expire in the past.
        jdbc.update("""
                UPDATE upload_session SET created_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 2 DAY),
                    expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 DAY) WHERE id=?
                """, expired);
        try {
            Map<String, Object> expiredProgress = progress(expired, uploader);
            require("EXPIRED".equals(expiredProgress.get("processingStatus")), "expired_progress_reports_expired");
            part("expired_session_part_rejected", expired, 1, uploader.token, png, 409, "UPLOAD_SESSION_EXPIRED");
        } finally {
            jdbc.update("UPDATE upload_session SET created_at=?,expires_at=?,status='UPLOADING' WHERE id=?",
                    original.get("created_at"), original.get("expires_at"), expired);
        }
        String frozen = createSession("frozen_session", uploader,
                createBody(UUID.randomUUID().toString(), firstScope, "frozen.png", "image/png", png.length));
        jdbc.update("UPDATE upload_session SET status='VERIFYING' WHERE id=?", frozen);
        try {
            require("VERIFYING".equals(progress(frozen, uploader).get("processingStatus")), "frozen_progress_remains_readable");
            part("frozen_session_part_rejected", frozen, 1, uploader.token, png, 409, "UPLOAD_NOT_WRITABLE");
        } finally {
            jdbc.update("UPDATE upload_session SET status='UPLOADING' WHERE id=?", frozen);
        }
        require(partCount(expired) == 0 && partCount(frozen) == 0, "expired_and_frozen_requests_do_not_persist_parts");
    }

    private void assertProgress(Map<String, Object> progress, String id, long size, long uploaded, List<Integer> parts) {
        require(progress.keySet().equals(PROGRESS_FIELDS), "progress_contains_only_public_upload_fields");
        require(id.equals(progress.get("uploadId")) && size == number(progress.get("size"))
                        && uploaded == number(progress.get("uploadedSize")) && progress.get("versionId") == null
                        && "UPLOADING".equals(progress.get("processingStatus")), "progress_reports_durable_unfinalized_state");
        List<Map<String, Object>> actual = objects(progress.get("uploadedParts"));
        require(actual.stream().map(row -> Math.toIntExact(number(row.get("partNo")))).toList().equals(parts),
                "progress_reports_only_sorted_committed_part_numbers");
        require(actual.stream().allMatch(row -> row.keySet().equals(Set.of("partNo", "size", "sha256"))),
                "progress_part_metadata_has_no_storage_key");
    }

    private void verifyPartResponse(Reply reply, String upload, int part, byte[] expected) throws Exception {
        Map<String, Object> data = reply.data();
        require(data.keySet().equals(Set.of("uploadId", "partNo", "size", "sha256"))
                        && upload.equals(data.get("uploadId")) && number(data.get("partNo")) == part
                        && number(data.get("size")) == expected.length && sha256(expected).equals(data.get("sha256")),
                "part_response_has_exact_size_and_server_computed_sha256");
    }

    private void verifyStoredPart(String upload, int partNo, byte[] expected, boolean anonymousCheck) throws Exception {
        Map<String, Object> row = jdbc.queryForMap("SELECT object_key,size,sha256 FROM upload_part WHERE session_id=? AND part_no=?",
                upload, partNo);
        String key = string(row.get("object_key"));
        byte[] actual;
        try (var stream = storage.open(key)) { actual = stream.readAllBytes(); }
        require(Arrays.equals(actual, expected) && number(row.get("size")) == actual.length
                        && sha256(actual).equals(row.get("sha256")), "real_garage_object_matches_part_bytes_and_database_hash");
        if (anonymousCheck) {
            String address = storageEndpoint.replaceAll("/$", "") + "/" + bucket + "/" + key;
            for (String method : List.of("HEAD", "GET")) {
                var request = HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(15))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                require(response.statusCode() == 403, "anonymous_private_object_" + method.toLowerCase() + "_is_forbidden");
            }
            report.put("anonymousPrivateObjectHeadAndGetForbidden", true);
        }
    }

    private String createSession(String name, Account actor, Map<String, Object> body) throws Exception {
        Reply reply = expect(name, "POST", uploadPath(), actor.token, body, 200);
        String id = string(reply.data().get("uploadId"));
        uploadIds.add(id);
        successAudit(reply, "UPLOAD_SESSION_CREATED");
        return id;
    }

    private Map<String, Object> progress(String id, Account actor) throws Exception {
        return expect("read_upload_progress", "GET", sessionPath(id), actor.token, null, 200).data();
    }

    private Reply part(String name, String id, int number, String token, byte[] bytes, int status, String code)
            throws Exception {
        Reply reply = request(name, "PUT", partPath(id, number), token, bytes, true);
        assertStatus(reply, name, status, code);
        if (status >= 400) noUploadAudit(reply);
        return reply;
    }

    private List<Reply> race(String name, String method, String path, String token, Object a, Object b, boolean raw)
            throws Exception {
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        CompletableFuture<Reply> first = parallel(name + "_a", method, path, token, a, raw, start, ready);
        CompletableFuture<Reply> second = parallel(name + "_b", method, path, token, b, raw, start, ready);
        require(ready.await(10, TimeUnit.SECONDS), name + "_requests_ready");
        start.countDown();
        CompletableFuture.allOf(first, second).get(45, TimeUnit.SECONDS);
        return List.of(first.get(), second.get());
    }

    private CompletableFuture<Reply> parallel(String name, String method, String path, String token, Object body,
            boolean raw, CountDownLatch start, CountDownLatch ready) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new ProbeFailure("parallel_start_timeout");
                return request(name, method, path, token, body, raw);
            } catch (Exception failure) { throw new ProbeFailure("parallel_http_request_failure"); }
        });
    }

    private Reply expect(String name, String method, String path, String token, Object body, int status) throws Exception {
        String code = switch (status) {
            case 200 -> "OK";
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 409 -> "VERSION_CONFLICT";
            case 413 -> "FILE_TOO_LARGE";
            default -> throw new ProbeFailure("unsupported_expected_status");
        };
        return expect(name, method, path, token, body, status, code);
    }

    private Reply expect(String name, String method, String path, String token, Object body, int status, String code)
            throws Exception {
        Reply reply = request(name, method, path, token, body, false);
        assertStatus(reply, name, status, code);
        if (status >= 400) noUploadAudit(reply);
        return reply;
    }

    private void assertStatus(Reply reply, String name, int status, String code) {
        require(reply.status() == status, name + "_status_" + status);
        require(code.equals(reply.body().get("code")), name + "_code");
    }

    private Reply request(String name, String method, String path, String token, Object body, boolean raw) throws Exception {
        String trace = "t010-" + runId + "-" + sequence.incrementAndGet();
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                .header("X-Trace-Id", trace).header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", raw ? "application/octet-stream" : "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : raw ? HttpRequest.BodyPublishers.ofByteArray((byte[]) body)
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        requests.add(Map.of("case", name, "status", response.statusCode(), "traceId", trace));
        require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"), name + "_json_content_type");
        String text = new String(response.body(), StandardCharsets.UTF_8);
        require(!text.contains("objectKey") && !text.contains("object_key") && !text.contains("upload-parts/")
                        && !text.contains(storageEndpoint) && secrets.stream().noneMatch(text::contains),
                name + "_response_does_not_expose_private_storage_or_credentials");
        Map<String, Object> envelope = JSON.readValue(response.body(),new TypeReference<Map<String,Object>>() {});
        require(envelope.keySet().equals(Set.of("code", "message", "data", "traceId")), name + "_uniform_envelope");
        require(trace.equals(response.headers().firstValue("X-Trace-Id").orElse(null))
                        && trace.equals(envelope.get("traceId")), name + "_trace_header_and_body");
        return new Reply(response.statusCode(), envelope, trace);
    }

    private long partCount(String id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM upload_part WHERE session_id=?", Long.class, id);
        require(count != null, "part_count_present");
        return count;
    }

    private long actionCount(String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE trace_id LIKE ? AND action=?", Long.class,
                "t010-" + runId + "-%", action);
        require(count != null, "audit_count_present");
        return count;
    }

    private void successAudit(Reply reply, String action) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT action,result FROM audit_log WHERE trace_id=?", reply.trace());
        require(rows.size() == 1 && action.equals(rows.getFirst().get("action"))
                        && "SUCCESS".equals(rows.getFirst().get("result")), action.toLowerCase() + "_has_one_success_audit");
    }

    private void noUploadAudit(Reply reply) {
        List<String> actions = jdbc.queryForList("SELECT action FROM audit_log WHERE trace_id=?", String.class, reply.trace());
        require(actions.stream().noneMatch(UPLOAD_ACTIONS::contains), "rejected_or_idempotent_operation_has_no_extra_upload_audit");
    }

    private void verifyAuditSafety() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id",
                "t010-" + runId + "-%");
        require(!rows.isEmpty(), "real_upload_operations_are_audited");
        for (var row : rows) {
            Account actor = accounts.stream().filter(account -> account.id.equals(row.get("actor_id")))
                    .findFirst().orElseThrow(() -> new ProbeFailure("audit_actor_is_not_fixture"));
            require("USER".equals(row.get("actor_type")) && actor.org.equals(row.get("org_id"))
                            && "SUCCESS".equals(row.get("result")), "audit_contains_correct_safe_actor_metadata");
            String serialized = row.values().stream().map(String::valueOf).collect(Collectors.joining("\n"));
            require(secrets.stream().noneMatch(serialized::contains) && !serialized.contains("$2a$")
                            && !serialized.contains("$2b$") && !serialized.contains("Bearer ")
                            && !serialized.contains("upload-parts/") && !serialized.contains(storageEndpoint),
                    "audit_does_not_contain_credentials_or_private_object_locations");
        }
        report.put("auditRowsRetained", rows.size());
        report.put("auditContainsNoCredentialsOrStorageKeys", true);
    }

    private Set<String> objectKeys(String upload) {
        Set<String> keys = new LinkedHashSet<>();
        String continuation = null;
        do {
            var response = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                    .prefix("upload-parts/" + upload + "/").continuationToken(continuation).build());
            response.contents().forEach(object -> keys.add(object.key()));
            continuation = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (continuation != null);
        return keys;
    }

    private void cleanupFixtures() {
        Set<String> taskIds = new LinkedHashSet<>();
        for (Account account : accounts) taskIds.addAll(jdbc.queryForList(
                "SELECT id FROM inspection_task WHERE created_by=?", String.class, account.id));
        for (String task : taskIds) uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?", String.class, task));
        Set<String> keys = new LinkedHashSet<>();
        int objectFailures = 0;
        for (String upload : uploadIds) {
            keys.addAll(jdbc.queryForList("SELECT object_key FROM upload_part WHERE session_id=?", String.class, upload));
            try { keys.addAll(objectKeys(upload)); }
            catch (Exception unavailable) { objectFailures++; }
        }
        for (String key : keys) {
            try { storage.delete(key); }
            catch (Exception unavailable) { objectFailures++; }
        }
        for (String task : taskIds) {
            jdbc.update("DELETE FROM evidence_version WHERE task_id=?", task);
            jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)", task);
            jdbc.update("DELETE FROM upload_session WHERE task_id=?", task);
            jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?", task);
            jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)", task);
            jdbc.update("DELETE FROM material_requirement WHERE task_id=?", task);
            jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?", task);
            jdbc.update("DELETE FROM task_member WHERE task_id=?", task);
            jdbc.update("DELETE FROM inspection_task WHERE id=?", task);
        }
        if (droneId != null) jdbc.update("DELETE FROM drone WHERE id=? AND code=?", droneId, droneCode);
        for (Account account : accounts) jdbc.update("DELETE FROM app_user WHERE id=? AND username=?", account.id, account.username);
        for (String upload : uploadIds) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE id=?", Long.class, upload);
            require(count != null && count == 0, "run_upload_session_deleted");
            try { require(objectKeys(upload).isEmpty(), "run_private_object_prefix_is_empty_after_cleanup"); }
            catch (ProbeFailure failed) { throw failed; }
            catch (Exception unavailable) { objectFailures++; }
        }
        report.put("fixtureTasksDeleted", taskIds.size());
        report.put("fixtureAccountsDeleted", accounts.size());
        report.put("fixtureUploadSessionsDeleted", uploadIds.size());
        report.put("fixturePrivateObjectsDeleted", keys.size());
        report.put("objectCleanupFailures", objectFailures);
        require(objectFailures == 0, "fixture_private_objects_cleanup_succeeded");
    }

    private Map<String, Long> businessCounts() {
        var counts = new LinkedHashMap<String, Long>();
        for (String table : TABLE_ORDER.keySet()) counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        return counts;
    }

    private byte[] businessSnapshot() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Connection connection = dataSource.getConnection()) {
            for (var table : TABLE_ORDER.entrySet()) {
                try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT * FROM " + table.getKey() + " ORDER BY " + table.getValue())) {
                    frame(digest, table.getKey());
                    while (rows.next()) {
                        digest.update((byte) 1);
                        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) frame(digest, rows.getString(column));
                    }
                    digest.update((byte) 0);
                }
            }
        }
        return digest.digest();
    }

    private void writeReport() throws Exception {
        report.put("caseCount", requests.size());
        report.put("checkCount", checks.size());
        Files.createDirectories(reportPath.getParent());
        Path temporary = Files.createTempFile(reportPath.getParent(), ".upload-flow-probe-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            byte[] serialized = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            String text = new String(serialized, StandardCharsets.UTF_8);
            if (secrets.stream().anyMatch(text::contains) || text.contains("upload-parts/"))
                throw new ProbeFailure("report_contains_private_credentials_or_object_locations");
            Files.write(temporary, serialized);
            Files.move(temporary, reportPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private void require(boolean condition, String name) {
        checks.add(Map.of("check", name, "passed", condition));
        if (!condition) throw new ProbeFailure(name);
    }

    private static byte[] png(int color) throws Exception {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) image.setRGB(x, y, color);
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new ProbeFailure("png_writer_unavailable");
        return output.toByteArray();
    }

    /** Insert a valid tEXt ancillary chunk before IEND; both PNGs can have exactly the same file length. */
    private static byte[] padPng(byte[] png, int targetSize) throws Exception {
        if (targetSize < png.length + 18) throw new ProbeFailure("png_padding_too_small");
        byte[] text = new byte[targetSize - png.length - 12];
        Arrays.fill(text, (byte) 'p');
        System.arraycopy("probe\0".getBytes(StandardCharsets.ISO_8859_1), 0, text, 0, 6);
        byte[] type = "tEXt".getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(text);
        var bytes = new ByteArrayOutputStream(targetSize);
        try (var output = new DataOutputStream(bytes)) {
            output.write(png, 0, png.length - 12);
            output.writeInt(text.length);
            output.write(type);
            output.write(text);
            output.writeInt((int) crc.getValue());
            output.write(png, png.length - 12, 12);
        }
        return bytes.toByteArray();
    }

    private static Map<String, Object> createBody(String request, Scope scope, String filename, String type, long size) {
        return Map.of("clientRequestId", request, "requirementId", scope.requirementId(), "executionId", scope.executionId(),
                "filename", filename, "contentType", type, "size", size);
    }
    private String taskPath() { return API + "/tasks/" + taskId; }
    private static String uploadPath() { return API + "/upload-sessions"; }
    private static String sessionPath(String id) { return uploadPath() + "/" + id; }
    private static String partPath(String id, int number) { return sessionPath(id) + "/parts/" + number; }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static long version(Map<String, Object> row) { return number(row.get("lockVersion")); }
    private static String string(Object value) {
        if (!(value instanceof String text)) throw new ProbeFailure("expected_json_string");
        return text;
    }
    private static long number(Object value) {
        if (!(value instanceof Number number)) throw new ProbeFailure("expected_json_number");
        return number.longValue();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new ProbeFailure("expected_json_object");
        return (Map<String, Object>) value;
    }
    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> list)) throw new ProbeFailure("expected_json_array");
        return list.stream().map(UploadFlowProbe::object).toList();
    }
    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) digest.update(bytes);
    }
    private static final class Account {
        final String id;
        final String org;
        final String username;
        final String password;
        String token;
        Account(String id, String org, String username, String password) {
            this.id = id; this.org = org; this.username = username; this.password = password;
        }
        @Override public String toString() { return "UploadProbeAccount[" + id + "]"; }
    }
    private record Scope(String requirementId, String executionId, String subtaskId) {}
    private record Reply(int status, Map<String, Object> body, String trace) {
        Map<String, Object> data() { return object(body.get("data")); }
        @Override public String toString() { return "UploadProbeReply[" + status + "," + trace + "]"; }
    }
    private static final class ProbeFailure extends RuntimeException {
        ProbeFailure(String name) { super(name); }
    }
}
