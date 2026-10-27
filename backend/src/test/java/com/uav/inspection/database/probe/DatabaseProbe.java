package com.uav.inspection.database.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper;
import com.uav.inspection.blockchain.anchor.AnchorTransactions;
import com.uav.inspection.evidence.cleanup.CleanupSettings;
import com.uav.inspection.evidence.cleanup.CleanupTransactions;
import com.uav.inspection.evidence.cleanup.persistence.CleanupMapper;
import com.uav.inspection.evidence.storage.ObjectInventory;
import com.uav.inspection.common.persistence.UtcInstantTypeHandler;
import com.uav.inspection.device.persistence.DroneMapper;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.inspection.persistence.TaskMapper;
import com.uav.inspection.messaging.persistence.OutboxMapper;
import com.uav.inspection.messaging.persistence.MessageRejectionMapper;
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
import java.time.ZoneOffset;
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
    private static final String SNAPSHOT_DOMAIN = "T025-full-twenty-four-table-snapshot-v1";
    private static final String ORIGINAL_TWENTY_THREE_SNAPSHOT_DOMAIN = "T024-full-twenty-three-table-snapshot-v1";
    private static final String ORIGINAL_TWENTY_TWO_SNAPSHOT_DOMAIN = "T022-full-twenty-two-table-snapshot-v1";
    private static final String ORIGINAL_TWENTY_ONE_SNAPSHOT_DOMAIN = "T020-full-twenty-one-table-snapshot-v1";
    private static final String ORIGINAL_TWENTY_SNAPSHOT_DOMAIN = "T018-full-twenty-table-snapshot-v1";
    private static final String ORIGINAL_EIGHTEEN_SNAPSHOT_DOMAIN = "T016-full-eighteen-table-snapshot-v1";
    private static final String ORIGINAL_SEVENTEEN_SNAPSHOT_DOMAIN = "T015-full-seventeen-table-snapshot-v1";
    private static final String ORIGINAL_SIXTEEN_SNAPSHOT_DOMAIN = "T013-full-sixteen-table-snapshot-v1";
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
        TABLE_ORDER.put("device_event", "id");
        TABLE_ORDER.put("execution_adjustment", "id");
        TABLE_ORDER.put("issue", "id");
        TABLE_ORDER.put("review_record", "id");
        TABLE_ORDER.put("access_grant", "id");
        TABLE_ORDER.put("message_rejection", "id");
        TABLE_ORDER.put("anchor_retry_request", "id");
        TABLE_ORDER.put("storage_cleanup_task", "id");
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
                            "outbox_event", "consumed_event", "chain_attempt", "verification_record", "device_event",
                            "execution_adjustment", "issue", "review_record", "access_grant", "message_rejection", "anchor_retry_request", "storage_cleanup_task")
                    .stream().allMatch(table -> counts.get(table) == 0L));
            if (demoEnabled) {
                report.put("stage", "demo_data_validation");
                validateDemoData(context);
            }
            String before = snapshotDigest();
            report.put("businessSnapshotDomain", SNAPSHOT_DOMAIN);
            report.put("businessSnapshotSha256", before);
            report.put("originalTwentyThreeSnapshotDomain", ORIGINAL_TWENTY_THREE_SNAPSHOT_DOMAIN);
            report.put("originalTwentyThreeSnapshotSha256", snapshotDigest(ORIGINAL_TWENTY_THREE_SNAPSHOT_DOMAIN, 23));
            report.put("originalTwentyTwoSnapshotDomain", ORIGINAL_TWENTY_TWO_SNAPSHOT_DOMAIN);
            report.put("originalTwentyTwoSnapshotSha256", snapshotDigest(ORIGINAL_TWENTY_TWO_SNAPSHOT_DOMAIN, 22));
            report.put("originalTwentyOneSnapshotDomain", ORIGINAL_TWENTY_ONE_SNAPSHOT_DOMAIN);
            report.put("originalTwentyOneSnapshotSha256", snapshotDigest(ORIGINAL_TWENTY_ONE_SNAPSHOT_DOMAIN, 21));
            report.put("originalTwentySnapshotDomain", ORIGINAL_TWENTY_SNAPSHOT_DOMAIN);
            report.put("originalTwentySnapshotSha256", snapshotDigest(ORIGINAL_TWENTY_SNAPSHOT_DOMAIN, 20));
            report.put("originalEighteenSnapshotDomain", ORIGINAL_EIGHTEEN_SNAPSHOT_DOMAIN);
            report.put("originalEighteenSnapshotSha256", snapshotDigest(ORIGINAL_EIGHTEEN_SNAPSHOT_DOMAIN, 18));
            report.put("originalSeventeenSnapshotDomain", ORIGINAL_SEVENTEEN_SNAPSHOT_DOMAIN);
            report.put("originalSeventeenSnapshotSha256", snapshotDigest(ORIGINAL_SEVENTEEN_SNAPSHOT_DOMAIN, 17));
            report.put("originalSixteenSnapshotDomain", ORIGINAL_SIXTEEN_SNAPSHOT_DOMAIN);
            report.put("originalSixteenSnapshotSha256", snapshotDigest(ORIGINAL_SIXTEEN_SNAPSHOT_DOMAIN, 16));
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
                report.put("stage", "message_rejection_round_trip");
                verifyMessageRejectionRoundTrip(context.getBean(MessageRejectionMapper.class));
                report.put("stage", "anchor_retry_budget_round_trip");
                verifyAnchorRetryBudget(context.getBean(AnchorMapper.class));
                report.put("stage", "storage_cleanup_lease_round_trip");
                verifyStorageCleanupLease(context.getBean(CleanupMapper.class));
                report.put("stage", "task_completion_transitions");
                verifyTaskCompletionTransitions(context.getBean(TaskMapper.class));
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
        Map<String, String> expectedScripts = Map.ofEntries(Map.entry("1", "V1__identity_and_task_scope.sql"),
                Map.entry("2", "V2__identity_audit.sql"), Map.entry("3", "V3__task_execution_scope.sql"),
                Map.entry("4", "V4__upload_sessions_and_versions.sql"),
                Map.entry("5", "V5__finalization_outbox_and_submission.sql"), Map.entry("6", "V6__chain_attempts.sql"),
                Map.entry("7", "V7__verification_records.sql"), Map.entry("8", "V8__device_reports.sql"),
                Map.entry("9", "V9__execution_adjustments.sql"), Map.entry("10", "V10__issues_and_reviews.sql"),
                Map.entry("11", "V11__version_access_grants.sql"),
                Map.entry("12", "V12__durable_message_rejections.sql"),
                Map.entry("13", "V13__anchor_retry_requests.sql"),
                Map.entry("14", "V14__storage_cleanup_tasks.sql"));
        require(entries.size() == expectedScripts.size(), "expected_successful_v1_through_v14_migrations");
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
                "3", -394507728, "4", -1021150431, "5", -507899413, "6", 186672578, "7", -763051633,
                "8", 1053255897, "9", 72853277, "10", -573715695);
        require(originalChecksums.entrySet().stream().allMatch(entry -> entry.getValue().equals(checksums.get(entry.getKey()))),
                "original_v1_through_v10_migration_checksums_changed");
        report.put("originalMigrationChecksumsPreserved", true);
        // Keep the original V1 field for existing evidence readers, while comparing all applied migrations below.
        report.put("flywayChecksum", checksums.get("1"));
        report.put("fullMigrationChecksums", checksums);
        require(Integer.valueOf(1029133993).equals(checksums.get("11")),"unexpected_v11_migration_checksum");
        require(Integer.valueOf(1723908793).equals(checksums.get("12")),"unexpected_v12_migration_checksum");
        require(Integer.valueOf(-1202069766).equals(checksums.get("13")),"unexpected_v13_migration_checksum");
        require(Integer.valueOf(-1562459282).equals(checksums.get("14")),"unexpected_v14_migration_checksum");
        report.put("flyway", Map.of("version", "14", "successfulEntries", entries.size(),
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
        verifyDeviceEventConstraints(checks, memberTask, validTask);
        verifyExecutionAdjustmentConstraints(checks, memberTask, validTask);
        verifyIssueAndReviewConstraints(checks, operatorUser, memberTask, validTask);
        verifyTaskCompletionConstraints(checks, memberTask, validTask);
        verifyAccessGrantConstraints(checks, operatorUser, memberTask, validTask);
        verifyMessageRejectionConstraints(checks);
        verifyAnchorRetryRequestConstraints(checks);
        verifyStorageCleanupConstraints(checks);
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

    private void verifyDeviceEventConstraints(Map<String,Object> checks,String taskId,Runnable validTask) {
        String subtaskId=UUID.randomUUID().toString();
        String executionId=UUID.randomUUID().toString();
        String eventRecordId=UUID.randomUUID().toString();
        String eventId=UUID.randomUUID().toString();
        String bootId=UUID.randomUUID().toString();
        String otherDrone=UUID.randomUUID().toString();
        String fingerprint="e".repeat(64);
        Runnable validScope=()->{
            validTask.run(); insertSubtask(subtaskId,taskId);
            insertExecution(executionId,subtaskId,"RUNNING",0,FIXED_UTC,null);
        };
        Runnable validEvent=()->{
            validScope.run();
            insertDeviceEvent(eventRecordId,DRONE_ID,bootId,0,eventId,executionId,"HEARTBEAT",null,fingerprint,0);
        };
        Runnable eventAndOtherDevice=()->{
            validEvent.run();
            require(jdbc.update("INSERT INTO drone(id,operator_org_id,code,token_hash) VALUES(?,?,?,?)",
                    otherDrone,OPERATOR,"DBPROBE-"+otherDrone,"d".repeat(64))==1,"temporary_second_device_not_inserted");
        };
        checks.put("duplicateDeviceBootSequence",expectConstraint("duplicate_device_boot_sequence",1062,"23000",validEvent,
                ()->insertDeviceEvent(UUID.randomUUID().toString(),DRONE_ID,bootId,0,UUID.randomUUID().toString(),
                        executionId,"HEARTBEAT",null,fingerprint,0)));
        checks.put("duplicateDeviceEventAcrossBoots",expectConstraint("duplicate_device_event_across_boots",1062,"23000",validEvent,
                ()->insertDeviceEvent(UUID.randomUUID().toString(),DRONE_ID,UUID.randomUUID().toString(),1,eventId,
                        executionId,"HEARTBEAT",null,fingerprint,0)));
        checks.put("foreignDeviceExecutionReport",expectConstraint("foreign_device_execution_report",1452,"23000",eventAndOtherDevice,
                ()->jdbc.update("UPDATE device_event SET drone_id=? WHERE id=?",otherDrone,eventRecordId)));
        checks.put("danglingDeviceReport",expectConstraint("dangling_device_report",1452,"23000",validEvent,
                ()->jdbc.update("UPDATE device_event SET drone_id=? WHERE id=?",UUID.randomUUID().toString(),eventRecordId)));
        checks.put("danglingReportExecution",expectConstraint("dangling_report_execution",1452,"23000",validEvent,
                ()->jdbc.update("UPDATE device_event SET execution_id=? WHERE id=?",UUID.randomUUID().toString(),eventRecordId)));
        checks.put("heartbeatWithoutExecution",expectConstraint("heartbeat_without_execution",1048,"23000",validEvent,
                ()->jdbc.update("UPDATE device_event SET execution_id=NULL WHERE id=?",eventRecordId)));
        checks.put("invalidReportBootId",expectConstraint("invalid_report_boot_id",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET boot_id='not-a-uuid' WHERE id=?",eventRecordId)));
        checks.put("invalidDeviceEventId",expectConstraint("invalid_device_event_id",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET event_id='not-a-uuid' WHERE id=?",eventRecordId)));
        checks.put("negativeDeviceSequence",expectConstraint("negative_device_sequence",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET seq=-1 WHERE id=?",eventRecordId)));
        checks.put("deviceSequenceAboveJsonIntegerLimit",expectConstraint("device_sequence_above_json_integer_limit",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET seq=9007199254740992 WHERE id=?",eventRecordId)));
        checks.put("invalidDeviceEventType",expectConstraint("invalid_device_event_type",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET event_type='LOCATION' WHERE id=?",eventRecordId)));
        checks.put("heartbeatWithProgress",expectConstraint("heartbeat_with_progress",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET progress=1 WHERE id=?",eventRecordId)));
        checks.put("progressWithoutPercentage",expectConstraint("progress_without_percentage",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET event_type='PROGRESS' WHERE id=?",eventRecordId)));
        checks.put("negativeDeviceProgress",expectConstraint("negative_device_progress",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET event_type='PROGRESS',progress=-1 WHERE id=?",eventRecordId)));
        checks.put("deviceProgressAboveHundred",expectConstraint("device_progress_above_hundred",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET event_type='PROGRESS',progress=101 WHERE id=?",eventRecordId)));
        checks.put("invalidDevicePayloadFingerprint",expectConstraint("invalid_device_payload_fingerprint",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET payload_fingerprint='not-a-sha256' WHERE id=?",eventRecordId)));
        checks.put("negativeAppliedDeviceProgress",expectConstraint("negative_applied_device_progress",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET applied_progress=-1 WHERE id=?",eventRecordId)));
        checks.put("appliedDeviceProgressAboveHundred",expectConstraint("applied_device_progress_above_hundred",3819,"HY000",validEvent,
                ()->jdbc.update("UPDATE device_event SET applied_progress=101 WHERE id=?",eventRecordId)));
        checks.put("restrictReportedExecutionDeletion",expectConstraint("restrict_reported_execution_deletion",1451,"23000",validEvent,
                ()->jdbc.update("DELETE FROM task_execution WHERE id=?",executionId)));
        Map<String,Object> replay=transactions.execute(transaction->{
            transaction.setRollbackOnly(); validEvent.run();
            String progressId=UUID.randomUUID().toString();
            insertDeviceEvent(progressId,DRONE_ID,bootId,1,UUID.randomUUID().toString(),executionId,"PROGRESS",37,fingerprint,65);
            insertDeviceEvent(UUID.randomUUID().toString(),DRONE_ID,UUID.randomUUID().toString(),0,UUID.randomUUID().toString(),
                    executionId,"HEARTBEAT",null,fingerprint,65);
            String otherSubtask=UUID.randomUUID().toString(); String otherExecution=UUID.randomUUID().toString();
            require(jdbc.update("INSERT INTO drone(id,operator_org_id,code,token_hash) VALUES(?,?,?,?)",
                    otherDrone,OPERATOR,"DBPROBE-"+otherDrone,"d".repeat(64))==1,"temporary_independent_device_not_inserted");
            insertSubtask(otherSubtask,taskId);
            require(jdbc.update("""
                    INSERT INTO task_execution(id,subtask_id,drone_id,status,progress,started_at)
                    VALUES(?,?,?,'RUNNING',0,?)
                    """,otherExecution,otherSubtask,otherDrone,FIXED_UTC)==1,"temporary_independent_execution_not_inserted");
            insertDeviceEvent(UUID.randomUUID().toString(),otherDrone,bootId,0,eventId,otherExecution,"HEARTBEAT",null,fingerprint,0);
            require(Long.valueOf(4).equals(jdbc.queryForObject("SELECT COUNT(*) FROM device_event WHERE execution_id IN (?,?)",
                    Long.class,executionId,otherExecution)),"device_event_scope_does_not_allow_boot_or_device_independence");
            Map<String,Object> accepted=jdbc.queryForMap("""
                    SELECT progress,applied_progress,payload_fingerprint,
                        received_at<=UTC_TIMESTAMP(3) AND received_at>=TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(3)) AS utc_default
                    FROM device_event WHERE id=?
                    """,progressId);
            require(((Number)accepted.get("progress")).intValue()==37 &&((Number)accepted.get("applied_progress")).intValue()==65
                    &&fingerprint.equals(accepted.get("payload_fingerprint")) &&booleanValue(accepted.get("utc_default")),
                    "accepted_device_result_or_database_utc_timestamp_changed");
            return Map.of("bootRestartMayReuseSequence",true,"differentDevicesMayReuseEventAndSequence",true,
                    "acceptedProgressSnapshotPreserved",true,"requestFingerprintPreserved",true,
                    "databaseUtcReceiveTimeVerified",true,"rolledBack",true,
                    "scope","Schema-only synthetic reports; no production device event or execution is updated.");
        });
        require(replay!=null,"missing_device_event_round_trip_result"); report.put("deviceEventRoundTrip",replay);
    }

    private void insertDeviceEvent(String id,String drone,String boot,long sequence,String event,String execution,
                                   String type,Integer progress,String fingerprint,int appliedProgress) {
        require(jdbc.update("""
                INSERT INTO device_event(id,drone_id,boot_id,seq,event_id,execution_id,event_type,progress,payload_fingerprint,applied_progress)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,id,drone,boot,sequence,event,execution,type,progress,fingerprint,appliedProgress)==1,
                "temporary_device_event_not_inserted");
    }

    private void verifyExecutionAdjustmentConstraints(Map<String,Object> checks,String taskId,Runnable validTask) {
        String subtaskId=UUID.randomUUID().toString();
        String previousId=UUID.randomUUID().toString();
        String currentId=UUID.randomUUID().toString();
        String thirdId=UUID.randomUUID().toString();
        String adjustmentId=UUID.randomUUID().toString();
        String actorId=UUID.randomUUID().toString();
        String otherSubtaskId=UUID.randomUUID().toString();
        String foreignExecutionId=UUID.randomUUID().toString();
        Runnable validScope=()->{
            validTask.run(); insertSubtask(subtaskId,taskId);
            insertExecution(previousId,subtaskId,"CLOSED",40,FIXED_UTC,FIXED_UTC.plusMinutes(1));
            insertExecution(currentId,subtaskId,"RUNNING",0,FIXED_UTC.plusMinutes(1),null);
            require(jdbc.update("""
                    INSERT INTO app_user(id,org_id,username,password_hash,roles)
                    SELECT ?,org_id,?,password_hash,roles FROM app_user WHERE id=?
                    """,actorId,"T016-adjustment-"+UUID.randomUUID(),users.get("operator-admin").id())==1,
                    "temporary_adjustment_actor_not_inserted");
        };
        Runnable validAdjustment=()->{
            validScope.run();
            insertAdjustment(adjustmentId,taskId,subtaskId,previousId,currentId,actorId,"设备电量不足，人工换机");
        };
        Runnable withThird=()->{
            validAdjustment.run();
            insertExecution(thirdId,subtaskId,"CLOSED",10,FIXED_UTC,FIXED_UTC.plusSeconds(30));
        };
        Runnable withForeignExecution=()->{
            validAdjustment.run(); insertSubtask(otherSubtaskId,taskId);
            insertExecution(foreignExecutionId,otherSubtaskId,"CLOSED",0,FIXED_UTC,FIXED_UTC.plusSeconds(30));
        };
        checks.put("adjustmentCannotReuseSameExecution",expectConstraint("adjustment_cannot_reuse_same_execution",3819,"HY000",validScope,
                ()->insertAdjustment(adjustmentId,taskId,subtaskId,previousId,previousId,actorId,"无效同批次替换")));
        checks.put("adjustmentCannotForkPreviousExecution",expectConstraint("adjustment_cannot_fork_previous_execution",1062,"23000",withThird,
                ()->insertAdjustment(UUID.randomUUID().toString(),taskId,subtaskId,previousId,thirdId,actorId,"重复旧批次")));
        checks.put("adjustmentCannotMergeIntoSameExecution",expectConstraint("adjustment_cannot_merge_into_same_execution",1062,"23000",withThird,
                ()->insertAdjustment(UUID.randomUUID().toString(),taskId,subtaskId,thirdId,currentId,actorId,"重复新批次")));
        String otherTaskId=UUID.randomUUID().toString();
        Runnable withOtherTask=()->{
            validAdjustment.run();
            insertTask(otherTaskId,users.get("owner-admin").id(),OWNER,OPERATOR,0);
        };
        checks.put("adjustmentSubtaskCannotBorrowAnotherTask",expectConstraint("adjustment_subtask_cannot_borrow_another_task",1452,"23000",withOtherTask,
                ()->jdbc.update("UPDATE execution_adjustment SET task_id=? WHERE id=?",otherTaskId,adjustmentId)));
        checks.put("adjustmentPreviousMustBelongToSubtask",expectConstraint("adjustment_previous_must_belong_to_subtask",1452,"23000",withForeignExecution,
                ()->jdbc.update("UPDATE execution_adjustment SET previous_execution_id=? WHERE id=?",foreignExecutionId,adjustmentId)));
        checks.put("adjustmentNewMustBelongToSubtask",expectConstraint("adjustment_new_must_belong_to_subtask",1452,"23000",withForeignExecution,
                ()->jdbc.update("UPDATE execution_adjustment SET new_execution_id=? WHERE id=?",foreignExecutionId,adjustmentId)));
        checks.put("danglingAdjustmentPrevious",expectConstraint("dangling_adjustment_previous",1452,"23000",validAdjustment,
                ()->jdbc.update("UPDATE execution_adjustment SET previous_execution_id=? WHERE id=?",UUID.randomUUID().toString(),adjustmentId)));
        checks.put("danglingAdjustmentNew",expectConstraint("dangling_adjustment_new",1452,"23000",validAdjustment,
                ()->jdbc.update("UPDATE execution_adjustment SET new_execution_id=? WHERE id=?",UUID.randomUUID().toString(),adjustmentId)));
        checks.put("danglingAdjustmentActor",expectConstraint("dangling_adjustment_actor",1452,"23000",validAdjustment,
                ()->jdbc.update("UPDATE execution_adjustment SET actor_id=? WHERE id=?",UUID.randomUUID().toString(),adjustmentId)));
        checks.put("emptyAdjustmentReason",expectConstraint("empty_adjustment_reason",3819,"HY000",validAdjustment,
                ()->jdbc.update("UPDATE execution_adjustment SET reason='' WHERE id=?",adjustmentId)));
        checks.put("whitespaceAdjustmentReason",expectConstraint("whitespace_adjustment_reason",3819,"HY000",validAdjustment,
                ()->jdbc.update("UPDATE execution_adjustment SET reason=? WHERE id=?"," \t\n ",adjustmentId)));
        checks.put("oversizedAdjustmentReason",expectConstraint("oversized_adjustment_reason",1406,"22001",validAdjustment,
                ()->jdbc.update("UPDATE execution_adjustment SET reason=? WHERE id=?","换".repeat(1001),adjustmentId)));
        checks.put("restrictAdjustedPreviousDeletion",expectConstraint("restrict_adjusted_previous_deletion",1451,"23000",validAdjustment,
                ()->jdbc.update("DELETE FROM task_execution WHERE id=?",previousId)));
        checks.put("restrictAdjustedNewDeletion",expectConstraint("restrict_adjusted_new_deletion",1451,"23000",validAdjustment,
                ()->jdbc.update("DELETE FROM task_execution WHERE id=?",currentId)));
        checks.put("restrictAdjustmentActorDeletion",expectConstraint("restrict_adjustment_actor_deletion",1451,"23000",validAdjustment,
                ()->jdbc.update("DELETE FROM app_user WHERE id=?",actorId)));
        Map<String,Object> chain=transactions.execute(transaction->{
            transaction.setRollbackOnly(); validScope.run();
            insertAdjustment(adjustmentId,taskId,subtaskId,previousId,currentId,actorId,"换".repeat(1000));
            require(jdbc.update("UPDATE task_execution SET status='CLOSED',closed_at=? WHERE id=?",
                    FIXED_UTC.plusMinutes(2),currentId)==1,"temporary_middle_execution_not_closed");
            insertExecution(thirdId,subtaskId,"RUNNING",0,FIXED_UTC.plusMinutes(2),null);
            insertAdjustment(UUID.randomUUID().toString(),taskId,subtaskId,currentId,thirdId,actorId,"再次换机保留批次链");
            require(jdbc.update("UPDATE inspection_subtask SET current_execution_id=? WHERE id=?",thirdId,subtaskId)==1,
                    "temporary_current_execution_pointer_not_updated");
            require(Long.valueOf(2).equals(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM execution_adjustment WHERE task_id=? AND subtask_id=? AND actor_id=?
                      AND created_at<=UTC_TIMESTAMP(3) AND created_at>=TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(3))
                    """,Long.class,taskId,subtaskId,actorId)),"adjustment_chain_or_database_utc_default_not_preserved");
            require(Integer.valueOf(1000).equals(jdbc.queryForObject("SELECT CHAR_LENGTH(reason) FROM execution_adjustment WHERE id=?",
                    Integer.class,adjustmentId)),"thousand_character_unicode_reason_not_preserved");
            require(thirdId.equals(jdbc.queryForObject("SELECT current_execution_id FROM inspection_subtask WHERE id=?",String.class,subtaskId))
                    &&Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM task_execution WHERE subtask_id=? AND status='RUNNING' AND progress=0",
                    Long.class,subtaskId)),"synthetic_adjustment_current_batch_or_reset_progress_not_preserved");
            return Map.of("onePredecessorAndSuccessorChainAccepted",true,"thousandCharacterReasonAccepted",true,
                    "actorAndTaskScopePreserved",true,"databaseUtcDefaultVerified",true,"rolledBack",true,
                    "scope","Synthetic schema-only adjustment fixtures; no retained task, execution or device is changed.");
        });
        require(chain!=null,"missing_adjustment_round_trip_result"); report.put("executionAdjustmentRoundTrip",chain);
    }

    private void insertAdjustment(String id,String task,String subtask,String previous,String replacement,String actor,String reason) {
        require(jdbc.update("""
                INSERT INTO execution_adjustment(id,task_id,subtask_id,previous_execution_id,new_execution_id,actor_id,reason)
                VALUES(?,?,?,?,?,?,?)
                """,id,task,subtask,previous,replacement,actor,reason)==1,"temporary_execution_adjustment_not_inserted");
    }

    private void verifyIssueAndReviewConstraints(Map<String,Object> checks,String operatorUser,
                                                 String taskId,Runnable validTask) {
        String subtaskId=UUID.randomUUID().toString();
        String requirementId=UUID.randomUUID().toString();
        String evidenceId=UUID.randomUUID().toString();
        String executionId=UUID.randomUUID().toString();
        String sessionId=UUID.randomUUID().toString();
        String versionId=UUID.randomUUID().toString();
        String creatorId=UUID.randomUUID().toString();
        String reviewerId=UUID.randomUUID().toString();
        String issueId=UUID.randomUUID().toString();
        String reviewId=UUID.randomUUID().toString();
        Runnable validVersion=()->{
            validTask.run(); insertSubtask(subtaskId,taskId);
            insertRequirement(requirementId,evidenceId,taskId,subtaskId,1);
            insertExecution(executionId,subtaskId,"RUNNING",0,FIXED_UTC,null);
            insertUploadSession(sessionId,taskId,subtaskId,requirementId,evidenceId,
                    executionId,operatorUser,UUID.randomUUID().toString());
            insertEvidenceVersion(versionId,sessionId,taskId,subtaskId,requirementId,evidenceId,
                    executionId,operatorUser,null,1);
            require(jdbc.update("""
                    INSERT INTO app_user(id,org_id,username,password_hash,roles)
                    SELECT ?,org_id,?,password_hash,roles FROM app_user WHERE id=?
                    """,creatorId,"T018-issue-"+UUID.randomUUID(),operatorUser)==1,
                    "temporary_issue_creator_not_inserted");
            require(jdbc.update("""
                    INSERT INTO app_user(id,org_id,username,password_hash,roles)
                    SELECT ?,org_id,?,password_hash,roles FROM app_user WHERE id=?
                    """,reviewerId,"T018-review-"+UUID.randomUUID(),users.get("owner-reviewer").id())==1,
                    "temporary_reviewer_not_inserted");
        };
        Runnable validIssue=()->{
            validVersion.run(); insertIssue(issueId,taskId,versionId,creatorId,"巡检发现设备异常，绑定原固定版本");
        };
        Runnable validReview=()->{
            validVersion.run(); insertReview(reviewId,versionId,reviewerId,"APPROVED",null);
        };
        checks.put("nullIssueDescription",expectConstraint("null_issue_description",1048,"23000",validIssue,
                ()->jdbc.update("UPDATE issue SET description=NULL WHERE id=?",issueId)));
        checks.put("emptyIssueDescription",expectConstraint("empty_issue_description",3819,"HY000",validIssue,
                ()->jdbc.update("UPDATE issue SET description='' WHERE id=?",issueId)));
        checks.put("whitespaceIssueDescription",expectConstraint("whitespace_issue_description",3819,"HY000",validIssue,
                ()->jdbc.update("UPDATE issue SET description=? WHERE id=?"," \t\r\n ",issueId)));
        checks.put("unicodeWhitespaceIssueDescription",expectConstraint("unicode_whitespace_issue_description",3819,"HY000",validIssue,
                ()->jdbc.update("UPDATE issue SET description=? WHERE id=?","\u00a0\u3000",issueId)));
        checks.put("oversizedIssueDescription",expectConstraint("oversized_issue_description",1406,"22001",validIssue,
                ()->jdbc.update("UPDATE issue SET description=? WHERE id=?","异".repeat(4001),issueId)));
        checks.put("danglingIssueVersion",expectConstraint("dangling_issue_version",1452,"23000",validIssue,
                ()->jdbc.update("UPDATE issue SET evidence_version_id=? WHERE id=?",UUID.randomUUID().toString(),issueId)));
        String otherTaskId=UUID.randomUUID().toString();
        Runnable withOtherTask=()->{
            validIssue.run(); insertTask(otherTaskId,users.get("owner-admin").id(),OWNER,OPERATOR,0);
        };
        checks.put("issueVersionCannotBorrowAnotherTask",expectConstraint("issue_version_cannot_borrow_another_task",1452,"23000",withOtherTask,
                ()->jdbc.update("UPDATE issue SET task_id=? WHERE id=?",otherTaskId,issueId)));
        checks.put("danglingIssueCreator",expectConstraint("dangling_issue_creator",1452,"23000",validIssue,
                ()->jdbc.update("UPDATE issue SET created_by=? WHERE id=?",UUID.randomUUID().toString(),issueId)));
        checks.put("restrictIssueCreatorDeletion",expectConstraint("restrict_issue_creator_deletion",1451,"23000",validIssue,
                ()->jdbc.update("DELETE FROM app_user WHERE id=?",creatorId)));
        checks.put("restrictIssuedVersionDeletion",expectConstraint("restrict_issued_version_deletion",1451,"23000",validIssue,
                ()->jdbc.update("DELETE FROM evidence_version WHERE id=?",versionId)));
        checks.put("invalidReviewDecision",expectConstraint("invalid_review_decision",3819,"HY000",validReview,
                ()->jdbc.update("UPDATE review_record SET decision='PENDING',reason='valid reason' WHERE id=?",reviewId)));
        checks.put("oneReviewConclusionPerVersion",expectConstraint("one_review_conclusion_per_version",1062,"23000",validReview,
                ()->insertReview(UUID.randomUUID().toString(),versionId,reviewerId,"RETURNED","第二次结论不得覆盖首次复核")));
        checks.put("returnedReviewRequiresReason",expectConstraint("returned_review_requires_reason",3819,"HY000",validReview,
                ()->jdbc.update("UPDATE review_record SET decision='RETURNED',reason=NULL WHERE id=?",reviewId)));
        checks.put("emptyReturnedReviewReason",expectConstraint("empty_returned_review_reason",3819,"HY000",validReview,
                ()->jdbc.update("UPDATE review_record SET decision='RETURNED',reason='' WHERE id=?",reviewId)));
        checks.put("whitespaceReturnedReviewReason",expectConstraint("whitespace_returned_review_reason",3819,"HY000",validReview,
                ()->jdbc.update("UPDATE review_record SET decision='RETURNED',reason=? WHERE id=?"," \t\n ",reviewId)));
        checks.put("unicodeWhitespaceReturnedReviewReason",expectConstraint("unicode_whitespace_returned_review_reason",3819,"HY000",validReview,
                ()->jdbc.update("UPDATE review_record SET decision='RETURNED',reason=? WHERE id=?","\u00a0\u3000",reviewId)));
        checks.put("emptyApprovedReviewReason",expectConstraint("empty_approved_review_reason",3819,"HY000",validReview,
                ()->jdbc.update("UPDATE review_record SET reason='' WHERE id=?",reviewId)));
        checks.put("whitespaceApprovedReviewReason",expectConstraint("whitespace_approved_review_reason",3819,"HY000",validReview,
                ()->jdbc.update("UPDATE review_record SET reason=? WHERE id=?"," \t\n ",reviewId)));
        checks.put("oversizedReviewReason",expectConstraint("oversized_review_reason",1406,"22001",validReview,
                ()->jdbc.update("UPDATE review_record SET reason=? WHERE id=?","复".repeat(1001),reviewId)));
        checks.put("danglingReviewVersion",expectConstraint("dangling_review_version",1452,"23000",validReview,
                ()->jdbc.update("UPDATE review_record SET evidence_version_id=? WHERE id=?",UUID.randomUUID().toString(),reviewId)));
        checks.put("danglingReviewer",expectConstraint("dangling_reviewer",1452,"23000",validReview,
                ()->jdbc.update("UPDATE review_record SET reviewer_id=? WHERE id=?",UUID.randomUUID().toString(),reviewId)));
        checks.put("restrictReviewerDeletion",expectConstraint("restrict_reviewer_deletion",1451,"23000",validReview,
                ()->jdbc.update("DELETE FROM app_user WHERE id=?",reviewerId)));
        checks.put("restrictReviewedVersionDeletion",expectConstraint("restrict_reviewed_version_deletion",1451,"23000",validReview,
                ()->jdbc.update("DELETE FROM evidence_version WHERE id=?",versionId)));
        Map<String,Object> roundTrip=transactions.execute(transaction->{
            transaction.setRollbackOnly(); validVersion.run();
            insertIssue(issueId,taskId,versionId,creatorId,"异".repeat(4000));
            insertIssue(UUID.randomUUID().toString(),taskId,versionId,creatorId,"同版允许多条异常记录\n保留原描述");
            insertReview(reviewId,versionId,reviewerId,"APPROVED",null);
            String returnedVersionId=UUID.randomUUID().toString(),returnedSessionId=UUID.randomUUID().toString();
            insertUploadSession(returnedSessionId,taskId,subtaskId,requirementId,evidenceId,
                    executionId,operatorUser,UUID.randomUUID().toString());
            insertEvidenceVersion(returnedVersionId,returnedSessionId,taskId,subtaskId,requirementId,evidenceId,
                    executionId,operatorUser,null,2);
            insertReview(UUID.randomUUID().toString(),returnedVersionId,reviewerId,"RETURNED","复".repeat(1000));
            String explainedVersionId=UUID.randomUUID().toString(),explainedSessionId=UUID.randomUUID().toString();
            insertUploadSession(explainedSessionId,taskId,subtaskId,requirementId,evidenceId,
                    executionId,operatorUser,UUID.randomUUID().toString());
            insertEvidenceVersion(explainedVersionId,explainedSessionId,taskId,subtaskId,requirementId,evidenceId,
                    executionId,operatorUser,null,3);
            insertReview(UUID.randomUUID().toString(),explainedVersionId,reviewerId,"APPROVED","检查通过，保留说明");
            require(Integer.valueOf(4000).equals(jdbc.queryForObject("SELECT CHAR_LENGTH(description) FROM issue WHERE id=?",
                    Integer.class,issueId)),"four_thousand_character_issue_not_preserved");
            require(Long.valueOf(2).equals(jdbc.queryForObject("SELECT COUNT(*) FROM issue WHERE task_id=? AND evidence_version_id=?",
                    Long.class,taskId,versionId)),"multiple_version_bound_issues_not_preserved");
            require(Long.valueOf(3).equals(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM review_record WHERE reviewer_id=?
                      AND created_at<=UTC_TIMESTAMP(3) AND created_at>=TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(3))
                    """,Long.class,reviewerId)),"review_decisions_or_database_utc_default_not_preserved");
            require(Long.valueOf(2).equals(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM issue WHERE created_by=?
                      AND created_at<=UTC_TIMESTAMP(3) AND created_at>=TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(3))
                    """,Long.class,creatorId)),"issue_database_utc_default_not_preserved");
            require(Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE evidence_version_id=? AND decision='APPROVED' AND reason IS NULL",
                    Long.class,versionId)),"approved_review_null_reason_not_preserved");
            require(Integer.valueOf(1000).equals(jdbc.queryForObject("SELECT CHAR_LENGTH(reason) FROM review_record WHERE evidence_version_id=? AND decision='RETURNED'",
                    Integer.class,returnedVersionId)),"thousand_character_return_reason_not_preserved");
            return Map.of("multipleIssuesPerExactVersionAccepted",true,"fourThousandCharacterDescriptionAccepted",true,
                    "approvedNullableAndExplainedReasonsAccepted",true,"returnedThousandCharacterReasonAccepted",true,
                    "independentVersionConclusionsAccepted",true,"databaseUtcDefaultsVerified",true,"rolledBack",true,
                    "scope","Synthetic schema-only issue/review fixtures; business CAS and permissions are verified separately.");
        });
        require(roundTrip!=null,"missing_issue_review_round_trip_result"); report.put("issueReviewRoundTrip",roundTrip);
    }

    private void insertIssue(String id,String taskId,String versionId,String creatorId,String description) {
        require(jdbc.update("""
                INSERT INTO issue(id,task_id,evidence_version_id,description,created_by) VALUES(?,?,?,?,?)
                """,id,taskId,versionId,description,creatorId)==1,"temporary_issue_not_inserted");
    }

    private void insertReview(String id,String versionId,String reviewerId,String decision,String reason) {
        require(jdbc.update("""
                INSERT INTO review_record(id,evidence_version_id,decision,reason,reviewer_id) VALUES(?,?,?,?,?)
                """,id,versionId,decision,reason,reviewerId)==1,"temporary_review_not_inserted");
    }

    private void verifyTaskCompletionConstraints(Map<String,Object> checks,String taskId,Runnable validTask) {
        checks.put("completedTaskRequiresCompletionTime",expectConstraint("completed_task_requires_completion_time",3819,"HY000",validTask,
                ()->jdbc.update("UPDATE inspection_task SET status='COMPLETED' WHERE id=?",taskId)));
        for(String status:List.of("DRAFT","ASSIGNED","RUNNING","WAITING_ACCEPTANCE")) {
            checks.put("nonCompletedTaskCannotHaveCompletionTime_"+status,
                    expectConstraint("non_completed_task_cannot_have_completion_time_"+status.toLowerCase(java.util.Locale.ROOT),
                            3819,"HY000",validTask,()->jdbc.update(
                                    "UPDATE inspection_task SET status=?,completed_at=UTC_TIMESTAMP(3) WHERE id=?",status,taskId)));
        }
    }

    /** Exercise actual production conditional updates; readiness and permissions belong to service/API probes. */
    private void verifyTaskCompletionTransitions(TaskMapper mapper) {
        Map<String,Object> result=transactions.execute(transaction->{
            transaction.setRollbackOnly();
            String taskId=UUID.randomUUID().toString();
            insertTask(taskId,users.get("owner-admin").id(),OWNER,OPERATOR,0);
            require(mapper.submitAcceptance(taskId,0)==0 && mapper.completeTask(taskId,0)==0,
                    "draft_task_accepted_submit_or_complete_mapper_transition");
            // Set up only this synthetic task; do not claim this bypasses business eligibility checks.
            require(jdbc.update("UPDATE inspection_task SET status='RUNNING' WHERE id=?",taskId)==1,
                    "temporary_completion_fixture_not_running");
            require(mapper.submitAcceptance(taskId,1)==0,"stale_submit_task_version_was_accepted");
            require(mapper.completeTask(taskId,0)==0,"running_task_completed_without_waiting_state");
            require(mapper.submitAcceptance(taskId,0)==1,"current_task_submit_mapper_transition_failed");
            var waiting=mapper.findTask(taskId);
            require(waiting!=null && "WAITING_ACCEPTANCE".equals(waiting.status())
                            && waiting.lockVersion()==1 && waiting.completedAt()==null,
                    "waiting_task_state_version_or_null_completion_time_not_preserved");
            require(mapper.submitAcceptance(taskId,1)==0,"duplicate_submit_mapper_changed_waiting_task");
            require(mapper.completeTask(taskId,0)==0,"stale_complete_task_version_was_accepted");
            Instant before=mapper.databaseNow();
            require(mapper.completeTask(taskId,1)==1,"current_task_complete_mapper_transition_failed");
            Instant after=mapper.databaseNow();
            var completed=mapper.findTask(taskId);
            require(completed!=null && "COMPLETED".equals(completed.status())
                            && completed.lockVersion()==2 && completed.completedAt()!=null,
                    "completed_task_state_version_or_timestamp_not_preserved");
            Instant at=completed.completedAt();
            require(!at.isBefore(before) && !at.isAfter(after) && at.getNano()%1000000==0,
                    "completion_time_is_not_database_utc_milliseconds");
            LocalDateTime stored=jdbc.queryForObject("SELECT completed_at FROM inspection_task WHERE id=?",LocalDateTime.class,taskId);
            require(stored!=null && at.equals(stored.toInstant(ZoneOffset.UTC)),
                    "completion_mapper_instant_differs_from_stored_utc_datetime");
            require(mapper.completeTask(taskId,2)==0 && mapper.completeTask(taskId,1)==0
                            && mapper.submitAcceptance(taskId,2)==0,
                    "terminal_task_mapper_repeated_or_reopened_completion");
            var unchanged=mapper.findTask(taskId);
            require(unchanged!=null && unchanged.lockVersion()==2 && at.equals(unchanged.completedAt())
                            && "COMPLETED".equals(unchanged.status()),
                    "rejected_terminal_mapper_updates_changed_completion_time");
            return Map.of("productionSubmitAndCompleteMapperUpdatesVerified",true,
                    "expectedStateAndLockVersionRequired",true,"completionTimestampWrittenOnce",true,
                    "databaseUtcMillisecondsAndInstantMappingVerified",true,"rolledBack",true,
                    "scope","Synthetic rollback-only production Mapper checks; service eligibility, roles and HTTP completion are verified separately.");
        });
        require(result!=null,"missing_task_completion_transition_result");
        report.put("taskCompletionTransitions",result);
    }

    private void verifyAccessGrantConstraints(Map<String,Object> checks,String operatorUser,String taskId,Runnable validTask) {
        String subtask=UUID.randomUUID().toString(),requirement=UUID.randomUUID().toString(),evidence=UUID.randomUUID().toString();
        String execution=UUID.randomUUID().toString(),session=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();
        String applicant=UUID.randomUUID().toString(),approver=UUID.randomUUID().toString(),revoker=UUID.randomUUID().toString();
        String grant=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();
        Runnable pending=()->{
            validTask.run();insertSubtask(subtask,taskId);insertRequirement(requirement,evidence,taskId,subtask,1);
            insertExecution(execution,subtask,"RUNNING",0,FIXED_UTC,null);
            insertUploadSession(session,taskId,subtask,requirement,evidence,execution,operatorUser,UUID.randomUUID().toString());
            insertEvidenceVersion(version,session,taskId,subtask,requirement,evidence,execution,operatorUser,null,1);
            insertGrantUser(applicant,operatorUser);insertGrantUser(approver,users.get("owner-approver").id());
            insertGrantUser(revoker,users.get("owner-approver").id());insertAccessGrant(grant,version,applicant,request);
        };
        Runnable approved=()->{pending.run();approveAccessGrant(grant,approver);};
        Runnable rejected=()->{
            pending.run();require(jdbc.update("""
                    UPDATE access_grant SET status='REJECTED',approver_id=?,decided_at=UTC_TIMESTAMP(3),decision_reason='资料范围不符'
                    WHERE id=?
                    """,approver,grant)==1,"temporary_grant_rejection_not_saved");
        };
        Runnable revoked=()->{approved.run();revokeAccessGrant(grant,revoker);};
        checks.put("duplicateGrantApplicantRequest",expectConstraint("duplicate_grant_applicant_request",1062,"23000",pending,
                ()->insertAccessGrant(UUID.randomUUID().toString(),version,applicant,request)));
        checks.put("invalidGrantRequestId",expectConstraint("invalid_grant_request_id",3819,"HY000",pending,
                ()->jdbc.update("UPDATE access_grant SET client_request_id='not-a-uuid' WHERE id=?",grant)));
        checks.put("invalidGrantRequestHash",expectConstraint("invalid_grant_request_hash",3819,"HY000",pending,
                ()->jdbc.update("UPDATE access_grant SET request_hash=? WHERE id=?","A".repeat(64),grant)));
        checks.put("grantExpiryMustBeDerived",expectConstraint("grant_expiry_must_be_derived",3819,"HY000",pending,
                ()->jdbc.update("UPDATE access_grant SET status='EXPIRED' WHERE id=?",grant)));
        checks.put("emptyGrantReason",expectConstraint("empty_grant_reason",3819,"HY000",pending,
                ()->jdbc.update("UPDATE access_grant SET reason='' WHERE id=?",grant)));
        checks.put("unicodeWhitespaceGrantReason",expectConstraint("unicode_whitespace_grant_reason",3819,"HY000",pending,
                ()->jdbc.update("UPDATE access_grant SET reason=? WHERE id=?"," \t\n\u00a0\u3000",grant)));
        checks.put("oversizedGrantReason",expectConstraint("oversized_grant_reason",1406,"22001",pending,
                ()->jdbc.update("UPDATE access_grant SET reason=? WHERE id=?","申".repeat(1001),grant)));
        checks.put("grantRequestMustEndAfterCreation",expectConstraint("grant_request_must_end_after_creation",3819,"HY000",pending,
                ()->jdbc.update("UPDATE access_grant SET requested_valid_until=created_at WHERE id=?",grant)));
        checks.put("negativeGrantLockVersion",expectConstraint("negative_grant_lock_version",3819,"HY000",pending,
                ()->jdbc.update("UPDATE access_grant SET lock_version=-1 WHERE id=?",grant)));
        checks.put("pendingGrantCannotHaveApprover",expectConstraint("pending_grant_cannot_have_approver",3819,"HY000",pending,
                ()->jdbc.update("UPDATE access_grant SET approver_id=? WHERE id=?",approver,grant)));
        checks.put("approvedGrantRequiresApprover",expectConstraint("approved_grant_requires_approver",3819,"HY000",approved,
                ()->jdbc.update("UPDATE access_grant SET approver_id=NULL WHERE id=?",grant)));
        checks.put("approvedGrantRequiresDecisionTime",expectConstraint("approved_grant_requires_decision_time",3819,"HY000",approved,
                ()->jdbc.update("UPDATE access_grant SET decided_at=NULL WHERE id=?",grant)));
        checks.put("approvedGrantRequiresValidUntil",expectConstraint("approved_grant_requires_valid_until",3819,"HY000",approved,
                ()->jdbc.update("UPDATE access_grant SET valid_until=NULL WHERE id=?",grant)));
        checks.put("rejectedGrantRequiresDecisionReason",expectConstraint("rejected_grant_requires_decision_reason",3819,"HY000",rejected,
                ()->jdbc.update("UPDATE access_grant SET decision_reason=NULL WHERE id=?",grant)));
        checks.put("rejectedGrantCannotHaveValidity",expectConstraint("rejected_grant_cannot_have_validity",3819,"HY000",rejected,
                ()->jdbc.update("UPDATE access_grant SET valid_until=TIMESTAMPADD(MINUTE,5,UTC_TIMESTAMP(3)) WHERE id=?",grant)));
        checks.put("grantDecisionCannotPrecedeRequest",expectConstraint("grant_decision_cannot_precede_request",3819,"HY000",approved,
                ()->jdbc.update("UPDATE access_grant SET decided_at=TIMESTAMPADD(SECOND,-1,created_at) WHERE id=?",grant)));
        checks.put("grantValidityMustFollowDecision",expectConstraint("grant_validity_must_follow_decision",3819,"HY000",approved,
                ()->jdbc.update("UPDATE access_grant SET valid_until=decided_at WHERE id=?",grant)));
        checks.put("grantValidityCannotExceedRequestedEnd",expectConstraint("grant_validity_cannot_exceed_requested_end",3819,"HY000",approved,
                ()->jdbc.update("UPDATE access_grant SET valid_until=TIMESTAMPADD(SECOND,1,requested_valid_until) WHERE id=?",grant)));
        checks.put("blankGrantDecisionReason",expectConstraint("blank_grant_decision_reason",3819,"HY000",approved,
                ()->jdbc.update("UPDATE access_grant SET decision_reason=? WHERE id=?"," \t\u3000",grant)));
        checks.put("pendingGrantCannotHaveRevocation",expectConstraint("pending_grant_cannot_have_revocation",3819,"HY000",pending,
                ()->jdbc.update("UPDATE access_grant SET revoked_by=? WHERE id=?",revoker,grant)));
        checks.put("revokedGrantRequiresRevoker",expectConstraint("revoked_grant_requires_revoker",3819,"HY000",revoked,
                ()->jdbc.update("UPDATE access_grant SET revoked_by=NULL WHERE id=?",grant)));
        checks.put("revokedGrantRequiresReason",expectConstraint("revoked_grant_requires_reason",3819,"HY000",revoked,
                ()->jdbc.update("UPDATE access_grant SET revoke_reason=NULL WHERE id=?",grant)));
        checks.put("revokedGrantRequiresTime",expectConstraint("revoked_grant_requires_time",3819,"HY000",revoked,
                ()->jdbc.update("UPDATE access_grant SET revoked_at=NULL WHERE id=?",grant)));
        checks.put("revocationCannotPrecedeDecision",expectConstraint("revocation_cannot_precede_decision",3819,"HY000",revoked,
                ()->jdbc.update("UPDATE access_grant SET revoked_at=TIMESTAMPADD(SECOND,-1,decided_at) WHERE id=?",grant)));
        checks.put("revocationMustPrecedeExpiry",expectConstraint("revocation_must_precede_expiry",3819,"HY000",revoked,
                ()->jdbc.update("UPDATE access_grant SET revoked_at=valid_until WHERE id=?",grant)));
        checks.put("blankGrantRevokeReason",expectConstraint("blank_grant_revoke_reason",3819,"HY000",revoked,
                ()->jdbc.update("UPDATE access_grant SET revoke_reason=? WHERE id=?","\t\u00a0\u3000",grant)));
        checks.put("danglingGrantVersion",expectConstraint("dangling_grant_version",1452,"23000",pending,
                ()->jdbc.update("UPDATE access_grant SET version_id=? WHERE id=?",UUID.randomUUID().toString(),grant)));
        checks.put("danglingGrantApplicant",expectConstraint("dangling_grant_applicant",1452,"23000",pending,
                ()->jdbc.update("UPDATE access_grant SET applicant_id=? WHERE id=?",UUID.randomUUID().toString(),grant)));
        checks.put("danglingGrantApprover",expectConstraint("dangling_grant_approver",1452,"23000",approved,
                ()->jdbc.update("UPDATE access_grant SET approver_id=? WHERE id=?",UUID.randomUUID().toString(),grant)));
        checks.put("danglingGrantRevoker",expectConstraint("dangling_grant_revoker",1452,"23000",revoked,
                ()->jdbc.update("UPDATE access_grant SET revoked_by=? WHERE id=?",UUID.randomUUID().toString(),grant)));
        checks.put("restrictGrantedVersionDeletion",expectConstraint("restrict_granted_version_deletion",1451,"23000",pending,
                ()->jdbc.update("DELETE FROM evidence_version WHERE id=?",version)));
        checks.put("restrictGrantApplicantDeletion",expectConstraint("restrict_grant_applicant_deletion",1451,"23000",pending,
                ()->jdbc.update("DELETE FROM app_user WHERE id=?",applicant)));
        checks.put("restrictGrantApproverDeletion",expectConstraint("restrict_grant_approver_deletion",1451,"23000",approved,
                ()->jdbc.update("DELETE FROM app_user WHERE id=?",approver)));
        checks.put("restrictGrantRevokerDeletion",expectConstraint("restrict_grant_revoker_deletion",1451,"23000",revoked,
                ()->jdbc.update("DELETE FROM app_user WHERE id=?",revoker)));
        Map<String,Object> result=transactions.execute(transaction->{
            transaction.setRollbackOnly();pending.run();
            require(jdbc.update("UPDATE access_grant SET reason=? WHERE id=?","申".repeat(1000),grant)==1,
                    "temporary_maximum_grant_reason_not_saved");
            String approvedId=UUID.randomUUID().toString(),rejectedId=UUID.randomUUID().toString(),revokedId=UUID.randomUUID().toString();
            insertAccessGrant(approvedId,version,applicant,UUID.randomUUID().toString());approveAccessGrant(approvedId,approver);
            require(jdbc.update("UPDATE access_grant SET decision_reason=? WHERE id=?","批".repeat(1000),approvedId)==1,
                    "temporary_maximum_decision_reason_not_saved");
            insertAccessGrant(rejectedId,version,applicant,UUID.randomUUID().toString());
            require(jdbc.update("UPDATE access_grant SET status='REJECTED',approver_id=?,decided_at=UTC_TIMESTAMP(3),decision_reason='不批准' WHERE id=?",
                    approver,rejectedId)==1,"temporary_positive_rejection_not_saved");
            insertAccessGrant(revokedId,version,applicant,UUID.randomUUID().toString());approveAccessGrant(revokedId,approver);revokeAccessGrant(revokedId,revoker);
            require(jdbc.update("UPDATE access_grant SET revoke_reason=? WHERE id=?","撤".repeat(1000),revokedId)==1,
                    "temporary_maximum_revocation_reason_not_saved");
            String expired=UUID.randomUUID().toString();insertAccessGrant(expired,version,applicant,UUID.randomUUID().toString());approveAccessGrant(expired,approver);
            require(jdbc.update("""
                    UPDATE access_grant SET created_at=TIMESTAMPADD(HOUR,-2,UTC_TIMESTAMP(3)),
                        requested_valid_until=TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(3)),
                        decided_at=TIMESTAMPADD(MINUTE,-100,UTC_TIMESTAMP(3)),valid_until=TIMESTAMPADD(MINUTE,-90,UTC_TIMESTAMP(3))
                    WHERE id=?
                    """,expired)==1,"temporary_expired_approved_grant_not_saved");
            insertAccessGrant(UUID.randomUUID().toString(),version,approver,request);
            require(Long.valueOf(6).equals(jdbc.queryForObject("SELECT COUNT(*) FROM access_grant WHERE version_id=?",Long.class,version)),
                    "positive_grant_state_rows_not_preserved");
            require(Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM access_grant WHERE id=? AND status='APPROVED' AND valid_until<=UTC_TIMESTAMP(3)",
                    Long.class,expired)),"grant_expiry_must_remain_derived_from_approved_row");
            require(Long.valueOf(2).equals(jdbc.queryForObject("SELECT COUNT(*) FROM access_grant WHERE version_id=? AND client_request_id=?",Long.class,version,request)),
                    "different_applicants_cannot_reuse_independent_request_keys");
            require(Long.valueOf(1).equals(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM access_grant WHERE id=? AND CHAR_LENGTH(reason)=1000
                      AND created_at<=UTC_TIMESTAMP(3) AND created_at>=TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(3))
                    """,Long.class,grant)),"maximum_grant_reason_or_database_utc_default_not_preserved");
            require(Integer.valueOf(1000).equals(jdbc.queryForObject("SELECT CHAR_LENGTH(decision_reason) FROM access_grant WHERE id=?",Integer.class,approvedId))
                            &&Integer.valueOf(1000).equals(jdbc.queryForObject("SELECT CHAR_LENGTH(revoke_reason) FROM access_grant WHERE id=?",Integer.class,revokedId)),
                    "maximum_grant_decision_or_revoke_reason_not_preserved");
            return Map.of("allFourPersistedStatesAccepted",true,"expiryDerivedWithoutStateRewrite",true,
                    "applicantRequestKeyScopeVerified",true,"maximumUnicodeReasonsPreserved",true,
                    "databaseUtcDefaultVerified",true,"rolledBack",true,
                    "scope","Synthetic schema-only grant fixtures; live permissions, CAS and signed downloads are verified separately.");
        });
        require(result!=null,"missing_access_grant_round_trip_result");report.put("accessGrantRoundTrip",result);
    }

    private void insertGrantUser(String id,String source) {
        require(jdbc.update("""
                INSERT INTO app_user(id,org_id,username,password_hash,roles)
                SELECT ?,org_id,?,password_hash,roles FROM app_user WHERE id=?
                """,id,"T020-grant-"+UUID.randomUUID(),source)==1,"temporary_grant_user_not_inserted");
    }
    private void insertAccessGrant(String id,String version,String applicant,String request) {
        require(jdbc.update("""
                INSERT INTO access_grant(id,version_id,applicant_id,client_request_id,request_hash,reason,requested_valid_until)
                VALUES(?,?,?,?,?,'请求共享固定版本',TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(3)))
                """,id,version,applicant,request,"a".repeat(64))==1,"temporary_access_grant_not_inserted");
    }
    private void approveAccessGrant(String id,String approver) {
        require(jdbc.update("""
                UPDATE access_grant SET status='APPROVED',approver_id=?,decided_at=UTC_TIMESTAMP(3),
                    valid_until=TIMESTAMPADD(MINUTE,10,UTC_TIMESTAMP(3)),lock_version=lock_version+1 WHERE id=?
                """,approver,id)==1,"temporary_access_grant_not_approved");
    }
    private void revokeAccessGrant(String id,String revoker) {
        require(jdbc.update("""
                UPDATE access_grant SET status='REVOKED',revoked_by=?,revoke_reason='撤销固定版本共享',
                    revoked_at=UTC_TIMESTAMP(3),lock_version=lock_version+1 WHERE id=?
                """,revoker,id)==1,"temporary_access_grant_not_revoked");
    }

    private void verifyStorageCleanupConstraints(Map<String,Object> checks) {
        String id=UUID.randomUUID().toString(),upload=UUID.randomUUID().toString();
        String key="evidence-files/"+upload+"/"+UUID.randomUUID();
        Runnable valid=()->insertCleanupTask(id,key,upload);
        checks.put("duplicateCleanupObjectIdentity",expectConstraint("duplicate_cleanup_object_identity",1062,"23000",valid,()->
                insertCleanupTask(UUID.randomUUID().toString(),key,upload)));
        checks.put("malformedCleanupHash",expectConstraint("malformed_cleanup_hash",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET object_key_hash=? WHERE id=?","A".repeat(64),id)));
        checks.put("malformedCleanupUploadId",expectConstraint("malformed_cleanup_upload_id",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET upload_id='NOT-A-UUID' WHERE id=?",id)));
        checks.put("foreignCleanupNamespace",expectConstraint("foreign_cleanup_namespace",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET object_key='foreign/private/file' WHERE id=?",id)));
        checks.put("cleanupUploadPrefixMismatch",expectConstraint("cleanup_upload_prefix_mismatch",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET upload_id=? WHERE id=?",UUID.randomUUID().toString(),id)));
        checks.put("negativeCleanupObjectSize",expectConstraint("negative_cleanup_object_size",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET object_size=-1 WHERE id=?",id)));
        checks.put("unknownCleanupStatus",expectConstraint("unknown_cleanup_status",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET status='DELETED' WHERE id=?",id)));
        checks.put("negativeCleanupAttempts",expectConstraint("negative_cleanup_attempts",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET attempt_count=-1 WHERE id=?",id)));
        checks.put("sendingCleanupWithoutLease",expectConstraint("sending_cleanup_without_lease",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET status='SENDING',next_action_at=NULL WHERE id=?",id)));
        checks.put("pendingCleanupWithLease",expectConstraint("pending_cleanup_with_lease",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET lease_token=?,lease_until=UTC_TIMESTAMP(3) WHERE id=?",UUID.randomUUID().toString(),id)));
        checks.put("pendingCleanupWithoutPlan",expectConstraint("pending_cleanup_without_plan",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET next_action_at=NULL WHERE id=?",id)));
        checks.put("terminalCleanupWithPlan",expectConstraint("terminal_cleanup_with_plan",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET status='DONE' WHERE id=?",id)));
        checks.put("unsafeCleanupError",expectConstraint("unsafe_cleanup_error",3819,"HY000",valid,()->
                jdbc.update("UPDATE storage_cleanup_task SET last_error='private provider details' WHERE id=?",id)));
    }

    private void verifyStorageCleanupLease(CleanupMapper mapper) {
        Map<String,Object> result=transactions.execute(transaction->{
            transaction.setRollbackOnly();
            String id=UUID.randomUUID().toString(),upload=UUID.randomUUID().toString();
            String key="evidence-files/"+upload+"/"+UUID.randomUUID();insertCleanupTask(id,key,upload);
            var phases=new CleanupTransactions(mapper,new CleanupSettings(new org.springframework.core.env.StandardEnvironment()));
            var claim=phases.claim(id);
            require(claim!=null && phases.verifyDeletion(claim,new ObjectInventory.Entry(key,mapper.now().minusSeconds(7200),0)),
                    "old_server_namespace_orphan_without_upload_fk_must_be_claimable");
            var wrong=new CleanupTransactions.Claim(claim.id(),claim.objectKey(),claim.uploadId(),UUID.randomUUID().toString(),claim.attemptCount(),claim.orgId());
            require(!phases.done(wrong) && !phases.retry(wrong) && !phases.renew(wrong),
                    "wrong_cleanup_owner_must_not_save_result_or_renew");
            jdbc.update("UPDATE storage_cleanup_task SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",id);
            require(!phases.done(claim) && !phases.retry(claim) && !phases.renew(claim),
                    "expired_cleanup_owner_must_not_save_result_or_renew");
            var replacement=phases.claim(id);
            require(replacement!=null && !replacement.token().equals(claim.token()) && !phases.done(claim),
                    "cleanup_takeover_must_fence_original_token");
            require(phases.retry(replacement) && phases.claim(id)==null,
                    "cleanup_storage_failure_must_keep_original_identity_and_enforce_backoff");
            jdbc.update("UPDATE storage_cleanup_task SET next_action_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",id);
            var resumed=phases.claim(id);
            require(resumed!=null && phases.done(resumed),"current_cleanup_owner_must_finish_with_cleared_lease");
            var complete=mapper.task(id);
            require("DONE".equals(complete.status()) && complete.nextActionAt()==null && complete.leaseToken()==null && complete.leaseUntil()==null,
                    "completed_cleanup_shape_must_be_terminal_and_lease_free");
            require(phases.discover(new ObjectInventory.Entry(key,mapper.now().minusSeconds(7200),0))
                            && "PENDING".equals(mapper.task(id).status()),
                    "late_reappearing_orphan_must_rearm_same_durable_cleanup_identity");
            String invalid=UUID.randomUUID().toString();insertCleanupTask(invalid,"evidence-files/"+upload+"/../private",upload);
            require(phases.claim(invalid)==null && "INVALID_OBJECT_KEY".equals(mapper.task(invalid).lastError()),
                    "prefix_shape_alone_must_not_authorize_noncanonical_cleanup_key");
            String fresh=UUID.randomUUID().toString(),freshKey="evidence-files/"+upload+"/"+UUID.randomUUID();insertCleanupTask(fresh,freshKey,upload);
            var freshClaim=phases.claim(fresh);
            require(freshClaim!=null && !phases.verifyDeletion(freshClaim,new ObjectInventory.Entry(freshKey,mapper.now(),0))
                            && "GRACE_PERIOD".equals(mapper.task(fresh).lastError()),
                    "fresh_head_metadata_must_prevent_deleting_replaced_young_object");
            return Map.of("missingUploadOrphanAccepted",true,"staleAndExpiredOwnersRejected",true,
                    "leaseTakeoverFenced",true,"retryBackoffEnforced",true,"lateObjectReappearanceRecoverable",true,
                    "noncanonicalKeysRejected",true,"freshHeadRespectsGrace",true,"rolledBack",true,
                    "scope","Rollback-only cleanup protocol and clock fixtures; no S3 object was deleted.");
        });
        require(result!=null,"missing_storage_cleanup_lease_round_trip_result");report.put("storageCleanupLeaseRoundTrip",result);
    }

    private void insertCleanupTask(String id,String key,String upload) {
        require(jdbc.update("""
                INSERT INTO storage_cleanup_task(id,object_key,object_key_hash,upload_id,object_last_modified,object_size)
                VALUES(?,?,?,?,TIMESTAMPADD(HOUR,-2,UTC_TIMESTAMP(3)),0)
                """,id,key,sha256(key.getBytes(StandardCharsets.UTF_8)),upload)==1,"temporary_storage_cleanup_task_not_inserted");
    }

    private void verifyAnchorRetryRequestConstraints(Map<String,Object> checks) {
        RetryFixture f=retryFixture();
        String event=UUID.randomUUID().toString(),request=UUID.randomUUID().toString(),client=UUID.randomUUID().toString();
        Runnable valid=()->{
            f.setup().run(); insertAnchorOutboxEvent(event,f.submissionId(),1);
            insertAnchorRetryRequest(request,f.submissionId(),f.actorId(),client,1,0,event);
        };
        checks.put("duplicateAnchorRetryClientKey",expectConstraint("duplicate_anchor_retry_client_key",1062,"23000",valid,()->{
            String other=UUID.randomUUID().toString();insertAnchorOutboxEvent(other,f.submissionId(),2);
            insertAnchorRetryRequest(UUID.randomUUID().toString(),f.submissionId(),f.actorId(),client,2,0,other);
        }));
        checks.put("duplicateAnchorRetryDispatch",expectConstraint("duplicate_anchor_retry_dispatch",1062,"23000",valid,()->{
            String other=UUID.randomUUID().toString();insertAnchorOutboxEvent(other,f.submissionId(),2);
            insertAnchorRetryRequest(UUID.randomUUID().toString(),f.submissionId(),f.actorId(),UUID.randomUUID().toString(),1,0,other);
        }));
        checks.put("duplicateAnchorRetryEvent",expectConstraint("duplicate_anchor_retry_event",1062,"23000",valid,()->
                insertAnchorRetryRequest(UUID.randomUUID().toString(),f.submissionId(),f.actorId(),UUID.randomUUID().toString(),2,0,event)));
        checks.put("invalidAnchorRetryClientId",expectConstraint("invalid_anchor_retry_client_id",3819,"HY000",valid,()->
                jdbc.update("UPDATE anchor_retry_request SET client_request_id='not-a-request-uuid' WHERE id=?",request)));
        checks.put("invalidAnchorRetryRequestHash",expectConstraint("invalid_anchor_retry_request_hash",3819,"HY000",valid,()->
                jdbc.update("UPDATE anchor_retry_request SET request_hash=? WHERE id=?","A".repeat(64),request)));
        checks.put("blankAnchorRetryReason",expectConstraint("blank_anchor_retry_reason",3819,"HY000",valid,()->
                jdbc.update("UPDATE anchor_retry_request SET reason=' \t\n' WHERE id=?",request)));
        checks.put("oversizedAnchorRetryReason",expectConstraint("oversized_anchor_retry_reason",1406,"22001",valid,()->
                jdbc.update("UPDATE anchor_retry_request SET reason=? WHERE id=?","理".repeat(1001),request)));
        checks.put("zeroAnchorRetryDispatch",expectConstraint("zero_anchor_retry_dispatch",3819,"HY000",valid,()->
                jdbc.update("UPDATE anchor_retry_request SET dispatch_no=0 WHERE id=?",request)));
        checks.put("negativeAnchorRetryBaseline",expectConstraint("negative_anchor_retry_baseline",3819,"HY000",valid,()->
                jdbc.update("UPDATE anchor_retry_request SET baseline_attempt_no=-1 WHERE id=?",request)));
        checks.put("danglingAnchorRetrySubmission",expectConstraint("dangling_anchor_retry_submission",1452,"23000",valid,()->
                jdbc.update("UPDATE anchor_retry_request SET submission_id=? WHERE id=?",UUID.randomUUID().toString(),request)));
        checks.put("danglingAnchorRetryRequester",expectConstraint("dangling_anchor_retry_requester",1452,"23000",valid,()->
                jdbc.update("UPDATE anchor_retry_request SET requested_by=? WHERE id=?",UUID.randomUUID().toString(),request)));
        checks.put("danglingAnchorRetryEvent",expectConstraint("dangling_anchor_retry_event",1452,"23000",valid,()->
                jdbc.update("UPDATE anchor_retry_request SET event_id=? WHERE id=?",UUID.randomUUID().toString(),request)));
        checks.put("restrictRetrySubmissionDeletion",expectConstraint("restrict_retry_submission_deletion",1451,"23000",valid,()->
                jdbc.update("DELETE FROM chain_submission WHERE id=?",f.submissionId())));
        checks.put("restrictRetryRequesterDeletion",expectConstraint("restrict_retry_requester_deletion",1451,"23000",valid,()->
                jdbc.update("DELETE FROM app_user WHERE id=?",f.actorId())));
        checks.put("restrictRetryEventDeletion",expectConstraint("restrict_retry_event_deletion",1451,"23000",valid,()->
                jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event)));
    }

    private void verifyAnchorRetryBudget(AnchorMapper mapper) {
        Map<String,Object> result=transactions.execute(transaction->{
            transaction.setRollbackOnly();RetryFixture f=retryFixture();f.setup().run();
            String id=f.submissionId(),token=UUID.randomUUID().toString();
            jdbc.update("UPDATE chain_submission SET status='UNKNOWN',retry_count=100,next_action_at=UTC_TIMESTAMP(3) WHERE id=?",id);
            require(mapper.automaticFailureCount(id)==0 && mapper.claim(id,token)==1 && mapper.renew(id,token)==1,
                    "unknown_reconciliations_must_not_exhaust_definitive_failure_budget");
            require(mapper.checkpoint(id,token,"UNKNOWN",true,mapper.now(),"LEDGER_QUERY_UNAVAILABLE")==1,
                    "unknown_lease_free_recovery_plan_not_saved");
            for(int n=1;n<=9;n++) insertSyntheticInvalidAttempt(id,n,n%2==0?12:11);
            jdbc.update("UPDATE chain_submission SET status='FAILED',retryable=TRUE,next_action_at=UTC_TIMESTAMP(3) WHERE id=?",id);
            var phases=new AnchorTransactions(mapper);
            var due=mapper.due().stream().filter(e->id.equals(e.bizId())).findFirst().orElseThrow();
            var tenthOwner=phases.claim(due,true);
            require(tenthOwner!=null && "PENDING".equals(mapper.find(id).status()),
                    "ninth_definite_failure_must_allow_current_owner_to_start_next_attempt");
            insertSyntheticInvalidAttempt(id,10,12);
            require(phases.renew(tenthOwner) && phases.checkpoint(tenthOwner,mapper.latest(id),"FAILED",true,"COMMIT_CONFLICT",false)
                            && mapper.find(id).nextActionAt()==null && "DONE".equals(mapper.consumption(due.eventId())),
                    "tenth_definite_failure_owner_must_commit_done_and_stop_without_losing_its_lease");
            report.put("tenthDefiniteConflictCheckpointVerified",true);
            require(mapper.automaticFailureCount(id)==10 && mapper.due().stream().noneMatch(e->id.equals(e.bizId()))
                            && mapper.claim(id,UUID.randomUUID().toString())==0,
                    "ten_definite_conflicts_must_stop_automatic_claim_and_scan");
            String request=UUID.randomUUID().toString(),event=UUID.randomUUID().toString();
            insertAnchorOutboxEvent(event,id,1);
            insertAnchorRetryRequest(request,id,f.actorId(),UUID.randomUUID().toString(),1,10,event);
            jdbc.update("UPDATE anchor_retry_request SET reason=? WHERE id=?","理".repeat(1000),request);
            require(mapper.automaticFailureCount(id)==0 && mapper.due().stream()
                            .anyMatch(e->id.equals(e.bizId()) && event.equals(e.eventId())),
                    "manual_baseline_must_preserve_old_attempts_and_select_latest_dispatch");
            token=UUID.randomUUID().toString();
            require(mapper.claim(id,token)==1 && "PENDING".equals(mapper.find(id).status()),
                    "new_cycle_must_claim_failed_work_without_old_total_checkpoint_cap");
            insertSyntheticInvalidAttempt(id,11,11);
            require(mapper.automaticFailureCount(id)==1 && mapper.renew(id,token)==1,
                    "current_cycle_counts_only_new_definite_conflicts");
            require(mapper.checkpoint(id,token,"FAILED",true,mapper.now(),"COMMIT_CONFLICT")==1,
                    "new_cycle_conflict_must_have_durable_next_action");
            insertSyntheticInvalidAttempt(id,12,25);
            require(mapper.automaticFailureCount(id)==1,"non_allowlisted_invalid_result_consumed_retry_budget");
            jdbc.update("UPDATE chain_submission SET status='FAILED',retryable=FALSE,next_action_at=NULL WHERE id=?",id);
            require(mapper.claim(id,UUID.randomUUID().toString())==0 && mapper.due().stream().noneMatch(e->id.equals(e.bizId())),
                    "permanent_failure_must_not_be_automatically_claimed");
            jdbc.update("UPDATE chain_submission SET status='UNKNOWN',retryable=TRUE,retry_count=2147483647,next_action_at=NULL WHERE id=?",id);
            token=UUID.randomUUID().toString();
            require(mapper.claim(id,token)==1 && mapper.checkpoint(id,token,"UNKNOWN",true,mapper.now(),"LEDGER_QUERY_UNAVAILABLE")==1
                            && mapper.find(id).retryCount()==Integer.MAX_VALUE,
                    "unknown_checkpoint_counter_must_saturate_without_stopping_recovery");
            require(Long.valueOf(12).equals(jdbc.queryForObject("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=?",Long.class,id)),
                    "manual_cycle_must_not_delete_or_renumber_old_attempts");
            require(Long.valueOf(1).equals(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM anchor_retry_request WHERE id=? AND CHAR_LENGTH(reason)=1000
                      AND created_at<=UTC_TIMESTAMP(3) AND created_at>=TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(3))
                    """,Long.class,request)),"retry_reason_or_database_utc_default_not_preserved");
            return Map.of("unknownDoesNotConsumeFailureBudget",true,"tenDefiniteConflictsStopNewClaims",true,
                    "manualBaselineStartsNewBoundedCycle",true,"latestDispatchSelected",true,
                    "allOldAttemptsRetained",true,"permanentFailuresExcluded",true,
                    "unknownCounterSaturates",true,"maximumUnicodeReasonAndDatabaseUtcVerified",true,
                    "rolledBack",true,"scope","Synthetic rollback-only SQL transaction outcomes and checkpoint bytes; no Fabric receipt or business permission is claimed.");
        });
        require(result!=null,"missing_anchor_retry_budget_round_trip_result");report.put("anchorRetryBudgetRoundTrip",result);
    }

    private RetryFixture retryFixture() {
        String task=UUID.randomUUID().toString(),subtask=UUID.randomUUID().toString(),requirement=UUID.randomUUID().toString();
        String evidence=UUID.randomUUID().toString(),execution=UUID.randomUUID().toString(),upload=UUID.randomUUID().toString();
        String version=UUID.randomUUID().toString(),submission=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString();
        Runnable setup=()->{
            insertTask(task,users.get("owner-admin").id(),OWNER,OPERATOR,0);insertSubtask(subtask,task);
            insertRequirement(requirement,evidence,task,subtask,1);insertExecution(execution,subtask,"RUNNING",0,FIXED_UTC,null);
            insertUploadSession(upload,task,subtask,requirement,evidence,execution,users.get("operator-user").id(),UUID.randomUUID().toString());
            insertEvidenceVersion(version,upload,task,subtask,requirement,evidence,execution,users.get("operator-user").id(),null,1);
            insertChainSubmission(submission,version);insertAnchorOutboxEvent(UUID.randomUUID().toString(),submission,0);
            require(jdbc.update("""
                    INSERT INTO app_user(id,org_id,username,password_hash,roles)
                    SELECT ?,org_id,?,password_hash,roles FROM app_user WHERE id=?
                    """,actor,"T024-rollback-"+actor,users.get("operator-admin").id())==1,"temporary_retry_requester_not_inserted");
        };
        return new RetryFixture(submission,actor,setup);
    }

    private void insertAnchorOutboxEvent(String event,String submission,int dispatch) {
        require(jdbc.update("""
                INSERT INTO outbox_event(event_id,event_type,biz_id,dispatch_no,payload)
                VALUES(?,'EVIDENCE_ANCHOR_REQUESTED',?,?,JSON_OBJECT('eventId',?,'eventType','EVIDENCE_ANCHOR_REQUESTED',
                    'bizId',?,'traceId','T024-rollback-probe'))
                """,event,submission,dispatch,event,submission)==1,"temporary_anchor_dispatch_not_inserted");
    }

    private void insertAnchorRetryRequest(String id,String submission,String actor,String client,int dispatch,int baseline,String event) {
        require(jdbc.update("""
                INSERT INTO anchor_retry_request(id,submission_id,requested_by,client_request_id,request_hash,
                    reason,dispatch_no,baseline_attempt_no,event_id)
                VALUES(?,?,?,?,?,'重新检查原交易',?,?,?)
                """,id,submission,actor,client,"a".repeat(64),dispatch,baseline,event)==1,"temporary_anchor_retry_request_not_inserted");
    }

    private void insertSyntheticInvalidAttempt(String submission,int number,int code) {
        String id=UUID.randomUUID().toString();
        insertChainAttempt(id,submission,number,sha256(id.getBytes(StandardCharsets.UTF_8)),new byte[]{1,2,3});
        require(jdbc.update("UPDATE chain_attempt SET phase='INVALID',validation_code=?,block_number=42 WHERE id=?",code,id)==1,
                "synthetic_rollback_invalid_outcome_not_saved");
    }

    private record RetryFixture(String submissionId,String actorId,Runnable setup) {}

    private void verifyMessageRejectionConstraints(Map<String,Object> checks) {
        String event = UUID.randomUUID().toString();
        Runnable outbox = () -> insertOutboxEvent(event, UUID.randomUUID().toString());
        checks.put("invalidOutboxStatus", expectConstraint("invalid_outbox_status",3819,"HY000",outbox,
                () -> jdbc.update("UPDATE outbox_event SET status='DROPPED' WHERE event_id=?",event)));
        checks.put("failedOutboxRequiresReason", expectConstraint("failed_outbox_requires_reason",3819,"HY000",outbox,
                () -> jdbc.update("UPDATE outbox_event SET status='FAILED' WHERE event_id=?",event)));
        checks.put("failedOutboxRequiresPermanentReason", expectConstraint("failed_outbox_requires_permanent_reason",3819,"HY000",outbox,
                () -> jdbc.update("UPDATE outbox_event SET status='FAILED',last_error='PUBLISH_NACK' WHERE event_id=?",event)));
        checks.put("failedOutboxCannotHaveLease", expectConstraint("failed_outbox_cannot_have_lease",3819,"HY000",outbox,
                () -> jdbc.update("""
                        UPDATE outbox_event SET status='FAILED',last_error='INVALID_ENVELOPE',
                            lease_token=?,lease_until=UTC_TIMESTAMP(3) WHERE event_id=?
                        """,UUID.randomUUID().toString(),event)));
        checks.put("failedOutboxCannotHaveSentTime", expectConstraint("failed_outbox_cannot_have_sent_time",3819,"HY000",outbox,
                () -> jdbc.update("""
                        UPDATE outbox_event SET status='FAILED',last_error='INVALID_ENVELOPE',sent_at=UTC_TIMESTAMP(3)
                        WHERE event_id=?
                        """,event)));
        String id = UUID.randomUUID().toString();
        String hash = sha256(id.getBytes(StandardCharsets.UTF_8));
        Runnable rejection = () -> insertMessageRejection(id,"FILE_FINALIZE",hash,0,"INVALID_ENVELOPE",null);
        checks.put("duplicateConsumerRejectedBody", expectConstraint("duplicate_consumer_rejected_body",1062,"23000",rejection,
                () -> insertMessageRejection(UUID.randomUUID().toString(),"FILE_FINALIZE",hash,3,"UNKNOWN_EVENT",null)));
        checks.put("invalidRejectionConsumer", expectConstraint("invalid_rejection_consumer",3819,"HY000",rejection,
                () -> jdbc.update("UPDATE message_rejection SET consumer_code='OTHER_CONSUMER' WHERE id=?",id)));
        checks.put("invalidRejectionHash", expectConstraint("invalid_rejection_hash",3819,"HY000",rejection,
                () -> jdbc.update("UPDATE message_rejection SET body_sha256=? WHERE id=?","g".repeat(64),id)));
        checks.put("nonCanonicalRejectionHash", expectConstraint("non_canonical_rejection_hash",3819,"HY000",rejection,
                () -> jdbc.update("UPDATE message_rejection SET body_sha256=? WHERE id=?","A".repeat(64),id)));
        checks.put("negativeRejectionBodySize", expectConstraint("negative_rejection_body_size",3819,"HY000",rejection,
                () -> jdbc.update("UPDATE message_rejection SET body_size=-1 WHERE id=?",id)));
        checks.put("rejectionSizeBeyondByteArray", expectConstraint("rejection_size_beyond_byte_array",3819,"HY000",rejection,
                () -> jdbc.update("UPDATE message_rejection SET body_size=2147483648 WHERE id=?",id)));
        checks.put("invalidRejectionReason", expectConstraint("invalid_rejection_reason",3819,"HY000",rejection,
                () -> jdbc.update("UPDATE message_rejection SET reason_code='UNSAFE_PROVIDER_ERROR' WHERE id=?",id)));
        checks.put("invalidRejectionMessageIdentity", expectConstraint("invalid_rejection_message_identity",3819,"HY000",rejection,
                () -> jdbc.update("UPDATE message_rejection SET message_id='not-a-canonical-uuid' WHERE id=?",id)));
        checks.put("nonCanonicalRejectionMessageIdentity", expectConstraint("non_canonical_rejection_message_identity",3819,"HY000",rejection,
                () -> jdbc.update("UPDATE message_rejection SET message_id='AAAAAAAA-0000-4000-8000-000000000001' WHERE id=?",id)));
    }

    private void insertMessageRejection(String id,String consumer,String hash,long size,String reason,String messageId) {
        require(jdbc.update("""
                INSERT INTO message_rejection(id,consumer_code,body_sha256,body_size,reason_code,message_id,created_at)
                VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))
                """,id,consumer,hash,size,reason,messageId)==1,"temporary_message_rejection_not_inserted");
    }

    private void verifyMessageRejectionRoundTrip(MessageRejectionMapper mapper) {
        Map<String,Object> result = transactions.execute(transaction -> {
            transaction.setRollbackOnly();
            String id=UUID.randomUUID().toString(), hash=sha256(id.getBytes(StandardCharsets.UTF_8));
            String messageId=UUID.randomUUID().toString();
            mapper.insertFirst(id,"FILE_FINALIZE",hash,0,"INVALID_ENVELOPE",null);
            LocalDateTime first=jdbc.queryForObject("SELECT created_at FROM message_rejection WHERE id=?",LocalDateTime.class,id);
            mapper.insertFirst(UUID.randomUUID().toString(),"FILE_FINALIZE",hash,4097,"MESSAGE_ID_MISMATCH",messageId);
            var row=jdbc.queryForMap("SELECT id,body_size,reason_code,message_id,created_at FROM message_rejection WHERE id=?",id);
            require(id.equals(row.get("id")) && ((Number)row.get("body_size")).longValue()==0
                            && "INVALID_ENVELOPE".equals(row.get("reason_code")) && row.get("message_id")==null
                            && first.equals(jdbc.queryForObject("SELECT created_at FROM message_rejection WHERE id=?",LocalDateTime.class,id)),
                    "duplicate_rejection_changed_first_metadata");
            String other=UUID.randomUUID().toString();
            mapper.insertFirst(other,"EVIDENCE_ANCHOR",hash,2147483647L,"UNKNOWN_EVENT",messageId);
            require(Long.valueOf(2).equals(jdbc.queryForObject("SELECT COUNT(*) FROM message_rejection WHERE body_sha256=?",Long.class,hash)),
                    "rejection_body_not_independent_between_consumers");
            require(Long.valueOf(2).equals(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM message_rejection WHERE body_sha256=?
                        AND created_at<=UTC_TIMESTAMP(3) AND created_at>=TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(3))
                    """,Long.class,hash)),"rejection_timestamp_not_database_utc");
            require(messageId.equals(jdbc.queryForObject("SELECT message_id FROM message_rejection WHERE id=?",String.class,other)),
                    "canonical_rejection_message_identity_not_preserved");
            return Map.of("duplicateBodyRetainsFirstMetadata",true,"consumerScopeIndependent",true,
                    "zeroAndMaximumByteArraySizesAccepted",true,"canonicalOrNullMessageIdentityPreserved",true,
                    "databaseUtcMillisecondsVerified",true,"rolledBack",true);
        });
        require(result!=null,"missing_message_rejection_round_trip_result");
        report.put("messageRejectionRoundTrip",result);
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
            require(mapper.failed(id, stale, "INVALID_ENVELOPE") == 0, "outbox_stale_token_marked_failed");
            require("SENDING".equals(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?",
                    String.class, id)), "outbox_stale_owner_changed_status");
            require(jdbc.update("""
                    UPDATE outbox_event SET lease_until = TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3))
                    WHERE event_id = ?
                    """, id) == 1, "outbox_probe_lease_not_expired");
            require(mapper.sent(id, token) == 0, "outbox_expired_owner_marked_sent");
            require(mapper.retry(id, token, "PUBLISH_NACK", 2) == 0, "outbox_expired_owner_scheduled_retry");
            require(mapper.failed(id, token, "INVALID_ENVELOPE") == 0, "outbox_expired_owner_marked_failed");
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
            require(mapper.claim(id, replacement) == 0, "outbox_retry_backoff_was_bypassed");
            require(jdbc.update("UPDATE outbox_event SET next_send_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE event_id=?",id)==1,
                    "outbox_probe_retry_not_made_due");
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
            String failedId=UUID.randomUUID().toString(), failedToken=UUID.randomUUID().toString();
            insertOutboxEvent(failedId,UUID.randomUUID().toString());
            require(mapper.claim(failedId,failedToken)==1 && mapper.failed(failedId,failedToken,"INVALID_ENVELOPE")==1,
                    "outbox_live_owner_permanent_failure_not_saved");
            var failed=jdbc.queryForMap("SELECT status,lease_token,lease_until,sent_at,last_error FROM outbox_event WHERE event_id=?",failedId);
            require("FAILED".equals(failed.get("status")) && "INVALID_ENVELOPE".equals(failed.get("last_error"))
                            && failed.get("lease_token")==null && failed.get("lease_until")==null && failed.get("sent_at")==null,
                    "outbox_failed_checkpoint_shape_invalid");
            require(mapper.claim(failedId,UUID.randomUUID().toString())==0 && mapper.sent(failedId,failedToken)==0
                            && mapper.retry(failedId,failedToken,"PUBLISH_NACK",2)==0
                            && mapper.failed(failedId,failedToken,"INVALID_ENVELOPE")==0,
                    "outbox_failed_terminal_state_changed");
            return Map.ofEntries(Map.entry("staleTokenSentRejected", true), Map.entry("staleTokenRetryRejected", true),
                    Map.entry("expiredOwnerSentRejected", true), Map.entry("expiredOwnerRetryRejected", true),
                    Map.entry("liveOwnerRetrySaved", true), Map.entry("priorOwnerAfterReplacementRejected", true),
                    Map.entry("liveOwnerSentSaved", true), Map.entry("checkpointShapesValid", true), Map.entry("rolledBack", true),
                    Map.entry("staleAndExpiredFailureOwnersRejected",true),Map.entry("retryBackoffEnforced",true),
                    Map.entry("permanentFailureTerminalAndLeaseFree",true));
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
        boolean originalSixteenBaseline = ORIGINAL_SIXTEEN_SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain"));
        boolean originalSeventeenBaseline = ORIGINAL_SEVENTEEN_SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain"));
        boolean originalEighteenBaseline = ORIGINAL_EIGHTEEN_SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain"));
        boolean originalTwentyBaseline = ORIGINAL_TWENTY_SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain"));
        boolean originalTwentyOneBaseline = ORIGINAL_TWENTY_ONE_SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain"));
        boolean originalTwentyTwoBaseline = ORIGINAL_TWENTY_TWO_SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain"));
        boolean originalTwentyThreeBaseline = ORIGINAL_TWENTY_THREE_SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain"));
        boolean olderBaseline = originalElevenBaseline || originalFourteenBaseline || originalFifteenBaseline
                || originalSixteenBaseline || originalSeventeenBaseline || originalEighteenBaseline
                || originalTwentyBaseline || originalTwentyOneBaseline || originalTwentyTwoBaseline || originalTwentyThreeBaseline;
        require(olderBaseline || SNAPSHOT_DOMAIN.equals(previous.get("businessSnapshotDomain")),
                "comparison_unknown_snapshot_domain");
        String snapshotField = originalElevenBaseline ? "originalElevenSnapshotSha256"
                : originalFourteenBaseline ? "originalFourteenSnapshotSha256"
                : originalFifteenBaseline ? "originalFifteenSnapshotSha256"
                : originalSixteenBaseline ? "originalSixteenSnapshotSha256"
                : originalSeventeenBaseline ? "originalSeventeenSnapshotSha256"
                : originalEighteenBaseline ? "originalEighteenSnapshotSha256"
                : originalTwentyBaseline ? "originalTwentySnapshotSha256"
                : originalTwentyOneBaseline ? "originalTwentyOneSnapshotSha256"
                : originalTwentyTwoBaseline ? "originalTwentyTwoSnapshotSha256"
                : originalTwentyThreeBaseline ? "originalTwentyThreeSnapshotSha256" : "businessSnapshotSha256";
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
