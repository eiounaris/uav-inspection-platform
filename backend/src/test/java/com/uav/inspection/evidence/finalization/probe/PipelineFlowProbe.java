package com.uav.inspection.evidence.finalization.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.evidence.storage.ObjectStorage;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import com.uav.inspection.messaging.OutboxPublisher;
import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessageDeliveryMode;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/** Explicit real HTTP/MySQL/Garage/RabbitMQ probe. Never runs as an ordinary unit test. */
public final class PipelineFlowProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
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
    private FaultStorage faults;
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
    private FinalizationProcessor processor;
    private RabbitTemplate rabbit;
    private RabbitAdmin rabbitAdmin;
    private SimpleMessageListenerContainer container;

    private PipelineFlowProbe(Path reportPath) {
        this.reportPath = reportPath.toAbsolutePath().normalize();
        report.put("formatVersion",1);
        report.put("runId",runId);
        report.put("startedAt",Instant.now().toString());
        report.put("passed",false);
        report.put("checks",checks);
        report.put("requests",requests);
    }
    public static void main(String[] args) {
        if(args.length!=1) { System.err.println("Usage: PipelineFlowProbe <reportPath>"); System.exit(2); return; }
        var probe=new PipelineFlowProbe(Path.of(args[0]));
        try {
            probe.execute(); probe.writeReport();
            System.out.println("[PASS] T011 real HTTP/MySQL/Garage/RabbitMQ pipeline probe: "+probe.reportPath);
        } catch(Exception failure) {
            probe.report.put("passed",false);
            probe.report.put("failureType",failure.getClass().getSimpleName());
            if(failure instanceof ProbeFailure) probe.report.put("failedCheck",failure.getMessage());
            try { probe.writeReport(); } catch(Exception ignored) { System.err.println("Could not write safe pipeline report"); }
            System.err.println("[FAIL] T011 pipeline probe ("+failure.getClass().getSimpleName()+"); inspect safe report metadata.");
            System.exit(1);
        }
    }
    private void execute() throws Exception {
        report.put("stage","application_startup");
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,FaultInjection.class)
                .web(WebApplicationType.SERVLET).profiles("local","database","storage","messaging","pipeline-probe")
                .logStartupInfo(false).run("--server.port=0", "--logging.level.com.uav.inspection=INFO",
                        "--inspection.messaging.exchange="+exchange,
                        "--inspection.messaging.finalize-queue="+finalizeQueue,
                        "--inspection.messaging.anchor-queue="+anchorQueue,
                        "--inspection.messaging.dead-letter-exchange="+deadExchange,
                        "--inspection.messaging.dead-letter-queue="+deadQueue,
                        "--inspection.messaging.publisher-enabled=false",
                        "--inspection.messaging.recovery-enabled=false")) {
            jdbc=context.getBean(JdbcTemplate.class); dataSource=context.getBean(DataSource.class);
            storage=context.getBean(ObjectStorage.class); faults=context.getBean(FaultStorage.class);
            s3=context.getBean(S3Client.class); rabbit=context.getBean(RabbitTemplate.class);
            rabbitAdmin=new RabbitAdmin(rabbit.getConnectionFactory());
            container=context.getBean(SimpleMessageListenerContainer.class);
            publisher=context.getBean(OutboxPublisher.class); processor=context.getBean(FinalizationProcessor.class);
            storageEndpoint=context.getEnvironment().getRequiredProperty("inspection.storage.endpoint");
            bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");
            baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            try(Connection connection=dataSource.getConnection()) {
                require("MySQL".equals(connection.getMetaData().getDatabaseProductName()),"real_mysql_required");
            }
            require(count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT'")==0
                    &&count("SELECT COUNT(*) FROM upload_session WHERE status='VERIFYING'")==0,
                    "no_preexisting_pending_work_before_isolated_pipeline_probe");
            byte[] baseline=businessSnapshot(); report.put("businessCountsBefore",businessCounts());
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                client=actual;
                try {
                    report.put("stage","isolated_fixture_setup"); createFixtures(); createRunningTask();
                    report.put("stage","real_pipeline_checks"); verifyPipeline(); verifyAuditSafety();
                } finally {
                    report.put("cleanupAttempted",true); container.stop(); cleanupFixtures(); cleanupTopology();
                    require(MessageDigest.isEqual(baseline,businessSnapshot()),"original_fourteen_business_tables_unchanged");
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
        for (Account account : accounts) {
            account.token = string(expect("fixture_login", "POST", API + "/auth/login", null,
                    Map.of("username", account.username, "password", account.password), 200).data().get("accessToken"));
            secrets.add(account.token);
        }
        droneId = UUID.randomUUID().toString();
        droneCode = "T011-" + runId.substring(0, 12);
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
                "t011-" + runId.substring(0, 12) + "-" + suffix, password);
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
                Map.of("name", "T011-" + runId, "scope", "isolated real upload verification", "requirements", List.of(
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

    private void verifyPipeline() throws Exception {
        Account uploader=accounts.get(2), peer=accounts.get(3), outsider=accounts.get(4);
        byte[] tiny=png(0x00aa11);
        String incomplete=createSession("incomplete_session",uploader,
                createBody(UUID.randomUUID().toString(),firstScope,"incomplete.png","image/png",tiny.length));
        expect("anonymous_complete","POST",completePath(incomplete),null,null,401);
        expect("other_owner_complete","POST",completePath(incomplete),peer.token,null,403);
        expect("nonmember_complete","POST",completePath(incomplete),outsider.token,null,403);
        expect("unknown_session_complete","POST",completePath(UUID.randomUUID().toString()),uploader.token,null,403);
        Reply missing=request("incomplete_complete","POST",completePath(incomplete),uploader.token,null,false);
        require(missing.status()==409,"incomplete_parts_cannot_freeze_session");
        require("UPLOADING".equals(status(incomplete))&&outboxCount(incomplete)==0,
                "incomplete_completion_leaves_no_outbox_or_frozen_state");

        int configuredPartSize=jdbc.queryForObject("SELECT part_size FROM upload_session WHERE id=?",Integer.class,incomplete);
        require(configuredPartSize>=8&&configuredPartSize<32*1024*1024,"configured_part_size_bounded_for_real_probe");
        byte[] full=padPng(tiny,configuredPartSize+tiny.length+32);
        String first=uploadFile("full_two_part_png",firstScope,full,"image/png","inspection.png");
        long nextBefore=nextVersion(firstScope);
        List<Reply> completions=race("parallel_complete","POST",completePath(first),uploader.token,null,null,false);
        require(completions.stream().allMatch(r->r.status()==202),"parallel_complete_returns_accepted_twice");
        require(completions.stream().allMatch(r->"VERIFYING".equals(r.data().get("processingStatus"))
                &&r.data().get("versionId")==null),"accepted_complete_returns_frozen_progress_without_fake_version");
        require("VERIFYING".equals(status(first))&&outboxCount(first)==1&&versionCount(first)==0,
                "completion_atomically_freezes_one_session_and_inserts_one_outbox");
        expect("repeat_pending_complete","POST",completePath(first),uploader.token,null,202);
        part("frozen_session_rejects_further_parts",first,1,uploader.token,
                tiny,409,"UPLOAD_NOT_WRITABLE");
        verifyPendingEvent(first);
        publisher.publishBatch();
        await(()->"COMPLETED".equals(status(first)),"real_rabbit_listener_finalizes_complete_upload");
        String version1=verifyVersion(first,full,nextBefore);
        String anchor1=anchorId(version1);
        require(nextVersion(firstScope)==nextBefore+1,"version_counter_increments_once_after_concurrent_complete");
        expect("repeat_completed_complete","POST",completePath(first),uploader.token,null,202);
        require(version1.equals(progress(first,uploader).get("versionId")),"progress_exposes_original_fixed_version_id");
        publisher.publishBatch();
        await(()->"SENT".equals(outboxStatus(anchor1,"EVIDENCE_ANCHOR_REQUESTED")),"anchor_event_is_confirmed_and_marked_sent");
        verifyAnchorQueue(anchor1);
        duplicateFinalize(first);
        require(versionCount(first)==1&&nextVersion(firstScope)==nextBefore+1,
                "broker_duplicate_cannot_create_second_version_or_advance_counter");
        require("PENDING".equals(jdbc.queryForObject("SELECT status FROM chain_submission WHERE version_id=?",String.class,version1)),
                "broker_publication_does_not_fake_fabric_confirmation");

        // Publish an anchor event while its route is temporarily absent: a broker ACK alone is insufficient.
        String second=uploadFile("second_fixed_version",firstScope,tiny,"image/png","second.png");
        jdbc.update("UPDATE inspection_task SET status='WAITING_ACCEPTANCE' WHERE id=?",taskId);
        expect("complete_waiting_task_upload","POST",completePath(second),uploader.token,null,202);
        rabbit.execute(channel->{channel.queueUnbind(anchorQueue,exchange,"evidence.anchor");return null;});
        publisher.publishBatch();
        await(()->"COMPLETED".equals(status(second)),"second_upload_has_its_own_immutable_version");
        String version2=verifyVersion(second,tiny,nextBefore+1);
        String anchor2=anchorId(version2);
        require("RUNNING".equals(jdbc.queryForObject("SELECT status FROM inspection_task WHERE id=?",String.class,taskId)),
                "new_current_batch_version_returns_waiting_task_to_running");
        try {
            publisher.publishBatch();
            require("PENDING".equals(outboxStatus(anchor2,"EVIDENCE_ANCHOR_REQUESTED")),
                    "mandatory_unroutable_anchor_is_not_marked_sent");
            require(jdbc.queryForObject("SELECT attempt_count FROM outbox_event WHERE biz_id=? AND event_type='EVIDENCE_ANCHOR_REQUESTED'",
                    Integer.class,anchor2)>0,"returned_message_persists_retry_attempt");
        } finally {rabbit.execute(channel->{channel.queueBind(anchorQueue,exchange,"evidence.anchor");return null;});}
        jdbc.update("UPDATE outbox_event SET next_send_at=UTC_TIMESTAMP(3) WHERE biz_id=? AND event_type='EVIDENCE_ANCHOR_REQUESTED'",anchor2);
        publisher.publishBatch();
        await(()->"SENT".equals(outboxStatus(anchor2,"EVIDENCE_ANCHOR_REQUESTED")),"restored_route_retries_original_anchor_event");

        String corrupted=uploadFile("corrupted_committed_part",secondScope,tiny,"image/png","corrupted.png");
        String corruptKey=jdbc.queryForObject("SELECT object_key FROM upload_part WHERE session_id=? AND part_no=1",String.class,corrupted);
        byte[] changed=tiny.clone();changed[changed.length/2]^=1;
        putPrivate(corruptKey,changed,"image/png");
        expect("complete_corrupted_part","POST",completePath(corrupted),uploader.token,null,202);
        publisher.publishBatch();
        await(()->"FAILED".equals(status(corrupted)),"worker_rehash_detects_changed_private_object_bytes");
        require(versionCount(corrupted)==0&&"PART_HASH_MISMATCH".equals(lastError(corrupted)),
                "corrupted_part_never_creates_fixed_version_or_anchor");
        require(outboxCount(corrupted)==1,"corrupt_upload_only_has_its_original_finalize_event");

        byte[] invalid=Arrays.copyOf(tiny,Math.min(tiny.length,32));
        String invalidFormat=uploadFile("signature_only_invalid_container",secondScope,invalid,"image/png","truncated.png");
        expect("complete_invalid_container","POST",completePath(invalidFormat),uploader.token,null,202);
        publisher.publishBatch();
        await(()->"FAILED".equals(status(invalidFormat)),"full_file_validation_rejects_truncated_png_after_valid_signature");
        require(versionCount(invalidFormat)==0,"invalid_file_container_creates_no_fixed_version");

        String retried=uploadFile("retry_stream_failure",secondScope,tiny,"image/png","retry.png");
        String retryKey=jdbc.queryForObject("SELECT object_key FROM upload_part WHERE session_id=? AND part_no=1",String.class,retried);
        faults.failReadOnce(retryKey);
        expect("complete_transient_fault","POST",completePath(retried),uploader.token,null,202);
        publisher.publishBatch();
        await(()->count("SELECT COUNT(*) FROM upload_session WHERE id=? AND status='VERIFYING' AND retry_count=1 AND next_action_at IS NOT NULL AND lease_token IS NULL",retried)==1,
                "interrupted_live_garage_stream_persists_retry_checkpoint");
        require(faults.injected.get()==1&&versionCount(retried)==0,"controlled_stream_failure_has_no_partial_version");
        String retryEvent=eventId(retried);
        require(count("SELECT COUNT(*) FROM consumed_event WHERE consumer_code='FILE_FINALIZE' AND event_id=? AND status='DONE'",retryEvent)==1,
                "manual_ack_follows_durable_retry_handoff");
        duplicateFinalize(retried);
        require(versionCount(retried)==0,"duplicate_delivery_does_not_bypass_retry_checkpoint");
        jdbc.update("UPDATE upload_session SET next_action_at=UTC_TIMESTAMP(3) WHERE id=?",retried);
        processor.recoverDue();
        await(()->"COMPLETED".equals(status(retried)),"durable_retry_scanner_recovers_original_upload");
        verifyVersion(retried,tiny,1);
        publisher.publishBatch();
        await(()->readyCount(finalizeQueue)==0,"manual_ack_listener_drains_finalize_queue");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE consumer_code='FILE_FINALIZE' AND event_id IN (SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?)) AND status<>'DONE'",taskId)==0,
                "all_owned_finalization_receipts_are_durable_done");
        report.put("parallelCompleteStatuses",completions.stream().map(Reply::status).toList());
        report.put("finalizedVersionCount",count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",taskId));
        report.put("failedUploadCount",count("SELECT COUNT(*) FROM upload_session WHERE task_id=? AND status='FAILED'",taskId));
        report.put("controlledLiveStorageStreamFaults",faults.injected.get());
        report.put("brokerMandatoryReturnRetried",true);report.put("manualAckDuplicateReplayIdempotent",true);
        report.put("immutableBytesAndHashVerifiedAgainstGarage",true);
        report.put("fabricSubmissionsRemainPending",true);
    }

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
    private String verifyVersion(String upload,byte[] expected,long expectedNo)throws Exception {
        List<Map<String,Object>> versions=jdbc.queryForList("SELECT * FROM evidence_version WHERE upload_session_id=?",upload);
        require(versions.size()==1,"one_fixed_version_per_upload_session");
        Map<String,Object> row=versions.getFirst();String version=string(row.get("id"));
        require(number(row.get("version_no"))==expectedNo&&number(row.get("size"))==expected.length
                &&sha256(expected).equals(row.get("sha256")),"fixed_version_number_size_full_sha256_match_bytes");
        require("PENDING".equals(row.get("review_status"))&&accounts.get(2).id.equals(row.get("uploader_user_id"))
                &&row.get("uploader_drone_id")==null&&OPERATOR.equals(row.get("submitter_org_id")),
                "fixed_version_retains_correct_user_provenance_and_review_state");
        try(InputStream stream=storage.open(string(row.get("object_key")))) {
            require(MessageDigest.isEqual(expected,stream.readAllBytes()),"final_private_garage_object_is_exact_ordered_full_file");
        }
        require(count("SELECT COUNT(*) FROM chain_submission WHERE version_id=? AND status='PENDING' AND confirmed_tx_id IS NULL",version)==1,
                "atomic_finalization_creates_one_pending_chain_submission");
        require(count("SELECT COUNT(*) FROM outbox_event WHERE biz_id=? AND event_type='EVIDENCE_ANCHOR_REQUESTED'",anchorId(version))==1,
                "atomic_finalization_creates_one_anchor_outbox");
        return version;
    }
    private void verifyPendingEvent(String upload) throws Exception {
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM outbox_event WHERE biz_id=? AND event_type='FILE_FINALIZE_REQUESTED'",upload);
        require("PENDING".equals(row.get("status"))&&number(row.get("dispatch_no"))==0,"completion_outbox_is_pending_dispatch_zero");
        Map<String,Object> payload=JSON.readValue(string(row.get("payload")),Map.class);
        require(payload.keySet().equals(Set.of("eventId","eventType","bizId","traceId"))
                &&upload.equals(payload.get("bizId"))&&row.get("event_id").equals(payload.get("eventId"))
                &&"FILE_FINALIZE_REQUESTED".equals(payload.get("eventType")),"outbox_payload_is_minimal_safe_event_envelope");
    }
    private void verifyAnchorQueue(String version)throws Exception {
        await(()->readyCount(anchorQueue)>0,"real_broker_holds_unconsumed_anchor_message_for_t012");
        rabbit.execute(channel->{
            var delivery=channel.basicGet(anchorQueue,false);
            require(delivery!=null,"anchor_message_is_observable_in_real_broker");
            try {
                Map<String,Object> payload=JSON.readValue(delivery.getBody(),Map.class);
                require(payload.keySet().equals(Set.of("eventId","eventType","bizId","traceId"))
                        &&"EVIDENCE_ANCHOR_REQUESTED".equals(payload.get("eventType"))&&version.equals(payload.get("bizId")),
                        "broker_anchor_payload_has_no_file_bytes_keys_or_identity_secrets");
                require(Integer.valueOf(2).equals(delivery.getProps().getDeliveryMode()),"broker_message_is_persistent");
            }finally{channel.basicNack(delivery.getEnvelope().getDeliveryTag(),false,true);}
            return null;
        });
    }
    private void duplicateFinalize(String upload)throws Exception {
        String payload=jdbc.queryForObject("SELECT CAST(payload AS CHAR) FROM outbox_event WHERE biz_id=? AND event_type='FILE_FINALIZE_REQUESTED'",String.class,upload);
        long deadBefore=readyCount(deadQueue);
        rabbit.execute(channel->{
            var properties=new AMQP.BasicProperties.Builder().deliveryMode(2).contentType("application/json").build();
            channel.basicPublish(exchange,"file.finalize",true,properties,payload.getBytes(StandardCharsets.UTF_8));
            // Same publishing channel, one consumer and prefetch=1 make the rejected marker a processing
            // barrier: it cannot reach the dead-letter queue before the duplicate has been handled.
            channel.basicPublish(exchange,"file.finalize",true,properties,new byte[]{'{'});
            return null;
        });
        String event=eventId(upload);
        await(()->readyCount(deadQueue)==deadBefore+1&&readyCount(finalizeQueue)==0,
                "duplicate_is_acked_before_subsequent_invalid_envelope_is_dead_lettered");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE consumer_code='FILE_FINALIZE' AND event_id=? AND status='DONE'",event)==1,
                "manual_ack_duplicate_delivery_keeps_one_durable_receipt");
    }
    private void putPrivate(String key,byte[] bytes,String type)throws Exception {
        Path file=Files.createTempFile(reportPath.getParent(),".pipeline-data-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{Files.write(file,bytes);storage.put(key,file,type);}finally{Files.deleteIfExists(file);}
    }
    private String status(String upload){return jdbc.queryForObject("SELECT status FROM upload_session WHERE id=?",String.class,upload);}
    private String lastError(String upload){return jdbc.queryForObject("SELECT last_error FROM upload_session WHERE id=?",String.class,upload);}
    private String outboxStatus(String biz,String type){return jdbc.queryForObject("SELECT status FROM outbox_event WHERE biz_id=? AND event_type=?",String.class,biz,type);}
    private String anchorId(String version){return jdbc.queryForObject("SELECT id FROM chain_submission WHERE version_id=?",String.class,version);}
    private String eventId(String upload){return jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE biz_id=? AND event_type='FILE_FINALIZE_REQUESTED'",String.class,upload);}
    private long nextVersion(Scope scope){return jdbc.queryForObject("SELECT next_version_no FROM material_requirement WHERE id=?",Long.class,scope.requirementId());}
    private long versionCount(String upload){return count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=?",upload);}
    private long outboxCount(String upload){return count("SELECT COUNT(*) FROM outbox_event WHERE biz_id=?",upload);}
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
        faults.armed.set(false);
        Set<String> taskIds=new LinkedHashSet<>();
        for(Account account:accounts)taskIds.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,account.id));
        for(String task:taskIds)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));
        Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));
        for(String key:keys)storage.delete(key);
        for(String task:taskIds) {
            jdbc.update("DELETE FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",task,task);
            jdbc.update("DELETE FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task,task);
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
        String trace = "t011-" + runId + "-" + sequence.incrementAndGet();
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
        Map<String, Object> envelope = JSON.readValue(response.body(), Map.class);
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
                "t011-" + runId + "-%", action);
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
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id", "t011-"+runId+"-%");
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

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new ProbeFailure("expected_json_object");
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> list)) throw new ProbeFailure("expected_json_array");
        return list.stream().map(PipelineFlowProbe::object).toList();
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
    @Profile("pipeline-probe")
    static class FaultInjection {
        @Bean @Primary
        FaultStorage faultStorage(@Qualifier("objectStorage") ObjectStorage delegate) {return new FaultStorage(delegate);}
    }
    static final class FaultStorage implements ObjectStorage {
        private final ObjectStorage delegate;
        private volatile String failKey;
        private final AtomicBoolean armed=new AtomicBoolean(false);
        private final AtomicInteger injected=new AtomicInteger();
        FaultStorage(ObjectStorage delegate){this.delegate=delegate;}
        void failReadOnce(String key){failKey=key;armed.set(true);}
        @Override public void put(String key,Path file,String type){delegate.put(key,file,type);}
        @Override public void delete(String key){delegate.delete(key);}
        @Override public InputStream open(String key){
            InputStream live=delegate.open(key);
            if(!key.equals(failKey)||!armed.compareAndSet(true,false))return live;
            return new FilterInputStream(live){
                @Override public int read(byte[] bytes,int offset,int length)throws IOException {
                    int result=super.read(bytes,offset,length); injected.incrementAndGet();
                    throw new IOException("Controlled probe stream interruption");
                }
                @Override public int read()throws IOException {
                    int result=super.read(); injected.incrementAndGet();
                    throw new IOException("Controlled probe stream interruption");
                }
            };
        }
    }
}
