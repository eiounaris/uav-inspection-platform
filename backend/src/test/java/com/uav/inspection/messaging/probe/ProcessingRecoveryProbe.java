package com.uav.inspection.messaging.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.InstitutionLedgerClients;
import com.uav.inspection.blockchain.anchor.AnchorProcessor;
import com.uav.inspection.blockchain.anchor.AnchorTransactions;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper;
import com.uav.inspection.common.lease.ProcessingLeaseKeeper;
import java.io.FilterInputStream;
import java.io.IOException;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import com.uav.inspection.evidence.finalization.FinalizationTransactions;
import com.uav.inspection.evidence.finalization.persistence.FinalizationMapper;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.evidence.upload.persistence.UploadMapper;
import com.uav.inspection.messaging.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
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

/** Real default-lease renewal and autonomous business recovery across two independent JVMs. */
public final class ProcessingRecoveryProbe {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String API="/api/v1",OWNER="00000000-0000-4000-8000-000000000001",OPERATOR="00000000-0000-4000-8000-000000000002";
    private static final Map<String,String> TABLE_ORDER=new LinkedHashMap<>();
    static {
        TABLE_ORDER.put("organization","id");TABLE_ORDER.put("app_user","id");TABLE_ORDER.put("inspection_task","id");TABLE_ORDER.put("task_member","task_id,user_id");TABLE_ORDER.put("drone","id");TABLE_ORDER.put("inspection_subtask","id");TABLE_ORDER.put("material_requirement","id");TABLE_ORDER.put("task_execution","id");TABLE_ORDER.put("upload_session","id");TABLE_ORDER.put("upload_part","session_id,part_no");TABLE_ORDER.put("evidence_version","id");TABLE_ORDER.put("outbox_event","event_id");TABLE_ORDER.put("consumed_event","consumer_code,event_id");TABLE_ORDER.put("chain_submission","id");TABLE_ORDER.put("chain_attempt","id");TABLE_ORDER.put("verification_record","id");TABLE_ORDER.put("device_event","id");TABLE_ORDER.put("execution_adjustment","id");TABLE_ORDER.put("issue","id");TABLE_ORDER.put("review_record","id");TABLE_ORDER.put("access_grant","id");TABLE_ORDER.put("message_rejection","id");
    }
    private final String phase,runId,sharedTrace,exchange,finalizeQueue,anchorQueue,deadExchange,deadQueue;
    private final Path runDirectory,reportPath,checkpointPath,credentialPath;
    private final Map<String,Object> checkpoint,report=new LinkedHashMap<>();
    private final List<Map<String,Object>> checks=new ArrayList<>(),requests=new ArrayList<>(),ledgerRecords=new ArrayList<>();
    private final List<Account> accounts=new ArrayList<>();
    private final List<String> secrets=new ArrayList<>();
    private final Set<String> uploadIds=new LinkedHashSet<>();
    private final Map<String,String> expectedDigests=new LinkedHashMap<>();
    private final List<CompletableFuture<Void>> controlledWorkers=new ArrayList<>();
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private S3Client s3;
    private ObjectStorage storage;
    private InstitutionLedgerClients clients;
    private RabbitTemplate rabbit;
    private RabbitAdmin rabbitAdmin;
    private OutboxPublisher publisher;
    private FinalizationProcessor processor;
    private AnchorProcessor anchorProcessor;
    private FinalizationTransactions finalPhases;
    private AnchorTransactions anchorPhases;
    private UploadMapper uploads;
    private AnchorMapper anchors;
    private FaultPlan faults;
    private SimpleMessageListenerContainer finalizeContainer,anchorContainer;
    private String baseUrl,bucket,taskId,deviceId,uploadId,eventId,versionId,submissionId,anchorEventId,lostUploadId,lostEventId,retryUploadId,retryEventId;
    private Scope scope;
    private byte[] baseline;
    private HttpClient client;

    private ProcessingRecoveryProbe(String phase,Path directory)throws Exception {
        this.phase=phase;
        runDirectory=directory.toAbsolutePath().normalize();
        checkpointPath=runDirectory.resolve("checkpoint.json");credentialPath=runDirectory.resolve("credentials.json");
        checkpoint=phase.equals("crash")?new LinkedHashMap<>():JSON.readValue(Files.readAllBytes(checkpointPath),Map.class);
        runId=phase.equals("crash")?UUID.randomUUID().toString():string(checkpoint.get("runId"));
        sharedTrace="t023-"+runId;exchange="inspection.recovery.probe."+runId;finalizeQueue=exchange+".finalize";
        anchorQueue=exchange+".anchor";deadExchange=exchange+".dead.events";deadQueue=exchange+".dead";
        reportPath=runDirectory.resolve(phase.equals("crash")?"crash.json":"report.json");
        report.put("formatVersion",1);report.put("runId",runId);report.put("phase",phase);report.put("pid",ProcessHandle.current().pid());
        report.put("startedAt",Instant.now().toString());report.put("passed",false);report.put("checks",checks);report.put("requests",requests);
        report.put("ledgerRecordsRetained",ledgerRecords);
    }
    public static void main(String[] args) {
        if(args.length!=2||!Set.of("crash","recover").contains(args[0])){System.err.println("Usage: ProcessingRecoveryProbe crash|recover <private-run-directory>");System.exit(2);return;}
        ProcessingRecoveryProbe probe=null;
        try {probe=new ProcessingRecoveryProbe(args[0],Path.of(args[1]));probe.execute();probe.writeReport();System.out.println("[PASS] T023 recovery phase: "+probe.phase);}
        catch(Exception failure) {
            if(probe!=null){probe.report.put("passed",false);probe.report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof ProbeFailure)probe.report.put("failedCheck",failure.getMessage());try{probe.writeReport();}catch(Exception ignored){}}
            System.err.println("[FAIL] T023 recovery probe; inspect private safe report");System.exit(1);
        }
    }
    private void execute()throws Exception {
        openDatabase();require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version='12' AND success=1")==1,"twelve_existing_migrations_present");
        if(phase.equals("crash")) {
            requireNoForeignWork(false);baseline=businessSnapshot();checkpoint.put("baselineSha256",HexFormat.of().formatHex(baseline));
            checkpoint.put("businessCountsBefore",businessCounts());
        } else {
            restoreCheckpoint();baseline=HexFormat.of().parseHex(string(checkpoint.get("baselineSha256")));
            requireNoForeignWork(true);verifyRestartCheckpointsBeforeApplication();waitForNaturalProcessingExpiryBeforeRestart();
        }
        report.put("businessCountsBefore",checkpoint.get("businessCountsBefore"));
        boolean automatic=phase.equals("recover");
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,FaultConfiguration.class)
                .profiles("local","database","storage","messaging","ledger","processing-recovery-probe")
                .web(WebApplicationType.SERVLET).logStartupInfo(false).run("--server.port=0",
                "--inspection.messaging.exchange="+exchange,"--inspection.messaging.finalize-queue="+finalizeQueue,
                "--inspection.messaging.anchor-queue="+anchorQueue,"--inspection.messaging.dead-letter-exchange="+deadExchange,
                "--inspection.messaging.dead-letter-queue="+deadQueue,"--inspection.messaging.publisher-enabled="+automatic,
                "--inspection.messaging.recovery-enabled="+automatic,"--inspection.ledger.recovery-enabled="+automatic)) {
            jdbc=context.getBean(JdbcTemplate.class);dataSource=context.getBean(DataSource.class);s3=context.getBean(S3Client.class);
            storage=context.getBean(ObjectStorage.class);clients=context.getBean(InstitutionLedgerClients.class);
            rabbit=context.getBean(RabbitTemplate.class);rabbitAdmin=context.getBean(RabbitAdmin.class);publisher=context.getBean(OutboxPublisher.class);
            processor=context.getBean(FinalizationProcessor.class);anchorProcessor=context.getBean(AnchorProcessor.class);
            finalPhases=context.getBean(FinalizationTransactions.class);anchorPhases=context.getBean(AnchorTransactions.class);
            uploads=context.getBean(UploadMapper.class);anchors=context.getBean(AnchorMapper.class);faults=context.getBean(FaultPlan.class);
            finalizeContainer=context.getBean("finalizationContainer",SimpleMessageListenerContainer.class);
            anchorContainer=context.getBean("anchorContainer",SimpleMessageListenerContainer.class);
            bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");
            baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            require(!finalizeContainer.isRunning()&&!anchorContainer.isRunning(),"own_broker_consumers_paused_to_prove_business_scanner_recovery");
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                client=actual;
                try {
                    if(!automatic){createFixtures();scope=createTask();taskId=scope.task;prepareCrash();throw new ProbeFailure("expected_durable_checkpoint_halt_did_not_occur");}
                    verifyExpiredFileOwnerBeforeAutomaticTick();
                    for(Account account:accounts)login(account);
                    verifyAutomaticRecovery();verifyAuditSafety();
                } finally {
                    faults.releaseAll();
                    for(CompletableFuture<Void> worker:controlledWorkers)worker.get(45,TimeUnit.SECONDS);
                    context.getBean(ThreadPoolTaskScheduler.class).shutdown();
                    context.getBean(BusinessRecoveryDispatcher.class).close();
                    context.getBean(ProcessingLeaseKeeper.class).shutdown();
                    finalizeContainer.stop();anchorContainer.stop();
                    report.put("cleanupAttempted",true);snapshotOwnedAttemptsBeforeCleanup();cleanupOwnedQueues();cleanupFixtures();cleanupTopology();Files.deleteIfExists(credentialPath);
                    require(MessageDigest.isEqual(baseline,businessSnapshot()),"all_original_twenty_two_table_fields_and_binary_checkpoints_preserved");
                    report.put("originalBusinessDataUnchanged",true);report.put("businessCountsAfter",businessCounts());
                }
            }
        }
        report.put("stage","complete");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }
    private void requireNoForeignWork(boolean recovering) {
        List<String> own=recovering?List.of(uploadId,lostUploadId,retryUploadId):List.of("00000000-0000-4000-8000-000000000000");
        Set<String> permitted=new HashSet<>(own);if(recovering)permitted.add(submissionId);
        for(var row:jdbc.queryForList("SELECT biz_id FROM outbox_event WHERE status NOT IN ('SENT','FAILED')"))require(permitted.contains(row.get("biz_id")),"no_foreign_pending_outbox_before_workers");
        for(String id:jdbc.queryForList("SELECT id FROM upload_session WHERE status='VERIFYING'",String.class))require(own.contains(id),"no_foreign_processing_upload_before_workers");
        for(String id:jdbc.queryForList("SELECT id FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1)",String.class))require(recovering&&submissionId.equals(id),"no_foreign_pending_chain_submission_before_workers");
        for(String id:jdbc.queryForList("SELECT event_id FROM consumed_event WHERE status<>'DONE'",String.class))require(recovering&&lostEventId.equals(id),"no_foreign_processing_consumption_before_workers");
    }
    private String createUpload(int color)throws Exception {
        byte[] bytes=png(color);
        String id=string(expect("create_upload","POST",API+"/upload-sessions",uploader(),Map.of("clientRequestId",UUID.randomUUID().toString(),"requirementId",scope.requirement,"executionId",scope.execution,"filename","inspection.png","contentType","image/png","size",bytes.length),200).data().get("uploadId"));
        uploadIds.add(id);expectedDigests.put(id,sha256(bytes));
        assertCode(requestHeaders("store_part","PUT",API+"/upload-sessions/"+id+"/parts/1",Map.of("Authorization","Bearer "+uploader().token),bytes,"application/octet-stream"),200,"OK","store_part");
        expect("complete_upload","POST",API+"/upload-sessions/"+id+"/complete",uploader(),null,202);return id;
    }
    private MessageEvent eventForUpload(String id) {
        return MessageEvent.decode(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type='FILE_FINALIZE_REQUESTED' AND biz_id=?",String.class,id).getBytes(StandardCharsets.UTF_8));
    }
    private void prepareCrash()throws Exception {
        report.put("stage","long_real_garage_read_with_default_lease_renewal");
        uploadId=createUpload(0x2559a3);MessageEvent original=eventForUpload(uploadId);eventId=original.eventId();
        Gate longRead=new Gate();faults.storageGates.put(uploadId,longRead);
        CompletableFuture<Void> first=CompletableFuture.runAsync(processor::recoverDue);controlledWorkers.add(first);
        require(longRead.entered.await(15,TimeUnit.SECONDS),"first_real_garage_bytes_read_before_long_open_stream_gate");
        var firstClaim=faults.finalClaims.get(uploadId);require(firstClaim!=null,"long_running_file_worker_has_committed_claim");
        Instant initialUntil=instant(jdbc.queryForMap("SELECT lease_until FROM upload_session WHERE id=?",uploadId).get("lease_until"));
        long heldFrom=System.nanoTime();writeReport();
        awaitSeconds(()->count("SELECT COUNT(*) FROM upload_session WHERE id=? AND UTC_TIMESTAMP(3)>?",uploadId,java.sql.Timestamp.from(initialUntil.plusSeconds(3)))==1,"database_time_passes_original_120_second_file_lease_without_sql_edits",140);
        long heldMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-heldFrom);
        var renewed=jdbc.queryForMap("SELECT lease_token,lease_until FROM upload_session WHERE id=?",uploadId);
        require(firstClaim.token().equals(renewed.get("lease_token"))&&instant(renewed.get("lease_until")).isAfter(initialUntil),"same_owner_has_renewed_lease_beyond_original_expiry");
        require(faults.finalRenewals.getOrDefault(uploadId,new AtomicInteger()).get()>=4,"independent_file_heartbeat_renewed_during_blocked_object_io");
        require(finalPhases.claim(original,true)==null,"competing_file_recovery_cannot_claim_live_renewed_lease");
        var wrong=new FinalizationTransactions.Claim(original,firstClaim.session(),firstClaim.parts(),UUID.randomUUID().toString());
        require(!finalPhases.renew(wrong)&&!finalPhases.checkpoint(wrong,false,"FILE_IO_UNAVAILABLE")&&!finalPhases.finish(wrong,"unused",1,"0".repeat(64)),"wrong_file_token_cannot_renew_checkpoint_or_finalize");
        require(count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=?",uploadId)==0,"blocked_file_io_has_no_version_before_committed_finish");
        longRead.release.countDown();first.get(30,TimeUnit.SECONDS);faults.storageGates.remove(uploadId);
        require(count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=?",uploadId)==1,"long_renewed_worker_creates_exactly_one_fixed_version");
        versionId=jdbc.queryForObject("SELECT id FROM evidence_version WHERE upload_session_id=?",String.class,uploadId);
        submissionId=jdbc.queryForObject("SELECT id FROM chain_submission WHERE version_id=?",String.class,versionId);
        anchorEventId=jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE event_type='EVIDENCE_ANCHOR_REQUESTED' AND biz_id=?",String.class,submissionId);
        report.put("processingLeaseSeconds",120);report.put("heartbeatIntervalSeconds",30);report.put("longFileIoHeldMillis",heldMillis);
        report.put("successfulFileRenewals",faults.finalRenewals.get(uploadId).get());report.put("longFileIoPassedOriginalExpiry",true);

        report.put("stage","renewal_failure_and_anchor_checkpoint_before_process_halt");
        lostUploadId=createUpload(0x3e6a40);lostEventId=eventForUpload(lostUploadId).eventId();
        Gate failedRead=new Gate();faults.storageGates.put(lostUploadId,failedRead);
        CompletableFuture<Void> failedWorker=CompletableFuture.runAsync(processor::recoverDue);controlledWorkers.add(failedWorker);
        require(failedRead.entered.await(15,TimeUnit.SECONDS),"renewal_failure_fixture_pauses_after_real_garage_read");
        faults.failRenewUpload.set(lostUploadId);
        Gate anchorGate=new Gate();faults.anchorGate=anchorGate;faults.dropCommitReply.set(true);
        CompletableFuture<Void> anchor=CompletableFuture.runAsync(anchorProcessor::recoverDue);controlledWorkers.add(anchor);
        require(anchorGate.entered.await(15,TimeUnit.SECONDS),"real_fabric_query_blocks_outside_database_transaction");
        awaitSeconds(()->faults.failedFileRenewals.get()>=1&&faults.anchorRenewals.get()>=2,"file_renewal_error_and_anchor_periodic_renewal_observed",45);
        var anchorClaim=faults.anchorClaims.get(submissionId);
        require(anchorClaim!=null,"anchor_has_current_processing_claim");
        var wrongAnchor=new AnchorTransactions.Claim(anchorClaim.event(),anchorClaim.submission(),anchorClaim.attempt(),UUID.randomUUID().toString());
        require(!anchorPhases.renew(wrongAnchor)&&!anchorPhases.checkpoint(wrongAnchor,null,"UNKNOWN",true,"QUERY_UNAVAILABLE",true)&&!anchorPhases.confirm(wrongAnchor,"0".repeat(64),"Org2MSP"),"wrong_anchor_token_cannot_renew_checkpoint_or_confirm");
        require(anchorPhases.claim(anchorClaim.event(),true)==null,"competing_anchor_recovery_cannot_claim_current_lease");
        failedRead.release.countDown();anchorGate.release.countDown();
        // recoverDue isolates the lost-worker exception; inspect persisted results rather than expecting an outer throw.
        failedWorker.get(30,TimeUnit.SECONDS);anchor.get(40,TimeUnit.SECONDS);
        require(count("SELECT COUNT(*) FROM upload_session WHERE id=? AND status='VERIFYING' AND lease_token IS NOT NULL AND retry_count=0 AND next_action_at IS NULL",lostUploadId)==1,"renewal_database_error_prevents_late_file_checkpoint_and_result");
        require(count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=?",lostUploadId)==0&&count("SELECT COUNT(*) FROM consumed_event WHERE event_id=? AND status='PROCESSING'",lostEventId)==1,"lost_worker_has_no_version_or_false_done_receipt");
        require(count("SELECT COUNT(*) FROM chain_submission WHERE id=? AND status='UNKNOWN' AND lease_token IS NULL AND next_action_at IS NOT NULL",submissionId)==1,"lost_commit_reply_commits_unknown_recovery_plan");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE event_id=? AND status='DONE'",anchorEventId)==1,"anchor_unknown_has_durable_done_before_restart");
        require(faults.droppedCommitReplies.get()==1,"one_real_valid_commit_reply_is_deliberately_lost");
        retainLedgerEvidence(versionId,false);
        var attempt=jdbc.queryForMap("SELECT * FROM chain_attempt WHERE submission_id=?",submissionId);
        checkpoint.put("firstTransactionId",attempt.get("tx_id"));checkpoint.put("firstTransactionSha256",sha256(blob(attempt.get("transaction_bytes"))));checkpoint.put("firstCommitSha256",sha256(blob(attempt.get("commit_bytes"))));
        report.put("successfulAnchorRenewals",faults.anchorRenewals.get());report.put("renewalErrorsInjected",faults.failedFileRenewals.get());report.put("renewalFailureStoppedBusinessWrites",true);

        retryUploadId=createUpload(0x964926);retryEventId=eventForUpload(retryUploadId).eventId();
        faults.failOpenUpload.set(retryUploadId);processor.recoverDue();
        require(count("SELECT COUNT(*) FROM upload_session WHERE id=? AND status='VERIFYING' AND lease_token IS NULL AND next_action_at IS NOT NULL AND retry_count=1",retryUploadId)==1,"storage_failure_saves_retry_plan_before_restart");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE event_id=? AND status='DONE'",retryEventId)==1,"file_retry_plan_has_done_receipt_for_scanner_bypass");
        publisher.publishBatch();
        var lostState=jdbc.queryForMap("SELECT lease_token,lease_until FROM upload_session WHERE id=?",lostUploadId);
        checkpoint.put("runId",runId);checkpoint.put("taskId",taskId);checkpoint.put("deviceId",deviceId);checkpoint.put("uploadId",uploadId);checkpoint.put("eventId",eventId);checkpoint.put("versionId",versionId);checkpoint.put("submissionId",submissionId);checkpoint.put("anchorEventId",anchorEventId);
        checkpoint.put("lostUploadId",lostUploadId);checkpoint.put("lostEventId",lostEventId);checkpoint.put("lostToken",lostState.get("lease_token"));checkpoint.put("lostLeaseUntil",instant(lostState.get("lease_until")).toString());
        checkpoint.put("retryUploadId",retryUploadId);checkpoint.put("retryEventId",retryEventId);checkpoint.put("requirementId",scope.requirement);checkpoint.put("subtaskId",scope.subtask);checkpoint.put("executionId",scope.execution);
        checkpoint.put("expectedDigests",expectedDigests);checkpoint.put("ledgerRecordsRetained",ledgerRecords);writePrivate(checkpointPath,checkpoint);
        report.put("intentionalHaltExitCode",73);report.put("durableRecoveryCheckpointsBeforeHalt",true);report.put("stage","real_recoverable_business_checkpoints_before_halt");report.put("passed",true);writeReport();
        System.out.flush();System.err.flush();Runtime.getRuntime().halt(73);
    }
    private void verifyRestartCheckpointsBeforeApplication() {
        require(count("SELECT COUNT(*) FROM upload_session WHERE id=? AND status='VERIFYING' AND lease_token=?",lostUploadId,checkpoint.get("lostToken"))==1,"new_jvm_observes_lost_workers_original_unedited_lease");
        require(count("SELECT COUNT(*) FROM upload_session WHERE id=? AND status='VERIFYING' AND lease_token IS NULL AND next_action_at IS NOT NULL",retryUploadId)==1&&count("SELECT COUNT(*) FROM consumed_event WHERE event_id=? AND status='DONE'",retryEventId)==1,"new_jvm_observes_durable_file_retry_with_done");
        require(count("SELECT COUNT(*) FROM chain_submission WHERE id=? AND status='UNKNOWN' AND next_action_at IS NOT NULL AND lease_token IS NULL",submissionId)==1&&count("SELECT COUNT(*) FROM consumed_event WHERE event_id=? AND status='DONE'",anchorEventId)==1,"new_jvm_observes_durable_unknown_with_done");
        require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=? AND tx_id=? AND commit_bytes IS NOT NULL",submissionId,checkpoint.get("firstTransactionId"))==1,"new_jvm_has_original_transaction_and_commit_checkpoints");
    }
    /** Wait without an application process: the original database lease must expire by itself. */
    private void waitForNaturalProcessingExpiryBeforeRestart()throws Exception {
        report.put("stage","natural_processing_lease_expiry_before_application_restart");
        long started=System.nanoTime();writeReport();
        awaitSeconds(()->count("SELECT COUNT(*) FROM upload_session WHERE id=? AND status='VERIFYING' AND lease_token=? AND lease_until<=UTC_TIMESTAMP(3)",lostUploadId,checkpoint.get("lostToken"))==1,"original_processing_lease_expires_naturally_before_restarted_application",140);
        report.put("naturalLeaseWaitMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
    }
    /** The database row is still VERIFYING, not terminal or newly owned, so only expiry can fence these writes. */
    private void verifyExpiredFileOwnerBeforeAutomaticTick() {
        require(!faults.recoveryClaimedUploads.contains(lostUploadId),"expired_owner_negative_checks_precede_first_automatic_claim");
        var before=jdbc.queryForMap("SELECT * FROM upload_session WHERE id=?",lostUploadId);
        require("VERIFYING".equals(before.get("status"))&&checkpoint.get("lostToken").equals(before.get("lease_token"))&&count("SELECT COUNT(*) FROM upload_session WHERE id=? AND lease_until<=UTC_TIMESTAMP(3)",lostUploadId)==1,"expired_negative_fixture_still_has_original_token_and_processing_state");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE event_id=? AND status='PROCESSING'",lostEventId)==1,"expired_original_worker_receipt_remains_processing_before_claim");
        var stale=new FinalizationTransactions.Claim(eventForUpload(lostUploadId),uploads.findSession(lostUploadId),uploads.listParts(lostUploadId),string(checkpoint.get("lostToken")));
        require(!finalPhases.renew(stale)&&!finalPhases.checkpoint(stale,false,"FILE_IO_UNAVAILABLE")&&!finalPhases.finish(stale,"unused",1,"0".repeat(64)),"expired_original_token_alone_cannot_renew_checkpoint_or_finalize_nonterminal_work");
        require(before.equals(jdbc.queryForMap("SELECT * FROM upload_session WHERE id=?",lostUploadId))&&!faults.recoveryClaimedUploads.contains(lostUploadId),"expired_owner_attempts_preserve_every_original_processing_row_field");
        report.put("expiredNonterminalOwnerFencedBeforeRecovery",true);
    }
    /** Preserve safe attempt identifiers even when a failure precedes the final ledger oracle. */
    private void snapshotOwnedAttemptsBeforeCleanup()throws Exception {
        List<Map<String,Object>> attempts=new ArrayList<>();
        if(taskId!=null)for(var row:jdbc.queryForList("SELECT a.*,v.evidence_id,v.version_no,v.sha256 FROM chain_attempt a JOIN chain_submission s ON s.id=a.submission_id JOIN evidence_version v ON v.id=s.version_id WHERE v.task_id=? ORDER BY v.version_no,a.attempt_no",taskId)) {
            Map<String,Object> safe=new LinkedHashMap<>();
            for(String key:List.of("id","submission_id","tx_id","phase","evidence_id","version_no","sha256","validation_code","block_number"))safe.put(key,row.get(key));
            safe.put("transactionCheckpointSha256",sha256(blob(row.get("transaction_bytes"))));
            safe.put("commitCheckpointSha256",row.get("commit_bytes")==null?null:sha256(blob(row.get("commit_bytes"))));
            attempts.add(safe);
        }
        report.put("observedOwnedTransactionAttemptsBeforeCleanup",attempts);
    }
    private void verifyAutomaticRecovery()throws Exception {
        report.put("stage","autonomous_restart_recovery_and_natural_expired_worker_fencing");
        long waitStarted=System.nanoTime();writeReport();
        awaitSeconds(()->count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id IN (?,?,?)",uploadId,lostUploadId,retryUploadId)==3&&count("SELECT COUNT(*) FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?) AND status='CONFIRMED'",taskId)==3,"default_scheduled_business_recovery_finishes_all_original_work",180);
        report.put("automaticRecoveryWaitMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-waitStarted));
        require(count("SELECT COUNT(*) FROM upload_session WHERE id=? AND UTC_TIMESTAMP(3)>?",lostUploadId,java.sql.Timestamp.from(Instant.parse(string(checkpoint.get("lostLeaseUntil")))))==1,"lost_original_processing_lease_naturally_expired_before_recovery_result");
        require(faults.recoveryClaimedUploads.contains(lostUploadId)&&faults.recoveryClaimedUploads.contains(retryUploadId),"real_scheduled_file_scanner_reclaims_expired_work_and_bypasses_done");
        require(faults.recoveryClaimedAnchors.contains(submissionId),"real_scheduled_anchor_scanner_bypasses_done_and_restores_unknown");
        var stale=new FinalizationTransactions.Claim(eventForUpload(lostUploadId),uploads.findSession(lostUploadId),uploads.listParts(lostUploadId),string(checkpoint.get("lostToken")));
        require(!finalPhases.renew(stale)&&!finalPhases.checkpoint(stale,false,"FILE_IO_UNAVAILABLE")&&!finalPhases.finish(stale,"unused",1,"0".repeat(64)),"expired_old_file_owner_cannot_modify_recovered_terminal_result");
        var attempt=jdbc.queryForMap("SELECT * FROM chain_attempt WHERE submission_id=?",submissionId);
        require(checkpoint.get("firstTransactionId").equals(attempt.get("tx_id"))&&checkpoint.get("firstTransactionSha256").equals(sha256(blob(attempt.get("transaction_bytes"))))&&checkpoint.get("firstCommitSha256").equals(sha256(blob(attempt.get("commit_bytes")))),"restart_reuses_exact_original_transaction_and_commit_bytes");
        require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=?",submissionId)==1,"unknown_recovery_does_not_create_second_transaction_attempt");
        require(count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",taskId)==3&&count("SELECT COUNT(*) FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",taskId)==3,"three_uploads_have_exactly_three_versions_and_submissions");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))) AND status='DONE'",taskId,taskId)==6,"all_three_file_and_anchor_events_end_with_durable_done");
        await(()->count("SELECT COUNT(*) FROM outbox_event WHERE (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))) AND status<>'SENT'",taskId,taskId)==0,"automatic_publisher_confirms_all_owned_recovery_events");
        ledgerRecords.clear();
        for(String id:jdbc.queryForList("SELECT id FROM evidence_version WHERE task_id=? ORDER BY version_no",String.class,taskId))retainLedgerEvidence(id,true);
        for(String id:uploadIds)expect("recovered_upload","GET",API+"/upload-sessions/"+id,uploader(),null,200);
        report.put("confirmedVersions",3);report.put("defaultScheduledRecoveryEnabled",true);report.put("brokerConsumersPausedForScannerEvidence",true);
        report.put("fileDoneBypassed",true);report.put("anchorDoneBypassed",true);report.put("originalTransactionAndCommitBytesPreserved",true);report.put("naturalProcessingLeaseExpiryUsed",true);
    }
    private void retainLedgerEvidence(String id,boolean confirmed)throws Exception {
        var row=jdbc.queryForMap("SELECT * FROM evidence_version WHERE id=?",id);
        require(expectedDigests.get(string(row.get("upload_session_id"))).equals(row.get("sha256")),"fixed_version_hash_matches_independent_original_png_bytes");
        var attempt=jdbc.queryForMap("SELECT a.* FROM chain_attempt a JOIN chain_submission s ON s.id=a.submission_id WHERE s.version_id=?",id);
        String tx=string(attempt.get("tx_id"));
        var outcome=clients.forOrganization(OPERATOR).queryCommit(new EvidenceLedgerClient.CommitCheckpoint(tx,blob(attempt.get("commit_bytes"))));
        require(outcome.valid()&&outcome.validationCode()==0,"original_saved_commit_request_is_real_valid_fabric_transaction");
        var owner=clients.forOrganization(OWNER).queryEvidence(string(row.get("evidence_id")),(int)number(row.get("version_no")));
        var operator=clients.forOrganization(OPERATOR).queryEvidence(string(row.get("evidence_id")),(int)number(row.get("version_no")));
        require(owner.found()&&owner.equals(operator)&&tx.equals(owner.record().registeredTxId())&&row.get("sha256").equals(owner.record().fileHash()),"both_real_institutions_query_same_first_transaction_and_hash");
        try(InputStream input=storage.open(string(row.get("object_key")))){byte[] bytes=input.readAllBytes();require(sha256(bytes).equals(row.get("sha256"))&&bytes.length==number(row.get("size")),"actual_private_fixed_object_matches_version_size_and_hash");}
        if(confirmed)require("VALID".equals(attempt.get("phase"))&&count("SELECT COUNT(*) FROM chain_submission WHERE version_id=? AND status='CONFIRMED' AND confirmed_tx_id=?",id,tx)==1,"recovered_business_confirmation_matches_original_valid_commit");
        ledgerRecords.add(Map.of("versionId",id,"evidenceId",row.get("evidence_id"),"versionNo",row.get("version_no"),"sha256",row.get("sha256"),"confirmedTxId",tx,"validationCode",0,"blockNumber",outcome.blockNumber(),"transactionCheckpointSha256",sha256(blob(attempt.get("transaction_bytes"))),"commitCheckpointSha256",sha256(blob(attempt.get("commit_bytes")))));
    }
    private void restoreCheckpoint()throws Exception {
        taskId=string(checkpoint.get("taskId"));deviceId=string(checkpoint.get("deviceId"));uploadId=string(checkpoint.get("uploadId"));eventId=string(checkpoint.get("eventId"));versionId=string(checkpoint.get("versionId"));submissionId=string(checkpoint.get("submissionId"));anchorEventId=string(checkpoint.get("anchorEventId"));
        lostUploadId=string(checkpoint.get("lostUploadId"));lostEventId=string(checkpoint.get("lostEventId"));retryUploadId=string(checkpoint.get("retryUploadId"));retryEventId=string(checkpoint.get("retryEventId"));
        uploadIds.addAll(List.of(uploadId,lostUploadId,retryUploadId));scope=new Scope(taskId,string(checkpoint.get("requirementId")),string(checkpoint.get("subtaskId")),string(checkpoint.get("executionId")));
        ledgerRecords.addAll(objects(checkpoint.get("ledgerRecordsRetained")));
        object(checkpoint.get("expectedDigests")).forEach((id,hash)->expectedDigests.put(id,string(hash)));
        for(var row:objects(JSON.readValue(Files.readAllBytes(credentialPath),Map.class).get("accounts"))){var account=new Account(string(row.get("id")),string(row.get("username")),string(row.get("password")));accounts.add(account);secrets.add(account.password);}
    }
    private void cleanupOwnedQueues() {
        Set<String> ids=new HashSet<>(jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,taskId,taskId));
        rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue)){while(true){var delivery=channel.basicGet(queue,false);if(delivery==null)break;MessageEvent event=MessageEvent.decode(delivery.getBody());require(ids.contains(event.eventId())&&sharedTrace.equals(event.traceId()),"only_owned_expected_business_deliveries_are_removed_at_cleanup");channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);}}return null;});
    }
    private void openDatabase(){var source=new DriverManagerDataSource();source.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")+"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8");source.setUsername(env("DB_USERNAME","uav_app"));source.setPassword(env("DB_PASSWORD",null));jdbc=new JdbcTemplate(source);dataSource=source;}

    private void createFixtures()throws Exception{account(OWNER,"owner",List.of("OWNER_TASK_ADMIN"));account(OPERATOR,"admin",List.of("OPERATOR_TASK_ADMIN"));account(OPERATOR,"uploader",List.of("OPERATOR_USER"));for(Account account:accounts)login(account);var created=expect("register_own_device","POST",API+"/drones",admin(),Map.of("code","T023-"+runId.substring(0,12)),200).data();deviceId=string(object(created.get("device")).get("id"));secrets.add(string(created.get("deviceToken")));saveCredentials();}
    private void account(String org,String suffix,List<String> roles){byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);String password=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);secrets.add(password);Account a=new Account(UUID.randomUUID().toString(),"t023-"+runId.substring(0,12)+"-"+suffix,password);accounts.add(a);require(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",a.id,org,a.username,new BCryptPasswordEncoder(12).encode(password),JSON.writeValueAsString(roles))==1,"own_fixture_account_inserted");}
    private void login(Account a)throws Exception{a.token=string(expect("fixture_login","POST",API+"/auth/login",null,Map.of("username",a.username,"password",a.password),200).data().get("accessToken"));secrets.add(a.token);}
    private Scope createTask()throws Exception{var task=expect("create_task","POST",API+"/tasks",owner(),Map.of("name","T023-"+runId,"scope","lease renewal and restart business recovery","requirements",List.of(Map.of("name","photo","required",true))),200).data();String id=string(task.get("id")),path=API+"/tasks/"+id,requirement=string(objects(task.get("requirements")).getFirst().get("id"));taskId=id;task=expect("assign_task","POST",path+"/assign",owner(),Map.of("operatorOrgId",OPERATOR,"operatorAdminId",admin().id,"expectedLockVersion",version(task)),200).data();task=expect("add_uploader","PUT",path+"/members",admin(),Map.of("userIds",List.of(admin().id,uploader().id),"expectedLockVersion",version(task)),200).data();task=expect("create_subtask","POST",path+"/subtasks",admin(),Map.of("name","flight","requirementIds",List.of(requirement),"expectedLockVersion",version(task)),200).data();var sub=objects(task.get("subtasks")).getFirst();String execution=string(expect("create_execution","POST",API+"/subtasks/"+sub.get("id")+"/executions",admin(),Map.of("droneId",deviceId,"expectedLockVersion",version(sub)),200).data().get("id"));task=expect("refresh_task","GET",path,admin(),null,200).data();expect("start_task","POST",path+"/start",admin(),Map.of("expectedLockVersion",version(task)),200);return new Scope(id,requirement,string(sub.get("id")),execution);}

    private void verifyAuditSafety(){var rows=jdbc.queryForList("SELECT action,resource_type,resource_id,trace_id,result FROM audit_log WHERE trace_id=?",sharedTrace);require(!rows.isEmpty(),"real_business_audits_are_retained_append_only");for(var row:rows){String text=JSON.writeValueAsString(row);require(secrets.stream().noneMatch(text::contains)&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("X-Amz-"),"retained_audits_exclude_credentials_and_private_locations");}report.put("auditRowsRetained",rows.size());}
    private void saveCredentials()throws Exception{writePrivate(credentialPath,Map.of("accounts",accounts.stream().map(a->Map.of("id",a.id,"username",a.username,"password",a.password)).toList()));}

    private static void writePrivate(Path path,Object value)throws Exception{Path tmp=Files.createTempFile(path.getParent(),".reliability-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.write(tmp,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
    private Set<String> objectKeys(String upload){Set<String> keys=new LinkedHashSet<>();for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")){String continuation=null;do{var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(continuation).build());page.contents().forEach(o->keys.add(o.key()));continuation=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;}while(continuation!=null);}return keys;}

    private void cleanupFixtures(){
        Set<String> tasks=new LinkedHashSet<>();for(Account a:accounts)tasks.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,a.id));for(String task:tasks)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        for(String task:tasks){jdbc.update("DELETE FROM access_grant WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM issue WHERE task_id=?",task);jdbc.update("DELETE FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",task);jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);List<String> events=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,task,task);for(String event:events){jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);jdbc.update("DELETE FROM device_event WHERE execution_id IN (SELECT id FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?))",task);jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);}
        jdbc.update("DELETE FROM drone WHERE operator_org_id=? AND code=?",OPERATOR,"T023-"+runId.substring(0,12));for(Account a:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",a.id,a.username);for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"all_only_owned_part_and_final_objects_removed");report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",1);report.put("fixtureUploadSessionsDeleted",uploadIds.size());report.put("fixturePrivateObjectsDeleted",keys.size());report.put("fabricRecordsRetainedAppendOnly",true);
    }
    private void cleanupTopology(){rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue)){var state=channel.queueDeclarePassive(queue);require(state.getMessageCount()==0&&state.getConsumerCount()==0,"only_empty_unused_owned_queue_deleted");channel.queueDelete(queue,true,true);}channel.exchangeDelete(exchange,true);channel.exchangeDelete(deadExchange,true);return null;});report.put("ownedRabbitTopologyDeleted",true);}
    private Reply expect(String name,String method,String path,Account actor,Object body,int status)throws Exception{Reply result=requestHeaders(name,method,path,actor==null?Map.of():Map.of("Authorization","Bearer "+actor.token),body==null?null:JSON.writeValueAsBytes(body),body==null?null:"application/json");assertCode(result,status,status==200||status==202?"OK":status==400?"INVALID_ARGUMENT":status==401?"UNAUTHORIZED":status==403?"FORBIDDEN":"VERSION_CONFLICT",name);return result;}
    private void assertCode(Reply reply,int status,String code,String name){require(reply.status==status,name+"_http_"+status);require(code.equals(reply.body.get("code")),name+"_code");}
    private Reply requestHeaders(String name,String method,String path,Map<String,String> headers,byte[] bytes,String type)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(30)).header("X-Trace-Id",sharedTrace).header("Accept","application/json");headers.forEach(builder::header);if(type!=null)builder.header("Content-Type",type);builder.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());requests.add(Map.of("case",name,"status",response.statusCode(),"traceId",sharedTrace));require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),name+"_json");String text=new String(response.body(),StandardCharsets.UTF_8);boolean signed=path.endsWith("/download-url")&&response.statusCode()==200,credential=path.equals(API+"/auth/login")||path.equals(API+"/drones")&&method.equals("POST");if(!signed)require((credential||secrets.stream().noneMatch(text::contains))&&!text.contains("object_key")&&!text.contains("objectKey")&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("transactionBytes")&&!text.contains("commitBytes")&&!text.contains("X-Amz-"),name+"_safe_response");Map<String,Object> envelope=JSON.readValue(response.body(),Map.class);require(envelope.keySet().equals(Set.of("code","message","data","traceId")),name+"_uniform_envelope");require(sharedTrace.equals(envelope.get("traceId"))&&sharedTrace.equals(response.headers().firstValue("X-Trace-Id").orElse(null)),name+"_shared_trace_is_preserved");return new Reply(response.statusCode(),envelope);
    }
    private void writeReport()throws Exception{report.put("caseCount",requests.size());report.put("checkCount",checks.size());byte[] content=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);String text=new String(content,StandardCharsets.UTF_8);if(secrets.stream().anyMatch(text::contains)||text.contains("upload-parts/")||text.contains("evidence-files/")||text.contains("X-Amz-")||text.contains("Signature="))throw new ProbeFailure("unsafe_report");Files.createDirectories(reportPath.getParent());Path tmp=Files.createTempFile(reportPath.getParent(),".reliability-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.write(tmp,content);Files.move(tmp,reportPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
    private void require(boolean value,String name){checks.add(Map.of("check",name,"passed",value));if(!value)throw new ProbeFailure(name);}
    private Account owner(){return accounts.getFirst();}private Account admin(){return accounts.get(1);}private Account uploader(){return accounts.get(2);}
    private static String env(String key,String fallback){String value=System.getenv(key);if(value==null||value.isBlank()){if(fallback==null)throw new ProbeFailure("missing_required_environment");return fallback;}return value;}private static long version(Map<String,Object> row){return number(row.get("lockVersion"));}private static long number(Object value){if(!(value instanceof Number n))throw new ProbeFailure("expected_number");return n.longValue();}private static String string(Object value){if(!(value instanceof String text))throw new ProbeFailure("expected_string");return text;}private static Instant instant(Object value){if(value instanceof java.time.LocalDateTime t)return t.toInstant(java.time.ZoneOffset.UTC);if(value instanceof java.sql.Timestamp t)return t.toInstant();if(value instanceof Instant t)return t;throw new ProbeFailure("expected_jdbc_utc_timestamp");}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new ProbeFailure("expected_object");return (Map<String,Object>)value;}private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new ProbeFailure("expected_array");return list.stream().map(ProcessingRecoveryProbe::object).toList();}private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private static byte[] png(int color)throws Exception{var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);for(int x=0;x<8;x++)for(int y=0;y<8;y++)image.setRGB(x,y,color);var output=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",output))throw new ProbeFailure("png_encoder_unavailable");return output.toByteArray();}
    private static final class Account{final String id,username,password;String token;Account(String id,String username,String password){this.id=id;this.username=username;this.password=password;}@Override public String toString(){return "AuditAccount["+id+"]";}}private record Scope(String task,String requirement,String subtask,String execution){}private record Reply(int status,Map<String,Object> body){Map<String,Object> data(){return object(body.get("data"));}@Override public String toString(){return "AuditReply["+status+"]";}}private static final class ProbeFailure extends RuntimeException{ProbeFailure(String name){super(name);}}
    private void await(BooleanSupplier condition,String name)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(System.nanoTime()<deadline){if(condition.getAsBoolean()){require(true,name);return;}Thread.sleep(100);}require(false,name);}
    private void awaitSeconds(BooleanSupplier condition,String name,int seconds)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<deadline){if(condition.getAsBoolean()){require(true,name);return;}Thread.sleep(100);}require(false,name);}
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
    static final class Gate {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicBoolean used=new AtomicBoolean();
        void await()throws IOException {
            entered.countDown();
            try {if(!release.await(190,TimeUnit.SECONDS))throw new IOException("T023_SAFE_GATE_TIMEOUT");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("T023_SAFE_GATE_INTERRUPTED");}
        }
    }
    static final class FaultPlan {
        final Map<String,Gate> storageGates=new ConcurrentHashMap<>();
        final Map<String,FinalizationTransactions.Claim> finalClaims=new ConcurrentHashMap<>();
        final Map<String,AnchorTransactions.Claim> anchorClaims=new ConcurrentHashMap<>();
        final Map<String,AtomicInteger> finalRenewals=new ConcurrentHashMap<>();
        final Set<String> recoveryClaimedUploads=ConcurrentHashMap.newKeySet(),recoveryClaimedAnchors=ConcurrentHashMap.newKeySet();
        final AtomicReference<String> failRenewUpload=new AtomicReference<>(),failOpenUpload=new AtomicReference<>();
        final AtomicInteger failedFileRenewals=new AtomicInteger(),anchorRenewals=new AtomicInteger(),droppedCommitReplies=new AtomicInteger();
        final AtomicBoolean dropCommitReply=new AtomicBoolean();
        volatile Gate anchorGate;
        void releaseAll(){storageGates.values().forEach(g->g.release.countDown());if(anchorGate!=null)anchorGate.release.countDown();failRenewUpload.set(null);failOpenUpload.set(null);dropCommitReply.set(false);}
    }
    @TestConfiguration(proxyBeanMethods=false)
    @Profile("processing-recovery-probe")
    static class FaultConfiguration {
        @Bean FaultPlan processingRecoveryFaultPlan(){return new FaultPlan();}
        @Bean static BeanPostProcessor pauseOnlyOwnBrokerConsumers(){return new BeanPostProcessor(){
            @Override public Object postProcessBeforeInitialization(Object bean,String name){if(bean instanceof SimpleMessageListenerContainer container)container.setAutoStartup(false);return bean;}
        };}
        @Bean @Primary FaultFinalizationTransactions recoveryFinalizationTransactions(UploadMapper uploads,FinalizationMapper work,OutboxWriter writer,FaultPlan faults){return new FaultFinalizationTransactions(uploads,work,writer,faults);}
        @Bean @Primary FaultAnchorTransactions recoveryAnchorTransactions(AnchorMapper mapper,FaultPlan faults){return new FaultAnchorTransactions(mapper,faults);}
        @Bean @Primary FaultStorage recoveryStorage(@Qualifier("objectStorage")ObjectStorage real,FaultPlan faults){return new FaultStorage(real,faults);}
        @Bean @Primary InstitutionLedgerClients recoveryLedgerClients(@Qualifier("institutionLedgerClients")InstitutionLedgerClients real,FaultPlan faults){
            return new InstitutionLedgerClients(real.forOrganization(OWNER),new FaultLedger(real.forOrganization(OPERATOR),faults),real.mspId(OWNER),real.mspId(OPERATOR)){
                @Override public void close(){/* The original bean owns both Gateway lifetimes. */}
            };
        }
    }
    static class FaultFinalizationTransactions extends FinalizationTransactions {
        private final FaultPlan faults;
        FaultFinalizationTransactions(UploadMapper uploads,FinalizationMapper work,OutboxWriter writer,FaultPlan faults){super(uploads,work,writer);this.faults=faults;}
        @Override @Transactional public Claim claim(MessageEvent event,boolean recovery) {
            Claim claim=super.claim(event,recovery);
            if(claim!=null){faults.finalClaims.put(event.bizId(),claim);if(recovery)faults.recoveryClaimedUploads.add(event.bizId());}
            return claim;
        }
        @Override @Transactional public boolean renew(Claim claim) {
            if(claim.session().id().equals(faults.failRenewUpload.get())){faults.failedFileRenewals.incrementAndGet();throw new DataAccessResourceFailureException("T023_SAFE_RENEWAL_DATABASE_UNAVAILABLE");}
            boolean renewed=super.renew(claim);if(renewed)faults.finalRenewals.computeIfAbsent(claim.session().id(),ignored->new AtomicInteger()).incrementAndGet();return renewed;
        }
    }
    static class FaultAnchorTransactions extends AnchorTransactions {
        private final FaultPlan faults;
        FaultAnchorTransactions(AnchorMapper mapper,FaultPlan faults){super(mapper);this.faults=faults;}
        @Override @Transactional public Claim claim(MessageEvent event,boolean recovery) {
            Claim claim=super.claim(event,recovery);if(claim!=null){faults.anchorClaims.put(event.bizId(),claim);if(recovery)faults.recoveryClaimedAnchors.add(event.bizId());}return claim;
        }
        @Override @Transactional public boolean renew(Claim claim){boolean renewed=super.renew(claim);if(renewed)faults.anchorRenewals.incrementAndGet();return renewed;}
    }
    static final class FaultStorage implements ObjectStorage {
        private final ObjectStorage real;private final FaultPlan faults;
        FaultStorage(ObjectStorage real,FaultPlan faults){this.real=real;this.faults=faults;}
        @Override public void put(String key,Path file,String type){real.put(key,file,type);}
        @Override public InputStream open(String key) {
            String failed=faults.failOpenUpload.get();
            if(failed!=null&&key.startsWith("upload-parts/"+failed+"/")&&faults.failOpenUpload.compareAndSet(failed,null))throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            InputStream input=real.open(key);
            Gate selected=null;
            for(var entry:faults.storageGates.entrySet())if(key.startsWith("upload-parts/"+entry.getKey()+"/")&&entry.getValue().used.compareAndSet(false,true)){selected=entry.getValue();break;}
            if(selected==null)return input;
            Gate gate=selected;
            return new FilterInputStream(input) {
                private boolean waited;
                private void pauseAfterFirstRealRead(int count)throws IOException{if(!waited&&count>0){waited=true;gate.await();}}
                // AWS ResponseInputStream aborts if no read starts within its 60-second default.
                // Read real bytes first to cancel that timer, then hold the open stream and caller's buffer.
                @Override public int read(byte[] bytes,int offset,int length)throws IOException{int count=super.read(bytes,offset,length);pauseAfterFirstRealRead(count);return count;}
                @Override public int read()throws IOException{int value=super.read();pauseAfterFirstRealRead(value<0?0:1);return value;}
            };
        }
        @Override public void delete(String key){real.delete(key);}
    }
    static final class FaultLedger implements EvidenceLedgerClient {
        private final EvidenceLedgerClient real;private final FaultPlan faults;
        FaultLedger(EvidenceLedgerClient real,FaultPlan faults){this.real=real;this.faults=faults;}
        @Override public PreparedRegistration prepareRegistration(String evidenceId,int version,String hash)throws org.hyperledger.fabric.client.EndorseException{return real.prepareRegistration(evidenceId,version,hash);}
        @Override public CommitCheckpoint submitPrepared(PreparedRegistration prepared)throws org.hyperledger.fabric.client.SubmitException{return real.submitPrepared(prepared);}
        @Override public CommitOutcome queryCommit(CommitCheckpoint checkpoint)throws org.hyperledger.fabric.client.CommitStatusException {
            CommitOutcome actual=real.queryCommit(checkpoint);
            if(faults.dropCommitReply.compareAndSet(true,false)) {
                if(!actual.valid())throw new ProbeFailure("injected_reply_loss_requires_actual_valid_commit");
                faults.droppedCommitReplies.incrementAndGet();throw new IllegalStateException("T023_SAFE_COMMIT_REPLY_LOST");
            }
            return actual;
        }
        @Override public EvidenceQuery queryEvidence(String evidenceId,int version)throws org.hyperledger.fabric.client.GatewayException {
            EvidenceQuery result=real.queryEvidence(evidenceId,version);Gate gate=faults.anchorGate;
            if(gate!=null&&gate.used.compareAndSet(false,true))try{gate.await();}catch(IOException unavailable){throw new IllegalStateException("T023_SAFE_FABRIC_GATE_UNAVAILABLE");}
            return result;
        }
        @Override public void close(){/* Original clients own the connections. */}
    }
}
