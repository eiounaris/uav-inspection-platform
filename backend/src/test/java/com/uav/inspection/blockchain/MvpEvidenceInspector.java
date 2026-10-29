package com.uav.inspection.blockchain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.uav.inspection.blockchain.EvidenceLedgerClient.CommitCheckpoint;
import com.uav.inspection.blockchain.EvidenceLedgerClient.EvidenceRecord;
import com.uav.inspection.blockchain.EvidenceLedgerClient.PreparedRegistration;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.hyperledger.fabric.protos.common.Payload;
import org.hyperledger.fabric.protos.gateway.PreparedTransaction;
import org.hyperledger.fabric.protos.msp.SerializedIdentity;
import org.hyperledger.fabric.protos.peer.ChaincodeActionPayload;
import org.hyperledger.fabric.protos.peer.ChaincodeInvocationSpec;
import org.hyperledger.fabric.protos.peer.ChaincodeProposalPayload;
import org.hyperledger.fabric.protos.peer.Transaction;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Explicit SELECT/GET/query-only CLI. It never starts Spring, Flyway, Rabbit consumers or workers. */
public final class MvpEvidenceInspector {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String OWNER="00000000-0000-4000-8000-000000000001";
    private static final String OPERATOR="00000000-0000-4000-8000-000000000002";
    private static final String DRONE="20000000-0000-4000-8000-000000000001";
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String DOMAIN="T014-original-sixteen-row-snapshots-v1";
    private static final Map<String,List<String>> TABLES=new LinkedHashMap<>();
    private static final List<Account> ACCOUNTS=List.of(
            new Account(1,OWNER,"owner-admin",Set.of("ORG_ADMIN","OWNER_TASK_ADMIN")),
            new Account(2,OWNER,"owner-reviewer",Set.of("OWNER_REVIEWER")),
            new Account(3,OWNER,"owner-approver",Set.of("OWNER_DATA_APPROVER")),
            new Account(4,OPERATOR,"operator-admin",Set.of("ORG_ADMIN","OPERATOR_TASK_ADMIN")),
            new Account(5,OPERATOR,"operator-user",Set.of("OPERATOR_USER")),
            new Account(6,OPERATOR,"operator-outsider",Set.of("OPERATOR_USER")));
    static {
        TABLES.put("organization",List.of("id")); TABLES.put("app_user",List.of("id"));
        TABLES.put("inspection_task",List.of("id")); TABLES.put("task_member",List.of("task_id","user_id"));
        TABLES.put("drone",List.of("id")); TABLES.put("inspection_subtask",List.of("id"));
        TABLES.put("material_requirement",List.of("id")); TABLES.put("task_execution",List.of("id"));
        TABLES.put("upload_session",List.of("id")); TABLES.put("upload_part",List.of("session_id","part_no"));
        TABLES.put("evidence_version",List.of("id")); TABLES.put("chain_submission",List.of("id"));
        TABLES.put("outbox_event",List.of("event_id")); TABLES.put("consumed_event",List.of("consumer_code","event_id"));
        TABLES.put("chain_attempt",List.of("id")); TABLES.put("verification_record",List.of("id"));
    }

    private final Map<String,String> environment=System.getenv();
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<String> checks=new ArrayList<>();
    private final List<String> secrets=new ArrayList<>();
    private final Path output;
    private Connection connection;

    private MvpEvidenceInspector(String phase,Path output) {
        this.output=output.toAbsolutePath().normalize();
        report.put("formatVersion",1); report.put("phase",phase); report.put("passed",false);
        report.put("startedAt",Instant.now().toString()); report.put("checks",checks);
        environment.forEach((key,value)->{
            if((key.contains("PASSWORD") || key.equals("JWT_SECRET") || key.equals("S3_ACCESS_KEY")
                    || key.equals("S3_SECRET_KEY")) && value!=null && !value.isBlank()) secrets.add(value);
        });
    }

    public static void main(String[] arguments) {
        if(arguments.length<2 || !(arguments[0].equals("preflight") && arguments.length==2
                || arguments[0].equals("inspect") && arguments.length==3)) {
            System.err.println("Usage: MvpEvidenceInspector preflight <output-json> | inspect <run-input-json> <output-json>");
            System.exit(2); return;
        }
        // SDK/provider diagnostics can include request URLs. Only explicit safe CLI output is emitted.
        if(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
        var inspector=new MvpEvidenceInspector(arguments[0],Path.of(arguments[arguments.length-1]));
        try {
            if(arguments[0].equals("preflight")) inspector.preflight();
            else inspector.inspect(Path.of(arguments[1]));
            inspector.report.put("passed",true); inspector.report.put("completedAt",Instant.now().toString());
            inspector.write();
            System.out.println("[PASS] T014 read-only evidence inspector "+arguments[0]+": "+inspector.output);
        } catch(Exception failure) {
            inspector.report.put("passed",false); inspector.report.put("failureType",failure.getClass().getSimpleName());
            if(failure instanceof CheckFailure) inspector.report.put("failedCheck",failure.getMessage());
            try { inspector.write(); } catch(Exception ignored) { System.err.println("Could not write safe inspector report"); }
            System.err.println("[FAIL] T014 read-only evidence inspector ("+failure.getClass().getSimpleName()+")");
            System.exit(1);
        }
    }

    private void preflight() throws Exception {
        try(Connection actual=openDatabase()) {
            connection=actual;
            inspectDatabase(); verifyDemoAccounts(); pendingWork();
            Snapshot snapshot=snapshot();
            report.put("snapshot",snapshot); report.put("snapshotSha256",hash(JSON.writeValueAsBytes(snapshot)));
            report.put("tableCounts",counts(snapshot)); report.put("demoCredentialsSha256",credentialsDigest());
            report.put("scope","Read-only database readiness and complete original-row digests; no business process is started.");
            connection.rollback();
        }
    }

    private void inspect(Path inputPath) throws Exception {
        RunInput input=JSON.readValue(Files.readAllBytes(inputPath),RunInput.class);
        validateInput(input);
        Map<String,Object> preflight=readPreflight(input.preflightReport());
        Snapshot original=JSON.convertValue(preflight.get("snapshot"),Snapshot.class);
        check(input.preflightReport()!=null && !Path.of(input.preflightReport()).toAbsolutePath().normalize().equals(output),
                "original_preflight_is_not_overwritten");
        try(Connection actual=openDatabase()) {
            connection=actual; inspectDatabase(); pendingWork();
            check(Objects.equals(preflight.get("demoCredentialsSha256"),credentialsDigest()),"original_demo_credentials_unchanged");
            Snapshot current=snapshot();
            compareOriginal(original,current);
            boolean recheck=input.recheckPreflightReport()!=null;
            Snapshot appendBaseline=original;
            if(recheck) {
                Map<String,Object> fresh=readPreflight(input.recheckPreflightReport());
                appendBaseline=JSON.convertValue(fresh.get("snapshot"),Snapshot.class);
                compareOriginal(appendBaseline,current);
                check(appendBaseline.tables().get("inspection_task").rows().containsKey(input.taskId())
                        &&appendBaseline.tables().get("evidence_version").rows().containsKey(input.versionId()),
                        "recheck_baseline_contains_retained_task_and_version");
            } else check(!original.tables().get("inspection_task").rows().containsKey(input.taskId()),"demo_task_is_new_to_initial_baseline");
            verifyResources(input);
            Map<String,Set<String>> allowed=allowedAdditions(input,recheck);
            Map<String,Long> additions=new LinkedHashMap<>();
            for(String table:TABLES.keySet()) {
                Set<String> added=new LinkedHashSet<>(current.tables().get(table).rows().keySet());
                added.removeAll(appendBaseline.tables().get(table).rows().keySet());
                check(allowed.getOrDefault(table,Set.of()).containsAll(added),"only_demo_scope_rows_are_appended");
                if(table.equals("verification_record")) check(added.equals(allowed.get(table)),
                        "exactly_three_new_http_verification_results_are_appended");
                additions.put(table,(long)added.size());
            }
            report.put("originalBusinessRowsUnchanged",true); report.put("auditExcludedFromOriginalSnapshot",true);
            report.put("recheck",recheck); report.put("appendedRowsByTable",additions);
            report.put("tableCountsBefore",counts(appendBaseline)); report.put("tableCountsAfter",counts(current));
            report.put("initialSnapshotSha256",preflight.get("snapshotSha256"));
            report.put("currentSnapshotSha256",hash(JSON.writeValueAsBytes(current)));
            report.put("resources",Map.of("taskId",input.taskId(),"requirementId",input.requirementId(),
                    "subtaskId",input.subtaskId(),"executionId",input.executionId(),"uploadId",input.uploadId(),
                    "versionId",input.versionId(),"evidenceId",input.evidenceId(),"submissionId",input.submissionId()));
            report.put("expectedFile",Map.of("sha256",input.expectedSha256(),"size",input.size()));
            verifyMessages(input);
            verifyVerifications(input);
            verifyAudit(input);
            try(S3Client storage=openStorage();HttpClient anonymous=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                verifyObjects(input,storage,anonymous);
            }
            verifyLedger(input);
            connection.rollback();
        }
        report.put("scope","SELECT-only database inspection, private object GET/anonymous access checks and Fabric queries; retained demo data is not changed.");
    }

    private Connection openDatabase() throws Exception {
        String host=value("DB_HOST","127.0.0.1"),database=value("DB_NAME","uav_inspection"),port=value("DB_PORT","3306");
        check(host.matches("[A-Za-z0-9.-]+") && database.matches("[A-Za-z0-9_]+")
                &&port.matches("[0-9]{1,5}"),"database_connection_identifiers_valid");
        Properties properties=new Properties(); properties.setProperty("user",value("DB_USERNAME","uav_app"));
        properties.setProperty("password",required("DB_PASSWORD"));
        properties.setProperty("sslMode",value("DB_SSL_MODE","REQUIRED"));
        properties.setProperty("connectionTimeZone","+00:00"); properties.setProperty("forceConnectionTimeZoneToSession","true");
        properties.setProperty("preserveInstants","true"); properties.setProperty("characterEncoding","UTF-8");
        properties.setProperty("connectTimeout","5000"); properties.setProperty("socketTimeout","15000");
        Connection actual=DriverManager.getConnection("jdbc:mysql://"+host+":"+port+"/"+database,properties);
        actual.setReadOnly(true); actual.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); actual.setAutoCommit(false);
        return actual;
    }

    private void inspectDatabase() throws Exception {
        check(connection.isReadOnly() && !connection.getAutoCommit()
                &&connection.getTransactionIsolation()==Connection.TRANSACTION_REPEATABLE_READ,"consistent_read_only_database_transaction");
        check("MySQL".equals(connection.getMetaData().getDatabaseProductName()),"real_mysql_required");
        Map<String,Object> session=one("SELECT @@session.time_zone AS zone,@@session.transaction_isolation AS isolation_level");
        check(Set.of("+00:00","UTC").contains(text(session,"zone"))
                &&"REPEATABLE-READ".equals(text(session,"isolation_level")),"database_snapshot_and_utc_session_verified");
        List<Map<String,Object>> migrations=query("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        Map<String,Integer> expected=Map.ofEntries(Map.entry("1",-539134757),Map.entry("2",1185045295),
                Map.entry("3",-394507728),Map.entry("4",-1021150431),Map.entry("5",-507899413),
                Map.entry("6",186672578),Map.entry("7",-763051633),Map.entry("8",1053255897),
                Map.entry("9",72853277),Map.entry("10",-573715695),Map.entry("11",1029133993),
                Map.entry("12",1723908793),Map.entry("13",-1202069766),Map.entry("14",-1562459282));
        check(migrations.size()==expected.size(),"all_fourteen_existing_migrations_present");
        for(Map<String,Object> migration:migrations) check(expected.containsKey(text(migration,"version"))
                &&expected.get(text(migration,"version")).longValue()==number(migration,"checksum")
                &&truth(migration.get("success")),"existing_migration_checksums_unchanged");
        report.put("readOnlyDatabaseTransaction",true); report.put("migrationsValidated",expected.size());
        report.put("accessGrantTableRows",count("SELECT COUNT(*) FROM access_grant"));
        report.put("messageRejectionTableRows",count("SELECT COUNT(*) FROM message_rejection"));
        report.put("anchorRetryRequestTableRows",count("SELECT COUNT(*) FROM anchor_retry_request"));
        report.put("storageCleanupTaskTableRows",count("SELECT COUNT(*) FROM storage_cleanup_task"));
        report.put("legacySixteenTableSnapshotUnchangedByAccessGrantTable",true);
        report.put("legacySixteenTableSnapshotUnchangedByMessageRejectionTable",true);
        report.put("legacySixteenTableSnapshotUnchangedByAnchorRetryRequestTable",true);
        report.put("legacySixteenTableSnapshotUnchangedByStorageCleanupTaskTable",true);
        List<Map<String,Object>> tasks=query("""
                SELECT id,status,completed_at,
                    (completed_at>=created_at AND completed_at<=UTC_TIMESTAMP(3)) AS completion_time_valid
                FROM inspection_task ORDER BY id
                """);
        long completed=0;
        for(Map<String,Object> task:tasks) {
            boolean terminal="COMPLETED".equals(text(task,"status"));
            check(terminal ? task.get("completed_at")!=null &&truth(task.get("completion_time_valid"))
                    : task.get("completed_at")==null,"task_status_and_existing_completion_time_are_consistent");
            if(terminal) completed++;
        }
        report.put("taskCompletionColumn",Map.of("presentSinceMigration",1,"includedInOriginalSixteenTableSnapshot",true,
                "legacyColumnProjectionRequired",false,"inspectedTasks",tasks.size(),"completedTasks",completed,
                "statusAndDatabaseTimeConsistent",true));
    }

    private void verifyDemoAccounts() throws Exception {
        Map<String,Object> credentials=object(JSON.readValue(Files.readAllBytes(credentialsPath()),new TypeReference<Map<String,Object>>() {}));
        Map<String,Object> passwords=object(credentials.get("passwords"));
        Map<String,Object> tokens=object(credentials.get("droneTokens"));
        check(number(credentials,"formatVersion")==1 && passwords.size()==6 && tokens.size()==1,"prepared_demo_credential_structure_valid");
        BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
        List<Map<String,Object>> accounts=new ArrayList<>();
        for(Account account:ACCOUNTS) {
            Map<String,Object> row=one("SELECT id,org_id,username,password_hash,roles,enabled FROM app_user WHERE id=?",account.id());
            check(account.username().equals(text(row,"username")) && account.organization().equals(text(row,"org_id"))
                    &&truth(row.get("enabled")),"fixed_demo_account_identity_and_enabled_state");
            Set<String> roles=new LinkedHashSet<>(Arrays.asList(JSON.readValue(text(row,"roles"),String[].class)));
            check(roles.equals(account.roles()),"fixed_demo_account_roles_ready");
            String password=(String)passwords.get(account.username());
            check(password!=null && encoder.matches(password,text(row,"password_hash")),"original_demo_password_still_matches");
            secrets.add(password);
            accounts.add(Map.of("id",account.id(),"username",account.username(),"organizationId",account.organization(),"roles",roles.stream().sorted().toList()));
        }
        String token=(String)tokens.get("UAV-DEMO-001");
        Map<String,Object> drone=one("SELECT id,operator_org_id,code,token_hash,enabled FROM drone WHERE id=?",DRONE);
        check(token!=null && "UAV-DEMO-001".equals(text(drone,"code")) && OPERATOR.equals(text(drone,"operator_org_id"))
                &&truth(drone.get("enabled")) && hash(token.getBytes(StandardCharsets.UTF_8)).equals(text(drone,"token_hash")),
                "fixed_demo_drone_and_original_token_ready");
        secrets.add(token); report.put("demoAccounts",accounts); report.put("demoDrone",Map.of("id",DRONE,"code","UAV-DEMO-001"));
    }

    private void pendingWork() throws Exception {
        Map<String,Long> pending=new LinkedHashMap<>();
        pending.put("outbox",count("SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','SENDING')"));
        report.put("failedOutboxTableRows",count("SELECT COUNT(*) FROM outbox_event WHERE status='FAILED'"));
        pending.put("upload",count("SELECT COUNT(*) FROM upload_session WHERE status IN ('UPLOADING','VERIFYING')"));
        pending.put("anchor",count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=TRUE)"));
        pending.put("consumer",count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'"));
        check(pending.values().stream().allMatch(value->value==0),"no_unfinished_database_work");
        report.put("pendingWork",pending);
    }

    private void verifyResources(RunInput input) throws Exception {
        Map<String,Object> task=one("SELECT * FROM inspection_task WHERE id=?",input.taskId());
        check(OWNER.equals(text(task,"owner_org_id")) && OPERATOR.equals(text(task,"operator_org_id"))
                &&ACCOUNTS.getFirst().id().equals(text(task,"created_by")) && "RUNNING".equals(text(task,"status")),"demo_task_has_expected_institutions_creator_and_running_state");
        for(int index:new int[]{0,3,4}) check(count("SELECT COUNT(*) FROM task_member WHERE task_id=? AND user_id=?",
                input.taskId(),ACCOUNTS.get(index).id())==1,"demo_task_keeps_owner_operator_admin_and_uploader_members");
        Map<String,Object> requirement=one("SELECT * FROM material_requirement WHERE id=?",input.requirementId());
        check(input.taskId().equals(text(requirement,"task_id")) &&input.subtaskId().equals(text(requirement,"subtask_id"))
                &&input.evidenceId().equals(text(requirement,"evidence_id")) &&number(requirement,"next_version_no")==2,"one_material_item_allocated_exactly_one_fixed_version");
        Map<String,Object> subtask=one("SELECT * FROM inspection_subtask WHERE id=?",input.subtaskId());
        Map<String,Object> execution=one("SELECT * FROM task_execution WHERE id=?",input.executionId());
        check(input.taskId().equals(text(subtask,"task_id")) && input.executionId().equals(text(subtask,"current_execution_id"))
                &&input.subtaskId().equals(text(execution,"subtask_id")) &&DRONE.equals(text(execution,"drone_id"))
                &&"RUNNING".equals(text(execution,"status")),"started_subtask_preserves_original_execution_and_demo_drone");
        Map<String,Object> upload=one("SELECT * FROM upload_session WHERE id=?",input.uploadId());
        check(input.taskId().equals(text(upload,"task_id")) &&input.requirementId().equals(text(upload,"requirement_id"))
                &&input.executionId().equals(text(upload,"execution_id")) &&input.subtaskId().equals(text(upload,"subtask_id"))
                &&input.evidenceId().equals(text(upload,"evidence_id")) &&"USER".equals(text(upload,"actor_type"))
                &&ACCOUNTS.get(4).id().equals(text(upload,"actor_id")) &&"COMPLETED".equals(text(upload,"status"))
                &&number(upload,"expected_size")==input.size() &&number(upload,"part_count")==1
                &&number(upload,"part_size")>=input.size() &&upload.get("lease_token")==null &&upload.get("lease_until")==null,
                "single_part_upload_completed_with_original_scope_and_uploader");
        Map<String,Object> version=one("SELECT * FROM evidence_version WHERE id=?",input.versionId());
        check(input.taskId().equals(text(version,"task_id")) &&input.requirementId().equals(text(version,"requirement_id"))
                &&input.executionId().equals(text(version,"execution_id")) &&input.uploadId().equals(text(version,"upload_session_id"))
                &&input.evidenceId().equals(text(version,"evidence_id")) &&number(version,"version_no")==1
                &&number(version,"size")==input.size() &&input.expectedSha256().equals(text(version,"sha256"))
                &&"PENDING".equals(text(version,"review_status")) &&ACCOUNTS.get(4).id().equals(text(version,"uploader_user_id"))
                &&version.get("uploader_drone_id")==null &&OPERATOR.equals(text(version,"submitter_org_id")),
                "immutable_version_metadata_matches_original_upload_and_server_digest");
        for(String[] item:List.of(new String[]{"inspection_subtask","task_id"},new String[]{"material_requirement","task_id"},
                new String[]{"evidence_version","task_id"},new String[]{"upload_session","task_id"}))
            check(count("SELECT COUNT(*) FROM "+item[0]+" WHERE "+item[1]+"=?",input.taskId())==1,"mvp_has_only_one_subtask_requirement_upload_and_version");
        check(count("SELECT COUNT(*) FROM task_execution WHERE subtask_id=?",input.subtaskId())==1
                &&count("SELECT COUNT(*) FROM upload_part WHERE session_id=?",input.uploadId())==1,"mvp_has_only_original_execution_and_single_part");
    }

    private void verifyMessages(RunInput input) throws Exception {
        List<Map<String,Object>> events=new ArrayList<>();
        for(String[] event:List.of(new String[]{"FILE_FINALIZE_REQUESTED",input.uploadId(),"FILE_FINALIZE"},
                new String[]{"EVIDENCE_ANCHOR_REQUESTED",input.submissionId(),"EVIDENCE_ANCHOR"})) {
            Map<String,Object> row=one("SELECT * FROM outbox_event WHERE event_type=? AND biz_id=?",event[0],event[1]);
            String id=text(row,"event_id");
            check("SENT".equals(text(row,"status")) &&number(row,"dispatch_no")==0 &&row.get("sent_at")!=null
                    &&row.get("lease_token")==null &&row.get("lease_until")==null &&row.get("last_error")==null,
                    "both_durable_outbox_events_are_confirmed_sent_without_active_lease");
            Map<String,Object> payload=object(JSON.readValue(text(row,"payload"),new TypeReference<Map<String,Object>>() {}));
            check(payload.keySet().equals(Set.of("eventId","eventType","bizId","traceId")) &&id.equals(payload.get("eventId"))
                    &&event[0].equals(payload.get("eventType")) &&event[1].equals(payload.get("bizId"))
                    &&payload.get("traceId") instanceof String trace &&trace.matches("[A-Za-z0-9_-]{1,64}"),"message_envelope_contains_only_safe_business_identifiers");
            Map<String,Object> consumption=one("SELECT status FROM consumed_event WHERE consumer_code=? AND event_id=?",event[2],id);
            check("DONE".equals(text(consumption,"status")),"both_message_consumers_have_durable_done_checkpoints");
            events.add(Map.of("eventId",id,"eventType",event[0],"businessId",event[1],"status","SENT","consumerStatus","DONE"));
        }
        report.put("messages",events);
    }

    private void verifyVerifications(RunInput input) throws Exception {
        List<Map<String,Object>> records=new ArrayList<>();
        for(String[] expected:List.of(new String[]{input.verificationIds().storedMatch(),"STORED","MATCH",OWNER},
                new String[]{input.verificationIds().copyMatch(),"COPY","MATCH",OPERATOR},
                new String[]{input.verificationIds().copyMismatch(),"COPY","MISMATCH",OPERATOR})) {
            Map<String,Object> row=one("SELECT v.*,u.org_id FROM verification_record v JOIN app_user u ON u.id=v.user_id WHERE v.id=?",expected[0]);
            String expectedHash="MISMATCH".equals(expected[2])?input.changedSha256():input.expectedSha256();
            check(input.versionId().equals(text(row,"version_id")) &&expected[1].equals(text(row,"source"))
                    &&expected[2].equals(text(row,"result")) &&expected[3].equals(text(row,"org_id"))
                    &&expectedHash.equals(text(row,"actual_sha256")) &&input.expectedSha256().equals(text(row,"ledger_sha256"))
                    &&number(row,"actual_size")==input.size() &&row.get("verified_at")!=null,"stored_and_two_copy_verifications_match_actual_input_digests");
            String user="STORED".equals(expected[1])?ACCOUNTS.getFirst().id():ACCOUNTS.get(4).id();
            check(user.equals(text(row,"user_id")),"verification_results_keep_actual_requesting_demo_user");
            String transaction=text(one("SELECT confirmed_tx_id FROM chain_submission WHERE id=?",input.submissionId()),"confirmed_tx_id");
            check(transaction.equals(text(row,"ledger_tx_id")),"verification_ledger_transaction_matches_confirmed_original");
            records.add(Map.of("id",expected[0],"source",expected[1],"result",expected[2],"userId",user,
                    "actualSha256",expectedHash,"ledgerSha256",input.expectedSha256(),"size",input.size(),"ledgerTransactionId",transaction));
        }
        report.put("verifications",records);
        report.put("verificationHistoryCount",count("SELECT COUNT(*) FROM verification_record WHERE version_id=?",input.versionId()));
    }

    private void verifyAudit(RunInput input) throws Exception {
        List<Map<String,Object>> rows=query("""
                SELECT actor_type,actor_id,org_id,action,resource_type,resource_id,trace_id,result FROM audit_log
                WHERE resource_id IN (?,?,?,?,?,?)
                """,input.taskId(),input.executionId(),input.uploadId(),input.versionId(),input.submissionId(),input.subtaskId());
        Map<String,Long> actions=new LinkedHashMap<>();
        for(Map<String,Object> row:rows) {
            check("SUCCESS".equals(text(row,"result")) &&text(row,"trace_id").matches("[A-Za-z0-9_-]{1,64}"),"retained_resource_audit_has_safe_trace_and_success_metadata");
            assertSafe(JSON.writeValueAsString(row));
            actions.merge(text(row,"action"),1L,Long::sum);
        }
        check(actions.keySet().containsAll(Set.of("TASK_CREATED","TASK_ASSIGNED","SUBTASK_CREATED","EXECUTION_CREATED","TASK_STARTED",
                "UPLOAD_SESSION_CREATED","UPLOAD_PART_STORED","UPLOAD_COMPLETION_REQUESTED","UPLOAD_FINALIZED",
                "EVIDENCE_ANCHOR_CONFIRMED","FILE_VERIFICATION_COMPLETED")),"retained_audit_covers_actual_task_upload_anchor_and_verification_operations");
        report.put("auditActionCounts",actions); report.put("auditContainsNoCredentialsOrObjectKeys",true);
    }

    private void verifyObjects(RunInput input,S3Client storage,HttpClient anonymous) throws Exception {
        Map<String,Object> part=one("SELECT * FROM upload_part WHERE session_id=?",input.uploadId());
        Map<String,Object> fixed=one("SELECT object_key FROM evidence_version WHERE id=?",input.versionId());
        String partKey=text(part,"object_key"),finalKey=text(fixed,"object_key");
        check(partKey.matches("upload-parts/"+input.uploadId()+"/1/"+UUID)
                &&finalKey.matches("evidence-files/"+input.uploadId()+"/"+UUID),"private_objects_use_the_current_upload_server_owned_namespace");
        check(number(part,"part_no")==1 &&number(part,"size")==input.size()
                &&input.expectedSha256().equals(text(part,"sha256")),"registered_single_part_has_expected_size_and_digest");
        List<Map<String,Object>> objects=new ArrayList<>();
        for(String[] object:List.of(new String[]{"part",partKey},new String[]{"fixedVersion",finalKey})) {
            Digest actual;
            try(var stream=storage.getObject(request->request.bucket(value("S3_BUCKET","uav-evidence")).key(object[1]))) {
                check(stream.response().contentLength()==input.size(),"private_object_response_has_expected_length");
                actual=digest(stream,input.size());
            }
            check(actual.size()==input.size() &&actual.sha256().equals(input.expectedSha256()),"actual_private_object_bytes_match_original_file");
            URI uri=anonymousObjectUri(object[1]);
            int head=anonymous.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            int get=anonymous.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
            check(head==403 &&get==403,"private_part_and_fixed_object_reject_anonymous_head_and_get");
            objects.add(Map.of("kind",object[0],"size",actual.size(),"sha256",actual.sha256(),"anonymousHeadStatus",head,"anonymousGetStatus",get));
        }
        report.put("privateObjects",objects);
    }

    private void verifyLedger(RunInput input) throws Exception {
        Map<String,Object> submission=one("SELECT * FROM chain_submission WHERE id=?",input.submissionId());
        check(input.versionId().equals(text(submission,"version_id")) &&"CONFIRMED".equals(text(submission,"status"))
                &&"Org2MSP".equals(text(submission,"confirmed_by")) &&!truth(submission.get("retryable"))
                &&submission.get("next_action_at")==null &&submission.get("lease_token")==null
                &&submission.get("lease_until")==null &&submission.get("last_error")==null,"business_submission_is_actually_confirmed_without_pending_recovery");
        Map<String,Object> attempt=one("SELECT * FROM chain_attempt WHERE submission_id=?",input.submissionId());
        String tx=text(attempt,"tx_id"); byte[] transaction=bytes(attempt,"transaction_bytes"),commit=bytes(attempt,"commit_bytes");
        check(tx.equals(text(submission,"confirmed_tx_id")) &&number(attempt,"attempt_no")==1
                &&"VALID".equals(text(attempt,"phase")) &&number(attempt,"validation_code")==0
                &&number(attempt,"block_number")>=0,"one_original_transaction_has_persisted_valid_commit_outcome");
        FabricGatewaySettings ownerSettings=FabricGatewaySettings.fromEnvironment(environment,"ORGA");
        FabricGatewaySettings operatorSettings=FabricGatewaySettings.fromEnvironment(environment,"ORGB");
        X509Identity identity;
        try(var certificate=Files.newBufferedReader(operatorSettings.certificate(),StandardCharsets.UTF_8)) {
            identity=new X509Identity(operatorSettings.mspId(),Identities.readX509Certificate(certificate));
        }
        var validator=new CheckpointValidator(identity.getMspId(),identity.getCredentials(),operatorSettings.channel(),operatorSettings.chaincode());
        validator.transaction(new PreparedRegistration(tx,transaction)); validator.commit(new CommitCheckpoint(tx,commit));
        check(true,"original_gateway_checkpoints_match_identity_channel_chaincode_and_transaction");
        var prepared=PreparedTransaction.parseFrom(transaction);
        var payload=Payload.parseFrom(prepared.getEnvelope().getPayload());
        var actions=Transaction.parseFrom(payload.getData());
        check(actions.getActionsCount()==1,"prepared_transaction_contains_one_chaincode_action");
        var action=ChaincodeActionPayload.parseFrom(actions.getActions(0).getPayload());
        var proposal=ChaincodeProposalPayload.parseFrom(action.getChaincodeProposalPayload());
        var invocation=ChaincodeInvocationSpec.parseFrom(proposal.getInput());
        var arguments=invocation.getChaincodeSpec().getInput().getArgsList().stream().map(value->value.toStringUtf8()).toList();
        check(arguments.equals(List.of(operatorSettings.contract()+":RegisterEvidence",input.evidenceId(),"1",input.expectedSha256())),
                "prepared_original_transaction_registers_this_exact_evidence_version_and_hash");
        Set<String> endorsers=new LinkedHashSet<>();
        for(var endorsement:action.getAction().getEndorsementsList()) endorsers.add(SerializedIdentity.parseFrom(endorsement.getEndorser()).getMspid());
        check(endorsers.containsAll(Set.of("Org1MSP","Org2MSP")),"prepared_transaction_contains_both_institution_endorsements");
        try(var owner=GatewayEvidenceLedgerClient.connect(ownerSettings);var operator=GatewayEvidenceLedgerClient.connect(operatorSettings)) {
            var outcome=operator.queryCommit(new CommitCheckpoint(tx,commit));
            check(outcome.valid() &&outcome.validationCode()==0 &&tx.equals(outcome.transactionId())
                    &&outcome.blockNumber()==number(attempt,"block_number"),"original_commit_checkpoint_requeries_same_actual_valid_fabric_result");
            var a=owner.queryEvidence(input.evidenceId(),1); var b=operator.queryEvidence(input.evidenceId(),1);
            EvidenceRecord expected=new EvidenceRecord(input.evidenceId(),1,input.expectedSha256(),"Org2MSP",tx);
            check(a.found() &&b.found() &&expected.equals(a.record()) &&expected.equals(b.record()),"both_institutions_query_the_same_five_ledger_fields_and_original_transaction");
            report.put("ledgerByInstitution",Map.of("OrgA",a.record(),"OrgB",b.record()));
        }
        report.put("originalTransaction",Map.of("transactionId",tx,"phase","VALID","validationCode",0,
                "blockNumber",number(attempt,"block_number"),"transactionCheckpointLength",transaction.length,
                "transactionCheckpointSha256",hash(transaction),"commitCheckpointLength",commit.length,
                "commitCheckpointSha256",hash(commit),"endorserMsps",endorsers.stream().sorted().toList()));
    }

    private Map<String,Set<String>> allowedAdditions(RunInput input,boolean recheck) throws Exception {
        Map<String,Set<String>> allowed=new LinkedHashMap<>();
        allowed.put("verification_record",Set.of(input.verificationIds().storedMatch(),input.verificationIds().copyMatch(),input.verificationIds().copyMismatch()));
        if(recheck) return allowed;
        allowed.put("inspection_task",Set.of(input.taskId()));
        allowed.put("inspection_subtask",Set.of(input.subtaskId())); allowed.put("material_requirement",Set.of(input.requirementId()));
        allowed.put("task_execution",Set.of(input.executionId())); allowed.put("upload_session",Set.of(input.uploadId()));
        allowed.put("upload_part",Set.of(input.uploadId()+"|1")); allowed.put("evidence_version",Set.of(input.versionId()));
        allowed.put("chain_submission",Set.of(input.submissionId()));
        allowed.put("chain_attempt",ids("SELECT id FROM chain_attempt WHERE submission_id=?",input.submissionId()));
        Set<String> members=new LinkedHashSet<>();
        for(Map<String,Object> row:query("SELECT user_id FROM task_member WHERE task_id=?",input.taskId())) members.add(input.taskId()+"|"+text(row,"user_id"));
        allowed.put("task_member",members);
        Set<String> events=ids("SELECT event_id FROM outbox_event WHERE (event_type='FILE_FINALIZE_REQUESTED' AND biz_id=?) OR (event_type='EVIDENCE_ANCHOR_REQUESTED' AND biz_id=?)",input.uploadId(),input.submissionId());
        allowed.put("outbox_event",events);
        Set<String> consumed=new LinkedHashSet<>();
        for(Map<String,Object> row:query("SELECT consumer_code,event_id FROM consumed_event"))
            if(events.contains(text(row,"event_id"))) consumed.add(text(row,"consumer_code")+"|"+text(row,"event_id"));
        allowed.put("consumed_event",consumed); return allowed;
    }

    private Snapshot snapshot() throws Exception {
        Map<String,TableSnapshot> tables=new LinkedHashMap<>();
        for(var table:TABLES.entrySet()) {
            String sql="SELECT * FROM "+table.getKey()+" ORDER BY "+String.join(",",table.getValue());
            try(var statement=select(sql);ResultSet rows=statement.executeQuery()) {
                ResultSetMetaData metadata=rows.getMetaData(); MessageDigest schema=sha();
                for(int index=1;index<=metadata.getColumnCount();index++) {
                    frame(schema,metadata.getColumnLabel(index)); frame(schema,Integer.toString(metadata.getColumnType(index)));
                }
                String schemaHash=finish(schema); Map<String,String> digests=new LinkedHashMap<>();
                while(rows.next()) {
                    MessageDigest digest=sha(); frame(digest,DOMAIN); frame(digest,table.getKey()); frame(digest,schemaHash);
                    List<String> primary=new ArrayList<>();
                    for(String key:table.getValue()) primary.add(rows.getString(key));
                    String key=String.join("|",primary);
                    for(int index=1;index<=metadata.getColumnCount();index++) {
                        int type=metadata.getColumnType(index);
                        if(Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB).contains(type)) {
                            try(InputStream binary=rows.getBinaryStream(index)) {
                                if(binary==null) frame(digest,null);
                                else { Digest content=digest(binary,16777215L); frame(digest,"binary"); frame(digest,Long.toString(content.size())); frame(digest,content.sha256()); }
                            }
                        } else frame(digest,rows.getString(index));
                    }
                    check(digests.put(key,finish(digest))==null,"snapshot_primary_keys_are_unique");
                }
                tables.put(table.getKey(),new TableSnapshot(schemaHash,digests));
            }
        }
        return new Snapshot(DOMAIN,tables);
    }

    private void compareOriginal(Snapshot baseline,Snapshot current) {
        check(DOMAIN.equals(baseline.domain()) && baseline.tables().keySet().equals(TABLES.keySet()),"complete_sixteen_table_baseline_required");
        for(String table:TABLES.keySet()) {
            TableSnapshot before=baseline.tables().get(table),after=current.tables().get(table);
            check(before.schemaSha256().equals(after.schemaSha256()) &&after.rows().entrySet().containsAll(before.rows().entrySet()),
                    "every_original_business_row_and_column_remains_unchanged");
        }
    }

    private Map<String,Object> readPreflight(String path) throws Exception {
        check(path!=null && !path.isBlank(),"preflight_report_required");
        Map<String,Object> baseline=object(JSON.readValue(Files.readAllBytes(Path.of(path)),new TypeReference<Map<String,Object>>() {}));
        check(Boolean.TRUE.equals(baseline.get("passed")) &&"preflight".equals(baseline.get("phase"))
                &&number(baseline,"formatVersion")==1,"passing_preflight_report_required");
        Snapshot snapshot=JSON.convertValue(baseline.get("snapshot"),Snapshot.class);
        check(hash(JSON.writeValueAsBytes(snapshot)).equals(baseline.get("snapshotSha256")),"preflight_snapshot_digest_is_consistent");
        return baseline;
    }

    private S3Client openStorage() {
        return S3Client.builder().endpointOverride(storageEndpoint()).region(Region.of(value("S3_REGION","garage")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(required("S3_ACCESS_KEY"),required("S3_SECRET_KEY"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(Boolean.parseBoolean(value("S3_FORCE_PATH_STYLE","true"))).chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(15)))
                .overrideConfiguration(config->config.apiCallTimeout(Duration.ofSeconds(25)).apiCallAttemptTimeout(Duration.ofSeconds(20))).build();
    }
    private URI storageEndpoint() {
        URI endpoint=URI.create(required("S3_ENDPOINT"));
        check(Set.of("http","https").contains(endpoint.getScheme()) &&endpoint.getHost()!=null &&endpoint.getUserInfo()==null
                &&endpoint.getRawQuery()==null &&endpoint.getRawFragment()==null
                &&(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")),"storage_endpoint_has_no_embedded_credentials");
        return endpoint;
    }
    private URI anonymousObjectUri(String key) throws Exception {
        URI endpoint=storageEndpoint(); String bucket=value("S3_BUCKET","uav-evidence");
        check(bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"),"storage_bucket_name_valid");
        boolean pathStyle=Boolean.parseBoolean(value("S3_FORCE_PATH_STYLE","true"));
        return new URI(endpoint.getScheme(),null,pathStyle?endpoint.getHost():bucket+"."+endpoint.getHost(),endpoint.getPort(),
                (pathStyle?"/"+bucket:"")+"/"+key,null,null);
    }

    private java.sql.PreparedStatement select(String sql,Object... parameters) throws Exception {
        check(sql.stripLeading().startsWith("SELECT "),"inspector_issues_select_statements_only");
        var statement=connection.prepareStatement(sql); statement.setQueryTimeout(15);
        for(int index=0;index<parameters.length;index++) statement.setObject(index+1,parameters[index]);
        return statement;
    }
    private List<Map<String,Object>> query(String sql,Object... parameters) throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();
        try(var statement=select(sql,parameters);ResultSet rows=statement.executeQuery()) {
            while(rows.next()) {
                Map<String,Object> row=new LinkedHashMap<>();
                for(int index=1;index<=rows.getMetaData().getColumnCount();index++) row.put(rows.getMetaData().getColumnLabel(index),rows.getObject(index));
                result.add(row);
            }
        }
        return result;
    }
    private Map<String,Object> one(String sql,Object... parameters) throws Exception {
        List<Map<String,Object>> rows=query(sql,parameters); check(rows.size()==1,"exactly_one_expected_database_row_required"); return rows.getFirst();
    }
    private long count(String sql,Object... parameters) throws Exception { return ((Number)one(sql,parameters).values().iterator().next()).longValue(); }
    private Set<String> ids(String sql,Object... parameters) throws Exception {
        Set<String> ids=new LinkedHashSet<>(); for(var row:query(sql,parameters)) ids.add((String)row.values().iterator().next()); return ids;
    }
    private static Map<String,Long> counts(Snapshot snapshot) {
        Map<String,Long> counts=new LinkedHashMap<>(); snapshot.tables().forEach((table,value)->counts.put(table,(long)value.rows().size())); return counts;
    }
    private static Digest digest(InputStream input,long maximum) throws Exception {
        MessageDigest hash=sha(); long size=0; byte[] buffer=new byte[65536]; int count;
        while((count=input.read(buffer))!=-1) {
            if(count==0) continue; size=Math.addExact(size,count);
            if(size>maximum) throw new CheckFailure("read_bytes_exceed_expected_bound"); hash.update(buffer,0,count);
        }
        return new Digest(size,finish(hash));
    }
    private Path credentialsPath() { return Path.of(value("DEMO_CREDENTIALS_PATH",".local/demo-credentials.json")).toAbsolutePath().normalize(); }
    private String credentialsDigest() throws Exception { return hash(Files.readAllBytes(credentialsPath())); }
    private String required(String name) { String value=environment.get(name); check(value!=null&&!value.isBlank(),"required_environment_setting_present"); return value; }
    private String value(String name,String fallback) { String value=environment.get(name); return value==null||value.isBlank()?fallback:value; }
    private static String text(Map<String,Object> map,String name) { if(!(map.get(name) instanceof String value)) throw new CheckFailure("required_text_field_missing"); return value; }
    private static long number(Map<String,Object> map,String name) { if(!(map.get(name) instanceof Number value)) throw new CheckFailure("required_numeric_field_missing"); return value.longValue(); }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) ||value instanceof Number number&&number.intValue()==1; }
    private static byte[] bytes(Map<String,Object> map,String name) { if(!(map.get(name) instanceof byte[] value)||value.length==0) throw new CheckFailure("required_binary_checkpoint_missing"); return value; }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { if(!(value instanceof Map<?,?>)) throw new CheckFailure("json_object_required"); return (Map<String,Object>)value; }
    private static MessageDigest sha() { try { return MessageDigest.getInstance("SHA-256"); } catch(Exception impossible) { throw new IllegalStateException("SHA-256 unavailable"); } }
    private static String finish(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }
    private static String hash(byte[] bytes) { MessageDigest digest=sha(); digest.update(bytes); return finish(digest); }
    private static void frame(MessageDigest digest,String value) {
        if(value==null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return; }
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    private void check(boolean condition,String name) { if(!condition) throw new CheckFailure(name); if(!checks.contains(name)) checks.add(name); }
    private void validateInput(RunInput input) {
        check(input!=null && input.formatVersion()==1,"run_input_format_valid");
        for(String id:new String[]{input.taskId(),input.requirementId(),input.subtaskId(),input.executionId(),input.uploadId(),input.versionId(),input.evidenceId(),input.submissionId()})
            check(id!=null &&id.matches(UUID),"run_resource_identifiers_valid");
        check(input.expectedSha256()!=null &&input.expectedSha256().matches("[0-9a-f]{64}")
                &&input.changedSha256()!=null &&input.changedSha256().matches("[0-9a-f]{64}")
                &&!input.expectedSha256().equals(input.changedSha256()) &&input.size()>0 &&input.size()<=209715200,"run_file_metadata_valid");
        check(input.verificationIds()!=null,"run_verification_identifiers_present");
        for(String id:new String[]{input.verificationIds().storedMatch(),input.verificationIds().copyMatch(),input.verificationIds().copyMismatch()})
            check(id!=null &&id.matches(UUID),"run_verification_identifiers_valid");
    }
    private void assertSafe(String value) {
        check(secrets.stream().noneMatch(secret->!secret.isEmpty()&&value.contains(secret))
                &&!value.contains("upload-parts/") &&!value.contains("evidence-files/")
                &&!value.contains("-----BEGIN") &&!value.contains("transaction_bytes") &&!value.contains("commit_bytes"),"report_contains_only_safe_metadata_and_hashes");
    }
    private void write() throws Exception {
        Files.createDirectories(output.getParent()); String json=JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        assertSafe(json);
        // assertSafe itself may append its successful check; serialize once again to retain it.
        byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        Path temporary=Files.createTempFile(output.getParent(),".mvp-inspector-",".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try { Files.write(temporary,bytes); Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }

    public record RunInput(int formatVersion,String taskId,String requirementId,String subtaskId,String executionId,String uploadId,
                           String versionId,String evidenceId,String submissionId,String expectedSha256,String changedSha256,long size,
                           VerificationIds verificationIds,String preflightReport,String recheckPreflightReport) {}
    public record VerificationIds(String storedMatch,String copyMatch,String copyMismatch) {}
    public record Snapshot(String domain,Map<String,TableSnapshot> tables) {}
    public record TableSnapshot(String schemaSha256,Map<String,String> rows) {}
    private record Digest(long size,String sha256) {}
    private record Account(int number,String organization,String username,Set<String> roles) {
        String id() { return "10000000-0000-4000-8000-%012d".formatted(number); }
    }
    private static final class CheckFailure extends RuntimeException { CheckFailure(String name) { super(name); } }
}
