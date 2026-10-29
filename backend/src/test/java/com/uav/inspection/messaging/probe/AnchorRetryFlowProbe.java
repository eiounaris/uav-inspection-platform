package com.uav.inspection.messaging.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.InstitutionLedgerClients;
import com.uav.inspection.blockchain.anchor.AnchorProcessor;
import com.uav.inspection.blockchain.anchor.AnchorTransactions;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper;
import com.uav.inspection.common.lease.ProcessingLeaseKeeper;
import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.messaging.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import io.grpc.Status;

/** Real T024 manual recovery, current task permission, immutable checkpoints and permanent failures. */
public final class AnchorRetryFlowProbe {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() {};
    private static final String API="/api/v1",OWNER="00000000-0000-4000-8000-000000000001",OPERATOR="00000000-0000-4000-8000-000000000002";
    private static final Map<String,String> TABLE_ORDER=new LinkedHashMap<>();
    static {
        TABLE_ORDER.put("organization","id");TABLE_ORDER.put("app_user","id");TABLE_ORDER.put("inspection_task","id");TABLE_ORDER.put("task_member","task_id,user_id");TABLE_ORDER.put("drone","id");TABLE_ORDER.put("inspection_subtask","id");TABLE_ORDER.put("material_requirement","id");TABLE_ORDER.put("task_execution","id");TABLE_ORDER.put("upload_session","id");TABLE_ORDER.put("upload_part","session_id,part_no");TABLE_ORDER.put("evidence_version","id");TABLE_ORDER.put("outbox_event","event_id");TABLE_ORDER.put("consumed_event","consumer_code,event_id");TABLE_ORDER.put("chain_submission","id");TABLE_ORDER.put("chain_attempt","id");TABLE_ORDER.put("verification_record","id");TABLE_ORDER.put("device_event","id");TABLE_ORDER.put("execution_adjustment","id");TABLE_ORDER.put("issue","id");TABLE_ORDER.put("review_record","id");TABLE_ORDER.put("access_grant","id");TABLE_ORDER.put("message_rejection","id");TABLE_ORDER.put("anchor_retry_request","id");
    }

    private final String runId=UUID.randomUUID().toString(),sharedTrace="t024-"+runId;
    private final String exchange="inspection.anchor-retry.probe."+runId,finalizeQueue=exchange+".finalize",
            anchorQueue=exchange+".anchor",deadExchange=exchange+".dead.events",deadQueue=exchange+".dead";
    private final Path reportPath;
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<Map<String,Object>> checks=Collections.synchronizedList(new ArrayList<>()),
            requests=Collections.synchronizedList(new ArrayList<>()),ledgerRecords=new ArrayList<>();
    private final List<Account> accounts=new ArrayList<>();
    private final List<String> secrets=new CopyOnWriteArrayList<>();
    private final Set<String> uploadIds=new LinkedHashSet<>(),taskIds=new LinkedHashSet<>();
    private final Map<String,String> expectedDigests=new LinkedHashMap<>();
    private JdbcTemplate jdbc;private DataSource dataSource;private S3Client s3;private ObjectStorage storage;
    private InstitutionLedgerClients realClients;private RabbitTemplate rabbit;private OutboxPublisher publisher;
    private FinalizationProcessor processor;private AnchorProcessor anchorProcessor;private AnchorTransactions anchorPhases;
    private AnchorMapper anchors;private FaultPlan faults;private HttpClient client;
    private SimpleMessageListenerContainer finalizeContainer,anchorContainer;
    private String baseUrl,bucket,taskId,deviceId,deviceToken;private Scope scope;private byte[] baseline;

    private AnchorRetryFlowProbe(Path output){reportPath=output.toAbsolutePath().normalize();report.put("formatVersion",1);report.put("runId",runId);report.put("startedAt",Instant.now().toString());report.put("passed",false);report.put("checks",checks);report.put("requests",requests);report.put("ledgerRecordsRetained",ledgerRecords);}
    public static void main(String[] args){
        if(args.length!=1){System.err.println("Usage: AnchorRetryFlowProbe <private-report-json>");System.exit(2);return;}
        AnchorRetryFlowProbe probe=new AnchorRetryFlowProbe(Path.of(args[0]));
        try{probe.execute();probe.writeReport();System.out.println("[PASS] T024 anchor retry flow report: "+probe.reportPath);}
        catch(Exception failure){probe.report.put("passed",false);probe.report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof ProbeFailure)probe.report.put("failedCheck",failure.getMessage());try{probe.writeReport();}catch(Exception ignored){}System.err.println("[FAIL] T024 anchor retry probe; inspect private safe report");System.exit(1);}
    }
    private void execute()throws Exception{
        openDatabase();requireNoForeignWork();baseline=businessSnapshot();report.put("originalBusinessSnapshotSha256",HexFormat.of().formatHex(baseline));report.put("businessCountsBefore",businessCounts());
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,FaultConfiguration.class)
                .profiles("local","database","storage","messaging","ledger","anchor-retry-probe")
                .web(WebApplicationType.SERVLET).logStartupInfo(false).run("--server.port=0",
                "--inspection.messaging.exchange="+exchange,"--inspection.messaging.finalize-queue="+finalizeQueue,
                "--inspection.messaging.anchor-queue="+anchorQueue,"--inspection.messaging.dead-letter-exchange="+deadExchange,
                "--inspection.messaging.dead-letter-queue="+deadQueue,"--inspection.messaging.publisher-enabled=true",
                "--inspection.messaging.recovery-enabled=false","--inspection.ledger.recovery-enabled=false")){
            jdbc=context.getBean(JdbcTemplate.class);dataSource=context.getBean(DataSource.class);s3=context.getBean(S3Client.class);
            storage=context.getBean(ObjectStorage.class);context.getBean(InstitutionLedgerClients.class);
            realClients=context.getBean("institutionLedgerClients",InstitutionLedgerClients.class);
            rabbit=context.getBean(RabbitTemplate.class);publisher=context.getBean(OutboxPublisher.class);
            processor=context.getBean(FinalizationProcessor.class);anchorProcessor=context.getBean(AnchorProcessor.class);
            anchorPhases=context.getBean(AnchorTransactions.class);anchors=context.getBean(AnchorMapper.class);faults=context.getBean(FaultPlan.class);
            finalizeContainer=context.getBean("finalizationContainer",SimpleMessageListenerContainer.class);anchorContainer=context.getBean("anchorContainer",SimpleMessageListenerContainer.class);
            bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            require(!finalizeContainer.isRunning()&&!anchorContainer.isRunning(),"own_consumers_paused_for_precise_manual_retry_boundaries");
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
                client=actual;
                try{
                    createFixtures();createForeignTask();scope=createTask(true);taskId=scope.task;
                    Fixed first=createFixed(0x246f91);verifyCommitCheckpointRecheck(first);verifyGrantCannotRetry(first);completeFirstTask(first);
                    scope=createTask(false);taskId=scope.task;
                    Fixed second=createFixed(0x67a931);verifyOriginalTransactionRecovery(second);
                    Fixed denied=createFixed(0xc75e26);verifyPermanentPermissionRejection(denied);
                    Fixed conflict=createFixed(0x9b3487);verifyRealHashConflict(conflict);
                    publisher.publishBatch();await(()->ownedPendingOutbox()==0,"all_owned_events_published_with_real_confirms");
                    require(count("SELECT COUNT(*) FROM anchor_retry_request WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id IN (SELECT id FROM inspection_task WHERE created_by=?)))",owner().id)==2,"two_manual_intents_have_exactly_two_request_rows");
                    require(ledgerRecords.size()==3,"two_matching_records_and_one_real_conflicting_record_are_retained");
                    verifyAuditSafety();report.put("confirmedVersions",2);report.put("permanentFailedVersions",2);report.put("realLedgerRegistrations",3);report.put("manualRequestsAccepted",2);
                    report.put("commitCheckpointReused",true);report.put("originalPreparedTransactionReused",true);report.put("manualIdempotencyAndPendingGateVerified",true);report.put("currentTaskPermissionVerified",true);
                }finally{
                    faults.clear();context.getBean(ThreadPoolTaskScheduler.class).shutdown();context.getBean(BusinessRecoveryDispatcher.class).close();context.getBean(ProcessingLeaseKeeper.class).shutdown();finalizeContainer.stop();anchorContainer.stop();
                    report.put("cleanupAttempted",true);snapshotOwnedAttemptsBeforeCleanup();cleanupOwnedQueues();cleanupFixtures();cleanupTopology();
                    require(MessageDigest.isEqual(baseline,businessSnapshot()),"all_original_twenty_three_table_fields_and_binary_checkpoints_preserved");report.put("originalBusinessDataUnchanged",true);report.put("businessCountsAfter",businessCounts());
                }
            }
        }
        report.put("stage","complete");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }
    private void requireNoForeignWork(){
        require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version='13' AND success=1")==1,"thirteen_existing_migrations_present");
        require(count("SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','SENDING')")==0,"no_foreign_pending_outbox_before_workers");
        require(count("SELECT COUNT(*) FROM upload_session WHERE status='VERIFYING'")==0,"no_foreign_processing_upload_before_workers");
        require(count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1 AND next_action_at IS NOT NULL)")==0,"no_foreign_pending_anchor_before_workers");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'")==0,"no_foreign_unfinished_consumption_before_workers");
    }
    private Fixed createFixed(int color)throws Exception{
        byte[] bytes=png(color);String upload=string(expect("create_upload","POST",API+"/upload-sessions",uploader(),Map.of("clientRequestId",UUID.randomUUID().toString(),"requirementId",scope.requirement,"executionId",scope.execution,"filename","inspection.png","contentType","image/png","size",bytes.length),200).data().get("uploadId"));
        uploadIds.add(upload);expectedDigests.put(upload,sha256(bytes));assertCode(requestHeaders("store_real_part","PUT",API+"/upload-sessions/"+upload+"/parts/1",Map.of("Authorization","Bearer "+uploader().token),bytes,"application/octet-stream"),200,"OK","store_real_part");expect("request_finalization","POST",API+"/upload-sessions/"+upload+"/complete",uploader(),null,202);
        processor.recoverDue();var row=jdbc.queryForMap("SELECT v.*,s.id AS submission_id FROM evidence_version v JOIN chain_submission s ON s.version_id=v.id WHERE upload_session_id=?",upload);
        require(expectedDigests.get(upload).equals(row.get("sha256")),"real_finalizer_calculates_original_file_hash");return new Fixed(upload,string(row.get("id")),string(row.get("submission_id")),string(row.get("evidence_id")),(int)number(row.get("version_no")),string(row.get("sha256")));
    }
    private void verifyCommitCheckpointRecheck(Fixed fixed)throws Exception{
        report.put("stage","real_commit_reply_loss_and_manual_recheck");faults.dropCommitReply.set(true);anchorProcessor.process(event(fixed.submission));
        requireUnknown(fixed,true);var original=attempt(fixed);String tx=string(original.get("tx_id")),transactionHash=sha256(blob(original.get("transaction_bytes"))),commitHash=sha256(blob(original.get("commit_bytes")));
        require(faults.commitRepliesDropped.get()==1,"one_actual_valid_fabric_commit_reply_is_deliberately_lost");
        verifyInitialPermissionFailures(fixed);
        await(()->count("SELECT COUNT(*) FROM chain_submission WHERE id=? AND next_action_at<=UTC_TIMESTAMP(3)",fixed.submission)==1,"original_unknown_plan_naturally_becomes_due");
        var leased=anchorPhases.claim(event(fixed.submission),true);require(leased!=null,"real_claim_before_active_lease_manual_rejection");
        retryExpect("active_worker_prevents_manual_recheck",fixed,admin(),body(UUID.randomUUID().toString(),"等待活动处理租约"),409,"VERSION_CONFLICT");
        require(anchorPhases.checkpoint(leased,leased.attempt(),"UNKNOWN",true,"RECHECK_WAIT",true),"real_active_worker_releases_durable_unknown_plan");
        String key=UUID.randomUUID().toString();var payload=body(key,"  检查原确认请求  ");
        List<Reply> pair=race(fixed,admin(),payload,payload);require(pair.stream().allMatch(r->r.status==202),"concurrent_same_request_replays_one_accepted_intent");Map<String,Object> receipt=pair.getFirst().data();require(receipt.equals(pair.getLast().data()),"idempotent_replays_return_same_safe_receipt");assertReceipt(fixed,receipt,1);
        require(count("SELECT COUNT(*) FROM anchor_retry_request WHERE submission_id=?",fixed.submission)==1&&count("SELECT COUNT(*) FROM outbox_event WHERE biz_id=? AND dispatch_no=1",fixed.submission)==1,"concurrent_idempotency_creates_single_request_and_dispatch");
        retryExpect("changed_reason_conflicts_with_existing_request",fixed,admin(),body(key,"不同的理由"),409,"IDEMPOTENCY_CONFLICT");
        retryExpect("second_manual_intent_waits_for_first",fixed,admin(),body(UUID.randomUUID().toString(),"另一次检查"),409,"VERSION_CONFLICT");
        verifyCurrentIdentityChanges(fixed);
        require(count("SELECT COUNT(*) FROM chain_submission WHERE id=? AND status='UNKNOWN' AND next_action_at IS NOT NULL AND lease_token IS NULL",fixed.submission)==1,"accepted_recheck_only_schedules_unknown_without_forcing_success");
        require(string(anchors.due().stream().filter(e->e.bizId().equals(fixed.submission)).findFirst().orElseThrow().eventId()).equals(receipt.get("eventId")),"business_scanner_selects_latest_manual_dispatch");
        anchorProcessor.recoverDue();requireConfirmed(fixed,tx,transactionHash,commitHash);retainLedgerEvidence(fixed);
        retryExpect("same_manual_request_replays_after_confirmation",fixed,admin(),body(key,"检查原确认请求"),202,"OK");retryExpect("new_request_cannot_rewrite_confirmed_result",fixed,admin(),body(UUID.randomUUID().toString(),"不能重写确认结果"),409,"VERSION_CONFLICT");
        require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=?",fixed.submission)==1,"commit_recheck_does_not_prepare_second_attempt");report.put("realCommitReplyLosses",faults.commitRepliesDropped.get());
    }
    private void verifyInitialPermissionFailures(Fixed fixed)throws Exception{
        Map<String,Object> b=body(UUID.randomUUID().toString(),"权限检查");retryExpect("anonymous_retry_unauthorized",fixed,null,b,401,"UNAUTHORIZED");retryExpect("owner_organization_cannot_retry",fixed,owner(),b,403,"FORBIDDEN");retryExpect("operator_uploader_cannot_retry",fixed,uploader(),b,403,"FORBIDDEN");retryExpect("other_task_admin_cannot_retry",fixed,accounts.get(3),b,403,"FORBIDDEN");
        assertCode(requestHeaders("device_credential_cannot_retry","POST",retryPath(fixed),Map.of("Authorization","Bearer "+deviceToken),JSON.writeValueAsBytes(b),"application/json"),401,"UNAUTHORIZED","device_credential_cannot_retry");
        assertCode(requestHeaders("invalid_token_cannot_retry","POST",retryPath(fixed),Map.of("Authorization","Bearer invalid-token"),JSON.writeValueAsBytes(b),"application/json"),401,"UNAUTHORIZED","invalid_token_cannot_retry");
        retryExpect("blank_retry_reason_rejected",fixed,admin(),body(UUID.randomUUID().toString()," \t\u3000"),400,"INVALID_ARGUMENT");retryExpect("invalid_retry_request_id_rejected",fixed,admin(),body("bad-id","非法请求号"),400,"INVALID_ARGUMENT");
        retryExpect("oversized_retry_reason_rejected",fixed,admin(),body(UUID.randomUUID().toString(),"检".repeat(1001)),400,"INVALID_ARGUMENT");
        expect("unknown_version_retry_is_forbidden","POST",API+"/evidence-versions/"+UUID.randomUUID()+"/anchor-retries",admin(),b,403);
        require(count("SELECT COUNT(*) FROM anchor_retry_request WHERE submission_id=?",fixed.submission)==0,"rejected_manual_requests_create_no_intents");
    }
    private void verifyCurrentIdentityChanges(Fixed fixed)throws Exception{
        Map<String,Object> b=body(UUID.randomUUID().toString(),"当前权限复核");retryExpect("current_admin_member_reaches_pending_gate",fixed,accounts.get(5),b,409,"VERSION_CONFLICT");
        var task=task();expect("remove_retry_admin_membership","PUT",API+"/tasks/"+taskId+"/members",admin(),Map.of("userIds",List.of(admin().id,uploader().id,accounts.get(4).id,accounts.get(6).id),"expectedLockVersion",version(task)),200);
        retryExpect("removed_member_cannot_retry",fixed,accounts.get(5),b,403,"FORBIDDEN");
        var identity=expect("read_retry_role_fixture","GET",API+"/users/me",accounts.get(4),null,200).data();expect("remove_retry_business_role","PUT",API+"/users/"+accounts.get(4).id+"/roles",admin(),Map.of("roles",List.of("ORG_ADMIN"),"expectedLockVersion",version(identity)),200);
        retryExpect("obsolete_role_token_cannot_retry",fixed,accounts.get(4),b,401,"UNAUTHORIZED");login(accounts.get(4));retryExpect("org_admin_only_member_cannot_retry",fixed,accounts.get(4),b,403,"FORBIDDEN");
        require(jdbc.update("UPDATE app_user SET enabled=0 WHERE id=? AND username=?",accounts.get(6).id,accounts.get(6).username)==1,"only_isolated_negative_identity_fixture_disabled");retryExpect("disabled_retry_admin_cannot_retry",fixed,accounts.get(6),b,401,"UNAUTHORIZED");
    }
    private void verifyGrantCannotRetry(Fixed fixed)throws Exception{
        expect("review_confirmed_version_for_precise_grant","POST",API+"/evidence-versions/"+fixed.version+"/review",owner(),Map.of("decision","APPROVED","expectedReviewStatus","PENDING"),200);
        var grant=expect("outsider_requests_exact_version","POST",API+"/access-grants",accounts.get(3),Map.of("clientRequestId",UUID.randomUUID().toString(),"versionId",fixed.version,"reason","单版本只读授权","requestedValidUntil",Instant.now().plusSeconds(900).toString()),200).data();
        grant=expect("approve_exact_version_grant","POST",API+"/access-grants/"+grant.get("id")+"/decision",owner(),Map.of("decision","APPROVED","validUntil",Instant.now().plusSeconds(600).toString(),"expectedLockVersion",version(grant)),200).data();
        expect("shared_admin_can_read_exact_version","GET",API+"/evidence-versions/"+fixed.version,accounts.get(3),null,200);retryExpect("precise_shared_grant_does_not_authorize_retry",fixed,accounts.get(3),body(UUID.randomUUID().toString(),"授权不能扩大为任务管理"),403,"FORBIDDEN");
    }
    private void completeFirstTask(Fixed fixed)throws Exception{
        expect("submit_first_task_acceptance","POST",API+"/tasks/"+taskId+"/submit-acceptance",admin(),Map.of("expectedLockVersion",version(task())),200);
        expect("complete_first_task","POST",API+"/tasks/"+taskId+"/complete",owner(),Map.of("expectedLockVersion",version(task())),200);
        retryExpect("completed_task_rejects_new_recheck",fixed,admin(),body(UUID.randomUUID().toString(),"已完成任务不能重新提交"),409,"TASK_COMPLETED");
    }
    private void verifyOriginalTransactionRecovery(Fixed fixed)throws Exception{
        report.put("stage","original_transaction_without_commit_checkpoint");faults.failSubmitBeforeNetwork.set(true);anchorProcessor.process(event(fixed.submission));requireUnknown(fixed,false);
        var original=attempt(fixed);String tx=string(original.get("tx_id")),bytes=sha256(blob(original.get("transaction_bytes")));require(faults.submitFailuresBeforeNetwork.get()==1,"one_submit_call_fails_before_network_without_fake_ledger_outcome");
        List<Reply> pair=race(fixed,admin(),body(UUID.randomUUID().toString(),"恢复原交易甲"),body(UUID.randomUUID().toString(),"恢复原交易乙"));
        require(pair.stream().map(r->r.status).sorted().toList().equals(List.of(202,409)),"concurrent_distinct_rechecks_accept_exactly_one_intent");var accepted=pair.stream().filter(r->r.status==202).findFirst().orElseThrow().data();assertReceipt(fixed,accepted,1);
        int before=faults.prepared.get();anchorProcessor.recoverDue();require(faults.prepared.get()==before,"missing_commit_recovery_never_prepares_new_transaction");
        require("CONFIRMED".equals(jdbc.queryForObject("SELECT status FROM chain_submission WHERE id=?",String.class,fixed.submission)),"original_prepared_transaction_reaches_real_confirmation");
        var recovered=attempt(fixed);require(tx.equals(recovered.get("tx_id"))&&bytes.equals(sha256(blob(recovered.get("transaction_bytes"))))&&recovered.get("commit_bytes")!=null,"original_transaction_id_and_bytes_preserved_when_commit_is_recovered");
        require(faults.realMissingByKey.getOrDefault(fixed.evidence+"#"+fixed.number,new AtomicInteger()).get()>=2,"real_absent_business_queries_do_not_create_another_attempt");retainLedgerEvidence(fixed);report.put("submitFailuresBeforeNetwork",faults.submitFailuresBeforeNetwork.get());
    }
    private void verifyPermanentPermissionRejection(Fixed fixed)throws Exception{
        report.put("stage","permanent_permission_denial");faults.denyPrepare.set(true);anchorProcessor.process(event(fixed.submission));faults.denyPrepare.set(false);
        require(count("SELECT COUNT(*) FROM chain_submission WHERE id=? AND status='FAILED' AND retryable=0 AND next_action_at IS NULL AND last_error='LEDGER_FORBIDDEN'",fixed.submission)==1,"prepare_permission_denial_is_permanent_and_not_unknown");
        int before=faults.prepared.get();anchorProcessor.recoverDue();require(before==faults.prepared.get()&&count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=?",fixed.submission)==0,"permanent_denial_has_no_automatic_attempt_or_fabric_submission");
        retryExpect("manual_recheck_cannot_override_permission_failure",fixed,admin(),body(UUID.randomUUID().toString(),"不能越过链权限"),409,"VERSION_CONFLICT");report.put("injectedPermanentPermissionDenials",1);
    }
    private void verifyRealHashConflict(Fixed fixed)throws Exception{
        report.put("stage","actual_existing_different_hash_conflict");EvidenceLedgerClient real=realClients.forOrganization(OPERATOR);String registeredHash="0".repeat(64);
        var prepared=real.prepareRegistration(fixed.evidence,fixed.number,registeredHash);var commit=real.submitPrepared(prepared);var outcome=real.queryCommit(commit);
        ledgerRecords.add(Map.of("kind","CONFLICTING_LEDGER_FIXTURE","evidenceId",fixed.evidence,"versionNo",fixed.number,"versionId",fixed.version,"registeredSha256",registeredHash,"originalFileSha256",fixed.hash,"confirmedTxId",prepared.transactionId(),"blockNumber",outcome.blockNumber(),"validationCode",outcome.validationCode()));
        require(outcome.valid()&&outcome.validationCode()==0,"negative_hash_fixture_is_actual_valid_chain_registration");
        var owner=realClients.forOrganization(OWNER).queryEvidence(fixed.evidence,fixed.number);var operator=real.queryEvidence(fixed.evidence,fixed.number);require(owner.found()&&owner.equals(operator)&&registeredHash.equals(owner.record().fileHash())&&prepared.transactionId().equals(owner.record().registeredTxId()),"both_institutions_confirm_actual_different_hash_fixture");
        anchorProcessor.process(event(fixed.submission));require(count("SELECT COUNT(*) FROM chain_submission WHERE id=? AND status='FAILED' AND retryable=0 AND next_action_at IS NULL AND last_error='EVIDENCE_HASH_CONFLICT'",fixed.submission)==1,"actual_different_ledger_hash_is_permanent_business_failure");
        anchorProcessor.recoverDue();require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=?",fixed.submission)==0,"existing_hash_conflict_never_prepares_or_overwrites_an_attempt");retryExpect("manual_recheck_cannot_override_real_hash_conflict",fixed,admin(),body(UUID.randomUUID().toString(),"不能覆盖已登记摘要"),409,"VERSION_CONFLICT");
    }
    private void requireUnknown(Fixed fixed,boolean withCommit){
        require(count("SELECT COUNT(*) FROM chain_submission WHERE id=? AND status='UNKNOWN' AND lease_token IS NULL AND lease_until IS NULL AND next_action_at IS NOT NULL",fixed.submission)==1,"uncertain_rpc_result_preserves_durable_unknown_plan");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE consumer_code='EVIDENCE_ANCHOR' AND event_id=? AND status='DONE'",event(fixed.submission).eventId())==1,"unknown_plan_commits_done_before_manual_recheck");
        require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=? AND phase='UNKNOWN' AND "+(withCommit?"commit_bytes IS NOT NULL":"commit_bytes IS NULL"),fixed.submission)==1,"unknown_keeps_expected_original_checkpoint_shape");
    }
    private void requireConfirmed(Fixed fixed,String tx,String bytes,String commit)throws Exception{var row=attempt(fixed);require(count("SELECT COUNT(*) FROM chain_submission WHERE id=? AND status='CONFIRMED' AND confirmed_tx_id=?",fixed.submission,tx)==1,"manual_recheck_uses_original_first_valid_transaction");require(bytes.equals(sha256(blob(row.get("transaction_bytes"))))&&commit.equals(sha256(blob(row.get("commit_bytes")))),"manual_recheck_preserves_original_transaction_and_commit_bytes");}
    private MessageEvent event(String submission){return MessageEvent.decode(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type='EVIDENCE_ANCHOR_REQUESTED' AND biz_id=? ORDER BY dispatch_no DESC LIMIT 1",String.class,submission).getBytes(StandardCharsets.UTF_8));}
    private Map<String,Object> attempt(Fixed fixed){return jdbc.queryForMap("SELECT * FROM chain_attempt WHERE submission_id=? ORDER BY attempt_no DESC LIMIT 1",fixed.submission);}
    private Map<String,Object> task()throws Exception{return expect("read_current_task","GET",API+"/tasks/"+taskId,admin(),null,200).data();}
    private static Map<String,Object> body(String key,String reason){return Map.of("clientRequestId",key,"reason",reason);}
    private String retryPath(Fixed f){return API+"/evidence-versions/"+f.version+"/anchor-retries";}
    private Reply retryExpect(String name,Fixed f,Account actor,Map<String,Object> body,int status,String code)throws Exception{Reply r=requestHeaders(name,"POST",retryPath(f),actor==null?Map.of():Map.of("Authorization","Bearer "+actor.token),JSON.writeValueAsBytes(body),"application/json");assertCode(r,status,code,name);return r;}
    private List<Reply> race(Fixed fixed,Account actor,Map<String,Object> left,Map<String,Object> right)throws Exception{
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{var a=executor.submit(()->{ready.countDown();go.await();return requestHeaders("concurrent_manual_recheck","POST",retryPath(fixed),Map.of("Authorization","Bearer "+actor.token),JSON.writeValueAsBytes(left),"application/json");});var b=executor.submit(()->{ready.countDown();go.await();return requestHeaders("concurrent_manual_recheck","POST",retryPath(fixed),Map.of("Authorization","Bearer "+actor.token),JSON.writeValueAsBytes(right),"application/json");});require(ready.await(5,TimeUnit.SECONDS),"parallel_manual_requests_ready");go.countDown();Reply x=a.get(30,TimeUnit.SECONDS),y=b.get(30,TimeUnit.SECONDS);for(Reply r:List.of(x,y))assertCode(r,r.status,r.status==202?"OK":"VERSION_CONFLICT","concurrent_manual_recheck");return List.of(x,y);}finally{executor.shutdownNow();}
    }
    private void assertReceipt(Fixed fixed,Map<String,Object> receipt,int dispatch){require(receipt.keySet().equals(Set.of("id","submissionId","versionId","eventId","dispatchNo","acceptedAt")),"manual_receipt_contains_only_safe_acceptance_metadata");require(fixed.version.equals(receipt.get("versionId"))&&fixed.submission.equals(receipt.get("submissionId"))&&number(receipt.get("dispatchNo"))==dispatch,"accepted_receipt_points_to_new_dispatch_for_same_immutable_version");}
    private void retainLedgerEvidence(Fixed fixed)throws Exception{
        var row=attempt(fixed);String tx=string(row.get("tx_id"));var outcome=realClients.forOrganization(OPERATOR).queryCommit(new EvidenceLedgerClient.CommitCheckpoint(tx,blob(row.get("commit_bytes"))));var owner=realClients.forOrganization(OWNER).queryEvidence(fixed.evidence,fixed.number);var operator=realClients.forOrganization(OPERATOR).queryEvidence(fixed.evidence,fixed.number);
        require(outcome.valid()&&outcome.validationCode()==0&&owner.found()&&owner.equals(operator)&&fixed.hash.equals(owner.record().fileHash())&&tx.equals(owner.record().registeredTxId()),"original_commit_and_both_institutions_match_actual_version_hash_and_first_transaction");
        var version=jdbc.queryForMap("SELECT * FROM evidence_version WHERE id=?",fixed.version);try(InputStream in=storage.open(string(version.get("object_key")))){byte[] bytes=in.readAllBytes();require(bytes.length==number(version.get("size"))&&fixed.hash.equals(sha256(bytes)),"actual_private_object_retains_original_size_and_full_digest");}
        ledgerRecords.add(Map.of("kind","MATCHING_BUSINESS_CONFIRMATION","evidenceId",fixed.evidence,"versionNo",fixed.number,"versionId",fixed.version,"sha256",fixed.hash,"confirmedTxId",tx,"blockNumber",outcome.blockNumber(),"validationCode",0,"transactionCheckpointSha256",sha256(blob(row.get("transaction_bytes"))),"commitCheckpointSha256",sha256(blob(row.get("commit_bytes")))));
    }
    private void snapshotOwnedAttemptsBeforeCleanup()throws Exception{
        List<Map<String,Object>> observed=new ArrayList<>();for(String task:taskIds)for(var row:jdbc.queryForList("SELECT a.*,v.evidence_id,v.version_no,v.sha256 FROM chain_attempt a JOIN chain_submission s ON s.id=a.submission_id JOIN evidence_version v ON v.id=s.version_id WHERE v.task_id=?",task)){Map<String,Object> safe=new LinkedHashMap<>();for(String key:List.of("id","submission_id","attempt_no","tx_id","phase","validation_code","block_number","evidence_id","version_no","sha256"))safe.put(key,row.get(key));safe.put("transactionCheckpointSha256",sha256(blob(row.get("transaction_bytes"))));if(row.get("commit_bytes")!=null)safe.put("commitCheckpointSha256",sha256(blob(row.get("commit_bytes"))));observed.add(safe);}report.put("observedOwnedTransactionAttemptsBeforeCleanup",observed);
    }
    private long ownedPendingOutbox(){long total=0;for(String task:taskIds)total+=count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT' AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",task,task);return total;}
    private void cleanupOwnedQueues(){Set<String> events=new HashSet<>();for(String task:taskIds)events.addAll(jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,task,task));rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue))while(true){var delivery=channel.basicGet(queue,false);if(delivery==null)break;MessageEvent e=MessageEvent.decode(delivery.getBody());require(events.contains(e.eventId())&&sharedTrace.equals(e.traceId()),"cleanup_drains_only_owned_business_events");channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);}return null;});}
    private void openDatabase(){var source=new DriverManagerDataSource();source.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")+"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8");source.setUsername(env("DB_USERNAME","uav_app"));source.setPassword(env("DB_PASSWORD",null));jdbc=new JdbcTemplate(source);dataSource=source;}

    private void createFixtures()throws Exception{
        account(OWNER,"owner",List.of("ORG_ADMIN","OWNER_TASK_ADMIN","OWNER_REVIEWER","OWNER_DATA_APPROVER"));account(OPERATOR,"admin",List.of("ORG_ADMIN","OPERATOR_TASK_ADMIN"));account(OPERATOR,"uploader",List.of("OPERATOR_USER"));account(OPERATOR,"outside-admin",List.of("OPERATOR_TASK_ADMIN"));account(OPERATOR,"role-admin",List.of("OPERATOR_TASK_ADMIN"));account(OPERATOR,"member-admin",List.of("OPERATOR_TASK_ADMIN"));account(OPERATOR,"disabled-admin",List.of("OPERATOR_TASK_ADMIN"));
        for(Account a:accounts)login(a);var result=expect("register_own_device","POST",API+"/drones",admin(),Map.of("code","T024-"+runId.substring(0,12)),200).data();deviceId=string(object(result.get("device")).get("id"));deviceToken=string(result.get("deviceToken"));secrets.add(deviceToken);
    }
    private void account(String org,String suffix,List<String> roles){byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);String password=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);secrets.add(password);Account a=new Account(UUID.randomUUID().toString(),"t024-"+runId.substring(0,12)+"-"+suffix,password);accounts.add(a);require(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",a.id,org,a.username,new BCryptPasswordEncoder(12).encode(password),JSON.writeValueAsString(roles))==1,"only_own_fixture_account_inserted");}
    private void login(Account a)throws Exception{a.token=string(expect("fixture_login","POST",API+"/auth/login",null,Map.of("username",a.username,"password",a.password),200).data().get("accessToken"));secrets.add(a.token);}
    private void createForeignTask()throws Exception{var task=expect("create_other_admin_task","POST",API+"/tasks",owner(),Map.of("name","T024-other-"+runId,"scope","other administrator task","requirements",List.of(Map.of("name","foreign","required",true))),200).data();String id=string(task.get("id"));taskIds.add(id);expect("assign_other_admin_task","POST",API+"/tasks/"+id+"/assign",owner(),Map.of("operatorOrgId",OPERATOR,"operatorAdminId",accounts.get(3).id,"expectedLockVersion",version(task)),200);}
    private Scope createTask(boolean securityMembers)throws Exception{var task=expect("create_task","POST",API+"/tasks",owner(),Map.of("name","T024-"+runId,"scope","manual original transaction recovery","requirements",List.of(Map.of("name","photo","required",true))),200).data();String id=string(task.get("id")),path=API+"/tasks/"+id,requirement=string(objects(task.get("requirements")).getFirst().get("id"));taskId=id;taskIds.add(id);task=expect("assign_task","POST",path+"/assign",owner(),Map.of("operatorOrgId",OPERATOR,"operatorAdminId",admin().id,"expectedLockVersion",version(task)),200).data();List<String> members=securityMembers?List.of(admin().id,uploader().id,accounts.get(4).id,accounts.get(5).id,accounts.get(6).id):List.of(admin().id,uploader().id);task=expect("add_uploader_and_security_members","PUT",path+"/members",admin(),Map.of("userIds",members,"expectedLockVersion",version(task)),200).data();task=expect("create_subtask","POST",path+"/subtasks",admin(),Map.of("name","flight","requirementIds",List.of(requirement),"expectedLockVersion",version(task)),200).data();var sub=objects(task.get("subtasks")).getFirst();String execution=string(expect("create_execution","POST",API+"/subtasks/"+sub.get("id")+"/executions",admin(),Map.of("droneId",deviceId,"expectedLockVersion",version(sub)),200).data().get("id"));task=expect("refresh_task","GET",path,admin(),null,200).data();expect("start_task","POST",path+"/start",admin(),Map.of("expectedLockVersion",version(task)),200);return new Scope(id,requirement,string(sub.get("id")),execution);}
    private void verifyAuditSafety(){var rows=jdbc.queryForList("SELECT action,resource_type,resource_id,trace_id,result FROM audit_log WHERE trace_id=?",sharedTrace);require(!rows.isEmpty(),"real_business_audits_are_retained_append_only");for(var row:rows){String text=JSON.writeValueAsString(row);require(secrets.stream().noneMatch(text::contains)&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("X-Amz-"),"retained_audits_exclude_credentials_and_private_locations");}require(rows.stream().filter(r->"EVIDENCE_ANCHOR_RECHECK_REQUESTED".equals(r.get("action"))).count()==2,"only_two_accepted_manual_intents_append_two_audits");report.put("auditRowsRetained",rows.size());}
    private record Fixed(String upload,String version,String submission,String evidence,int number,String hash){}
    private Set<String> objectKeys(String upload){Set<String> keys=new LinkedHashSet<>();for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")){String continuation=null;do{var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(continuation).build());page.contents().forEach(o->keys.add(o.key()));continuation=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;}while(continuation!=null);}return keys;}

    private void cleanupFixtures(){
        Set<String> tasks=new LinkedHashSet<>();for(Account a:accounts)tasks.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,a.id));for(String task:tasks)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        for(String task:tasks){jdbc.update("DELETE FROM anchor_retry_request WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);jdbc.update("DELETE FROM access_grant WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM issue WHERE task_id=?",task);jdbc.update("DELETE FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",task);jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);List<String> events=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,task,task);for(String event:events){jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);jdbc.update("DELETE FROM device_event WHERE execution_id IN (SELECT id FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?))",task);jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);}
        jdbc.update("DELETE FROM drone WHERE operator_org_id=? AND code=?",OPERATOR,"T024-"+runId.substring(0,12));for(Account a:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",a.id,a.username);for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"all_only_owned_part_and_final_objects_removed");report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",1);report.put("fixtureUploadSessionsDeleted",uploadIds.size());report.put("fixturePrivateObjectsDeleted",keys.size());report.put("fabricRecordsRetainedAppendOnly",true);
    }
    private void cleanupTopology(){rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue)){var state=channel.queueDeclarePassive(queue);require(state.getMessageCount()==0&&state.getConsumerCount()==0,"only_empty_unused_owned_queue_deleted");channel.queueDelete(queue,true,true);}channel.exchangeDelete(exchange,true);channel.exchangeDelete(deadExchange,true);return null;});report.put("ownedRabbitTopologyDeleted",true);}
    private Reply expect(String name,String method,String path,Account actor,Object body,int status)throws Exception{Reply result=requestHeaders(name,method,path,actor==null?Map.of():Map.of("Authorization","Bearer "+actor.token),body==null?null:JSON.writeValueAsBytes(body),body==null?null:"application/json");assertCode(result,status,status==200||status==202?"OK":status==400?"INVALID_ARGUMENT":status==401?"UNAUTHORIZED":status==403?"FORBIDDEN":"VERSION_CONFLICT",name);return result;}
    private void assertCode(Reply reply,int status,String code,String name){require(reply.status==status,name+"_http_"+status);require(code.equals(reply.body.get("code")),name+"_code");}
    private Reply requestHeaders(String name,String method,String path,Map<String,String> headers,byte[] bytes,String type)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(30)).header("X-Trace-Id",sharedTrace).header("Accept","application/json");headers.forEach(builder::header);if(type!=null)builder.header("Content-Type",type);builder.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());requests.add(Map.of("case",name,"status",response.statusCode(),"traceId",sharedTrace));require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),name+"_json");String text=new String(response.body(),StandardCharsets.UTF_8);boolean signed=path.endsWith("/download-url")&&response.statusCode()==200,credential=path.equals(API+"/auth/login")||path.equals(API+"/drones")&&method.equals("POST");if(!signed)require((credential||secrets.stream().noneMatch(text::contains))&&!text.contains("object_key")&&!text.contains("objectKey")&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("transactionBytes")&&!text.contains("commitBytes")&&!text.contains("X-Amz-"),name+"_safe_response");Map<String,Object> envelope=JSON.readValue(response.body(), JSON_OBJECT_TYPE);require(envelope.keySet().equals(Set.of("code","message","data","traceId")),name+"_uniform_envelope");require(sharedTrace.equals(envelope.get("traceId"))&&sharedTrace.equals(response.headers().firstValue("X-Trace-Id").orElse(null)),name+"_shared_trace_is_preserved");return new Reply(response.statusCode(),envelope);
    }
    private void writeReport()throws Exception{report.put("caseCount",requests.size());report.put("checkCount",checks.size());byte[] content=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);String text=new String(content,StandardCharsets.UTF_8);if(secrets.stream().anyMatch(text::contains)||text.contains("upload-parts/")||text.contains("evidence-files/")||text.contains("X-Amz-")||text.contains("Signature="))throw new ProbeFailure("unsafe_report");Files.createDirectories(reportPath.getParent());Path tmp=Files.createTempFile(reportPath.getParent(),".reliability-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.write(tmp,content);Files.move(tmp,reportPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
    private void require(boolean value,String name){checks.add(Map.of("check",name,"passed",value));if(!value)throw new ProbeFailure(name);}
    private Account owner(){return accounts.getFirst();}private Account admin(){return accounts.get(1);}private Account uploader(){return accounts.get(2);}
    private static String env(String key,String fallback){String value=System.getenv(key);if(value==null||value.isBlank()){if(fallback==null)throw new ProbeFailure("missing_required_environment");return fallback;}return value;}private static long version(Map<String,Object> row){return number(row.get("lockVersion"));}private static long number(Object value){if(!(value instanceof Number n))throw new ProbeFailure("expected_number");return n.longValue();}private static String string(Object value){if(!(value instanceof String text))throw new ProbeFailure("expected_string");return text;}
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new ProbeFailure("expected_object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new ProbeFailure("expected_object");
            result.put(key, entry.getValue());
        }
        return result;
    }
    private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new ProbeFailure("expected_array");return list.stream().map(AnchorRetryFlowProbe::object).toList();}private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private static byte[] png(int color)throws Exception{var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);for(int x=0;x<8;x++)for(int y=0;y<8;y++)image.setRGB(x,y,color);var output=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",output))throw new ProbeFailure("png_encoder_unavailable");return output.toByteArray();}
    private static final class Account{final String id,username,password;String token;Account(String id,String username,String password){this.id=id;this.username=username;this.password=password;}@Override public String toString(){return "RetryAccount["+id+"]";}}private record Scope(String task,String requirement,String subtask,String execution){}private record Reply(int status,Map<String,Object> body){Map<String,Object> data(){return object(body.get("data"));}@Override public String toString(){return "RetryReply["+status+"]";}}private static final class ProbeFailure extends RuntimeException{ProbeFailure(String name){super(name);}}
    private void await(BooleanSupplier condition,String name)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(System.nanoTime()<deadline){if(condition.getAsBoolean()){require(true,name);return;}Thread.sleep(100);}require(false,name);}
    private static byte[] blob(Object value)throws Exception{if(value instanceof byte[] bytes)return bytes;if(value instanceof java.sql.Blob blob)return blob.getBytes(1,(int)blob.length());throw new ProbeFailure("expected_binary_checkpoint");}
    private long count(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);if(value==null)throw new ProbeFailure("missing_sql_count");return value;}
    private Map<String,Long> businessCounts(){Map<String,Long> result=new LinkedHashMap<>();TABLE_ORDER.keySet().forEach(t->result.put(t,count("SELECT COUNT(*) FROM "+t)));return result;}
    private byte[] businessSnapshot()throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(Connection connection=dataSource.getConnection()){
            for(var table:TABLE_ORDER.entrySet()){frame(digest,table.getKey());try(var statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT * FROM "+table.getKey()+" ORDER BY "+table.getValue())){
                while(rows.next()){digest.update((byte)1);for(int column=1;column<=rows.getMetaData().getColumnCount();column++){
                    int type=rows.getMetaData().getColumnType(column);if(type==java.sql.Types.BINARY||type==java.sql.Types.VARBINARY||type==java.sql.Types.LONGVARBINARY||type==java.sql.Types.BLOB){byte[] bytes=rows.getBytes(column);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}else frame(digest,rows.getString(column));
                }}digest.update((byte)0);
            }}
        }return digest.digest();
    }

    static final class FaultPlan {
        final AtomicBoolean dropCommitReply=new AtomicBoolean(),failSubmitBeforeNetwork=new AtomicBoolean(),denyPrepare=new AtomicBoolean();
        final AtomicInteger commitRepliesDropped=new AtomicInteger(),submitFailuresBeforeNetwork=new AtomicInteger(),prepared=new AtomicInteger(),realMissingQueries=new AtomicInteger();
        final Map<String,AtomicInteger> realMissingByKey=new ConcurrentHashMap<>();
        void clear(){dropCommitReply.set(false);failSubmitBeforeNetwork.set(false);denyPrepare.set(false);}
    }
    @TestConfiguration(proxyBeanMethods=false) @Profile("anchor-retry-probe")
    static class FaultConfiguration {
        @Bean FaultPlan anchorRetryFaultPlan(){return new FaultPlan();}
        @Bean static BeanPostProcessor pauseOnlyOwnBrokerConsumers(){return new BeanPostProcessor(){@Override public Object postProcessBeforeInitialization(Object bean,String name){if(bean instanceof SimpleMessageListenerContainer c)c.setAutoStartup(false);return bean;}};}
        @Bean @Primary InstitutionLedgerClients retryProbeClients(@Qualifier("institutionLedgerClients")InstitutionLedgerClients real,FaultPlan faults){return new InstitutionLedgerClients(real.forOrganization(OWNER),new FaultLedger(real.forOrganization(OPERATOR),faults),real.mspId(OWNER),real.mspId(OPERATOR)){@Override public void close(){}};}
    }
    static final class FaultLedger implements EvidenceLedgerClient {
        private final EvidenceLedgerClient real;private final FaultPlan faults;FaultLedger(EvidenceLedgerClient real,FaultPlan faults){this.real=real;this.faults=faults;}
        @Override public PreparedRegistration prepareRegistration(String evidenceId,int version,String hash)throws org.hyperledger.fabric.client.EndorseException{faults.prepared.incrementAndGet();if(faults.denyPrepare.get())throw Status.PERMISSION_DENIED.withDescription("T024_SAFE_INJECTED_PERMISSION_DENIAL").asRuntimeException();return real.prepareRegistration(evidenceId,version,hash);}
        @Override public CommitCheckpoint submitPrepared(PreparedRegistration prepared)throws org.hyperledger.fabric.client.SubmitException{if(faults.failSubmitBeforeNetwork.compareAndSet(true,false)){faults.submitFailuresBeforeNetwork.incrementAndGet();throw Status.UNAVAILABLE.withDescription("T024_SAFE_SUBMIT_NOT_SENT").asRuntimeException();}return real.submitPrepared(prepared);}
        @Override public CommitOutcome queryCommit(CommitCheckpoint checkpoint)throws org.hyperledger.fabric.client.CommitStatusException{CommitOutcome actual=real.queryCommit(checkpoint);if(faults.dropCommitReply.compareAndSet(true,false)){if(!actual.valid())throw new ProbeFailure("reply_loss_requires_real_valid_fabric_result");faults.commitRepliesDropped.incrementAndGet();throw Status.DEADLINE_EXCEEDED.withDescription("T024_SAFE_COMMIT_REPLY_LOST").asRuntimeException();}return actual;}
        @Override public EvidenceQuery queryEvidence(String evidenceId,int version)throws org.hyperledger.fabric.client.GatewayException{EvidenceQuery actual=real.queryEvidence(evidenceId,version);if(!actual.found()){faults.realMissingQueries.incrementAndGet();faults.realMissingByKey.computeIfAbsent(evidenceId+"#"+version,ignored->new AtomicInteger()).incrementAndGet();}return actual;}
        @Override public void close(){}
    }
}
