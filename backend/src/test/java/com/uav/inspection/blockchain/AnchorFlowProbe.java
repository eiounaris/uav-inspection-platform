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
import java.io.InputStream;
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
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import com.rabbitmq.client.AMQP;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Explicit real HTTP/MySQL/Garage/RabbitMQ/Fabric probe. Never runs as an ordinary unit test. */
public final class AnchorFlowProbe {
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
    private InstitutionLedgerClients realLedgers;
    private AnchorProcessor anchorProcessor;
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

    private AnchorFlowProbe(Path reportPath) {
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
        if(args.length!=1) { System.err.println("Usage: AnchorFlowProbe <reportPath>"); System.exit(2); return; }
        var probe=new AnchorFlowProbe(Path.of(args[0]));
        try {
            probe.execute(); probe.writeReport();
            System.out.println("[PASS] T012 real HTTP/MySQL/Garage/RabbitMQ/Fabric pipeline probe: "+probe.reportPath);
        } catch(Exception failure) {
            probe.report.put("passed",false);
            probe.report.put("failureType",failure.getClass().getSimpleName());
            if(failure instanceof ProbeFailure) probe.report.put("failedCheck",failure.getMessage());
            try { probe.writeReport(); } catch(Exception ignored) { System.err.println("Could not write safe pipeline report"); }
            System.err.println("[FAIL] T012 pipeline probe ("+failure.getClass().getSimpleName()+"); inspect safe report metadata.");
            System.exit(1);
        }
    }
    private void execute() throws Exception {
        report.put("stage","application_startup");
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,FaultInjection.class)
                .web(WebApplicationType.SERVLET).profiles("local","database","storage","messaging","ledger","anchor-probe")
                .logStartupInfo(false).run("--server.port=0", "--logging.level.com.uav.inspection=INFO",
                        "--inspection.messaging.exchange="+exchange,
                        "--inspection.messaging.finalize-queue="+finalizeQueue,
                        "--inspection.messaging.anchor-queue="+anchorQueue,
                        "--inspection.messaging.dead-letter-exchange="+deadExchange,
                        "--inspection.messaging.dead-letter-queue="+deadQueue,
                        "--inspection.messaging.publisher-enabled=false",
                        "--inspection.messaging.recovery-enabled=false", "--inspection.ledger.recovery-enabled=false")) {
            jdbc=context.getBean(JdbcTemplate.class); dataSource=context.getBean(DataSource.class);
            storage=context.getBean(ObjectStorage.class); faults=context.getBean(FaultClients.class);
            realLedgers=context.getBean("institutionLedgerClients",InstitutionLedgerClients.class);
            anchorProcessor=context.getBean(AnchorProcessor.class);
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
                    report.put("stage","real_pipeline_checks"); verifyAnchorFlow(); verifyAuditSafety();
                } finally {
                    report.put("cleanupAttempted",true); containers.forEach(SimpleMessageListenerContainer::stop); cleanupFixtures(); cleanupTopology();
                    require(MessageDigest.isEqual(baseline,businessSnapshot()),"original_fifteen_business_tables_unchanged");
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
        droneCode = "T012-" + runId.substring(0, 12);
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
        var account = new Account(UUID.randomUUID().toString(),
                "t012-" + runId.substring(0, 12) + "-" + suffix, password);
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
                Map.of("name", "T012-" + runId, "scope", "isolated real upload verification", "requirements", List.of(
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

    private void verifyAnchorFlow() throws Exception {
        byte[] bytes=png(0x1155aa);
        String healthy=uploadFile("healthy_anchor_upload",firstScope,bytes,"image/png","healthy.png");
        String version=completeThroughFinalization(healthy);
        publisher.publishBatch();
        await(()->"CONFIRMED".equals(anchorStatus(version)),"real_rabbit_fabric_consumer_confirms_fixed_version");
        Map<String,Object> attempt=verifyConfirmed(version,bytes);
        String originalTx=string(attempt.get("tx_id"));
        int prepares=faults.operator.prepares.get(),submits=faults.operator.submits.get();
        duplicateAnchor(version);
        require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=?",anchorId(version))==1
                &&originalTx.equals(jdbc.queryForObject("SELECT confirmed_tx_id FROM chain_submission WHERE version_id=?",String.class,version)),
                "duplicate_anchor_keeps_one_attempt_and_original_registered_transaction");
        require(faults.operator.prepares.get()==prepares&&faults.operator.submits.get()==submits,
                "duplicate_anchor_does_not_endorse_or_submit_a_new_transaction");
        verifyReadEndpoints(version,bytes,originalTx);

        faults.operator.loseCommitReply.set(true);
        String interrupted=uploadFile("lost_commit_response_upload",secondScope,bytes,"image/png","interrupted.png");
        String recoveredVersion=completeThroughFinalization(interrupted);
        publisher.publishBatch();
        await(()->"UNKNOWN".equals(anchorStatus(recoveredVersion)),"lost_actual_commit_response_persists_unknown_status");
        String submission=anchorId(recoveredVersion);
        Map<String,Object> unknown=jdbc.queryForMap("SELECT * FROM chain_submission WHERE id=?",submission);
        Map<String,Object> saved=onlyAttempt(submission);
        require("UNKNOWN".equals(saved.get("phase"))&&unknown.get("next_action_at")!=null
                &&unknown.get("lease_token")==null&&unknown.get("lease_until")==null
                &&number(unknown.get("retry_count"))==1&&Boolean.TRUE.equals(unknown.get("retryable")),
                "unknown_checkpoint_is_durable_retryable_and_releases_processing_lease");
        require("COMMIT_STATUS_UNAVAILABLE".equals(unknown.get("last_error")),"lost_response_has_safe_fixed_error_code");
        byte[] transaction=blob(saved,"transaction_bytes"),commit=blob(saved,"commit_bytes");
        String savedTx=string(saved.get("tx_id"));
        validateCheckpoints(savedTx,transaction,commit);
        var actual=faults.operator.lostOutcome.get();
        require(actual!=null&&actual.valid()&&actual.validationCode()==0&&savedTx.equals(actual.transactionId()),
                "injected_timeout_occurs_after_real_fabric_valid_commit_response");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE consumer_code='EVIDENCE_ANCHOR' AND event_id=(SELECT event_id FROM outbox_event WHERE biz_id=? AND event_type='EVIDENCE_ANCHOR_REQUESTED') AND status='DONE'",submission)==1,
                "unknown_delivery_is_acked_only_after_durable_recovery_handoff");
        var unknownApi=expect("read_unknown_anchor","GET",versionPath(recoveredVersion)+"/anchor",accounts.get(2).token,null,200).data();
        require("UNKNOWN".equals(unknownApi.get("status"))&&unknownApi.get("confirmedTxId")==null
                &&savedTx.equals(unknownApi.get("transactionId")),"unknown_api_does_not_claim_confirmation");
        compareInstitutionQueries(recoveredVersion,savedTx);
        int beforePrepare=faults.operator.prepares.get(),beforeSubmit=faults.operator.submits.get();
        duplicateAnchor(recoveredVersion);
        require("UNKNOWN".equals(anchorStatus(recoveredVersion))&&count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=?",submission)==1,
                "duplicate_unknown_delivery_keeps_durable_retry_checkpoint");
        jdbc.update("UPDATE chain_submission SET next_action_at=UTC_TIMESTAMP(3) WHERE id=?",submission);
        anchorProcessor.recoverDue();
        await(()->"CONFIRMED".equals(anchorStatus(recoveredVersion)),"recovery_queries_saved_original_commit_and_confirms_version");
        Map<String,Object> restored=verifyConfirmed(recoveredVersion,bytes);
        require(saved.get("id").equals(restored.get("id"))&&savedTx.equals(restored.get("tx_id"))
                &&MessageDigest.isEqual(transaction,blob(restored,"transaction_bytes"))
                &&MessageDigest.isEqual(commit,blob(restored,"commit_bytes")),
                "unknown_recovery_preserves_attempt_transaction_and_both_binary_checkpoints");
        require(faults.operator.prepares.get()==beforePrepare&&faults.operator.submits.get()==beforeSubmit,
                "unknown_recovery_creates_no_new_proposal_or_submit_rpc");
        require(faults.operator.injected.get()==1,"exactly_one_real_commit_response_is_deliberately_lost");
        verifyReadEndpoints(recoveredVersion,bytes,savedTx);
        await(()->readyCount(finalizeQueue)==0&&readyCount(anchorQueue)==0,"both_manual_ack_queues_are_drained");
        report.put("confirmedVersions",2);report.put("attemptsCreated",count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",taskId));
        report.put("ledgerRecordsRetained",ledgerRecords);report.put("realCommitResponseFaults",faults.operator.injected.get());
        report.put("unknownRecoveredWithOriginalTransaction",true);report.put("twoInstitutionsReadMatchingFiveFields",true);
    }
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
    private void verifyReadEndpoints(String version,byte[] expected,String tx)throws Exception {
        Set<String> fields=Set.of("id","evidenceId","versionNo","requirementId","taskId","subtaskId","executionId",
                "filename","contentType","size","sha256","reviewStatus","createdAt","anchor");
        Set<String> anchorFields=Set.of("submissionId","versionId","status","confirmedTxId","confirmedBy","retryable",
                "retryCount","nextActionAt","lastError","transactionId","attemptPhase","validationCode","blockNumber");
        var body=expect("read_operator_fixed_version","GET",versionPath(version),accounts.get(2).token,null,200).data();
        require(fields.equals(body.keySet())&&version.equals(body.get("id"))&&sha256(expected).equals(body.get("sha256")),
                "version_api_returns_only_safe_metadata_and_original_full_hash");
        var anchor=object(body.get("anchor"));
        require(anchorFields.equals(anchor.keySet())&&"CONFIRMED".equals(anchor.get("status"))
                &&tx.equals(anchor.get("confirmedTxId"))&&tx.equals(anchor.get("transactionId")),
                "version_api_contains_safe_confirmed_anchor_view");
        var owner=expect("read_owner_anchor","GET",versionPath(version)+"/anchor",accounts.get(0).token,null,200).data();
        require(anchor.equals(owner),"both_institution_member_apis_return_same_persisted_anchor");
        expect("peer_task_member_can_read_anchor","GET",versionPath(version)+"/anchor",accounts.get(3).token,null,200);
        expect("anonymous_version_read","GET",versionPath(version),null,null,401);
        expect("anonymous_anchor_read","GET",versionPath(version)+"/anchor",null,null,401);
        expect("nonmember_version_read","GET",versionPath(version),accounts.get(4).token,null,403);
        expect("nonmember_anchor_read","GET",versionPath(version)+"/anchor",accounts.get(4).token,null,403);
        expect("org_admin_only_member_cannot_read_version","GET",versionPath(version),accounts.get(5).token,null,403);
        expect("org_admin_only_member_cannot_read_anchor","GET",versionPath(version)+"/anchor",accounts.get(5).token,null,403);
        expect("unknown_version_does_not_leak_existence","GET",versionPath(UUID.randomUUID().toString()),accounts.get(2).token,null,403);
        expect("malformed_version_id","GET",versionPath("not-a-uuid"),accounts.get(2).token,null,400);
    }
    private void duplicateAnchor(String version)throws Exception {
        String submission=anchorId(version);
        String payload=jdbc.queryForObject("SELECT CAST(payload AS CHAR) FROM outbox_event WHERE biz_id=? AND event_type='EVIDENCE_ANCHOR_REQUESTED'",String.class,submission);
        long deadBefore=readyCount(deadQueue);
        rabbit.execute(channel->{
            var properties=new AMQP.BasicProperties.Builder().deliveryMode(2).contentType("application/json").build();
            channel.basicPublish(exchange,"evidence.anchor",true,properties,payload.getBytes(StandardCharsets.UTF_8));
            channel.basicPublish(exchange,"evidence.anchor",true,properties,new byte[]{'{'});
            return null;
        });
        await(()->readyCount(deadQueue)==deadBefore+1&&readyCount(anchorQueue)==0,
                "anchor_duplicate_is_acked_before_invalid_envelope_dead_letter_barrier");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE consumer_code='EVIDENCE_ANCHOR' AND event_id=(SELECT event_id FROM outbox_event WHERE biz_id=? AND event_type='EVIDENCE_ANCHOR_REQUESTED') AND status='DONE'",submission)==1,
                "anchor_duplicate_keeps_one_durable_done_receipt");
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
    private long readyCount(String queue){return rabbit.execute(channel->(long)channel.queueDeclarePassive(queue).getMessageCount());}
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
        faults.operator.loseCommitReply.set(false);
        Set<String> taskIds=new LinkedHashSet<>();
        for(Account account:accounts)taskIds.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,account.id));
        for(String task:taskIds)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));
        Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));
        for(String key:keys)storage.delete(key);
        for(String task:taskIds) {
            jdbc.update("DELETE FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",task,task);
            jdbc.update("DELETE FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task,task);
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

    private Reply request(String name, String method, String path, String token, Object body, boolean raw) throws Exception {
        String trace = "t012-" + runId + "-" + sequence.incrementAndGet();
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
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id", "t012-"+runId+"-%");
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
        return list.stream().map(AnchorFlowProbe::object).toList();
    }

    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) digest.update(bytes);
    }

    private static final class Account {
        final String id,username,password; String token;
        Account(String id,String username,String password){this.id=id;this.username=username;this.password=password;}
        @Override public String toString(){return "PipelineProbeAccount["+id+"]";}
    }
    private record Scope(String requirementId,String executionId,String subtaskId) {}
    private record Reply(int status,Map<String,Object> body,String trace) {
        Map<String,Object> data(){return object(body.get("data"));}
        @Override public String toString(){return "PipelineProbeReply["+status+","+trace+"]";}
    }
    private static final class ProbeFailure extends RuntimeException { ProbeFailure(String name){super(name);} }

    @TestConfiguration(proxyBeanMethods=false)
    @Profile("anchor-probe")
    static class FaultInjection {
        @Bean(destroyMethod="") @Primary
        FaultClients faultClients(@Qualifier("institutionLedgerClients") InstitutionLedgerClients real) {
            return new FaultClients(real.forOrganization(OWNER),new FaultClient(real.forOrganization(OPERATOR)),
                    real.mspId(OWNER),real.mspId(OPERATOR));
        }
    }
    static final class FaultClients extends InstitutionLedgerClients {
        final FaultClient operator;
        FaultClients(EvidenceLedgerClient owner,FaultClient operator,String ownerMsp,String operatorMsp) {
            super(owner,operator,ownerMsp,operatorMsp);this.operator=operator;
        }
    }
    static final class FaultClient implements EvidenceLedgerClient {
        private final EvidenceLedgerClient delegate;
        final AtomicBoolean loseCommitReply=new AtomicBoolean();
        final AtomicInteger injected=new AtomicInteger(),prepares=new AtomicInteger(),submits=new AtomicInteger();
        final AtomicReference<CommitOutcome> lostOutcome=new AtomicReference<>();
        FaultClient(EvidenceLedgerClient delegate){this.delegate=delegate;}
        @Override public PreparedRegistration prepareRegistration(String evidenceId,int version,String hash)throws EndorseException {
            prepares.incrementAndGet();return delegate.prepareRegistration(evidenceId,version,hash);
        }
        @Override public CommitCheckpoint submitPrepared(PreparedRegistration prepared)throws SubmitException {
            submits.incrementAndGet();return delegate.submitPrepared(prepared);
        }
        @Override public CommitOutcome queryCommit(CommitCheckpoint checkpoint)throws CommitStatusException {
            CommitOutcome actual=delegate.queryCommit(checkpoint);
            if(loseCommitReply.compareAndSet(true,false)) {
                lostOutcome.set(actual);injected.incrementAndGet();
                throw Status.DEADLINE_EXCEEDED.withDescription("Controlled probe response loss after real commit query").asRuntimeException();
            }
            return actual;
        }
        @Override public EvidenceQuery queryEvidence(String evidence,int version)throws GatewayException{return delegate.queryEvidence(evidence,version);}
        @Override public void close(){/* Original application registry owns the actual Gateway lifecycle. */}
    }

}
