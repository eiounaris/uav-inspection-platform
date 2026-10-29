package com.uav.inspection.inspection.probe;

import com.uav.inspection.UavInspectionApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
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

/** Explicit real HTTP/MySQL CLI. Temporary fixtures are removed; safe audit events are retained. */
public final class TaskFlowProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private static final String API = "/api/v1";
    private static final Set<String> ACTIONS = Set.of("TASK_CREATED", "TASK_REQUIREMENTS_UPDATED",
            "TASK_ASSIGNED", "TASK_MEMBERS_UPDATED", "SUBTASK_CREATED", "EXECUTION_CREATED", "TASK_STARTED");
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
    }

    private final Path reportPath;
    private final String runId = UUID.randomUUID().toString();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> checks = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Account> accounts = new ArrayList<>();
    private final List<Device> devices = new ArrayList<>();
    private final List<String> secrets = new ArrayList<>();
    private final Set<String> createdTasks = new LinkedHashSet<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final SecureRandom random = new SecureRandom();
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private HttpClient client;
    private String baseUrl;

    private TaskFlowProbe(Path reportPath) {
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
            System.err.println("Usage: TaskFlowProbe <reportPath>");
            System.exit(2);
            return;
        }
        var probe = new TaskFlowProbe(Path.of(args[0]));
        try {
            probe.execute();
            probe.writeReport();
            System.out.println("[PASS] T009 real HTTP/MySQL task flow probe: " + probe.reportPath);
        } catch (Exception failure) {
            probe.report.put("passed", false);
            probe.report.put("failureType", failure.getClass().getSimpleName());
            if (failure instanceof ProbeFailure) {
                probe.report.put("failedCheck", failure.getMessage());
            }
            try {
                probe.writeReport();
            } catch (Exception ignored) {
                System.err.println("Could not write the task flow probe failure report.");
            }
            // SQL/JSON/HTTP exception diagnostics can contain credentials; keep them out of output.
            System.err.println("[FAIL] T009 task flow probe (" + failure.getClass().getSimpleName()
                    + "); inspect the safe report metadata.");
            System.exit(1);
        }
    }

    private void execute() throws Exception {
        report.put("stage", "application_startup");
        try (var context = new SpringApplicationBuilder(UavInspectionApplication.class)
                .web(WebApplicationType.SERVLET).profiles("local", "database").logStartupInfo(false)
                .run("--server.port=0", "--logging.level.com.uav.inspection=INFO")) {
            require(context instanceof ServletWebServerApplicationContext, "real_servlet_context_required");
            require(!List.of(context.getEnvironment().getActiveProfiles()).contains("demo"),
                    "probe_does_not_initialize_demo_data");
            jdbc = context.getBean(JdbcTemplate.class);
            dataSource = context.getBean(DataSource.class);
            baseUrl = "http://127.0.0.1:"
                    + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            try (Connection connection = dataSource.getConnection()) {
                require("MySQL".equals(connection.getMetaData().getDatabaseProductName()), "real_mysql_required");
            }
            report.put("realHttp", true);
            byte[] baseline = businessSnapshot();
            report.put("businessCountsBefore", businessCounts());
            try (HttpClient actualClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                client = actualClient;
                try {
                    report.put("stage", "fixture_creation");
                    createFixtures();
                    loginFixtures();
                    report.put("stage", "task_authorization_and_state_flow");
                    verifyFlow();
                    report.put("stage", "safe_audit_validation");
                    verifyAuditSafety();
                } finally {
                    report.put("cleanupAttempted", true);
                    cleanupFixtures();
                    require(MessageDigest.isEqual(baseline, businessSnapshot()),
                            "original_eight_business_tables_changed");
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
        account(OWNER, "reviewer", List.of("OWNER_REVIEWER"));
        account(OWNER, "owner-outsider", List.of("OWNER_TASK_ADMIN"));
        account(OWNER, "organization-only", List.of("ORG_ADMIN"));
        account(OPERATOR, "operator-admin", List.of("OPERATOR_TASK_ADMIN"));
        account(OPERATOR, "operator-user", List.of("OPERATOR_USER"));
        account(OPERATOR, "operator-outsider", List.of("OPERATOR_TASK_ADMIN"));
        device("enabled", true);
        device("disabled", false);
        report.put("fixtureAccountIds", accounts.stream().map(account -> account.id).toList());
        report.put("fixtureDeviceIds", devices.stream().map(Device::id).toList());
    }

    private void account(String orgId, String suffix, List<String> roles) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        secrets.add(password);
        var account = new Account(UUID.randomUUID().toString(), orgId,
                "t009-" + runId.substring(0, 12) + "-" + suffix, password);
        accounts.add(account);
        String encoded = new BCryptPasswordEncoder(12).encode(password);
        require(jdbc.update("""
                INSERT INTO app_user (id,org_id,username,password_hash,roles)
                VALUES (?,?,?,?,CAST(? AS JSON))
                """, account.id, orgId, account.username, encoded,
                JSON.writeValueAsString(roles)) == 1, "fixture_account_inserted");
    }

    private void device(String suffix, boolean enabled) throws Exception {
        byte[] token = new byte[32];
        random.nextBytes(token);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token));
        var device = new Device(UUID.randomUUID().toString(), "T009-" + runId.substring(0, 12) + "-" + suffix);
        devices.add(device);
        require(jdbc.update("""
                INSERT INTO drone (id,operator_org_id,code,token_hash,enabled) VALUES (?,?,?,?,?)
                """, device.id(), OPERATOR, device.code(), digest, enabled) == 1, "fixture_device_inserted");
    }

    private void loginFixtures() throws Exception {
        for (Account account : accounts) {
            Reply reply = expect("fixture_login", "POST", API + "/auth/login", null,
                    Map.of("username", account.username, "password", account.password), 200);
            account.token = string(reply.data().get("accessToken"));
            secrets.add(account.token);
        }
    }

    private void verifyFlow() throws Exception {
        Account creator = accounts.get(0), reviewer = accounts.get(1), outsider = accounts.get(2);
        Account organizationOnly = accounts.get(3), operator = accounts.get(4), user = accounts.get(5);
        Account operatorOutsider = accounts.get(6);
        String enabledDrone = devices.get(0).id(), disabledDrone = devices.get(1).id();
        expect("anonymous_task_list", "GET", API + "/tasks", null, null, 401);
        expect("anonymous_task_create", "POST", API + "/tasks", null,
                createBody("anonymous", List.of(newRequirement("photo", true))), 401);
        expect("blank_task_name", "POST", API + "/tasks", creator.token,
                createBody(" ", List.of(newRequirement("photo", true))), 400);
        var spoofed = new LinkedHashMap<String, Object>(createBody("spoofed", List.of(newRequirement("photo", true))));
        spoofed.put("createdBy", outsider.id);
        expect("forged_task_creator", "POST", API + "/tasks", creator.token, spoofed, 400);
        expect("reviewer_cannot_create_task", "POST", API + "/tasks", reviewer.token,
                createBody("reviewer", List.of(newRequirement("photo", true))), 403);
        expect("organization_admin_has_no_task_permission", "POST", API + "/tasks", organizationOnly.token,
                createBody("organization-only", List.of(newRequirement("photo", true))), 403);

        Map<String, Object> main = createTask("main", creator,
                List.of(newRequirement("first-photo", true), newRequirement("second-photo", true)));
        String taskId = string(main.get("id"));
        require("DRAFT".equals(main.get("status")) && version(main) == 0, "new_task_is_draft_version_zero");
        require(memberIds(main).equals(Set.of(creator.id)), "creator_is_automatically_a_task_member");
        require(list(main.get("subtasks")).isEmpty(), "draft_has_no_subtasks");
        List<Map<String, Object>> originalRequirements = objects(main.get("requirements"));
        require(originalRequirements.size() == 2, "create_stores_requirements");
        for (var requirement : originalRequirements) {
            require(isUuid(requirement.get("id")) && isUuid(requirement.get("evidenceId"))
                            && requirement.get("subtaskId") == null, "draft_requirement_has_stable_unbound_evidence_id");
        }
        expect("owner_nonmember_detail", "GET", taskPath(taskId), outsider.token, null, 403);
        expect("organization_admin_nonmember_detail", "GET", taskPath(taskId), organizationOnly.token, null, 403);
        expect("operator_before_assignment_detail", "GET", taskPath(taskId), operator.token, null, 403);
        expect("unknown_task_detail", "GET", taskPath(UUID.randomUUID().toString()), creator.token, null, 403);
        expect("malformed_task_uuid", "GET", taskPath("not-a-uuid"), creator.token, null, 400);
        verifyPage(outsider, 0, 0, "owner_nonmember_empty_list");
        verifyPage(operatorOutsider, 0, 0, "operator_nonmember_empty_list");
        expect("organization_admin_cannot_list_tasks", "GET", API + "/tasks?page=1&pageSize=1",
                organizationOnly.token, null, 403);

        var updated = new ArrayList<Map<String, Object>>();
        updated.add(existingRequirement(originalRequirements.get(0), "first-photo-updated", true));
        updated.add(existingRequirement(originalRequirements.get(1), "second-photo", true));
        updated.add(newRequirement("optional-overview", false));
        main = writeTask("update_draft_requirements", "PUT", taskPath(taskId) + "/requirements", creator,
                Map.of("requirements", updated, "expectedLockVersion", version(main)), "TASK_REQUIREMENTS_UPDATED");
        require(version(main) == 1 && objects(main.get("requirements")).size() == 3,
                "draft_requirement_update_increments_task_version");
        assertRequirementIdentityPreserved(originalRequirements, objects(main.get("requirements")));

        expect("wrong_operator_organization", "POST", taskPath(taskId) + "/assign", creator.token,
                assignment(OWNER, operator.id, version(main)), 400);
        expect("operator_admin_from_wrong_org", "POST", taskPath(taskId) + "/assign", creator.token,
                assignment(OPERATOR, creator.id, version(main)), 400);
        expect("malformed_operator_admin_uuid", "POST", taskPath(taskId) + "/assign", creator.token,
                assignment(OPERATOR, "not-a-uuid", version(main)), 400);
        expect("stale_assignment_version", "POST", taskPath(taskId) + "/assign", creator.token,
                assignment(OPERATOR, operator.id, 0), 409);
        main = writeTask("assign_operator", "POST", taskPath(taskId) + "/assign", creator,
                assignment(OPERATOR, operator.id, version(main)), "TASK_ASSIGNED");
        require("ASSIGNED".equals(main.get("status")) && version(main) == 2
                        && memberIds(main).equals(Set.of(creator.id, operator.id)), "assignment_adds_operator_member");
        expect("empty_subtask_set_cannot_start", "POST", taskPath(taskId) + "/start", operator.token,
                expectedVersion(main), 409);
        expect("operator_cannot_modify_owner_requirements", "PUT", taskPath(taskId) + "/requirements", operator.token,
                Map.of("requirements", requirementInputs(main), "expectedLockVersion", version(main)), 403);
        expect("nonmember_admin_cannot_modify_members", "PUT", taskPath(taskId) + "/members", outsider.token,
                membersBody(List.of(outsider.id), version(main)), 403);
        expect("owner_cannot_add_other_org_member", "PUT", taskPath(taskId) + "/members", creator.token,
                membersBody(List.of(user.id), version(main)), 400);
        expect("operator_cannot_add_other_org_member", "PUT", taskPath(taskId) + "/members", operator.token,
                membersBody(List.of(reviewer.id), version(main)), 400);
        expect("unicode_member_identifier_rejected_before_database", "PUT", taskPath(taskId) + "/members", creator.token,
                membersBody(List.of("invalid-identifier-🚁"), version(main)), 400);
        main = writeTask("add_owner_reviewer", "PUT", taskPath(taskId) + "/members", creator,
                membersBody(List.of(reviewer.id), version(main)), "TASK_MEMBERS_UPDATED");
        require(memberIds(main).equals(Set.of(creator.id, reviewer.id, operator.id)),
                "owner_member_update_preserves_creator_and_operator");
        main = writeTask("add_operator_user", "PUT", taskPath(taskId) + "/members", operator,
                membersBody(List.of(operator.id, user.id), version(main)), "TASK_MEMBERS_UPDATED");
        require(memberIds(main).equals(Set.of(creator.id, reviewer.id, operator.id, user.id)),
                "operator_member_update_preserves_owner_members");
        verifyPage(reviewer, 1, 1, "reviewer_can_list_only_member_task");
        main = writeTask("empty_owner_members_preserves_creator", "PUT", taskPath(taskId) + "/members", creator,
                membersBody(List.of(), version(main)), "TASK_MEMBERS_UPDATED");
        require(memberIds(main).equals(Set.of(creator.id, operator.id, user.id)),
                "empty_owner_replacement_keeps_creator_and_other_org");
        expect("removed_member_loses_detail", "GET", taskPath(taskId), reviewer.token, null, 403);
        expect("operator_user_cannot_manage_members", "PUT", taskPath(taskId) + "/members", user.token,
                membersBody(List.of(operator.id, user.id), version(main)), 403);
        main = verifyConcurrentMembers(taskId, creator, reviewer.id);

        Map<String, Object> foreign = createTask("foreign", creator, List.of(newRequirement("foreign-photo", true)));
        String foreignRequirement = string(objects(foreign.get("requirements")).getFirst().get("id"));
        verifyPage(creator, 2, 1, "pagination_counts_after_membership_filter");
        verifyPage(user, 1, 1, "operator_member_count_is_scoped_before_pagination");
        verifyPage(outsider, 0, 0, "outsider_pagination_never_exposes_global_total");
        List<Map<String, Object>> requirements = objects(main.get("requirements"));
        String firstReq = string(requirements.stream().filter(r -> "first-photo-updated".equals(r.get("name")))
                .findFirst().orElseThrow().get("id"));
        String secondReq = string(requirements.stream().filter(r -> "second-photo".equals(r.get("name")))
                .findFirst().orElseThrow().get("id"));
        String optionalReq = string(requirements.stream().filter(r -> "optional-overview".equals(r.get("name")))
                .findFirst().orElseThrow().get("id"));
        expect("cross_task_requirement_binding", "POST", taskPath(taskId) + "/subtasks", operator.token,
                subtaskBody("foreign-binding", List.of(foreignRequirement), version(main)), 400);
        expect("malformed_requirement_uuid", "POST", taskPath(taskId) + "/subtasks", operator.token,
                subtaskBody("invalid-requirement", List.of("not-a-uuid"), version(main)), 400);
        expect("empty_requirement_binding", "POST", taskPath(taskId) + "/subtasks", operator.token,
                subtaskBody("empty-binding", List.of(), version(main)), 400);
        expect("duplicate_requirement_binding", "POST", taskPath(taskId) + "/subtasks", operator.token,
                subtaskBody("duplicate-binding", List.of(firstReq, firstReq), version(main)), 400);
        expect("owner_cannot_create_subtasks", "POST", taskPath(taskId) + "/subtasks", creator.token,
                subtaskBody("owner-binding", List.of(firstReq), version(main)), 403);
        main = writeTask("create_first_subtask", "POST", taskPath(taskId) + "/subtasks", operator,
                subtaskBody("first-flight", List.of(firstReq, optionalReq), version(main)), "SUBTASK_CREATED");
        Map<String, Object> firstSubtask = subtask(main, "first-flight");
        String firstSubtaskId = string(firstSubtask.get("id"));
        require(firstSubtask.get("currentExecutionId") == null && version(firstSubtask) == 0,
                "new_subtask_has_no_execution");
        var removeBound = requirementInputs(main).stream().filter(r -> !firstReq.equals(r.get("id"))).toList();
        expect("cannot_remove_bound_requirement", "PUT", taskPath(taskId) + "/requirements", creator.token,
                Map.of("requirements", removeBound, "expectedLockVersion", version(main)), 409);
        List<Map<String, Object>> beforeAssignedEdit = objects(main.get("requirements"));
        List<Map<String, Object>> assignedUpdate = beforeAssignedEdit.stream().map(row ->
                existingRequirement(row, secondReq.equals(row.get("id")) ? "second-photo-assigned"
                        : string(row.get("name")), Boolean.TRUE.equals(row.get("required")))).toList();
        main = writeTask("update_unbound_requirement_while_assigned", "PUT", taskPath(taskId) + "/requirements", creator,
                Map.of("requirements", assignedUpdate, "expectedLockVersion", version(main)), "TASK_REQUIREMENTS_UPDATED");
        assertRequirementIdentityPreserved(beforeAssignedEdit, objects(main.get("requirements")));
        require(objects(main.get("requirements")).stream().anyMatch(row -> secondReq.equals(row.get("id"))
                        && "second-photo-assigned".equals(row.get("name")) && row.get("subtaskId") == null),
                "assigned_update_changes_only_unbound_requirement_content");
        expect("cannot_bind_requirement_twice", "POST", taskPath(taskId) + "/subtasks", operator.token,
                subtaskBody("duplicate-bound", List.of(firstReq), version(main)), 409);
        expect("unknown_subtask_execution", "POST", executionPath(UUID.randomUUID().toString()), operator.token,
                executionBody(enabledDrone, 0), 403);
        expect("nonmember_subtask_execution", "POST", executionPath(firstSubtaskId), operatorOutsider.token,
                executionBody(enabledDrone, 0), 403);
        expect("disabled_device_initial_assignment", "POST", executionPath(firstSubtaskId), operator.token,
                executionBody(disabledDrone, 0), 400);
        expect("unknown_device_initial_assignment", "POST", executionPath(firstSubtaskId), operator.token,
                executionBody(UUID.randomUUID().toString(), 0), 400);
        expect("malformed_device_uuid", "POST", executionPath(firstSubtaskId), operator.token,
                executionBody("not-a-uuid", 0), 400);
        String firstExecution = verifyConcurrentExecution(taskId, firstSubtaskId, operator, enabledDrone);
        main = detail(taskId, creator, "detail_after_concurrent_execution");
        expect("unbound_requirement_prevents_start", "POST", taskPath(taskId) + "/start", operator.token,
                expectedVersion(main), 409);
        expect("ready_execution_cannot_be_reassigned", "POST", executionPath(firstSubtaskId), operator.token,
                executionBody(enabledDrone, version(subtask(main, "first-flight"))), 409);

        main = writeTask("create_second_subtask", "POST", taskPath(taskId) + "/subtasks", operator,
                subtaskBody("second-flight", List.of(secondReq), version(main)), "SUBTASK_CREATED");
        String secondSubtask = string(subtask(main, "second-flight").get("id"));
        expect("missing_ready_execution_prevents_start", "POST", taskPath(taskId) + "/start", operator.token,
                expectedVersion(main), 409);
        Reply secondExecutionReply = write("assign_second_execution", "POST", executionPath(secondSubtask), operator,
                executionBody(enabledDrone, 0), 200, "EXECUTION_CREATED");
        String secondExecution = string(secondExecutionReply.data().get("id"));
        require("READY".equals(secondExecutionReply.data().get("status"))
                        && number(secondExecutionReply.data().get("progress")) == 0,
                "initial_execution_is_ready_at_zero_progress");
        main = detail(taskId, creator, "all_subtasks_ready");
        long beforeStartVersion = version(main);
        require(jdbc.update("UPDATE drone SET enabled=0 WHERE id=?", enabledDrone) == 1,
                "fixture_device_disabled_after_assignment");
        try {
            expect("disabled_current_device_prevents_start", "POST", taskPath(taskId) + "/start", operator.token,
                    expectedVersion(main), 409);
            require(version(detail(taskId, creator, "failed_start_is_unchanged")) == beforeStartVersion,
                    "failed_start_does_not_increment_version");
        } finally {
            jdbc.update("UPDATE drone SET enabled=1 WHERE id=?", enabledDrone);
        }
        expect("operator_user_cannot_start", "POST", taskPath(taskId) + "/start", user.token,
                expectedVersion(main), 403);
        main = writeTask("start_task", "POST", taskPath(taskId) + "/start", operator,
                expectedVersion(main), "TASK_STARTED");
        require("RUNNING".equals(main.get("status")) && version(main) == beforeStartVersion + 1,
                "start_transitions_assigned_to_running_once");
        verifyRunningExecution(subtask(main, "first-flight"), firstExecution);
        verifyRunningExecution(subtask(main, "second-flight"), secondExecution);
        Long executionCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_execution e JOIN inspection_subtask s ON s.id=e.subtask_id
                WHERE s.task_id=?
                """, Long.class, taskId);
        require(executionCount != null && executionCount == 2, "start_reuses_two_original_execution_rows");
        expect("running_task_cannot_start_again", "POST", taskPath(taskId) + "/start", operator.token,
                expectedVersion(main), 409);
        expect("running_execution_cannot_be_reassigned", "POST", executionPath(secondSubtask), operator.token,
                executionBody(enabledDrone, version(subtask(main, "second-flight"))), 409);
        expect("running_requirements_are_frozen", "PUT", taskPath(taskId) + "/requirements", creator.token,
                Map.of("requirements", requirementInputs(main), "expectedLockVersion", version(main)), 409);
        report.put("startedTaskId", taskId);
        report.put("executionIdsReusedAtStart", List.of(firstExecution, secondExecution));
        report.put("stateFlow", List.of("DRAFT", "ASSIGNED", "RUNNING"));

        verifyEverySubtaskRequiresRequiredMaterial(creator, operator, enabledDrone);
        verifyPage(creator, 3, 1, "three_task_pagination_total");
        Reply beyond = expect("page_beyond_last", "GET", API + "/tasks?page=4&pageSize=1", creator.token, null, 200);
        require(number(beyond.data().get("total")) == 3 && list(beyond.data().get("items")).isEmpty(),
                "empty_page_retains_scoped_total");
        verifyPage(outsider, 0, 0, "nonmember_list_still_empty_after_full_flow");
        report.put("createdTaskIds", List.copyOf(createdTasks));
    }

    private Map<String, Object> verifyConcurrentMembers(String taskId, Account actor, String reviewer) throws Exception {
        Map<String, Object> before = detail(taskId, actor, "before_parallel_members");
        long auditBefore = actionCount("TASK_MEMBERS_UPDATED");
        List<Reply> replies = race("parallel_members", "PUT", taskPath(taskId) + "/members", actor,
                membersBody(List.of(reviewer), version(before)), membersBody(List.of(), version(before)));
        require(oneSuccessOneConflict(replies), "parallel_members_has_one_winner_one_conflict");
        for (Reply reply : replies) {
            if (reply.status() == 200) successAudit(reply, "TASK_MEMBERS_UPDATED");
            else noTaskAudit(reply);
        }
        Map<String, Object> after = detail(taskId, actor, "after_parallel_members");
        require(version(after) == version(before) + 1 && actionCount("TASK_MEMBERS_UPDATED") == auditBefore + 1,
                "parallel_members_changes_task_and_audit_only_once");
        require(memberIds(after).containsAll(Set.of(accounts.get(0).id, accounts.get(4).id, accounts.get(5).id)),
                "parallel_members_keeps_creator_and_other_organization");
        report.put("parallelMembersStatuses", replies.stream().map(Reply::status).toList());
        return after;
    }

    private String verifyConcurrentExecution(String taskId, String subtaskId, Account actor, String droneId)
            throws Exception {
        Map<String, Object> before = detail(taskId, actor, "before_parallel_execution");
        Map<String, Object> subtaskBefore = objects(before.get("subtasks")).stream()
                .filter(s -> subtaskId.equals(s.get("id"))).findFirst().orElseThrow();
        long auditBefore = actionCount("EXECUTION_CREATED");
        Map<String, Object> body = executionBody(droneId, version(subtaskBefore));
        List<Reply> replies = race("parallel_execution", "POST", executionPath(subtaskId), actor, body, body);
        require(oneSuccessOneConflict(replies), "parallel_execution_has_one_winner_one_conflict");
        Reply winner = replies.stream().filter(reply -> reply.status() == 200).findFirst().orElseThrow();
        successAudit(winner, "EXECUTION_CREATED");
        noTaskAudit(replies.stream().filter(reply -> reply.status() == 409).findFirst().orElseThrow());
        String executionId = string(winner.data().get("id"));
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM task_execution WHERE subtask_id=?", Long.class, subtaskId);
        require(count != null && count == 1, "parallel_execution_creates_one_row");
        Map<String, Object> after = detail(taskId, actor, "after_parallel_execution");
        Map<String, Object> subtaskAfter = objects(after.get("subtasks")).stream()
                .filter(s -> subtaskId.equals(s.get("id"))).findFirst().orElseThrow();
        require(executionId.equals(subtaskAfter.get("currentExecutionId"))
                        && version(subtaskAfter) == version(subtaskBefore) + 1
                        && version(after) == version(before) + 1,
                "parallel_execution_updates_task_subtask_and_pointer_once");
        require(actionCount("EXECUTION_CREATED") == auditBefore + 1, "parallel_execution_writes_one_audit");
        require("READY".equals(winner.data().get("status")), "parallel_execution_winner_is_ready");
        report.put("parallelExecutionStatuses", replies.stream().map(Reply::status).toList());
        return executionId;
    }

    private void verifyEverySubtaskRequiresRequiredMaterial(Account creator, Account operator, String drone)
            throws Exception {
        Map<String, Object> task = createTask("optional-only", creator, List.of(newRequirement("optional-only", false)));
        String id = string(task.get("id"));
        task = writeTask("assign_optional_task", "POST", taskPath(id) + "/assign", creator,
                assignment(OPERATOR, operator.id, version(task)), "TASK_ASSIGNED");
        String requirementId = string(objects(task.get("requirements")).getFirst().get("id"));
        task = writeTask("create_optional_only_subtask", "POST", taskPath(id) + "/subtasks", operator,
                subtaskBody("optional-flight", List.of(requirementId), version(task)), "SUBTASK_CREATED");
        String subtaskId = string(subtask(task, "optional-flight").get("id"));
        write("assign_optional_execution", "POST", executionPath(subtaskId), operator,
                executionBody(drone, 0), 200, "EXECUTION_CREATED");
        task = detail(id, creator, "optional_only_subtask_ready");
        expect("subtask_without_required_material_cannot_start", "POST", taskPath(id) + "/start", operator.token,
                expectedVersion(task), 409);
        require("ASSIGNED".equals(detail(id, creator, "optional_task_unchanged").get("status")),
                "each_required_guard_preserves_assigned_state");
    }

    private void verifyRunningExecution(Map<String, Object> subtask, String expectedId) {
        require(expectedId.equals(subtask.get("currentExecutionId")), "start_keeps_current_execution_pointer");
        Map<String, Object> execution = object(subtask.get("execution"));
        require(expectedId.equals(execution.get("id")) && "RUNNING".equals(execution.get("status"))
                        && number(execution.get("progress")) == 0 && execution.get("startedAt") instanceof String,
                "start_reuses_ready_execution_and_fills_started_at");
        String databaseStatus = jdbc.queryForObject("SELECT status FROM task_execution WHERE id=?", String.class, expectedId);
        require("RUNNING".equals(databaseStatus), "running_execution_is_committed_to_mysql");
    }

    private Map<String, Object> createTask(String suffix, Account actor, List<Map<String, Object>> requirements)
            throws Exception {
        Map<String, Object> task = writeTask("create_" + suffix + "_task", "POST", API + "/tasks", actor,
                createBody("T009-" + runId.substring(0, 12) + "-" + suffix, requirements), "TASK_CREATED");
        require(isUuid(task.get("id")), "created_task_has_uuid");
        createdTasks.add(string(task.get("id")));
        return task;
    }

    private Map<String, Object> writeTask(String name, String method, String path, Account actor, Object body,
            String action) throws Exception {
        return write(name, method, path, actor, body, 200, action).data();
    }

    private Reply write(String name, String method, String path, Account actor, Object body, int status,
            String action) throws Exception {
        Reply reply = expect(name, method, path, actor.token, body, status);
        if (status == 200) successAudit(reply, action);
        return reply;
    }

    private Map<String, Object> detail(String taskId, Account actor, String name) throws Exception {
        return expect(name, "GET", taskPath(taskId), actor.token, null, 200).data();
    }

    private void verifyPage(Account actor, long total, int items, String name) throws Exception {
        Map<String, Object> page = expect(name, "GET", API + "/tasks?page=1&pageSize=1", actor.token, null, 200).data();
        require(number(page.get("total")) == total && list(page.get("items")).size() == items
                        && number(page.get("page")) == 1 && number(page.get("pageSize")) == 1,
                name + "_scoped_page_and_total");
    }

    private List<Reply> race(String name, String method, String path, Account actor, Object a, Object b)
            throws Exception {
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        CompletableFuture<Reply> first = parallelRequest(name + "_a", method, path, actor.token, a, start, ready);
        CompletableFuture<Reply> second = parallelRequest(name + "_b", method, path, actor.token, b, start, ready);
        require(ready.await(10, TimeUnit.SECONDS), name + "_requests_ready");
        start.countDown();
        CompletableFuture.allOf(first, second).get(35, TimeUnit.SECONDS);
        return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
    }

    private CompletableFuture<Reply> parallelRequest(String name, String method, String path, String token,
            Object body, CountDownLatch start, CountDownLatch ready) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new ProbeFailure("parallel_start_timeout");
                return request(name, method, path, token, body);
            } catch (Exception failure) {
                throw new ProbeFailure("parallel_http_request_failure");
            }
        });
    }

    private boolean oneSuccessOneConflict(List<Reply> replies) {
        return Set.copyOf(replies.stream().map(Reply::status).toList()).equals(Set.of(200, 409))
                && replies.stream().allMatch(reply -> (reply.status() == 200 ? "OK" : "VERSION_CONFLICT")
                        .equals(reply.body().get("code")));
    }

    private Reply expect(String name, String method, String path, String token, Object body, int status)
            throws Exception {
        Reply reply = request(name, method, path, token, body);
        require(reply.status() == status, name + "_status_" + status);
        String code = switch (status) {
            case 200 -> "OK";
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 409 -> "VERSION_CONFLICT";
            default -> throw new ProbeFailure("unsupported_expected_status");
        };
        require(code.equals(reply.body().get("code")), name + "_code");
        if (status >= 400) noTaskAudit(reply);
        return reply;
    }

    private Reply request(String name, String method, String path, String token, Object body) throws Exception {
        String trace = "t009-" + runId + "-" + sequence.incrementAndGet();
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
                .header("X-Trace-Id", trace).header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        requests.add(Map.of("case", name, "status", response.statusCode(), "traceId", trace));
        require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),
                name + "_json_content_type");
        Map<String, Object> envelope = JSON.readValue(response.body(),new TypeReference<Map<String,Object>>() {});
        require(envelope.keySet().equals(Set.of("code", "message", "data", "traceId")), name + "_uniform_envelope");
        require(trace.equals(response.headers().firstValue("X-Trace-Id").orElse(null))
                        && trace.equals(envelope.get("traceId")), name + "_trace_header_and_body");
        return new Reply(response.statusCode(), envelope, trace);
    }

    private void successAudit(Reply reply, String action) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action,result FROM audit_log WHERE trace_id=?", reply.trace());
        require(rows.size() == 1 && action.equals(rows.getFirst().get("action"))
                        && "SUCCESS".equals(rows.getFirst().get("result")),
                action.toLowerCase() + "_has_one_success_audit");
    }

    private void noTaskAudit(Reply reply) {
        List<String> actions = jdbc.queryForList("SELECT action FROM audit_log WHERE trace_id=?", String.class, reply.trace());
        require(actions.stream().noneMatch(ACTIONS::contains), "rejected_operation_has_no_task_success_audit");
    }

    private long actionCount(String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE trace_id LIKE ? AND action=?",
                Long.class, "t009-" + runId + "-%", action);
        require(count != null, "action_audit_count_present");
        return count;
    }

    private void verifyAuditSafety() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id", "t009-" + runId + "-%");
        require(!rows.isEmpty(), "successful_task_operations_are_audited");
        for (var row : rows) {
            Account actor = accounts.stream().filter(account -> account.id.equals(row.get("actor_id")))
                    .findFirst().orElseThrow(() -> new ProbeFailure("audit_actor_is_not_fixture"));
            require("USER".equals(row.get("actor_type")) && actor.orgId.equals(row.get("org_id"))
                            && "SUCCESS".equals(row.get("result"))
                            && (ACTIONS.contains(row.get("action")) || "AUTH_LOGIN".equals(row.get("action"))),
                    "audit_actor_organization_action_and_result_are_safe");
            String serialized = row.values().stream().map(String::valueOf).collect(Collectors.joining("\n"));
            require(secrets.stream().noneMatch(serialized::contains)
                            && !serialized.contains("$2a$") && !serialized.contains("$2b$")
                            && !serialized.contains("$2y$") && !serialized.contains("Bearer "),
                    "audit_does_not_contain_credentials");
        }
        report.put("auditRowsRetained", rows.size());
        report.put("auditContainsNoCredentials", true);
        report.put("successfulTaskActions", ACTIONS.stream().sorted().toList());
    }

    private void cleanupFixtures() {
        // Discover by this run's creator UUIDs, even if a committed create response failed validation.
        Set<String> taskIds = new LinkedHashSet<>();
        for (Account account : accounts) {
            taskIds.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?", String.class, account.id));
        }
        for (String taskId : taskIds) {
            jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?", taskId);
            jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)", taskId);
            jdbc.update("DELETE FROM material_requirement WHERE task_id=?", taskId);
            jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?", taskId);
            jdbc.update("DELETE FROM task_member WHERE task_id=?", taskId);
            jdbc.update("DELETE FROM inspection_task WHERE id=?", taskId);
        }
        for (Device device : devices) {
            jdbc.update("DELETE FROM drone WHERE id=? AND code=?", device.id(), device.code());
        }
        for (Account account : accounts) {
            jdbc.update("DELETE FROM app_user WHERE id=? AND username=?", account.id, account.username);
        }
        for (String taskId : taskIds) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inspection_task WHERE id=?", Long.class, taskId);
            require(count != null && count == 0, "run_task_deleted");
        }
        for (Account account : accounts) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=?", Long.class, account.id);
            require(count != null && count == 0, "run_account_deleted");
        }
        for (Device device : devices) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM drone WHERE id=?", Long.class, device.id());
            require(count != null && count == 0, "run_device_deleted");
        }
        report.put("fixtureTasksDeleted", taskIds.size());
        report.put("fixtureAccountsDeleted", accounts.size());
        report.put("fixtureDevicesDeleted", devices.size());
    }

    private Map<String, Long> businessCounts() {
        var counts = new LinkedHashMap<String, Long>();
        for (String table : TABLE_ORDER.keySet()) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    private byte[] businessSnapshot() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Connection connection = dataSource.getConnection()) {
            for (var table : TABLE_ORDER.entrySet()) {
                // Identifiers come solely from the fixed table map. Original data stays in memory.
                try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT * FROM " + table.getKey() + " ORDER BY " + table.getValue())) {
                    frame(digest, table.getKey());
                    while (rows.next()) {
                        digest.update((byte) 1);
                        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                            frame(digest, rows.getString(column));
                        }
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
        Path temporary = Files.createTempFile(reportPath.getParent(), ".task-flow-probe-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            byte[] serialized = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            String text = new String(serialized, StandardCharsets.UTF_8);
            if (secrets.stream().anyMatch(text::contains)) throw new ProbeFailure("report_contains_credentials");
            Files.write(temporary, serialized);
            Files.move(temporary, reportPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void require(boolean condition, String check) {
        checks.add(Map.of("check", check, "passed", condition));
        if (!condition) throw new ProbeFailure(check);
    }

    private static Map<String, Object> createBody(String name, List<Map<String, Object>> requirements) {
        return Map.of("name", name, "scope", "T009 isolated HTTP task verification", "requirements", requirements);
    }
    private static Map<String, Object> newRequirement(String name, boolean required) {
        return Map.of("name", name, "required", required);
    }
    private static Map<String, Object> existingRequirement(Map<String, Object> row, String name, boolean required) {
        return Map.of("id", row.get("id"), "name", name, "required", required);
    }
    private static Map<String, Object> assignment(String org, String admin, long version) {
        return Map.of("operatorOrgId", org, "operatorAdminId", admin, "expectedLockVersion", version);
    }
    private static Map<String, Object> expectedVersion(Map<String, Object> task) {
        return Map.of("expectedLockVersion", version(task));
    }
    private static Map<String, Object> membersBody(List<String> ids, long version) {
        return Map.of("userIds", ids, "expectedLockVersion", version);
    }
    private static Map<String, Object> subtaskBody(String name, List<String> ids, long version) {
        return Map.of("name", name, "requirementIds", ids, "expectedLockVersion", version);
    }
    private static Map<String, Object> executionBody(String drone, long version) {
        return Map.of("droneId", drone, "expectedLockVersion", version);
    }
    private static List<Map<String, Object>> requirementInputs(Map<String, Object> task) {
        return objects(task.get("requirements")).stream().map(row ->
                existingRequirement(row, string(row.get("name")), Boolean.TRUE.equals(row.get("required")))).toList();
    }
    private void assertRequirementIdentityPreserved(List<Map<String, Object>> before, List<Map<String, Object>> after) {
        for (Map<String, Object> previous : before) {
            var next = after.stream().filter(row -> previous.get("id").equals(row.get("id"))).findFirst().orElseThrow();
            require(previous.get("evidenceId").equals(next.get("evidenceId")), "requirement_edit_preserves_evidence_id");
        }
    }
    private static Set<String> memberIds(Map<String, Object> task) {
        return objects(task.get("members")).stream().map(member -> string(member.get("userId"))).collect(Collectors.toSet());
    }
    private static Map<String, Object> subtask(Map<String, Object> task, String name) {
        return objects(task.get("subtasks")).stream().filter(row -> name.equals(row.get("name")))
                .findFirst().orElseThrow(() -> new ProbeFailure("expected_subtask_missing"));
    }
    private static String taskPath(String id) { return API + "/tasks/" + id; }
    private static String executionPath(String id) { return API + "/subtasks/" + id + "/executions"; }
    private static long version(Map<String, Object> row) { return number(row.get("lockVersion")); }
    private static String string(Object value) {
        if (!(value instanceof String text)) throw new ProbeFailure("expected_json_string");
        return text;
    }
    private static long number(Object value) {
        if (!(value instanceof Number number)) throw new ProbeFailure("expected_json_number");
        return number.longValue();
    }
    private static boolean isUuid(Object value) {
        if (!(value instanceof String text)) return false;
        try { return UUID.fromString(text).toString().equals(text); }
        catch (IllegalArgumentException ignored) { return false; }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new ProbeFailure("expected_json_object");
        return (Map<String, Object>) value;
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> list)) throw new ProbeFailure("expected_json_array");
        return list;
    }
    private static List<Map<String, Object>> objects(Object value) {
        return list(value).stream().map(TaskFlowProbe::object).toList();
    }
    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) digest.update(bytes);
    }
    private static final class Account {
        final String id;
        final String orgId;
        final String username;
        final String password;
        String token;
        Account(String id, String orgId, String username, String password) {
            this.id = id;
            this.orgId = orgId;
            this.username = username;
            this.password = password;
        }
        @Override public String toString() { return "TaskProbeAccount[" + id + "]"; }
    }
    private record Device(String id, String code) {}
    private record Reply(int status, Map<String, Object> body, String trace) {
        Map<String, Object> data() { return object(body.get("data")); }
        @Override public String toString() { return "TaskProbeReply[" + status + "," + trace + "]"; }
    }
    private static final class ProbeFailure extends RuntimeException {
        ProbeFailure(String check) { super(check); }
    }
}
