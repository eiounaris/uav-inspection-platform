package com.uav.inspection.blockchain;

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
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import com.uav.inspection.messaging.OutboxPublisher;
import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import com.uav.inspection.blockchain.anchor.AnchorProcessor;
import org.hyperledger.fabric.client.CommitStatusException;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.SubmitException;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.X509Identity;
import io.grpc.Status;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
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

/** Explicit real HTTP/MySQL/Garage/RabbitMQ/Fabric probe. Never runs as an ordinary unit test. */
public final class VerificationFlowProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {};
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private static final String API = "/api/v1";
    private static final Set<String> UPLOAD_ACTIONS = Set.of("UPLOAD_SESSION_CREATED", "UPLOAD_PART_STORED",
            "UPLOAD_COMPLETION_REQUESTED", "UPLOAD_FINALIZE_REQUESTED");
    private static final Map<String, String> TABLE_ORDER = new LinkedHashMap<>();
    static {
        TABLE_ORDER.put("organization", "id");
        TABLE_ORDER.put("app_user", "id");
        TABLE_ORDER.put("inspection_task", "id");
        TABLE_ORDER.put("task_member", "task_id,user_id");
        TABLE_ORDER.put("drone", "id");
        TABLE_ORDER.put("inspection_subtask", "id");
        TABLE_ORDER.put("material_requirement", "id");
        TABLE_ORDER.put("task_execution", "id");
        TABLE_ORDER.put("upload_session", "id");
        TABLE_ORDER.put("upload_part", "session_id,part_no");
        TABLE_ORDER.put("evidence_version", "id");
        TABLE_ORDER.put("outbox_event", "event_id");
        TABLE_ORDER.put("consumed_event", "consumer_code,event_id");
        TABLE_ORDER.put("chain_submission", "id");
        TABLE_ORDER.put("chain_attempt", "id");
        TABLE_ORDER.put("verification_record", "id");
    }
    private final Path reportPath;
    private final String runId = UUID.randomUUID().toString();
    private final String exchange = "inspection.probe." + runId;
    private final String finalizeQueue = exchange + ".finalize";
    private final String anchorQueue = exchange + ".anchor";
    private final String deadExchange = exchange + ".dead.events";
    private final String deadQueue = exchange + ".dead";
    private final Map<String,Object> report = new LinkedHashMap<>();
    private final List<Map<String,Object>> checks = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String,Object>> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Account> accounts = new ArrayList<>();
    private final List<String> secrets = new ArrayList<>();
    private final Set<String> uploadIds = new LinkedHashSet<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final SecureRandom random = new SecureRandom();
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private ObjectStorage storage;
    private FaultClients faults;
    private FaultStorage faultStorage;
    private SimpleMessageListenerContainer anchorContainer;
    private final Set<String> verificationIds=new LinkedHashSet<>();
    private InstitutionLedgerClients realLedgers;
    private final List<Map<String,Object>> ledgerRecords=new ArrayList<>();
    private final List<Map<String,Object>> ledgerIntents=new ArrayList<>();
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
    private OutboxPublisher publisher;
    private RabbitTemplate rabbit;
    private RabbitAdmin rabbitAdmin;
    private List<SimpleMessageListenerContainer> containers;

    private VerificationFlowProbe(Path reportPath) {
        this.reportPath = reportPath.toAbsolutePath().normalize();
        report.put("formatVersion",1);
        report.put("runId",runId);
        report.put("startedAt",Instant.now().toString());
        report.put("passed",false);
        report.put("checks",checks);
        report.put("requests",requests);
        report.put("ledgerRecordsRetained",ledgerRecords);
        report.put("ledgerIntents",ledgerIntents);
    }
    public static void main(String[] args) {
        if(args.length!=1) { System.err.println("Usage: VerificationFlowProbe <reportPath>"); System.exit(2); return; }
        var probe=new VerificationFlowProbe(Path.of(args[0]));
        try {
            probe.execute(); probe.writeReport();
            System.out.println("[PASS] T013 real HTTP/MySQL/Garage/RabbitMQ/Fabric pipeline probe: "+probe.reportPath);
        } catch(Exception failure) {
            probe.report.put("passed",false);
            probe.report.put("failureType",failure.getClass().getSimpleName());
            if(failure instanceof ProbeFailure) probe.report.put("failedCheck",failure.getMessage());
            try { probe.writeReport(); } catch(Exception ignored) { System.err.println("Could not write safe pipeline report"); }
            System.err.println("[FAIL] T013 pipeline probe ("+failure.getClass().getSimpleName()+"); inspect safe report metadata.");
            System.exit(1);
        }
    }
    private void execute() throws Exception {
        report.put("stage","application_startup");
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,FaultInjection.class)
                .web(WebApplicationType.SERVLET).profiles("local","database","storage","messaging","ledger","verification-probe")
                .logStartupInfo(false).run("--server.port=0", "--logging.level.com.uav.inspection=INFO",
                        "--inspection.messaging.exchange="+exchange,
                        "--inspection.messaging.finalize-queue="+finalizeQueue,
                        "--inspection.messaging.anchor-queue="+anchorQueue,
                        "--inspection.messaging.dead-letter-exchange="+deadExchange,
                        "--inspection.messaging.dead-letter-queue="+deadQueue,
                        "--inspection.messaging.publisher-enabled=false",
                        "--inspection.messaging.recovery-enabled=false", "--inspection.ledger.recovery-enabled=false",
                        "--inspection.verification.max-file-size=1024")) {
            jdbc=context.getBean(JdbcTemplate.class); dataSource=context.getBean(DataSource.class);
            storage=context.getBean(ObjectStorage.class); faults=context.getBean(FaultClients.class);
            faultStorage=context.getBean(FaultStorage.class);
            anchorContainer=context.getBean("anchorContainer",SimpleMessageListenerContainer.class);
            realLedgers=context.getBean("institutionLedgerClients",InstitutionLedgerClients.class);
            context.getBean(AnchorProcessor.class);
            s3=context.getBean(S3Client.class); rabbit=context.getBean(RabbitTemplate.class);
            rabbitAdmin=new RabbitAdmin(rabbit.getConnectionFactory());
            containers=List.copyOf(context.getBeansOfType(SimpleMessageListenerContainer.class).values());
            publisher=context.getBean(OutboxPublisher.class); context.getBean(FinalizationProcessor.class);
            storageEndpoint=context.getEnvironment().getRequiredProperty("inspection.storage.endpoint");
            bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");
            baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            try(Connection connection=dataSource.getConnection()) {
                require("MySQL".equals(connection.getMetaData().getDatabaseProductName()),"real_mysql_required");
            }
            require(count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT'")==0
                    &&count("SELECT COUNT(*) FROM upload_session WHERE status='VERIFYING'")==0
                    &&count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN')")==0,
                    "no_preexisting_pending_work_before_isolated_pipeline_probe");
            byte[] baseline=businessSnapshot(); report.put("businessCountsBefore",businessCounts());
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                client=actual;
                try {
                    report.put("stage","isolated_fixture_setup"); createFixtures(); createRunningTask();
                    report.put("stage","real_pipeline_checks"); verifyVerifications(); verifyAuditSafety();
                } finally {
                    report.put("cleanupAttempted",true); containers.forEach(SimpleMessageListenerContainer::stop); cleanupFixtures(); cleanupTopology();
                    require(MessageDigest.isEqual(baseline,businessSnapshot()),"original_sixteen_business_tables_unchanged");
                    report.put("originalBusinessDataUnchanged",true); report.put("businessCountsAfter",businessCounts());
                }
            }
        }
        report.put("stage","complete");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }

    private void createFixtures() throws Exception {
        account(OWNER, "creator", List.of("OWNER_TASK_ADMIN"));
        account(OPERATOR, "admin", List.of("OPERATOR_TASK_ADMIN"));
        account(OPERATOR, "uploader", List.of("OPERATOR_USER"));
        account(OPERATOR, "peer-user", List.of("OPERATOR_USER"));
        account(OPERATOR, "outsider", List.of("OPERATOR_USER"));
        account(OWNER, "wrong-role", List.of("ORG_ADMIN"));
        for (Account account : accounts) {
            account.token = string(expect("fixture_login", "POST", API + "/auth/login", null,
                    Map.of("username", account.username, "password", account.password), 200).data().get("accessToken"));
            secrets.add(account.token);
        }
        droneId = UUID.randomUUID().toString();
        droneCode = "T013-" + runId.substring(0, 12);
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
                "t013-" + runId.substring(0, 12) + "-" + suffix, password);
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
                Map.of("name", "T013-" + runId, "scope", "isolated real upload verification", "requirements", List.of(
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
        require(jdbc.update("INSERT INTO task_member(task_id,user_id) VALUES (?,?)",taskId,accounts.get(5).id)==1,
                "wrong_role_fixture_is_explicit_member_to_isolate_role_check");
        firstScope = scopes.get(0);
        secondScope = scopes.get(1);
        report.put("fixtureTaskId", taskId);
    }

    private void verifyVerifications() throws Exception {
        byte[] original=png(0x114477);
        byte[] changed=original.clone();changed[changed.length-9]^=1; // Preserve PNG magic; content is deliberately different.
        require(!sha256(original).equals(sha256(changed)),"one_byte_copy_change_has_different_real_digest");
        String upload=uploadFile("verification_original_upload",firstScope,original,"image/png","original.png");
        String version=completeThroughFinalization(upload);
        publisher.publishBatch();
        await(()->"CONFIRMED".equals(anchorStatus(version)),"fixture_is_anchored_by_real_upload_and_fabric_consumers");
        Map<String,Object> attempt=verifyConfirmed(version,original);
        String tx=string(attempt.get("tx_id"));
        String key=jdbc.queryForObject("SELECT object_key FROM evidence_version WHERE id=?",String.class,version);

        int operatorBefore=faults.operator.queries.get();
        verifyResult("stored_operator_match",version,accounts.get(2),"STORED",null,"MATCH",original,original,tx);
        require(faults.operator.queries.get()==operatorBefore+1,"operator_verification_uses_real_operator_gateway");
        int ownerBefore=faults.owner.queries.get(),operatorAtOwner=faults.operator.queries.get();
        verifyResult("stored_owner_match",version,accounts.get(0),"STORED",null,"MATCH",original,original,tx);
        require(faults.owner.queries.get()==ownerBefore+1&&faults.operator.queries.get()==operatorAtOwner,
                "owner_verification_uses_its_own_real_gateway_identity");

        byte[] formalBefore=formalSnapshot();Set<String> keysBefore=ownedObjectKeys();
        verifyResult("identical_uploaded_copy_match",version,accounts.get(2),"COPY",original,"MATCH",original,original,tx);
        verifyResult("one_byte_uploaded_copy_mismatch",version,accounts.get(2),"COPY",changed,"MISMATCH",changed,original,tx);
        require(MessageDigest.isEqual(formalBefore,formalSnapshot())&&keysBefore.equals(ownedObjectKeys()),
                "copy_verification_creates_no_formal_version_upload_object_anchor_or_outbox");
        try(InputStream input=storage.open(key)) {
            require(MessageDigest.isEqual(original,input.readAllBytes()),"modified_copy_never_overwrites_private_original");
        }

        // Deliberately mutate only this probe-owned object, then restore it. DB metadata stays unchanged.
        try {
            putPrivate(key,changed);
            verifyResult("stored_bytes_rehashed_after_object_changed",version,accounts.get(2),"STORED",null,"MISMATCH",changed,original,tx);
        } finally {putPrivate(key,original);}
        require(sha256(original).equals(jdbc.queryForObject("SELECT sha256 FROM evidence_version WHERE id=?",String.class,version)),
                "stored_verification_reads_actual_bytes_instead_of_database_hash");
        try {
            storage.delete(key);
            verifyResult("missing_private_original",version,accounts.get(2),"STORED",null,"FILE_READ_FAILED",null,null,null);
        } finally {putPrivate(key,original);}
        faultStorage.failPartialRead(key);
        verifyResult("interrupted_real_object_stream",version,accounts.get(2),"STORED",null,"FILE_READ_FAILED",null,null,null);
        require(faultStorage.partialFailures.get()==1,"real_object_stream_returns_prefix_then_controlled_io_failure");

        faults.operator.failNext.set(true);
        verifyResult("real_ledger_query_response_lost",version,accounts.get(2),"STORED",null,"CHAIN_QUERY_FAILED",original,null,null);
        require(faults.operator.queryFaults.get()==1&&"CONFIRMED".equals(anchorStatus(version)),
                "query_failure_is_uncertain_and_does_not_downgrade_confirmed_anchor");

        jdbc.update("UPDATE chain_submission SET status='PENDING',confirmed_tx_id=NULL,confirmed_by=NULL,retryable=TRUE WHERE version_id=?",version);
        try {
            verifyResult("actual_ledger_record_overrides_local_pending_for_this_result",version,accounts.get(2),"STORED",null,"MATCH",original,original,tx);
            require("PENDING".equals(anchorStatus(version))&&jdbc.queryForObject("SELECT confirmed_tx_id FROM chain_submission WHERE version_id=?",String.class,version)==null,
                    "verification_does_not_repair_or_rewrite_local_anchor_state");
        } finally {jdbc.update("UPDATE chain_submission SET status='CONFIRMED',confirmed_tx_id=?,confirmed_by='Org2MSP',retryable=FALSE WHERE version_id=?",tx,version);}

        verifyInvalidInputsAndPermissions(version,original);
        verifyMembershipRevocationDuringRead(version,key);
        verifyHistory(version);

        anchorContainer.stop(); // Leave the second version genuinely unregistered without publishing a new ledger write.
        String notAnchoredUpload=uploadFile("unregistered_version_upload",secondScope,original,"image/png","unregistered.png");
        String notAnchored=completeThroughFinalization(notAnchoredUpload);
        publisher.publishBatch();
        require("PENDING".equals(anchorStatus(notAnchored)),"second_version_has_genuine_unconsumed_pending_anchor");
        verifyResult("real_ledger_found_false",notAnchored,accounts.get(2),"STORED",null,"ANCHOR_UNCONFIRMED",original,null,null);
        require(!realLedgers.forOrganization(OPERATOR).queryEvidence(
                jdbc.queryForObject("SELECT evidence_id FROM evidence_version WHERE id=?",String.class,notAnchored),1).found(),
                "unconfirmed_result_is_backed_by_actual_missing_fabric_record");
        require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=?",anchorId(notAnchored))==0,
                "unregistered_version_never_prepares_or_submits_ledger_transaction");
        var healthyHistory=expect("history_excludes_other_versions","GET",verificationPath(version)+"?pageSize=100",accounts.get(2).token,null,200).data();
        var pendingHistory=expect("other_version_has_its_own_history","GET",verificationPath(notAnchored),accounts.get(2).token,null,200).data();
        require(number(healthyHistory.get("total"))==verificationCount(version)
                &&objects(healthyHistory.get("items")).stream().allMatch(item->version.equals(item.get("versionId")))
                &&number(pendingHistory.get("total"))==1&&objects(pendingHistory.get("items")).size()==1
                &&notAnchored.equals(objects(pendingHistory.get("items")).getFirst().get("versionId")),
                "verification_history_is_filtered_by_visible_version_before_paging");
        require(count("SELECT COUNT(*) FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",taskId)==verificationIds.size(),
                "every_successful_verification_response_has_one_durable_result");
        report.put("verificationRows",verificationIds.size());
        report.put("verificationResults",jdbc.queryForList("SELECT result,COUNT(*) AS count FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?) GROUP BY result ORDER BY result",taskId));
        report.put("copyNeverChangesFormalData",true);report.put("membershipRevocationDuringIoRejected",true);
        report.put("ownerRealLedgerQueries",faults.owner.queries.get());report.put("operatorRealLedgerQueries",faults.operator.queries.get());
        report.put("partialObjectReadFaults",faultStorage.partialFailures.get());report.put("realLedgerQueryResponseFaults",faults.operator.queryFaults.get());
        report.put("confirmedLedgerVersionsRetained",1);
    }
    private Map<String,Object> verifyResult(String label,String version,Account actor,String source,byte[] copy,
            String result,byte[] actual,byte[] ledger,String tx)throws Exception {
        Reply reply="COPY".equals(source)
                ?multipart(label,verificationPath(version)+"?source=COPY",actor.token,
                        List.of(new FormPart("file","copy.png","image/png",copy)),200,"OK")
                :expect(label,"POST",verificationPath(version)+"?source=STORED",actor.token,null,200);
        Map<String,Object> row=reply.data();
        require(row.keySet().equals(verificationFields()),"verification_response_has_only_safe_result_fields");
        require(version.equals(row.get("versionId"))&&actor.id.equals(row.get("userId"))&&source.equals(row.get("source"))
                &&result.equals(row.get("result")),"verification_identity_source_and_result_match_request");
        String id=string(row.get("id"));require(verificationIds.add(id),"each_verification_has_a_fresh_result_identifier");
        require(actual==null?row.get("actualSha256")==null&&row.get("actualSize")==null
                :sha256(actual).equals(row.get("actualSha256"))&&number(row.get("actualSize"))==actual.length,
                "verification_only_records_complete_actual_digest_and_size");
        require(ledger==null?row.get("ledgerSha256")==null&&row.get("ledgerTxId")==null
                :sha256(ledger).equals(row.get("ledgerSha256"))&&tx.equals(row.get("ledgerTxId")),
                "verification_ledger_fields_come_only_from_successful_real_query");
        require(string(row.get("reason")).matches("[A-Z][A-Z0-9_]{0,199}")&&row.get("verifiedAt") instanceof String,
                "verification_has_safe_reason_code_and_server_timestamp");
        Map<String,Object> stored=jdbc.queryForMap("SELECT * FROM verification_record WHERE id=?",id);
        require(version.equals(stored.get("version_id"))&&actor.id.equals(stored.get("user_id"))
                &&source.equals(stored.get("source"))&&result.equals(stored.get("result"))
                &&java.util.Objects.equals(row.get("actualSha256"),stored.get("actual_sha256"))
                &&java.util.Objects.equals(row.get("ledgerSha256"),stored.get("ledger_sha256"))
                &&java.util.Objects.equals(row.get("ledgerTxId"),stored.get("ledger_tx_id")),
                "verification_response_is_the_committed_mysql_record");
        List<Map<String,Object>> audit=jdbc.queryForList("SELECT actor_type,actor_id,org_id,action,resource_type,resource_id,result FROM audit_log WHERE trace_id=?",reply.trace());
        require(audit.size()==1&&actor.id.equals(audit.getFirst().get("actor_id"))
                &&actor.org.equals(audit.getFirst().get("org_id"))&&"SUCCESS".equals(audit.getFirst().get("result"))
                &&"FILE_VERIFICATION_COMPLETED".equals(audit.getFirst().get("action"))
                &&"EVIDENCE_VERSION".equals(audit.getFirst().get("resource_type"))&&version.equals(audit.getFirst().get("resource_id")),
                "verification_result_and_safe_actor_audit_are_committed_together");
        return row;
    }
    private void verifyInvalidInputsAndPermissions(String version,byte[] png)throws Exception {
        long before=verificationCount(version);
        expect("anonymous_verification","POST",verificationPath(version)+"?source=STORED",null,null,401);
        Reply anonymousMultipart=requestContent("anonymous_malformed_copy_is_not_parsed","POST",verificationPath(version)+"?source=COPY",null,new byte[]{1,2,3},"multipart/form-data; boundary=deliberately-broken");
        assertStatus(anonymousMultipart,"anonymous_malformed_copy_is_not_parsed",401,"UNAUTHORIZED");
        Reply outsiderMultipart=requestContent("nonmember_malformed_copy_is_not_parsed","POST",verificationPath(version)+"?source=COPY",accounts.get(4).token,new byte[]{1,2,3},"multipart/form-data; boundary=deliberately-broken");
        assertStatus(outsiderMultipart,"nonmember_malformed_copy_is_not_parsed",403,"FORBIDDEN");
        expect("nonmember_verification","POST",verificationPath(version)+"?source=STORED",accounts.get(4).token,null,403);
        expect("org_admin_only_verification","POST",verificationPath(version)+"?source=STORED",accounts.get(5).token,null,403);
        expect("unknown_version_verification","POST",verificationPath(UUID.randomUUID().toString())+"?source=STORED",accounts.get(2).token,null,403);
        expect("invalid_source","POST",verificationPath(version)+"?source=UNKNOWN",accounts.get(2).token,null,400);
        expect("missing_source","POST",verificationPath(version),accounts.get(2).token,null,400);
        expect("copy_requires_multipart_file","POST",verificationPath(version)+"?source=COPY",accounts.get(2).token,null,415,"UNSUPPORTED_MEDIA_TYPE");
        multipart("copy_requires_file_part",verificationPath(version)+"?source=COPY",accounts.get(2).token,List.of(),400,"INVALID_ARGUMENT");
        multipart("copy_wrong_field_name",verificationPath(version)+"?source=COPY",accounts.get(2).token,
                List.of(new FormPart("image","copy.png","image/png",png)),400,"INVALID_ARGUMENT");
        multipart("copy_multiple_files",verificationPath(version)+"?source=COPY",accounts.get(2).token,
                List.of(new FormPart("file","first.png","image/png",png),new FormPart("file","second.png","image/png",png)),400,"INVALID_ARGUMENT");
        multipart("stored_rejects_attached_file",verificationPath(version)+"?source=STORED",accounts.get(2).token,
                List.of(new FormPart("file","copy.png","image/png",png)),400,"INVALID_ARGUMENT");
        multipart("copy_rejects_unsupported_type",verificationPath(version)+"?source=COPY",accounts.get(2).token,
                List.of(new FormPart("file","copy.exe","application/octet-stream",png)),400,"INVALID_ARGUMENT");
        multipart("copy_rejects_extension_mime_mismatch",verificationPath(version)+"?source=COPY",accounts.get(2).token,
                List.of(new FormPart("file","copy.jpg","image/png",png)),400,"INVALID_ARGUMENT");
        multipart("copy_rejects_empty_file",verificationPath(version)+"?source=COPY",accounts.get(2).token,
                List.of(new FormPart("file","copy.png","image/png",new byte[0])),400,"INVALID_ARGUMENT");
        multipart("copy_rejects_oversized_file",verificationPath(version)+"?source=COPY",accounts.get(2).token,
                List.of(new FormPart("file","copy.png","image/png",Arrays.copyOf(png,1025))),413,"FILE_TOO_LARGE");
        require(verificationCount(version)==before,"invalid_or_unauthorized_requests_create_no_verification_records");
    }
    private void verifyMembershipRevocationDuringRead(String version,String key)throws Exception {
        Account peer=accounts.get(3);long before=verificationCount(version);
        faultStorage.blockRead(key);
        CompletableFuture<Reply> request=CompletableFuture.supplyAsync(()->{
            try{return request("membership_revoked_during_io","POST",verificationPath(version)+"?source=STORED",peer.token,null,false);}
            catch(Exception failure){throw new ProbeFailure("membership_race_http_failure");}
        });
        try {
            require(faultStorage.entered.await(10,TimeUnit.SECONDS),"verification_is_reading_real_object_before_membership_revocation");
            require(jdbc.update("DELETE FROM task_member WHERE task_id=? AND user_id=?",taskId,peer.id)==1,"peer_membership_is_revoked_while_io_has_no_database_lock");
            faultStorage.release.countDown();
            Reply reply=request.get(20,TimeUnit.SECONDS);assertStatus(reply,"membership_revoked_during_io",403,"FORBIDDEN");
            require(verificationCount(version)==before,"final_permission_recheck_prevents_result_after_membership_revocation");
            require(count("SELECT COUNT(*) FROM audit_log WHERE trace_id=?",reply.trace())==0,"revoked_inflight_verification_writes_no_success_audit");
        }finally {
            faultStorage.release.countDown();faultStorage.blockNext.set(false);
            jdbc.update("INSERT IGNORE INTO task_member(task_id,user_id) VALUES (?,?)",taskId,peer.id);
        }
    }
    private void verifyHistory(String version)throws Exception {
        Map<String,Object> page=expect("owner_reads_version_verification_history","GET",verificationPath(version)+"?page=1&pageSize=2",accounts.get(0).token,null,200).data();
        require(page.keySet().equals(Set.of("items","page","pageSize","total"))&&number(page.get("page"))==1
                &&number(page.get("pageSize"))==2&&number(page.get("total"))==verificationCount(version),
                "history_page_count_is_filtered_to_requested_visible_version");
        List<Map<String,Object>> first=objects(page.get("items"));require(first.size()==2,"history_first_page_has_requested_size");
        var second=objects(expect("read_second_verification_history_page","GET",verificationPath(version)+"?page=2&pageSize=2",accounts.get(2).token,null,200).data().get("items"));
        require(second.size()==2&&first.stream().noneMatch(a->second.stream().anyMatch(b->a.get("id").equals(b.get("id")))),
                "history_paging_has_no_duplicate_rows");
        var all=objects(expect("read_all_version_verification_history","GET",verificationPath(version)+"?page=1&pageSize=100",accounts.get(2).token,null,200).data().get("items"));
        List<String> expected=jdbc.queryForList("SELECT id FROM verification_record WHERE version_id=? ORDER BY verified_at DESC,id DESC",String.class,version);
        require(all.stream().map(v->string(v.get("id"))).toList().equals(expected),"history_uses_stable_server_time_and_id_descending_order");
        require(all.stream().allMatch(v->verificationFields().equals(v.keySet())&&version.equals(v.get("versionId"))),
                "history_contains_only_safe_result_fields_for_this_version");
        expect("anonymous_verification_history","GET",verificationPath(version),null,null,401);
        expect("nonmember_verification_history","GET",verificationPath(version),accounts.get(4).token,null,403);
        expect("org_admin_only_verification_history","GET",verificationPath(version),accounts.get(5).token,null,403);
        expect("unknown_version_history","GET",verificationPath(UUID.randomUUID().toString()),accounts.get(2).token,null,403);
        expect("history_invalid_page","GET",verificationPath(version)+"?page=0",accounts.get(2).token,null,400);
        expect("history_too_large_page","GET",verificationPath(version)+"?pageSize=101",accounts.get(2).token,null,400);
        Account peer=accounts.get(3);jdbc.update("DELETE FROM task_member WHERE task_id=? AND user_id=?",taskId,peer.id);
        try {expect("removed_member_cannot_read_history","GET",verificationPath(version),peer.token,null,403);}
        finally {jdbc.update("INSERT INTO task_member(task_id,user_id) VALUES (?,?)",taskId,peer.id);}
    }
    private Reply multipart(String label,String path,String token,List<FormPart> parts,int status,String code)throws Exception {
        String boundary="uav-probe-"+UUID.randomUUID();var bytes=new ByteArrayOutputStream();
        for(FormPart part:parts) {
            bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+part.name()+"\"; filename=\""+part.filename()+"\"\r\nContent-Type: "+part.contentType()+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            bytes.write(part.bytes());bytes.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        bytes.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        Reply reply=requestContent(label,"POST",path,token,bytes.toByteArray(),"multipart/form-data; boundary="+boundary);
        assertStatus(reply,label,status,code);return reply;
    }
    private byte[] formalSnapshot()throws Exception {
        Set<String> formal=Set.of("inspection_task","inspection_subtask","material_requirement","task_execution",
                "evidence_version","upload_session","upload_part","chain_submission","chain_attempt","outbox_event","consumed_event");
        Map<String,String> tables=new LinkedHashMap<>();TABLE_ORDER.forEach((table,order)->{if(formal.contains(table))tables.put(table,order);});
        return snapshot(tables);
    }
    private Set<String> ownedObjectKeys(){Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));return keys;}
    private void putPrivate(String key,byte[] bytes)throws Exception {
        Path file=Files.createTempFile(reportPath.getParent(),".restore-verification-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{Files.write(file,bytes);storage.put(key,file,"image/png");}finally{Files.deleteIfExists(file);}
    }
    private long verificationCount(String version){return count("SELECT COUNT(*) FROM verification_record WHERE version_id=?",version);}
    private static Set<String> verificationFields(){return Set.of("id","versionId","userId","source","actualSha256","ledgerSha256","actualSize","ledgerTxId","result","reason","verifiedAt");}
    private static String verificationPath(String version){return versionPath(version)+"/verifications";}
    private record FormPart(String name,String filename,String contentType,byte[] bytes){}

    private String completeThroughFinalization(String upload)throws Exception {
        expect("request_upload_completion","POST",completePath(upload),accounts.get(2).token,null,202);
        publisher.publishBatch();
        await(()->"COMPLETED".equals(status(upload)),"real_file_consumer_generates_fixed_version_before_anchor");
        String version=jdbc.queryForObject("SELECT id FROM evidence_version WHERE upload_session_id=?",String.class,upload);
        require(version!=null,"fixed_version_exists_for_anchor");
        var intended=jdbc.queryForMap("SELECT evidence_id,version_no,sha256 FROM evidence_version WHERE id=?",version);
        ledgerIntents.add(Map.of("evidenceId",intended.get("evidence_id"),"version",intended.get("version_no"),"fileHash",intended.get("sha256")));
        return version;
    }
    private Map<String,Object> verifyConfirmed(String version,byte[] expected)throws Exception {
        var fixed=jdbc.queryForMap("SELECT * FROM evidence_version WHERE id=?",version);
        String submission=anchorId(version);
        var row=jdbc.queryForMap("SELECT * FROM chain_submission WHERE id=?",submission);
        Map<String,Object> attempt=onlyAttempt(submission);
        String tx=string(attempt.get("tx_id"));
        require("CONFIRMED".equals(row.get("status"))&&tx.equals(row.get("confirmed_tx_id"))
                &&"Org2MSP".equals(row.get("confirmed_by"))&&row.get("last_error")==null
                &&row.get("next_action_at")==null&&row.get("lease_token")==null,
                "confirmed_submission_has_original_transaction_and_no_retry_lease");
        require("VALID".equals(attempt.get("phase"))&&number(attempt.get("validation_code"))==0
                &&number(attempt.get("block_number"))>=0,"persisted_attempt_has_real_valid_commit_outcome");
        byte[] prepared=blob(attempt,"transaction_bytes"),commit=blob(attempt,"commit_bytes");
        validateCheckpoints(tx,prepared,commit);
        var queried=realLedgers.forOrganization(OPERATOR).queryCommit(new EvidenceLedgerClient.CommitCheckpoint(tx,commit));
        require(queried.valid()&&queried.validationCode()==0&&tx.equals(queried.transactionId())
                &&queried.blockNumber()==number(attempt.get("block_number")),"saved_commit_bytes_requery_actual_fabric_same_outcome");
        require(sha256(expected).equals(fixed.get("sha256"))&&number(fixed.get("size"))==expected.length,
                "anchored_version_uses_server_computed_full_file_hash");
        try(InputStream input=storage.open(string(fixed.get("object_key")))) {
            require(MessageDigest.isEqual(expected,input.readAllBytes()),"anchored_fixed_object_still_matches_uploaded_private_bytes");
        }
        compareInstitutionQueries(version,tx);
        ledgerRecords.add(Map.of("evidenceId",fixed.get("evidence_id"),"version",fixed.get("version_no"),
                "fileHash",fixed.get("sha256"),"submitterMsp","Org2MSP","registeredTxId",tx,
                "transactionBytesLength",prepared.length,"transactionBytesSha256",sha256(prepared),
                "commitBytesLength",commit.length,"commitBytesSha256",sha256(commit),
                "blockNumber",attempt.get("block_number")));
        secrets.add(Base64.getEncoder().encodeToString(prepared));secrets.add(Base64.getEncoder().encodeToString(commit));
        return attempt;
    }
    private void validateCheckpoints(String tx,byte[] transaction,byte[] commit)throws Exception {
        require(tx.matches("[0-9a-f]{64}")&&transaction.length>0&&commit.length>0,"both_original_gateway_checkpoints_are_nonempty");
        FabricGatewaySettings settings=FabricGatewaySettings.fromEnvironment(System.getenv(),"ORGB");
        X509Identity identity;
        try(var reader=Files.newBufferedReader(settings.certificate(),StandardCharsets.UTF_8)) {
            identity=new X509Identity(settings.mspId(),Identities.readX509Certificate(reader));
        }
        var validator=new CheckpointValidator(identity.getMspId(),identity.getCredentials(),settings.channel(),settings.chaincode());
        validator.transaction(new EvidenceLedgerClient.PreparedRegistration(tx,transaction));
        validator.commit(new EvidenceLedgerClient.CommitCheckpoint(tx,commit));
        require(true,"database_binary_checkpoints_pass_original_t006_identity_channel_chaincode_validation");
    }
    private void compareInstitutionQueries(String version,String tx)throws Exception {
        var fixed=jdbc.queryForMap("SELECT evidence_id,version_no,sha256 FROM evidence_version WHERE id=?",version);
        String evidence=string(fixed.get("evidence_id"));int number=((Number)fixed.get("version_no")).intValue();
        var owner=realLedgers.forOrganization(OWNER).queryEvidence(evidence,number);
        var operator=realLedgers.forOrganization(OPERATOR).queryEvidence(evidence,number);
        require(owner.found()&&operator.found()&&owner.record().equals(operator.record()),"both_real_organizations_read_the_same_fabric_record");
        var record=owner.record();
        require(evidence.equals(record.evidenceId())&&number==record.version()&&fixed.get("sha256").equals(record.fileHash())
                &&"Org2MSP".equals(record.submitterMsp())&&tx.equals(record.registeredTxId()),
                "all_five_ledger_fields_match_original_fixed_version_and_transaction");
    }

    private Map<String,Object> onlyAttempt(String submission) {
        var attempts=jdbc.queryForList("SELECT * FROM chain_attempt WHERE submission_id=?",submission);
        require(attempts.size()==1,"one_gateway_attempt_per_anchored_version");return attempts.getFirst();
    }
    private static byte[] blob(Map<String,Object> row,String key) {
        Object value=row.get(key);if(!(value instanceof byte[] bytes))throw new ProbeFailure("missing_binary_checkpoint");return bytes.clone();
    }
    private String anchorStatus(String version){return jdbc.queryForObject("SELECT status FROM chain_submission WHERE version_id=?",String.class,version);}
    private static String versionPath(String id){return API+"/evidence-versions/"+id;}

    private String uploadFile(String label,Scope scope,byte[] bytes,String type,String filename)throws Exception {
        Account uploader=accounts.get(2);
        String id=createSession(label,uploader,createBody(UUID.randomUUID().toString(),scope,filename,type,bytes.length));
        int size=jdbc.queryForObject("SELECT part_size FROM upload_session WHERE id=?",Integer.class,id);
        int parts=1+(bytes.length-1)/size;
        // Deliberately reverse transfer order; worker must merge by durable part number.
        for(int partNo=parts;partNo>=1;partNo--) {
            byte[] part=Arrays.copyOfRange(bytes,(partNo-1)*size,Math.min(partNo*size,bytes.length));
            part(label+"_part_"+partNo,id,partNo,uploader.token,part,200,"OK");
        }
        return id;
    }

    private String status(String upload){return jdbc.queryForObject("SELECT status FROM upload_session WHERE id=?",String.class,upload);}
    private String anchorId(String version){return jdbc.queryForObject("SELECT id FROM chain_submission WHERE version_id=?",String.class,version);}
    private long count(String sql,Object...args){Long count=jdbc.queryForObject(sql,Long.class,args);if(count==null)throw new ProbeFailure("missing_count");return count;}
    private static String completePath(String upload){return sessionPath(upload)+"/complete";}
    private void await(BooleanSupplier condition,String name)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline){if(condition.getAsBoolean()){require(true,name);return;}Thread.sleep(100);}
        require(false,name);
    }
    private Set<String> objectKeys(String upload) {
        Set<String> keys=new LinkedHashSet<>();
        for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")) {
            String continuation=null;
            do {
                var response=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(continuation).build());
                response.contents().forEach(object->keys.add(object.key()));
                continuation=Boolean.TRUE.equals(response.isTruncated())?response.nextContinuationToken():null;
            }while(continuation!=null);
        }
        return keys;
    }
    private void cleanupFixtures() {
        faultStorage.clear();
        faults.owner.failNext.set(false);faults.operator.failNext.set(false);
        Set<String> taskIds=new LinkedHashSet<>();
        for(Account account:accounts)taskIds.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,account.id));
        for(String task:taskIds)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));
        Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));
        for(String key:keys)storage.delete(key);
        for(String task:taskIds) {
            jdbc.update("DELETE FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",task,task);
            jdbc.update("DELETE FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task,task);
            jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);
            jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);
            jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);
            jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);
            jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);
            jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);
            jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);
            jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);
            jdbc.update("DELETE FROM task_member WHERE task_id=?",task);
            jdbc.update("DELETE FROM inspection_task WHERE id=?",task);
        }
        if(droneId!=null)jdbc.update("DELETE FROM drone WHERE id=? AND code=?",droneId,droneCode);
        for(Account account:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",account.id,account.username);
        for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"owned_part_and_final_object_prefixes_are_empty_after_cleanup");
        report.put("fixtureTasksDeleted",taskIds.size());report.put("fixtureAccountsDeleted",accounts.size());
        report.put("fixtureUploadSessionsDeleted",uploadIds.size());report.put("fixturePrivateObjectsDeleted",keys.size());
    }
    private void cleanupTopology() {
        for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue))rabbitAdmin.deleteQueue(queue);
        rabbitAdmin.deleteExchange(exchange);rabbitAdmin.deleteExchange(deadExchange);
        report.put("isolatedBrokerTopologyDeleted",true);
    }

    private String createSession(String name, Account actor, Map<String, Object> body) throws Exception {
        Reply reply = expect(name, "POST", uploadPath(), actor.token, body, 200);
        String id = string(reply.data().get("uploadId"));
        uploadIds.add(id);
        successAudit(reply, "UPLOAD_SESSION_CREATED");
        return id;
    }

    private Reply part(String name, String id, int number, String token, byte[] bytes, int status, String code)
            throws Exception {
        Reply reply = request(name, "PUT", partPath(id, number), token, bytes, true);
        assertStatus(reply, name, status, code);
        if (status >= 400) noUploadAudit(reply);
        return reply;
    }

    private Reply expect(String name, String method, String path, String token, Object body, int status) throws Exception {
        String code = switch (status) {
            case 200, 202 -> "OK";
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

    private Reply request(String name,String method,String path,String token,Object body,boolean raw)throws Exception {
        return requestContent(name,method,path,token,body==null?null:raw?(byte[])body:JSON.writeValueAsBytes(body),body==null?null:raw?"application/octet-stream":"application/json");
    }
    private Reply requestContent(String name,String method,String path,String token,byte[] bytes,String contentType)throws Exception {
        String trace = "t013-" + runId + "-" + sequence.incrementAndGet();
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                .header("X-Trace-Id", trace).header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (contentType != null) builder.header("Content-Type",contentType);
        builder.method(method, bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        requests.add(Map.of("case", name, "status", response.statusCode(), "traceId", trace));
        require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"), name + "_json_content_type");
        String text = new String(response.body(), StandardCharsets.UTF_8);
        require(!text.contains("objectKey") && !text.contains("object_key") && !text.contains("upload-parts/")
                        && !text.contains("evidence-files/") && !text.contains("transactionBytes") && !text.contains("commitBytes")
                        && !text.contains("transaction_bytes") && !text.contains("commit_bytes")
                        && !text.contains(storageEndpoint) && secrets.stream().noneMatch(text::contains),
                name + "_response_does_not_expose_private_storage_or_credentials");
        Map<String, Object> envelope = JSON.readValue(response.body(), JSON_OBJECT_TYPE);
        require(envelope.keySet().equals(Set.of("code", "message", "data", "traceId")), name + "_uniform_envelope");
        require(trace.equals(response.headers().firstValue("X-Trace-Id").orElse(null))
                        && trace.equals(envelope.get("traceId")), name + "_trace_header_and_body");
        return new Reply(response.statusCode(), envelope, trace);
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
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id", "t013-"+runId+"-%");
        require(!rows.isEmpty(),"real_pipeline_operations_have_append_only_audit");
        for(var row:rows) {
            require("SUCCESS".equals(row.get("result")),"pipeline_audit_success_only");
            String serialized=row.values().stream().map(String::valueOf).collect(Collectors.joining("\n"));
            require(secrets.stream().noneMatch(serialized::contains)&&!serialized.contains("$2a$")
                    &&!serialized.contains("$2b$")&&!serialized.contains("Bearer ")
                    &&!serialized.contains("upload-parts/")&&!serialized.contains("evidence-files/")
                    &&!serialized.contains(storageEndpoint),"audit_contains_no_credentials_or_object_locations");
        }
        report.put("auditRowsRetained",rows.size()); report.put("auditContainsNoCredentialsOrStorageKeys",true);
    }

    private Map<String, Long> businessCounts() {
        var counts = new LinkedHashMap<String, Long>();
        for (String table : TABLE_ORDER.keySet()) counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        return counts;
    }

    private byte[] businessSnapshot()throws Exception{return snapshot(TABLE_ORDER);}
    private byte[] snapshot(Map<String,String> tables)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(Connection connection=dataSource.getConnection()) {
            for(var table:new java.util.TreeMap<>(tables).entrySet()) {
                try(var statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT * FROM "+table.getKey()+" ORDER BY "+table.getValue())) {
                    frame(digest,table.getKey());
                    while(rows.next()) {
                        digest.update((byte)1);
                        for(int column=1;column<=rows.getMetaData().getColumnCount();column++) {
                            int type=rows.getMetaData().getColumnType(column);
                            if(type==java.sql.Types.BINARY||type==java.sql.Types.VARBINARY||type==java.sql.Types.LONGVARBINARY||type==java.sql.Types.BLOB) {
                                byte[] bytes=rows.getBytes(column);
                                digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());
                                if(bytes!=null)digest.update(bytes);
                            }else frame(digest,rows.getString(column));
                        }
                    }
                    digest.update((byte)0);
                }
            }
        }
        return digest.digest();
    }

    private void writeReport() throws Exception {
        report.put("caseCount", requests.size());
        report.put("checkCount", checks.size());
        Files.createDirectories(reportPath.getParent());
        Path temporary = Files.createTempFile(reportPath.getParent(), ".verification-flow-probe-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            byte[] serialized = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            String text = new String(serialized, StandardCharsets.UTF_8);
            if (secrets.stream().anyMatch(text::contains) || text.contains("upload-parts/") || text.contains("evidence-files/"))
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

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new ProbeFailure("expected_json_object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new ProbeFailure("expected_json_object");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> list)) throw new ProbeFailure("expected_json_array");
        return list.stream().map(VerificationFlowProbe::object).toList();
    }

    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) digest.update(bytes);
    }

    private static final class Account {
        final String id,org,username,password; String token;
        Account(String id,String org,String username,String password){this.id=id;this.org=org;this.username=username;this.password=password;}
        @Override public String toString(){return "PipelineProbeAccount["+id+"]";}
    }
    private record Scope(String requirementId,String executionId,String subtaskId) {}
    private record Reply(int status,Map<String,Object> body,String trace) {
        Map<String,Object> data(){return object(body.get("data"));}
        @Override public String toString(){return "PipelineProbeReply["+status+","+trace+"]";}
    }
    private static final class ProbeFailure extends RuntimeException { ProbeFailure(String name){super(name);} }

    @TestConfiguration(proxyBeanMethods=false)
    @Profile("verification-probe")
    static class FaultInjection {
        @Bean(destroyMethod="") @Primary
        FaultClients faultClients(@Qualifier("institutionLedgerClients") InstitutionLedgerClients real) {
            return new FaultClients(new QueryClient(real.forOrganization(OWNER)),new QueryClient(real.forOrganization(OPERATOR)),real.mspId(OWNER),real.mspId(OPERATOR));
        }
        @Bean @Primary
        FaultStorage faultStorage(@Qualifier("objectStorage") ObjectStorage real){return new FaultStorage(real);}
    }
    static final class FaultClients extends InstitutionLedgerClients {
        final QueryClient owner,operator;
        FaultClients(QueryClient owner,QueryClient operator,String ownerMsp,String operatorMsp){super(owner,operator,ownerMsp,operatorMsp);this.owner=owner;this.operator=operator;}
    }
    static final class QueryClient implements EvidenceLedgerClient {
        private final EvidenceLedgerClient delegate;
        final AtomicInteger queries=new AtomicInteger(),queryFaults=new AtomicInteger();
        final AtomicBoolean failNext=new AtomicBoolean();
        QueryClient(EvidenceLedgerClient delegate){this.delegate=delegate;}
        @Override public PreparedRegistration prepareRegistration(String evidence,int version,String hash)throws EndorseException{return delegate.prepareRegistration(evidence,version,hash);}
        @Override public CommitCheckpoint submitPrepared(PreparedRegistration prepared)throws SubmitException{return delegate.submitPrepared(prepared);}
        @Override public CommitOutcome queryCommit(CommitCheckpoint checkpoint)throws CommitStatusException{return delegate.queryCommit(checkpoint);}
        @Override public EvidenceQuery queryEvidence(String evidence,int version)throws GatewayException {
            queries.incrementAndGet();EvidenceQuery result=delegate.queryEvidence(evidence,version);
            if(failNext.compareAndSet(true,false)){queryFaults.incrementAndGet();throw Status.UNAVAILABLE.withDescription("Controlled probe response loss after real ledger query").asRuntimeException();}
            return result;
        }
        @Override public void close(){/* Original application registry owns the real clients. */}
    }
    static final class FaultStorage implements ObjectStorage {
        private final ObjectStorage delegate;
        private volatile String faultKey,blockKey;
        final AtomicInteger partialFailures=new AtomicInteger();
        final AtomicBoolean failNext=new AtomicBoolean(),blockNext=new AtomicBoolean();
        volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
        FaultStorage(ObjectStorage delegate){this.delegate=delegate;}
        void failPartialRead(String key){faultKey=key;failNext.set(true);}
        void blockRead(String key){blockKey=key;entered=new CountDownLatch(1);release=new CountDownLatch(1);blockNext.set(true);}
        void clear(){failNext.set(false);blockNext.set(false);release.countDown();}
        @Override public void put(String key,Path file,String type){delegate.put(key,file,type);}
        @Override public void delete(String key){delegate.delete(key);}
        @Override public InputStream open(String key) {
            InputStream actual=delegate.open(key);
            if(key.equals(faultKey)&&failNext.compareAndSet(true,false))return new FilterInputStream(actual){
                private boolean prefix;
                @Override public int read(byte[] bytes,int offset,int length)throws IOException {
                    if(prefix){partialFailures.incrementAndGet();throw new IOException("Controlled probe interruption after a real prefix");}
                    prefix=true;return in.read(bytes,offset,Math.min(length,4));
                }
                @Override public int read()throws IOException {
                    if(prefix){partialFailures.incrementAndGet();throw new IOException("Controlled probe interruption after a real prefix");}
                    prefix=true;return in.read();
                }
            };
            if(key.equals(blockKey)&&blockNext.compareAndSet(true,false))return new FilterInputStream(actual){
                private boolean waiting=true;
                private void barrier()throws IOException {
                    if(!waiting)return;waiting=false;entered.countDown();
                    try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Controlled probe barrier timeout");}
                    catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException("Controlled probe barrier interrupted");}
                }
                @Override public int read(byte[] bytes,int offset,int length)throws IOException{barrier();return in.read(bytes,offset,length);}
                @Override public int read()throws IOException{barrier();return in.read();}
            };
            return actual;
        }
    }

}
