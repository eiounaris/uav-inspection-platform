package com.uav.inspection.database.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.common.persistence.UtcInstantTypeHandler;
import com.uav.inspection.device.persistence.DroneMapper;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.messaging.persistence.OutboxMapper;
import org.apache.ibatis.type.JdbcType;
import org.flywaydb.core.Flyway;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
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
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit real-MySQL CLI. It is neither a JUnit test nor part of the application JAR. */
public final class DatabaseProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private static final String DRONE_ID = "20000000-0000-4000-8000-000000000001";
    private static final String DRONE_CODE = "UAV-DEMO-001";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-21T00:00:00.123Z");
    private static final LocalDateTime FIXED_UTC = LocalDateTime.parse("2026-10-21T00:00:00.123");
    private static final String SNAPSHOT_DOMAIN = "T013-full-sixteen-table-snapshot-v1";
    private static final String ORIGINAL_FIFTEEN_SNAPSHOT_DOMAIN = "T012-full-fifteen-table-snapshot-v1";
    private static final String PREVIOUS_SNAPSHOT_DOMAIN = "T011-full-fourteen-table-snapshot-v1";
    private static final String ORIGINAL_SNAPSHOT_DOMAIN = "T010-full-eleven-table-snapshot-v1";
    private static final Map<String, String> TABLE_ORDER = new LinkedHashMap<>();
    private static final List<Account> ACCOUNTS = List.of(
            new Account(1, OWNER, "owner-admin", Set.of("ORG_ADMIN", "OWNER_TASK_ADMIN")),
            new Account(2, OWNER, "owner-reviewer", Set.of("OWNER_REVIEWER")),
            new Account(3, OWNER, "owner-approver", Set.of("OWNER_DATA_APPROVER")),
            new Account(4, OPERATOR, "operator-admin", Set.of("ORG_ADMIN", "OPERATOR_TASK_ADMIN")),
            new Account(5, OPERATOR, "operator-user", Set.of("OPERATOR_USER")),
            new Account(6, OPERATOR, "operator-outsider", Set.of("OPERATOR_USER")));

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
        TABLE_ORDER.put("upload_part", "session_id, part_no");
        TABLE_ORDER.put("evidence_version", "id");
        TABLE_ORDER.put("chain_submission", "id");
        TABLE_ORDER.put("outbox_event", "event_id");
        TABLE_ORDER.put("consumed_event", "consumer_code, event_id");
        TABLE_ORDER.put("chain_attempt", "id");
        TABLE_ORDER.put("verification_record", "id");
    }

    private final Map<String, Object> report = new LinkedHashMap<>();
    private final String phase;
    private final Path reportPath;
    private final Path compareReport;
    private final List<String> secrets = new ArrayList<>();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private DataSource dataSource;
    private Map<String, IdentityMapper.UserRow> users;
    private Path credentialsPath;
    private String credentialsDigest;

    private DatabaseProbe(String phase, Path reportPath, Path compareReport) {
        this.phase = phase;
        this.reportPath = reportPath.toAbsolutePath().normalize();
        this.compareReport = compareReport == null ? null : compareReport.toAbsolutePath().normalize();
        report.put("formatVersion", 1);
        report.put("phase", phase);
        report.put("pid", ProcessHandle.current().pid());
        report.put("startedAt", Instant.now().toString());
        report.put("passed", false);
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3 || !Set.of("migrate", "seed", "verify").contains(args[0])) {
            System.err.println("Usage: DatabaseProbe <migrate|seed|verify> <reportPath> [compareReport]");
            System.exit(2);
            return;
        }
        var probe = new DatabaseProbe(args[0], Path.of(args[1]), args.length == 3 ? Path.of(args[2]) : null);
        try {
            probe.execute();
            probe.writeReport();
            System.out.println("[PASS] T007 database phase " + probe.phase + ": " + probe.reportPath);
        } catch (Exception exception) {
            probe.report.put("passed", false);
            probe.report.put("failureType", exception.getClass().getSimpleName());
            if (exception instanceof ProbeFailure) {
                // ProbeFailure messages contain only our fixed check names and safe error codes.
                probe.report.put("failedCheck", exception.getMessage());
            }
            try {
                probe.writeReport();
            } catch (Exception reportFailure) {
                System.err.println("Could not write the database probe failure report.");
            }
            // SQL and JSON exception messages may contain values, so never print their text or causes.
            System.err.println("[FAIL] T007 database phase " + probe.phase + " ("
                    + exception.getClass().getSimpleName() + "); inspect the safe report metadata.");
            System.exit(1);
        }
    }

    private void execute() throws Exception {
        require(!reportPath.equals(compareReport), "comparison_report_must_be_a_separate_file");
        String[] profiles = phase.equals("migrate")
                ? new String[]{"local", "database"} : new String[]{"local", "database", "demo"};
        report.put("stage", "application_startup");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(UavInspectionApplication.class)
                .web(WebApplicationType.NONE).profiles(profiles).logStartupInfo(false).run()) {
            Set<String> actualProfiles = Set.copyOf(Arrays.asList(context.getEnvironment().getActiveProfiles()));
            require(actualProfiles.containsAll(Set.of("local", "database")) && !actualProfiles.contains("prod"),
                    "unexpected_database_probe_profiles");
            boolean demoEnabled = actualProfiles.contains("demo");
            require(demoEnabled == !phase.equals("migrate"), "demo_profile_does_not_match_phase");
            require(context.containsBean("initializeDemoData") == demoEnabled, "demo_initializer_profile_guard");
            report.put("activeProfiles", actualProfiles.stream().sorted().toList());
            report.put("demoDisabled", !demoEnabled);
            report.put("jvmTimeZone", ZoneId.systemDefault().getId());
            dataSource = context.getBean(DataSource.class);
            jdbc = context.getBean(JdbcTemplate.class);
            transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            report.put("stage", "connection_and_migration");
            inspectConnection();
            inspectMigration(context.getBean(Flyway.class));
            validateOrganizations(context.getBean(IdentityMapper.class));
            Map<String, Long> counts = counts();
            report.put("tableCounts", counts);
            report.put("emptyBusinessData", List.of("app_user", "inspection_task", "task_member", "drone",
                            "inspection_subtask", "material_requirement", "task_execution",
                            "upload_session", "upload_part", "evidence_version", "chain_submission",
                            "outbox_event", "consumed_event", "chain_attempt", "verification_record")
                    .stream().allMatch(table -> counts.get(table) == 0L));
            if (demoEnabled) {
                report.put("stage", "demo_data_validation");
                validateDemoData(context);
            }
            String before = snapshotDigest();
            report.put("businessSnapshotDomain", SNAPSHOT_DOMAIN);
            report.put("businessSnapshotSha256", before);
            report.put("originalFifteenSnapshotDomain", ORIGINAL_FIFTEEN_SNAPSHOT_DOMAIN);
            report.put("originalFifteenSnapshotSha256", snapshotDigest(ORIGINAL_FIFTEEN_SNAPSHOT_DOMAIN, 15));
            report.put("originalFourteenSnapshotDomain", PREVIOUS_SNAPSHOT_DOMAIN);
            report.put("originalFourteenSnapshotSha256", snapshotDigest(PREVIOUS_SNAPSHOT_DOMAIN, 14));
            report.put("originalElevenSnapshotDomain", ORIGINAL_SNAPSHOT_DOMAIN);
            report.put("originalElevenSnapshotSha256", snapshotDigest(ORIGINAL_SNAPSHOT_DOMAIN, 11));
            if (phase.equals("verify")) {
                report.put("stage", "rollback_constraints");
                verifyConstraints();
                report.put("stage", "outbox_lease_transitions");
                verifyOutboxLeaseTransitions(context.getBean(OutboxMapper.class));
                report.put("stage", "utc_round_trip");
                verifyUtcRoundTrip();
                require(before.equals(snapshotDigest()), "rollback_changed_persistent_business_data");
                report.put("allProbeWritesRolledBack", true);
            }
            if (demoEnabled) {
                require(credentialsDigest.equals(sha256(Files.readAllBytes(credentialsPath))),
                        "probe_changed_original_credential_file");
                report.put("credentialsFileSha256", credentialsDigest);
                report.put("knownCredentialsAbsentFromDatabase", true);
            }
            report.put("stage", "comparison");
            comparePreviousReport();
        }
        report.put("stage", "complete");
        report.put("completedAt", Instant.now().toString());
        report.put("passed", true);
        report.put("scope", "Real MySQL migration, Mapper reads, demo secret verification, and rollback-only "
                + "constraint/UTC probes; no authentication or task HTTP API is claimed.");
    }

    private void inspectConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT @@session.time_zone, @@session.transaction_isolation, VERSION()")) {
            require(result.next(), "missing_database_session_metadata");
            require(Set.of("+00:00", "UTC").contains(result.getString(1)), "database_session_is_not_utc");
            require("READ-COMMITTED".equals(result.getString(2)), "database_session_is_not_read_committed");
            require(connection.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED,
                    "jdbc_isolation_is_not_read_committed");
            var metadata = connection.getMetaData();
            require("MySQL".equals(metadata.getDatabaseProductName()), "probe_requires_real_mysql");
            require(metadata.getDriverName().contains("MySQL Connector/J"), "unexpected_jdbc_driver");
            require(result.getString(3).startsWith("8.4."), "unexpected_mysql_server_series");
            report.put("connection", Map.of("sessionTimeZone", result.getString(1),
                    "sessionIsolation", result.getString(2), "mysqlVersion", result.getString(3),
                    "driverName", metadata.getDriverName(), "driverVersion", metadata.getDriverVersion()));
        }
    }

    private void inspectMigration(Flyway flyway) {
        flyway.validate();
        var entries = jdbc.queryForList("""
                SELECT version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank
                """);
        Map<String, String> expectedScripts = Map.of("1", "V1__identity_and_task_scope.sql",
                "2", "V2__identity_audit.sql", "3", "V3__task_execution_scope.sql",
                "4", "V4__upload_sessions_and_versions.sql",
                "5", "V5__finalization_outbox_and_submission.sql", "6", "V6__chain_attempts.sql",
                "7", "V7__verification_records.sql");
        require(entries.size() == expectedScripts.size(), "expected_successful_v1_through_v7_migrations");
        Map<String, Integer> checksums = new LinkedHashMap<>();
        for (var entry : entries) {
            Object version = entry.get("version");
            require(version instanceof String && expectedScripts.containsKey(version)
                            && booleanValue(entry.get("success")) && entry.get("checksum") instanceof Number
                            && expectedScripts.get(version).equals(entry.get("script")),
                    "unexpected_flyway_history");
            require(checksums.put((String) version, ((Number) entry.get("checksum")).intValue()) == null,
                    "duplicate_flyway_migration_version");
        }
        require(checksums.keySet().equals(expectedScripts.keySet()), "incomplete_flyway_migration_history");
        Map<String, Integer> originalChecksums = Map.of("1", -539134757, "2", 1185045295,
                "3", -394507728, "4", -1021150431, "5", -507899413, "6", 186672578);
        require(originalChecksums.entrySet().stream().allMatch(entry -> entry.getValue().equals(checksums.get(entry.getKey()))),
                "original_v1_through_v6_migration_checksums_changed");
        report.put("originalMigrationChecksumsPreserved", true);
        // Keep the original V1 field for existing evidence readers, while comparing all applied migrations below.
        report.put("flywayChecksum", checksums.get("1"));
        report.put("fullMigrationChecksums", checksums);
        report.put("flyway", Map.of("version", "7", "successfulEntries", entries.size(),
                "checksums", checksums, "validationPassed", true));
    }

    private void validateOrganizations(IdentityMapper identities) {
        var organizations = identities.findOrganizations();
        require(organizations.size() == 2, "expected_two_organizations");
        for (var organization : organizations) {
            boolean owner = OWNER.equals(organization.id()) && "OrgA".equals(organization.code())
                    && "Org1MSP".equals(organization.mspId());
            boolean operator = OPERATOR.equals(organization.id()) && "OrgB".equals(organization.code())
                    && "Org2MSP".equals(organization.mspId());
            require((owner || operator) && organization.name() != null && !organization.name().isBlank(),
                    "organization_mapping_mismatch");
        }
        report.put("organizationMappingsVerified", true);
    }

    private void validateDemoData(ConfigurableApplicationContext context) throws IOException {
        credentialsPath = Path.of(context.getEnvironment().getRequiredProperty("inspection.demo.credentials"))
                .toAbsolutePath().normalize();
        byte[] file = Files.readAllBytes(credentialsPath);
        credentialsDigest = sha256(file);
        Credentials credentials = JSON.readValue(file, Credentials.class);
        require(credentials.formatVersion() == 1 && credentials.passwords() != null && credentials.droneTokens() != null,
                "invalid_credential_file_format");
        require(credentials.passwords().keySet().equals(Set.copyOf(ACCOUNTS.stream().map(Account::username).toList()))
                        && credentials.droneTokens().keySet().equals(Set.of(DRONE_CODE)),
                "credential_file_has_unexpected_accounts");
        credentials.passwords().values().forEach(password -> {
            require(password != null && password.matches("[A-Za-z0-9_-]{24,64}"), "invalid_demo_password_format");
            secrets.add(password);
        });
        String token = credentials.droneTokens().get(DRONE_CODE);
        require(token != null && token.matches("[0-9a-f]{64}"), "invalid_demo_device_token_format");
        secrets.add(token);
        var identities = context.getBean(IdentityMapper.class);
        var bcrypt = new BCryptPasswordEncoder();
        users = new LinkedHashMap<>();
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Account account : ACCOUNTS) {
            var user = identities.findUserByUsername(account.username());
            require(user != null && account.id().equals(user.id()) && account.orgId().equals(user.orgId())
                            && account.username().equals(user.username()), "demo_account_identity_mismatch");
            require(user.passwordHash() != null && user.passwordHash().length() == 60
                            && !user.passwordHash().equals(credentials.passwords().get(account.username()))
                            && bcrypt.matches(credentials.passwords().get(account.username()), user.passwordHash()),
                    "demo_password_does_not_match_bcrypt");
            String[] roles = JSON.readValue(user.rolesJson(), String[].class);
            require(roles != null && roles.length == account.roles().size()
                            && Set.copyOf(Arrays.asList(roles)).equals(account.roles()), "demo_account_roles_mismatch");
            require(user.enabled() && user.tokenVersion() == 0 && user.lockVersion() == 0
                            && user.createdAt() != null && user.updatedAt() != null, "demo_account_initial_state_mismatch");
            users.put(account.username(), user);
            accounts.add(Map.of("username", account.username(), "id", user.id(), "orgId", user.orgId(),
                    "roles", account.roles().stream().sorted().toList(), "bcryptVerified", true));
        }
        var drone = context.getBean(DroneMapper.class).findByCode(DRONE_CODE);
        require(drone != null && DRONE_ID.equals(drone.id()) && OPERATOR.equals(drone.operatorOrgId())
                        && DRONE_CODE.equals(drone.code()) && sha256(token.getBytes(StandardCharsets.UTF_8)).equals(drone.tokenHash())
                        && !token.equals(drone.tokenHash()), "demo_device_identity_or_token_hash_mismatch");
        require(drone.enabled() && drone.lockVersion() == 0 && drone.lastSeenAt() == null
                        && drone.createdAt() != null && drone.updatedAt() != null, "demo_device_initial_state_mismatch");
        report.put("accounts", accounts);
        report.put("device", Map.of("id", drone.id(), "code", drone.code(), "orgId", drone.operatorOrgId(),
                "sha256TokenVerified", true));
    }

    private void verifyConstraints() {
        Map<String, Object> checks = new LinkedHashMap<>();
        report.put("constraints", checks);
        Runnable noSetup = () -> {};
        String ownerAdmin = users.get("owner-admin").id();
        String operatorAdmin = users.get("operator-admin").id();
        String operatorUser = users.get("operator-user").id();
        checks.put("duplicateUsername", expectConstraint("duplicate_username", 1062, "23000", noSetup,
                () -> jdbc.update("""
                        INSERT INTO app_user (id, org_id, username, password_hash, roles)
                        SELECT ?, org_id, username, password_hash, roles FROM app_user WHERE id = ?
                        """, UUID.randomUUID().toString(), ownerAdmin)));
        checks.put("unknownRole", rolesConstraint("unknown_role", operatorUser, "[\"NOT_A_ROLE\"]"));
        checks.put("duplicateRole", rolesConstraint("duplicate_role", operatorUser,
                "[\"OPERATOR_USER\",\"OPERATOR_USER\"]"));
        checks.put("nonArrayRoles", rolesConstraint("non_array_roles", operatorUser,
                "{\"role\":\"OPERATOR_USER\"}"));
        checks.put("emptyRoles", rolesConstraint("empty_roles", operatorUser, "[]"));
        checks.put("ownerWithOperatorRole", rolesConstraint("owner_with_operator_role", ownerAdmin,
                "[\"OPERATOR_USER\"]"));
        checks.put("operatorWithOwnerRole", rolesConstraint("operator_with_owner_role", operatorUser,
                "[\"OWNER_REVIEWER\"]"));
        checks.put("wrongTaskOwner", expectConstraint("wrong_task_owner", 3819, "HY000", noSetup,
                () -> insertTask(operatorAdmin, OPERATOR, OPERATOR, 0)));
        checks.put("wrongTaskOperator", expectConstraint("wrong_task_operator", 3819, "HY000", noSetup,
                () -> insertTask(ownerAdmin, OWNER, OWNER, 0)));
        checks.put("nonOwnerTaskCreator", expectConstraint("non_owner_task_creator", 1452, "23000", noSetup,
                () -> insertTask(operatorAdmin, OWNER, OPERATOR, 0)));
        String missingTask = UUID.randomUUID().toString();
        checks.put("danglingMemberTask", expectConstraint("dangling_member_task", 1452, "23000", noSetup,
                () -> jdbc.update("INSERT INTO task_member (task_id, user_id) VALUES (?, ?)", missingTask, operatorUser)));
        String memberTask = UUID.randomUUID().toString();
        Runnable validTask = () -> insertTask(memberTask, ownerAdmin, OWNER, OPERATOR, 0);
        checks.put("danglingMemberUser", expectConstraint("dangling_member_user", 1452, "23000", validTask,
                () -> jdbc.update("INSERT INTO task_member (task_id, user_id) VALUES (?, ?)",
                        memberTask, UUID.randomUUID().toString())));
        Runnable validMember = () -> {
            validTask.run();
            require(jdbc.update("INSERT INTO task_member (task_id, user_id) VALUES (?, ?)", memberTask, operatorUser) == 1,
                    "valid_task_member_not_inserted");
        };
        checks.put("duplicateTaskMember", expectConstraint("duplicate_task_member", 1062, "23000", validMember,
                () -> jdbc.update("INSERT INTO task_member (task_id, user_id) VALUES (?, ?)", memberTask, operatorUser)));
        checks.put("duplicateDeviceCode", expectConstraint("duplicate_device_code", 1062, "23000", noSetup,
                () -> jdbc.update("""
                        INSERT INTO drone (id, operator_org_id, code, token_hash)
                        SELECT ?, operator_org_id, code, token_hash FROM drone WHERE id = ?
                        """, UUID.randomUUID().toString(), DRONE_ID)));
        checks.put("invalidDeviceHash", expectConstraint("invalid_device_hash", 3819, "HY000", noSetup,
                () -> jdbc.update("UPDATE drone SET token_hash = ? WHERE id = ?", "not-a-sha256-hash", DRONE_ID)));
        checks.put("wrongDeviceOperator", expectConstraint("wrong_device_operator", 3819, "HY000", noSetup,
                () -> jdbc.update("UPDATE drone SET operator_org_id = ? WHERE id = ?", OWNER, DRONE_ID)));
        checks.put("invalidPasswordHash", expectConstraint("invalid_password_hash", 3819, "HY000", noSetup,
                () -> jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = ?", "not-bcrypt", operatorUser)));
        checks.put("negativeUserLockVersion", expectConstraint("negative_user_lock_version", 3819, "HY000", noSetup,
                () -> jdbc.update("UPDATE app_user SET lock_version = -1 WHERE id = ?", operatorUser)));
        checks.put("negativeTaskLockVersion", expectConstraint("negative_task_lock_version", 3819, "HY000", noSetup,
                () -> insertTask(ownerAdmin, OWNER, OPERATOR, -1)));
        checks.put("negativeDeviceLockVersion", expectConstraint("negative_device_lock_version", 3819, "HY000", noSetup,
                () -> jdbc.update("UPDATE drone SET lock_version = -1 WHERE id = ?", DRONE_ID)));
        checks.put("restrictOrganizationDeletion", expectConstraint("restrict_organization_deletion", 1451, "23000",
                noSetup, () -> jdbc.update("DELETE FROM organization WHERE id = ?", OWNER)));
        checks.put("restrictTaskWithMemberDeletion", expectConstraint("restrict_task_with_member_deletion", 1451,
                "23000", validMember, () -> jdbc.update("DELETE FROM inspection_task WHERE id = ?", memberTask)));
        checks.put("restrictMemberUserDeletion", expectConstraint("restrict_member_user_deletion", 1451,
                "23000", validMember, () -> jdbc.update("DELETE FROM app_user WHERE id = ?", operatorUser)));
        checks.put("restrictTaskCreatorDeletion", expectConstraint("restrict_task_creator_deletion", 1451,
                "23000", validTask, () -> jdbc.update("DELETE FROM app_user WHERE id = ?", ownerAdmin)));
        verifyTaskExecutionConstraints(checks, ownerAdmin, memberTask, validTask);
        verifyUploadConstraints(checks, operatorUser, memberTask, validTask);
        verifyFinalizationConstraints(checks, operatorUser, memberTask, validTask);
        verifyChainAttemptConstraints(checks, operatorUser, memberTask, validTask);
        verifyVerificationConstraints(checks, operatorUser, memberTask, validTask);
        report.put("constraintCount", checks.size());
    }

    private void verifyTaskExecutionConstraints(Map<String, Object> checks, String ownerAdmin,
                                                 String taskId, Runnable validTask) {
        String subtaskId = UUID.randomUUID().toString();
        String otherSubtaskId = UUID.randomUUID().toString();
        String otherTaskId = UUID.randomUUID().toString();
        String executionId = UUID.randomUUID().toString();
        String requirementId = UUID.randomUUID().toString();
        String evidenceId = UUID.randomUUID().toString();
        Runnable validSubtask = () -> {
            validTask.run();
            insertSubtask(subtaskId, taskId);
        };
        Runnable twoTasks = () -> {
            validSubtask.run();
            insertTask(otherTaskId, ownerAdmin, OWNER, OPERATOR, 0);
        };
        checks.put("crossTaskRequirementBinding", expectConstraint("cross_task_requirement_binding", 1452,
                "23000", twoTasks,
                () -> insertRequirement(requirementId, evidenceId, otherTaskId, subtaskId, 1)));
        Runnable otherSubtaskExecution = () -> {
            validSubtask.run();
            insertSubtask(otherSubtaskId, taskId);
            insertExecution(executionId, otherSubtaskId, "READY", 0, null, null);
        };
        checks.put("foreignSubtaskCurrentExecution", expectConstraint("foreign_subtask_current_execution", 1452,
                "23000", otherSubtaskExecution,
                () -> jdbc.update("UPDATE inspection_subtask SET current_execution_id = ? WHERE id = ?",
                        executionId, subtaskId)));
        Runnable readyExecution = () -> {
            validSubtask.run();
            insertExecution(executionId, subtaskId, "READY", 0, null, null);
        };
        checks.put("secondActiveExecution", expectConstraint("second_active_execution", 1062, "23000",
                readyExecution, () -> insertExecution(UUID.randomUUID().toString(), subtaskId, "READY", 0, null, null)));
        checks.put("readyExecutionWithStart", expectConstraint("ready_execution_with_start", 3819, "HY000",
                validSubtask, () -> insertExecution(executionId, subtaskId, "READY", 0, FIXED_UTC, null)));
        checks.put("readyExecutionWithClose", expectConstraint("ready_execution_with_close", 3819, "HY000",
                validSubtask, () -> insertExecution(executionId, subtaskId, "READY", 0, null, FIXED_UTC)));
        checks.put("runningExecutionWithoutStart", expectConstraint("running_execution_without_start", 3819, "HY000",
                validSubtask, () -> insertExecution(executionId, subtaskId, "RUNNING", 0, null, null)));
        checks.put("runningExecutionWithClose", expectConstraint("running_execution_with_close", 3819, "HY000",
                validSubtask, () -> insertExecution(executionId, subtaskId, "RUNNING", 0, FIXED_UTC, FIXED_UTC)));
        checks.put("negativeExecutionProgress", expectConstraint("negative_execution_progress", 3819, "HY000",
                validSubtask, () -> insertExecution(executionId, subtaskId, "READY", -1, null, null)));
        checks.put("executionProgressAboveHundred", expectConstraint("execution_progress_above_hundred", 3819, "HY000",
                validSubtask, () -> insertExecution(executionId, subtaskId, "READY", 101, null, null)));
        checks.put("zeroNextMaterialVersion", expectConstraint("zero_next_material_version", 3819, "HY000",
                validSubtask, () -> insertRequirement(requirementId, evidenceId, taskId, subtaskId, 0)));
        Runnable validRequirement = () -> {
            validSubtask.run();
            insertRequirement(requirementId, evidenceId, taskId, subtaskId, 1);
        };
        checks.put("duplicateEvidenceIdentifier", expectConstraint("duplicate_evidence_identifier", 1062, "23000",
                validRequirement,
                () -> insertRequirement(UUID.randomUUID().toString(), evidenceId, taskId, subtaskId, 1)));
    }

    private void verifyUploadConstraints(Map<String, Object> checks, String operatorUser,
                                         String taskId, Runnable validTask) {
        String subtaskId = UUID.randomUUID().toString();
        String requirementId = UUID.randomUUID().toString();
        String evidenceId = UUID.randomUUID().toString();
        String executionId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        String clientRequestId = UUID.randomUUID().toString();
        String otherRequirementId = UUID.randomUUID().toString();
        String otherEvidenceId = UUID.randomUUID().toString();
        String otherSubtaskId = UUID.randomUUID().toString();
        String otherExecutionId = UUID.randomUUID().toString();
        String partKey = "upload-parts/" + UUID.randomUUID();
        String validHash = "a".repeat(64);
        Runnable validScope = () -> {
            validTask.run();
            insertSubtask(subtaskId, taskId);
            insertRequirement(requirementId, evidenceId, taskId, subtaskId, 1);
            insertExecution(executionId, subtaskId, "RUNNING", 0, FIXED_UTC, null);
        };
        Runnable validSession = () -> {
            validScope.run();
            insertUploadSession(sessionId, taskId, subtaskId, requirementId, evidenceId,
                    executionId, operatorUser, clientRequestId);
        };
        checks.put("duplicateUploadActorRequest", expectConstraint("duplicate_upload_actor_request", 1062,
                "23000", validSession, () -> insertUploadSession(UUID.randomUUID().toString(), taskId,
                        subtaskId, requirementId, evidenceId, executionId, operatorUser, clientRequestId)));
        checks.put("invalidUploadRequestHash", expectConstraint("invalid_upload_request_hash", 3819,
                "HY000", validSession,
                () -> jdbc.update("UPDATE upload_session SET request_hash = ? WHERE id = ?", "not-a-sha256", sessionId)));
        Runnable sessionAndOtherRequirement = () -> {
            validSession.run();
            insertRequirement(otherRequirementId, otherEvidenceId, taskId, subtaskId, 1);
        };
        checks.put("mismatchedUploadRequirementEvidence", expectConstraint("mismatched_upload_requirement_evidence",
                1452, "23000", sessionAndOtherRequirement,
                () -> jdbc.update("UPDATE upload_session SET evidence_id = ? WHERE id = ?", otherEvidenceId, sessionId)));
        Runnable sessionAndOtherExecution = () -> {
            validSession.run();
            insertSubtask(otherSubtaskId, taskId);
            insertExecution(otherExecutionId, otherSubtaskId, "RUNNING", 0, FIXED_UTC, null);
        };
        checks.put("foreignSubtaskUploadExecution", expectConstraint("foreign_subtask_upload_execution", 1452,
                "23000", sessionAndOtherExecution,
                () -> jdbc.update("UPDATE upload_session SET execution_id = ? WHERE id = ?", otherExecutionId, sessionId)));
        checks.put("invalidUploadGeometry", expectConstraint("invalid_upload_geometry", 3819,
                "HY000", validSession,
                () -> jdbc.update("UPDATE upload_session SET part_count = 2 WHERE id = ?", sessionId)));
        checks.put("unpairedUploadLease", expectConstraint("unpaired_upload_lease", 3819,
                "HY000", validSession,
                () -> jdbc.update("UPDATE upload_session SET lease_token = ? WHERE id = ?",
                        UUID.randomUUID().toString(), sessionId)));
        checks.put("invalidUploadExpiry", expectConstraint("invalid_upload_expiry", 3819,
                "HY000", validSession,
                () -> jdbc.update("UPDATE upload_session SET expires_at = created_at WHERE id = ?", sessionId)));
        checks.put("zeroUploadPartNumber", expectConstraint("zero_upload_part_number", 3819,
                "HY000", validSession, () -> insertUploadPart(sessionId, 0, validHash, partKey)));
        checks.put("invalidUploadPartHash", expectConstraint("invalid_upload_part_hash", 3819,
                "HY000", validSession, () -> insertUploadPart(sessionId, 1, "not-a-sha256", partKey)));
        Runnable sessionAndPart = () -> {
            validSession.run();
            insertUploadPart(sessionId, 1, validHash, partKey);
        };
        checks.put("duplicateUploadPart", expectConstraint("duplicate_upload_part", 1062,
                "23000", sessionAndPart,
                () -> insertUploadPart(sessionId, 1, validHash, "upload-parts/" + UUID.randomUUID())));
        String secondSessionId = UUID.randomUUID().toString();
        Runnable twoSessionsAndPart = () -> {
            sessionAndPart.run();
            insertUploadSession(secondSessionId, taskId, subtaskId, requirementId, evidenceId,
                    executionId, operatorUser, UUID.randomUUID().toString());
        };
        checks.put("duplicateUploadObjectKey", expectConstraint("duplicate_upload_object_key", 1062,
                "23000", twoSessionsAndPart,
                () -> insertUploadPart(secondSessionId, 1, validHash, partKey)));
        checks.put("mismatchedVersionUploadSession", expectConstraint("mismatched_version_upload_session", 1452,
                "23000", sessionAndOtherRequirement,
                () -> insertEvidenceVersion(UUID.randomUUID().toString(), sessionId, taskId, subtaskId,
                        otherRequirementId, otherEvidenceId, executionId, operatorUser, null, 1)));
        checks.put("ambiguousVersionUploader", expectConstraint("ambiguous_version_uploader", 3819,
                "HY000", validSession,
                () -> insertEvidenceVersion(UUID.randomUUID().toString(), sessionId, taskId, subtaskId,
                        requirementId, evidenceId, executionId, operatorUser, DRONE_ID, 1)));
        Runnable sessionAndVersion = () -> {
            validSession.run();
            insertEvidenceVersion(UUID.randomUUID().toString(), sessionId, taskId, subtaskId,
                    requirementId, evidenceId, executionId, operatorUser, null, 1);
        };
        checks.put("duplicateVersionUploadSession", expectConstraint("duplicate_version_upload_session", 1062,
                "23000", sessionAndVersion,
                () -> insertEvidenceVersion(UUID.randomUUID().toString(), sessionId, taskId, subtaskId,
                        requirementId, evidenceId, executionId, operatorUser, null, 2)));
        report.put("uploadConstraintScope", "Rollback-only upload/version scope and uniqueness checks; business finalization is verified separately.");
    }

    private void verifyFinalizationConstraints(Map<String, Object> checks, String operatorUser,
                                               String taskId, Runnable validTask) {
        Runnable noSetup = () -> {};
        String eventId = UUID.randomUUID().toString();
        String businessId = UUID.randomUUID().toString();
        Runnable validOutbox = () -> insertOutboxEvent(eventId, businessId);
        checks.put("duplicateOutboxDispatch", expectConstraint("duplicate_outbox_dispatch", 1062,
                "23000", validOutbox, () -> insertOutboxEvent(UUID.randomUUID().toString(), businessId)));
        checks.put("nonObjectOutboxPayload", expectConstraint("non_object_outbox_payload", 3819,
                "HY000", validOutbox,
                () -> jdbc.update("UPDATE outbox_event SET payload = JSON_ARRAY() WHERE event_id = ?", eventId)));
        checks.put("sendingOutboxWithoutLease", expectConstraint("sending_outbox_without_lease", 3819,
                "HY000", validOutbox,
                () -> jdbc.update("UPDATE outbox_event SET status = 'SENDING' WHERE event_id = ?", eventId)));
        checks.put("pendingOutboxWithLease", expectConstraint("pending_outbox_with_lease", 3819,
                "HY000", validOutbox, () -> jdbc.update("""
                        UPDATE outbox_event SET lease_token = ?, lease_until = CURRENT_TIMESTAMP(3)
                        WHERE event_id = ?
                        """, UUID.randomUUID().toString(), eventId)));
        checks.put("sentOutboxWithoutConfirmationTime", expectConstraint("sent_outbox_without_confirmation_time", 3819,
                "HY000", validOutbox,
                () -> jdbc.update("UPDATE outbox_event SET status = 'SENT' WHERE event_id = ?", eventId)));
        checks.put("pendingOutboxWithSentTime", expectConstraint("pending_outbox_with_sent_time", 3819,
                "HY000", validOutbox,
                () -> jdbc.update("UPDATE outbox_event SET sent_at = CURRENT_TIMESTAMP(3) WHERE event_id = ?", eventId)));
        checks.put("negativeOutboxAttemptCount", expectConstraint("negative_outbox_attempt_count", 3819,
                "HY000", validOutbox,
                () -> jdbc.update("UPDATE outbox_event SET attempt_count = -1 WHERE event_id = ?", eventId)));
        checks.put("unsafeOutboxErrorText", expectConstraint("unsafe_outbox_error_text", 3819,
                "HY000", validOutbox,
                () -> jdbc.update("UPDATE outbox_event SET last_error = ? WHERE event_id = ?",
                        "provider diagnostics must not be persisted", eventId)));
        Runnable consumed = () -> jdbc.update("""
                INSERT INTO consumed_event (consumer_code, event_id, status) VALUES ('FILE_FINALIZE', ?, 'PROCESSING')
                """, eventId);
        checks.put("duplicateConsumerEvent", expectConstraint("duplicate_consumer_event", 1062,
                "23000", consumed, consumed));
        checks.put("invalidConsumedEventStatus", expectConstraint("invalid_consumed_event_status", 3819,
                "HY000", consumed,
                () -> jdbc.update("UPDATE consumed_event SET status = 'ACKED' WHERE event_id = ?", eventId)));
        checks.put("unsafeConsumerCode", expectConstraint("unsafe_consumer_code", 3819,
                "HY000", noSetup, () -> jdbc.update("""
                        INSERT INTO consumed_event (consumer_code, event_id, status) VALUES ('unowned-code', ?, 'DONE')
                        """, eventId)));
        String subtaskId = UUID.randomUUID().toString();
        String requirementId = UUID.randomUUID().toString();
        String evidenceId = UUID.randomUUID().toString();
        String executionId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        String submissionId = UUID.randomUUID().toString();
        Runnable validVersion = () -> {
            validTask.run();
            insertSubtask(subtaskId, taskId);
            insertRequirement(requirementId, evidenceId, taskId, subtaskId, 1);
            insertExecution(executionId, subtaskId, "RUNNING", 0, FIXED_UTC, null);
            insertUploadSession(sessionId, taskId, subtaskId, requirementId, evidenceId,
                    executionId, operatorUser, UUID.randomUUID().toString());
            insertEvidenceVersion(versionId, sessionId, taskId, subtaskId, requirementId, evidenceId,
                    executionId, operatorUser, null, 1);
        };
        Runnable validSubmission = () -> {
            validVersion.run();
            insertChainSubmission(submissionId, versionId);
        };
        checks.put("danglingChainVersion", expectConstraint("dangling_chain_version", 1452,
                "23000", noSetup, () -> insertChainSubmission(submissionId, UUID.randomUUID().toString())));
        checks.put("duplicateChainVersion", expectConstraint("duplicate_chain_version", 1062,
                "23000", validSubmission,
                () -> insertChainSubmission(UUID.randomUUID().toString(), versionId)));
        checks.put("invalidChainStatus", expectConstraint("invalid_chain_status", 3819,
                "HY000", validSubmission,
                () -> jdbc.update("UPDATE chain_submission SET status = 'QUEUED' WHERE id = ?", submissionId)));
        checks.put("chainConfirmedWithoutTransaction", expectConstraint("chain_confirmed_without_transaction", 3819,
                "HY000", validSubmission,
                () -> jdbc.update("UPDATE chain_submission SET status = 'CONFIRMED' WHERE id = ?", submissionId)));
        checks.put("invalidChainTransaction", expectConstraint("invalid_chain_transaction", 3819,
                "HY000", validSubmission,
                () -> jdbc.update("UPDATE chain_submission SET confirmed_tx_id = ? WHERE id = ?", "not-a-txid", submissionId)));
        checks.put("negativeChainRetryCount", expectConstraint("negative_chain_retry_count", 3819,
                "HY000", validSubmission,
                () -> jdbc.update("UPDATE chain_submission SET retry_count = -1 WHERE id = ?", submissionId)));
        checks.put("unpairedChainLease", expectConstraint("unpaired_chain_lease", 3819,
                "HY000", validSubmission,
                () -> jdbc.update("UPDATE chain_submission SET lease_token = ? WHERE id = ?",
                        UUID.randomUUID().toString(), submissionId)));
        checks.put("restrictAnchoredVersionDeletion", expectConstraint("restrict_anchored_version_deletion", 1451,
                "23000", validSubmission,
                () -> jdbc.update("DELETE FROM evidence_version WHERE id = ?", versionId)));
        report.put("finalizationConstraintScope", "Rollback-only Outbox routing hand-off shape, consumer uniqueness, and one submission per immutable version.");
    }

    private void insertOutboxEvent(String eventId, String businessId) {
        require(jdbc.update("""
                INSERT INTO outbox_event (event_id, event_type, biz_id, payload)
                VALUES (?, 'FILE_FINALIZE_REQUESTED', ?, JSON_OBJECT(
                    'eventId', ?, 'eventType', 'FILE_FINALIZE_REQUESTED', 'bizId', ?, 'traceId', 'T011-rollback-probe'))
                """, eventId, businessId, eventId, businessId) == 1, "temporary_outbox_event_not_inserted");
    }

    private void insertChainSubmission(String id, String versionId) {
        require(jdbc.update("INSERT INTO chain_submission (id, version_id) VALUES (?, ?)", id, versionId) == 1,
                "temporary_chain_submission_not_inserted");
    }

    private void verifyChainAttemptConstraints(Map<String, Object> checks, String operatorUser,
                                               String taskId, Runnable validTask) {
        String subtaskId = UUID.randomUUID().toString();
        String requirementId = UUID.randomUUID().toString();
        String evidenceId = UUID.randomUUID().toString();
        String executionId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        String submissionId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        String transactionId = "c".repeat(64);
        byte[] transactionBytes = new byte[]{1, 3, 5, 7};
        byte[] commitBytes = new byte[]{2, 4, 6, 8};
        Runnable validSubmission = () -> {
            validTask.run();
            insertSubtask(subtaskId, taskId);
            insertRequirement(requirementId, evidenceId, taskId, subtaskId, 1);
            insertExecution(executionId, subtaskId, "RUNNING", 0, FIXED_UTC, null);
            insertUploadSession(sessionId, taskId, subtaskId, requirementId, evidenceId,
                    executionId, operatorUser, UUID.randomUUID().toString());
            insertEvidenceVersion(versionId, sessionId, taskId, subtaskId, requirementId, evidenceId,
                    executionId, operatorUser, null, 1);
            insertChainSubmission(submissionId, versionId);
        };
        Runnable validAttempt = () -> {
            validSubmission.run();
            insertChainAttempt(attemptId, submissionId, 1, transactionId, transactionBytes);
        };
        checks.put("danglingAttemptSubmission", expectConstraint("dangling_attempt_submission", 1452,
                "23000", () -> {}, () -> insertChainAttempt(attemptId, UUID.randomUUID().toString(), 1,
                        transactionId, transactionBytes)));
        checks.put("duplicateAttemptNumber", expectConstraint("duplicate_attempt_number", 1062,
                "23000", validAttempt, () -> insertChainAttempt(UUID.randomUUID().toString(), submissionId, 1,
                        "d".repeat(64), transactionBytes)));
        checks.put("duplicateAttemptTransaction", expectConstraint("duplicate_attempt_transaction", 1062,
                "23000", validAttempt, () -> insertChainAttempt(UUID.randomUUID().toString(), submissionId, 2,
                        transactionId, transactionBytes)));
        checks.put("zeroAttemptNumber", expectConstraint("zero_attempt_number", 3819,
                "HY000", validSubmission, () -> insertChainAttempt(attemptId, submissionId, 0,
                        transactionId, transactionBytes)));
        checks.put("invalidAttemptTransaction", expectConstraint("invalid_attempt_transaction", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET tx_id = ? WHERE id = ?", "C".repeat(64), attemptId)));
        checks.put("invalidAttemptPhase", expectConstraint("invalid_attempt_phase", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET phase = 'CONFIRMED' WHERE id = ?", attemptId)));
        checks.put("emptyTransactionCheckpoint", expectConstraint("empty_transaction_checkpoint", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET transaction_bytes = ? WHERE id = ?", new byte[0], attemptId)));
        checks.put("emptyCommitCheckpoint", expectConstraint("empty_commit_checkpoint", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET commit_bytes = ? WHERE id = ?", new byte[0], attemptId)));
        checks.put("submittedWithoutCommitCheckpoint", expectConstraint("submitted_without_commit_checkpoint", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET phase = 'SUBMITTED' WHERE id = ?", attemptId)));
        checks.put("validationWithoutBlock", expectConstraint("validation_without_block", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET validation_code = 0 WHERE id = ?", attemptId)));
        checks.put("blockWithoutValidation", expectConstraint("block_without_validation", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET block_number = 0 WHERE id = ?", attemptId)));
        checks.put("negativeValidationCode", expectConstraint("negative_validation_code", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET validation_code = -1, block_number = 0 WHERE id = ?", attemptId)));
        checks.put("negativeBlockNumber", expectConstraint("negative_block_number", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET validation_code = 0, block_number = -1 WHERE id = ?", attemptId)));
        checks.put("validAttemptWithoutOutcome", expectConstraint("valid_attempt_without_outcome", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET phase = 'VALID' WHERE id = ?", attemptId)));
        checks.put("validAttemptWithInvalidCode", expectConstraint("valid_attempt_with_invalid_code", 3819,
                "HY000", validAttempt, () -> jdbc.update("""
                        UPDATE chain_attempt SET phase = 'VALID', validation_code = 1, block_number = 0 WHERE id = ?
                        """, attemptId)));
        checks.put("invalidAttemptWithoutOutcome", expectConstraint("invalid_attempt_without_outcome", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET phase = 'INVALID' WHERE id = ?", attemptId)));
        checks.put("invalidAttemptWithValidCode", expectConstraint("invalid_attempt_with_valid_code", 3819,
                "HY000", validAttempt, () -> jdbc.update("""
                        UPDATE chain_attempt SET phase = 'INVALID', validation_code = 0, block_number = 0 WHERE id = ?
                        """, attemptId)));
        checks.put("unsafeAttemptErrorText", expectConstraint("unsafe_attempt_error_text", 3819,
                "HY000", validAttempt,
                () -> jdbc.update("UPDATE chain_attempt SET last_error = ? WHERE id = ?", "RPC provider diagnostic", attemptId)));
        checks.put("restrictAttemptSubmissionDeletion", expectConstraint("restrict_attempt_submission_deletion", 1451,
                "23000", validAttempt,
                () -> jdbc.update("DELETE FROM chain_submission WHERE id = ?", submissionId)));
        Map<String, Object> checkpoint = transactions.execute(transaction -> {
            transaction.setRollbackOnly();
            validAttempt.run();
            Map<String, Object> prepared = jdbc.queryForMap("""
                    SELECT tx_id, transaction_bytes, commit_bytes FROM chain_attempt WHERE id = ?
                    """, attemptId);
            require(transactionId.equals(prepared.get("tx_id"))
                            && Arrays.equals(transactionBytes, (byte[]) prepared.get("transaction_bytes"))
                            && prepared.get("commit_bytes") == null,
                    "prepared_checkpoint_round_trip_changed");
            require(jdbc.update("""
                    UPDATE chain_attempt SET phase = 'UNKNOWN', last_error = 'SUBMIT_UNCERTAIN' WHERE id = ?
                    """, attemptId) == 1, "unknown_without_commit_checkpoint_not_saved");
            require(jdbc.update("""
                    UPDATE chain_attempt SET phase = 'SUBMITTED', commit_bytes = ?, last_error = NULL WHERE id = ?
                    """, commitBytes, attemptId) == 1, "commit_checkpoint_not_saved");
            require(jdbc.update("""
                    UPDATE chain_attempt SET phase = 'VALID', validation_code = 0, block_number = 42 WHERE id = ?
                    """, attemptId) == 1, "valid_commit_outcome_not_saved");
            Map<String, Object> committed = jdbc.queryForMap("""
                    SELECT tx_id, transaction_bytes, commit_bytes, validation_code, block_number
                    FROM chain_attempt WHERE id = ?
                    """, attemptId);
            require(transactionId.equals(committed.get("tx_id"))
                            && Arrays.equals(transactionBytes, (byte[]) committed.get("transaction_bytes"))
                            && Arrays.equals(commitBytes, (byte[]) committed.get("commit_bytes"))
                            && ((Number) committed.get("validation_code")).intValue() == 0
                            && ((Number) committed.get("block_number")).longValue() == 42,
                    "submitted_checkpoint_or_outcome_round_trip_changed");
            return Map.of("transactionBytesPreserved", true, "commitBytesPreserved", true,
                    "unknownWithoutCommitAllowed", true, "transactionIdPreserved", true,
                    "validationOutcomePreserved", true, "rolledBack", true,
                    "scope", "Synthetic schema-only checkpoint bytes; no Fabric result is claimed.");
        });
        require(checkpoint != null, "missing_checkpoint_round_trip_result");
        report.put("chainCheckpointRoundTrip", checkpoint);
    }

    private void insertChainAttempt(String id, String submissionId, int number, String transactionId, byte[] bytes) {
        require(jdbc.update("""
                INSERT INTO chain_attempt(id,submission_id,attempt_no,tx_id,phase,transaction_bytes)
                VALUES (?, ?, ?, ?, 'PREPARED', ?)
                """, id, submissionId, number, transactionId, bytes) == 1, "temporary_chain_attempt_not_inserted");
    }

    private void verifyVerificationConstraints(Map<String, Object> checks, String operatorUser,
                                                String taskId, Runnable validTask) {
        String subtaskId = UUID.randomUUID().toString();
        String requirementId = UUID.randomUUID().toString();
        String evidenceId = UUID.randomUUID().toString();
        String executionId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        String verifierId = UUID.randomUUID().toString();
        String recordId = UUID.randomUUID().toString();
        String hash = "e".repeat(64);
        String otherHash = "d".repeat(64);
        String transactionId = "f".repeat(64);
        Runnable validVersion = () -> {
            validTask.run();
            insertSubtask(subtaskId, taskId);
            insertRequirement(requirementId, evidenceId, taskId, subtaskId, 1);
            insertExecution(executionId, subtaskId, "RUNNING", 0, FIXED_UTC, null);
            insertUploadSession(sessionId, taskId, subtaskId, requirementId, evidenceId,
                    executionId, operatorUser, UUID.randomUUID().toString());
            insertEvidenceVersion(versionId, sessionId, taskId, subtaskId, requirementId, evidenceId,
                    executionId, operatorUser, null, 1);
            require(jdbc.update("""
                    INSERT INTO app_user (id, org_id, username, password_hash, roles)
                    SELECT ?, org_id, ?, password_hash, roles FROM app_user WHERE id = ?
                    """, verifierId, "verification-probe-" + UUID.randomUUID(), operatorUser) == 1,
                    "temporary_verifier_not_inserted");
        };
        Runnable validRecord = () -> {
            validVersion.run();
            insertVerificationRecord(recordId, versionId, verifierId, "STORED", hash, hash,
                    4L, transactionId, "MATCH", "HASH_MATCH");
        };
        checks.put("danglingVerificationVersion", expectConstraint("dangling_verification_version", 1452,
                "23000", validRecord, () -> jdbc.update("UPDATE verification_record SET version_id = ? WHERE id = ?",
                        UUID.randomUUID().toString(), recordId)));
        checks.put("danglingVerificationUser", expectConstraint("dangling_verification_user", 1452,
                "23000", validRecord, () -> jdbc.update("UPDATE verification_record SET user_id = ? WHERE id = ?",
                        UUID.randomUUID().toString(), recordId)));
        checks.put("duplicateVerificationId", expectConstraint("duplicate_verification_id", 1062,
                "23000", validRecord, () -> insertVerificationRecord(recordId, versionId, verifierId,
                        "COPY", hash, hash, 4L, transactionId, "MATCH", "HASH_MATCH")));
        checks.put("invalidVerificationSource", expectConstraint("invalid_verification_source", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET source = 'LINK' WHERE id = ?", recordId)));
        checks.put("invalidVerificationActualHash", expectConstraint("invalid_verification_actual_hash", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET actual_sha256 = ? WHERE id = ?", "not-a-sha256", recordId)));
        checks.put("invalidVerificationLedgerHash", expectConstraint("invalid_verification_ledger_hash", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET ledger_sha256 = ? WHERE id = ?", "not-a-sha256", recordId)));
        checks.put("negativeVerificationActualSize", expectConstraint("negative_verification_actual_size", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET actual_size = -1 WHERE id = ?", recordId)));
        checks.put("invalidVerificationTransaction", expectConstraint("invalid_verification_transaction", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET ledger_tx_id = ? WHERE id = ?", "not-a-txid", recordId)));
        checks.put("invalidVerificationResult", expectConstraint("invalid_verification_result", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET result = 'UNKNOWN' WHERE id = ?", recordId)));
        checks.put("unsafeVerificationReason", expectConstraint("unsafe_verification_reason", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET reason = ? WHERE id = ?", "provider error text", recordId)));
        checks.put("verificationHashWithoutSize", expectConstraint("verification_hash_without_size", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET actual_size = NULL WHERE id = ?", recordId)));
        checks.put("verificationLedgerWithoutTransaction", expectConstraint("verification_ledger_without_transaction", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET ledger_tx_id = NULL WHERE id = ?", recordId)));
        checks.put("matchWithDifferentHashes", expectConstraint("match_with_different_hashes", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET actual_sha256 = ? WHERE id = ?", otherHash, recordId)));
        checks.put("matchWithoutActualHash", expectConstraint("match_without_actual_hash", 3819,
                "HY000", validRecord, () -> jdbc.update("""
                        UPDATE verification_record SET actual_sha256 = NULL, actual_size = NULL WHERE id = ?
                        """, recordId)));
        checks.put("matchWithoutLedgerHash", expectConstraint("match_without_ledger_hash", 3819,
                "HY000", validRecord, () -> jdbc.update("""
                        UPDATE verification_record SET ledger_sha256 = NULL, ledger_tx_id = NULL WHERE id = ?
                        """, recordId)));
        checks.put("mismatchWithSameHashes", expectConstraint("mismatch_with_same_hashes", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET result = 'MISMATCH' WHERE id = ?", recordId)));
        checks.put("mismatchWithoutActualHash", expectConstraint("mismatch_without_actual_hash", 3819,
                "HY000", validRecord, () -> jdbc.update("""
                        UPDATE verification_record SET result = 'MISMATCH', actual_sha256 = NULL, actual_size = NULL WHERE id = ?
                        """, recordId)));
        checks.put("unconfirmedWithLedgerRecord", expectConstraint("unconfirmed_with_ledger_record", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET result = 'ANCHOR_UNCONFIRMED' WHERE id = ?", recordId)));
        checks.put("chainFailureWithLedgerRecord", expectConstraint("chain_failure_with_ledger_record", 3819,
                "HY000", validRecord,
                () -> jdbc.update("UPDATE verification_record SET result = 'CHAIN_QUERY_FAILED' WHERE id = ?", recordId)));
        checks.put("unconfirmedWithoutFileRead", expectConstraint("unconfirmed_without_file_read", 3819,
                "HY000", validRecord, () -> jdbc.update("""
                        UPDATE verification_record SET result = 'ANCHOR_UNCONFIRMED', actual_sha256 = NULL,
                            actual_size = NULL, ledger_sha256 = NULL, ledger_tx_id = NULL WHERE id = ?
                        """, recordId)));
        checks.put("fileFailureWithActualHash", expectConstraint("file_failure_with_actual_hash", 3819,
                "HY000", validRecord, () -> jdbc.update("""
                        UPDATE verification_record SET result = 'FILE_READ_FAILED', ledger_sha256 = NULL,
                            ledger_tx_id = NULL WHERE id = ?
                        """, recordId)));
        checks.put("restrictVerifiedVersionDeletion", expectConstraint("restrict_verified_version_deletion", 1451,
                "23000", validRecord,
                () -> jdbc.update("DELETE FROM evidence_version WHERE id = ?", versionId)));
        checks.put("restrictVerifierDeletion", expectConstraint("restrict_verifier_deletion", 1451,
                "23000", validRecord,
                () -> jdbc.update("DELETE FROM app_user WHERE id = ?", verifierId)));
        Map<String, Object> history = transactions.execute(transaction -> {
            transaction.setRollbackOnly();
            validVersion.run();
            insertVerificationRecord(UUID.randomUUID().toString(), versionId, verifierId, "STORED", hash, hash,
                    4L, transactionId, "MATCH", "HASH_MATCH");
            insertVerificationRecord(UUID.randomUUID().toString(), versionId, verifierId, "COPY", otherHash, hash,
                    4L, transactionId, "MISMATCH", "HASH_MISMATCH");
            insertVerificationRecord(UUID.randomUUID().toString(), versionId, verifierId, "STORED", hash, null,
                    4L, null, "ANCHOR_UNCONFIRMED", "LEDGER_RECORD_ABSENT");
            insertVerificationRecord(UUID.randomUUID().toString(), versionId, verifierId, "COPY", null, null,
                    null, null, "FILE_READ_FAILED", "FILE_INPUT_UNAVAILABLE");
            insertVerificationRecord(UUID.randomUUID().toString(), versionId, verifierId, "STORED", hash, null,
                    4L, null, "CHAIN_QUERY_FAILED", "LEDGER_QUERY_UNAVAILABLE");
            require(Long.valueOf(5).equals(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM verification_record WHERE version_id = ? AND user_id = ?", Long.class,
                    versionId, verifierId)), "verification_history_not_appendable");
            require(Long.valueOf(5).equals(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM verification_record WHERE version_id = ?
                      AND verified_at <= UTC_TIMESTAMP(3)
                      AND verified_at >= TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(3))
                    """, Long.class, versionId)), "verification_timestamp_not_database_utc_default");
            return Map.of("allFiveResultsAccepted", true, "storedAndCopyAccepted", true,
                    "sameVersionAndUserHistoryAppendable", true, "databaseUtcDefaultVerified", true,
                    "rolledBack", true, "scope", "Schema-only synthetic result fixtures; no file or ledger verification is claimed.");
        });
        require(history != null, "missing_verification_record_round_trip_result");
        report.put("verificationRecordRoundTrip", history);
    }

    private void insertVerificationRecord(String id, String versionId, String userId, String source,
                                          String actualHash, String ledgerHash, Long actualSize,
                                          String transactionId, String result, String reason) {
        require(jdbc.update("""
                INSERT INTO verification_record
                    (id,version_id,user_id,source,actual_sha256,ledger_sha256,actual_size,ledger_tx_id,result,reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, versionId, userId, source, actualHash, ledgerHash, actualSize, transactionId, result, reason) == 1,
                "temporary_verification_record_not_inserted");
    }

    private void verifyOutboxLeaseTransitions(OutboxMapper mapper) {
        Map<String, Object> result = transactions.execute(transaction -> {
            transaction.setRollbackOnly();
            String id = UUID.randomUUID().toString();
            String token = UUID.randomUUID().toString();
            String stale = UUID.randomUUID().toString();
            insertOutboxEvent(id, UUID.randomUUID().toString());
            require(mapper.claim(id, token) == 1, "outbox_initial_lease_not_claimed");
            require(mapper.sent(id, stale) == 0, "outbox_stale_token_marked_sent");
            require(mapper.retry(id, stale, "PUBLISH_NACK", 2) == 0, "outbox_stale_token_scheduled_retry");
            require("SENDING".equals(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?",
                    String.class, id)), "outbox_stale_owner_changed_status");
            require(jdbc.update("""
                    UPDATE outbox_event SET lease_until = TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3))
                    WHERE event_id = ?
                    """, id) == 1, "outbox_probe_lease_not_expired");
            require(mapper.sent(id, token) == 0, "outbox_expired_owner_marked_sent");
            require(mapper.retry(id, token, "PUBLISH_NACK", 2) == 0, "outbox_expired_owner_scheduled_retry");
            require(jdbc.update("""
                    UPDATE outbox_event SET lease_until = TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(3))
                    WHERE event_id = ?
                    """, id) == 1, "outbox_probe_lease_not_restored");
            require(mapper.retry(id, token, "PUBLISH_NACK", 2) == 1, "outbox_live_owner_retry_not_saved");
            Map<String, Object> retry = jdbc.queryForMap("""
                    SELECT status,lease_token,lease_until,sent_at,last_error,
                           next_send_at > UTC_TIMESTAMP(3) AS deferred
                    FROM outbox_event WHERE event_id = ?
                    """, id);
            require("PENDING".equals(retry.get("status")) && retry.get("lease_token") == null
                            && retry.get("lease_until") == null && retry.get("sent_at") == null
                            && "PUBLISH_NACK".equals(retry.get("last_error")) && booleanValue(retry.get("deferred")),
                    "outbox_retry_checkpoint_shape_invalid");
            String replacement = UUID.randomUUID().toString();
            require(mapper.claim(id, replacement) == 1, "outbox_replacement_lease_not_claimed");
            require(mapper.sent(id, token) == 0, "outbox_prior_owner_overrode_replacement");
            require(mapper.sent(id, replacement) == 1, "outbox_current_owner_not_marked_sent");
            Map<String, Object> sent = jdbc.queryForMap("""
                    SELECT status,lease_token,lease_until,sent_at,last_error
                    FROM outbox_event WHERE event_id = ?
                    """, id);
            require("SENT".equals(sent.get("status")) && sent.get("lease_token") == null
                            && sent.get("lease_until") == null && sent.get("sent_at") != null && sent.get("last_error") == null,
                    "outbox_sent_checkpoint_shape_invalid");
            return Map.of("staleTokenSentRejected", true, "staleTokenRetryRejected", true,
                    "expiredOwnerSentRejected", true, "expiredOwnerRetryRejected", true,
                    "liveOwnerRetrySaved", true, "priorOwnerAfterReplacementRejected", true,
                    "liveOwnerSentSaved", true, "checkpointShapesValid", true, "rolledBack", true);
        });
        require(result != null, "missing_outbox_lease_probe_result");
        report.put("outboxLeaseTransitions", result);
    }

    private void insertUploadSession(String id, String taskId, String subtaskId, String requirementId,
                                     String evidenceId, String executionId, String actorId, String clientRequestId) {
        require(jdbc.update("""
                INSERT INTO upload_session
                    (id, task_id, subtask_id, requirement_id, evidence_id, execution_id, actor_type, actor_id,
                     client_request_id, request_hash, filename, content_type, expected_size, part_size,
                     part_count, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'USER', ?, ?, ?, 'T010 rollback-only image.png', 'image/png',
                        4, 4, 1, ?, ?, ?)
                """, id, taskId, subtaskId, requirementId, evidenceId, executionId, actorId, clientRequestId,
                "b".repeat(64), FIXED_UTC.plusHours(1), FIXED_UTC, FIXED_UTC) == 1,
                "temporary_upload_session_not_inserted");
    }

    private void insertUploadPart(String sessionId, int partNo, String sha256, String objectKey) {
        require(jdbc.update("""
                INSERT INTO upload_part (session_id, part_no, size, sha256, object_key)
                VALUES (?, ?, 4, ?, ?)
                """, sessionId, partNo, sha256, objectKey) == 1, "temporary_upload_part_not_inserted");
    }

    private void insertEvidenceVersion(String id, String sessionId, String taskId, String subtaskId,
                                       String requirementId, String evidenceId, String executionId,
                                       String uploaderUserId, String uploaderDroneId, int versionNo) {
        require(jdbc.update("""
                INSERT INTO evidence_version
                    (id, requirement_id, evidence_id, task_id, subtask_id, execution_id, upload_session_id,
                     version_no, uploader_user_id, uploader_drone_id, object_key, size, sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 4, ?)
                """, id, requirementId, evidenceId, taskId, subtaskId, executionId, sessionId, versionNo,
                uploaderUserId, uploaderDroneId, "evidence-files/" + id, "a".repeat(64)) == 1,
                "temporary_evidence_version_not_inserted");
    }

    private void insertSubtask(String id, String taskId) {
        require(jdbc.update("""
                INSERT INTO inspection_subtask (id, task_id, name)
                VALUES (?, ?, 'T009 rollback-only subtask')
                """, id, taskId) == 1, "temporary_subtask_not_inserted");
    }

    private void insertRequirement(String id, String evidenceId, String taskId, String subtaskId, int nextVersion) {
        require(jdbc.update("""
                INSERT INTO material_requirement (id, evidence_id, task_id, subtask_id, name, next_version_no)
                VALUES (?, ?, ?, ?, 'T009 rollback-only requirement', ?)
                """, id, evidenceId, taskId, subtaskId, nextVersion) == 1, "temporary_requirement_not_inserted");
    }

    private void insertExecution(String id, String subtaskId, String executionStatus, int progress,
                                 LocalDateTime startedAt, LocalDateTime closedAt) {
        require(jdbc.update("""
                INSERT INTO task_execution (id, subtask_id, drone_id, status, progress, started_at, closed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, subtaskId, DRONE_ID, executionStatus, progress, startedAt, closedAt) == 1,
                "temporary_execution_not_inserted");
    }

    private Map<String, Object> rolesConstraint(String name, String user, String roles) {
        return expectConstraint(name, 3819, "HY000", () -> {},
                () -> jdbc.update("UPDATE app_user SET roles = CAST(? AS JSON) WHERE id = ?", roles, user));
    }

    private Map<String, Object> expectConstraint(String name, int vendorCode, String sqlState,
                                                 Runnable setup, Runnable action) {
        report.put("lastConstraint", name);
        Map<String, Object> result = transactions.execute(status -> {
            status.setRollbackOnly();
            // Setup is outside the expected-error catch: its failure must never satisfy the assertion.
            setup.run();
            try {
                action.run();
            } catch (DataAccessException exception) {
                SQLException sqlException = findSqlException(exception);
                require(sqlException != null, name + "_did_not_produce_a_sql_exception");
                require(sqlException.getErrorCode() == vendorCode && sqlState.equals(sqlException.getSQLState()),
                        name + "_unexpected_vendor_code_" + sqlException.getErrorCode()
                                + "_sqlstate_" + sqlException.getSQLState());
                return Map.of("rejected", true, "vendorCode", vendorCode, "sqlState", sqlState,
                        "rolledBack", true);
            }
            throw new ProbeFailure(name + "_unexpectedly_accepted");
        });
        require(result != null, name + "_missing_transaction_result");
        return result;
    }

    private void insertTask(String creator, String owner, String operator, long lockVersion) {
        insertTask(UUID.randomUUID().toString(), creator, owner, operator, lockVersion);
    }

    private void insertTask(String id, String creator, String owner, String operator, long lockVersion) {
        require(jdbc.update("""
                INSERT INTO inspection_task (id, owner_org_id, operator_org_id, name, scope, status, created_by, lock_version)
                VALUES (?, ?, ?, 'T007 rollback-only task', 'T007 probe scope', 'DRAFT', ?, ?)
                """, id, owner, operator, creator, lockVersion) == 1, "temporary_task_not_inserted");
    }

    private void verifyUtcRoundTrip() {
        require("Asia/Shanghai".equals(ZoneId.systemDefault().getId()), "utc_probe_requires_non_utc_shanghai_jvm");
        Map<String, Object> result = transactions.execute(status -> {
            status.setRollbackOnly();
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                var handler = new UtcInstantTypeHandler();
                try (var statement = connection.prepareStatement("UPDATE app_user SET created_at = ? WHERE id = ?")) {
                    handler.setParameter(statement, 1, FIXED_INSTANT, JdbcType.TIMESTAMP);
                    statement.setString(2, users.get("owner-admin").id());
                    require(statement.executeUpdate() == 1, "utc_probe_user_not_updated");
                }
                try (var statement = connection.prepareStatement("SELECT created_at FROM app_user WHERE id = ?")) {
                    statement.setString(1, users.get("owner-admin").id());
                    try (ResultSet rows = statement.executeQuery()) {
                        require(rows.next(), "utc_probe_user_not_found");
                        require(FIXED_UTC.equals(rows.getObject(1, LocalDateTime.class)), "stored_datetime_is_not_utc");
                        require(FIXED_INSTANT.equals(handler.getNullableResult(rows, 1)), "instant_round_trip_changed");
                        require(FIXED_INSTANT.equals(handler.getNullableResult(rows, "created_at")),
                                "named_column_instant_round_trip_changed");
                    }
                }
                try (var statement = connection.prepareStatement("UPDATE drone SET last_seen_at = ? WHERE id = ?")) {
                    handler.setParameter(statement, 1, null, JdbcType.TIMESTAMP);
                    statement.setString(2, DRONE_ID);
                    require(statement.executeUpdate() == 1, "utc_null_probe_device_not_updated");
                }
                try (var statement = connection.prepareStatement("SELECT last_seen_at FROM drone WHERE id = ?")) {
                    statement.setString(1, DRONE_ID);
                    try (ResultSet rows = statement.executeQuery()) {
                        require(rows.next() && handler.getNullableResult(rows, 1) == null, "sql_null_did_not_remain_null");
                    }
                }
                return Map.of("jvmTimeZone", ZoneId.systemDefault().getId(), "inputInstant", FIXED_INSTANT.toString(),
                        "storedUtcDateTime", FIXED_UTC.toString(), "instantPreserved", true,
                        "sqlNullPreserved", true, "rolledBack", true);
            } catch (SQLException exception) {
                throw new ProbeFailure("utc_round_trip_sql_failure_vendor_" + exception.getErrorCode());
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        });
        require(result != null, "missing_utc_probe_result");
        report.put("utcRoundTrip", result);
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLE_ORDER.keySet()) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            require(count != null, "missing_table_count");
            counts.put(table, count);
        }
        return counts;
    }

    private String snapshotDigest() throws SQLException {
        return snapshotDigest(SNAPSHOT_DOMAIN, TABLE_ORDER.size());
    }

    private String snapshotDigest(String domain, int tableLimit) throws SQLException {
        MessageDigest digest = newDigest();
        frame(digest, domain);
        try (Connection connection = dataSource.getConnection()) {
            for (var table : TABLE_ORDER.entrySet().stream().limit(tableLimit).toList()) {
                // Both identifiers come solely from the fixed table map above.
                try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT * FROM " + table.getKey() + " ORDER BY " + table.getValue())) {
                    frame(digest, table.getKey());
                    int columnCount = rows.getMetaData().getColumnCount();
                    frame(digest, Integer.toString(columnCount));
                    for (int index = 1; index <= columnCount; index++) {
                        frame(digest, rows.getMetaData().getColumnName(index));
                    }
                    long rowCount = 0;
                    while (rows.next()) {
                        digest.update((byte) 1);
                        for (int index = 1; index <= columnCount; index++) {
                            int type = rows.getMetaData().getColumnType(index);
                            if (Set.of(java.sql.Types.BINARY, java.sql.Types.VARBINARY,
                                    java.sql.Types.LONGVARBINARY, java.sql.Types.BLOB).contains(type)) {
                                frameBinary(digest, rows, index);
                                continue;
                            }
                            String value = rows.getString(index);
                            if (value != null) {
                                require(secrets.stream().noneMatch(value::contains), "plaintext_credential_found_in_database");
                            }
                            frame(digest, value);
                        }
                        rowCount++;
                    }
                    digest.update((byte) 0);
                    frame(digest, Long.toString(rowCount));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void frameBinary(MessageDigest digest, ResultSet rows, int column) throws SQLException {
        try (var input = rows.getBinaryStream(column)) {
            if (input == null) {
                frame(digest, null);
                return;
            }
            MessageDigest content = newDigest();
            byte[] buffer = new byte[65536];
            long length = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                content.update(buffer, 0, count);
                length += count;
            }
            // Hash exact binary bytes without decoding, materializing or reporting checkpoint contents.
            frame(digest, "binary-sha256");
            frame(digest, Long.toString(length));
            frame(digest, HexFormat.of().formatHex(content.digest()));
        } catch (IOException failure) {
            throw new ProbeFailure("binary_snapshot_read_failed");
        }
    }

    private void comparePreviousReport() throws IOException {
        if (compareReport == null) {
            report.put("comparisonPerformed", false);
            return;
        }
        Map<String, Object> previous = readMap(compareReport);
        require(Boolean.TRUE.equals(previous.get("passed")), "comparison_baseline_did_not_pass");
        require(previous.get("flywayChecksum") != null && previous.get("flywayChecksum").equals(report.get("flywayChecksum")),
                "comparison_changed_flywayChecksum");
        boolean originalElevenBaseline = ORIGINAL_SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain"));
        boolean originalFourteenBaseline = PREVIOUS_SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain"));
        boolean originalFifteenBaseline = ORIGINAL_FIFTEEN_SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain"));
        boolean olderBaseline = originalElevenBaseline || originalFourteenBaseline || originalFifteenBaseline;
        require(olderBaseline || SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain")),
                "comparison_unknown_snapshot_domain");
        String snapshotField = originalElevenBaseline ? "originalElevenSnapshotSha256"
                : originalFourteenBaseline ? "originalFourteenSnapshotSha256"
                : originalFifteenBaseline ? "originalFifteenSnapshotSha256" : "businessSnapshotSha256";
        Object actualSnapshot = report.get(snapshotField);
        require(previous.get("businessSnapshotSha256") != null && previous.get("businessSnapshotSha256").equals(actualSnapshot),
                "comparison_changed_businessSnapshotSha256");
        Object previousChecksums = previous.get("fullMigrationChecksums");
        Object currentChecksums = report.get("fullMigrationChecksums");
        require(previousChecksums instanceof Map<?, ?> oldChecksums && currentChecksums instanceof Map<?, ?> newChecksums
                        && newChecksums.entrySet().containsAll(oldChecksums.entrySet())
                        && (olderBaseline || oldChecksums.equals(newChecksums)),
                "comparison_changed_full_migration_checksums");
        if (credentialsDigest != null) {
            require(credentialsDigest.equals(previous.get("credentialsFileSha256")), "comparison_changed_credentials_file");
        }
        report.put("comparisonPerformed", true);
        report.put("sameMigrationChecksum", true);
        report.put("sameFullMigrationChecksums", previousChecksums.equals(currentChecksums));
        report.put("samePreviouslyAppliedMigrationChecksums", true);
        report.put("comparisonBaselineDomain", previous.get("businessSnapshotDomain"));
        report.put("sameBusinessSnapshot", true);
        if (credentialsDigest != null) {
            report.put("sameCredentialFile", true);
        }
    }

    private void writeReport() throws IOException {
        Files.createDirectories(reportPath.getParent());
        Path temporary = Files.createTempFile(reportPath.getParent(), ".database-probe-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, reportPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }

    private static SQLException findSqlException(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
            cause = cause.getCause();
        }
        return null;
    }

    private static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() == 1;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(Path path) throws IOException {
        return JSON.readValue(Files.readAllBytes(path), LinkedHashMap.class);
    }

    private static void require(boolean condition, String check) {
        if (!condition) {
            throw new ProbeFailure(check);
        }
    }

    private record Account(int number, String orgId, String username, Set<String> roles) {
        String id() {
            return "10000000-0000-4000-8000-%012d".formatted(number);
        }
    }

    public record Credentials(int formatVersion, Map<String, String> passwords, Map<String, String> droneTokens) {}

    private static final class ProbeFailure extends IllegalStateException {
        private ProbeFailure(String check) {
            super(check);
        }
    }
}
