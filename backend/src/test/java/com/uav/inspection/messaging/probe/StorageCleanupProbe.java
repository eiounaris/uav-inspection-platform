package com.uav.inspection.messaging.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.lease.ProcessingLeaseKeeper;
import com.uav.inspection.common.temp.TemporaryFileManager;
import com.uav.inspection.device.DeviceAuthenticationService;
import com.uav.inspection.evidence.cleanup.CleanupProcessor;
import com.uav.inspection.evidence.cleanup.CleanupTransactions;
import com.uav.inspection.evidence.cleanup.CleanupSettings;
import com.uav.inspection.evidence.cleanup.persistence.CleanupMapper;
import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import com.uav.inspection.evidence.finalization.FinalizationTransactions;
import com.uav.inspection.evidence.storage.ObjectInventory;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.evidence.storage.UploadSettings;
import com.uav.inspection.evidence.upload.UploadModels.PartCommit;
import com.uav.inspection.evidence.upload.UploadTransactions;
import com.uav.inspection.evidence.upload.persistence.UploadMapper;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
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

/** Real T025 reference protection, durable retries and cross-process temporary-file ownership. */
public final class StorageCleanupProbe {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String API="/api/v1",OWNER="00000000-0000-4000-8000-000000000001",OPERATOR="00000000-0000-4000-8000-000000000002";
    private static final Map<String,String> TABLE_ORDER=new LinkedHashMap<>();
    static {
        TABLE_ORDER.put("organization","id");TABLE_ORDER.put("app_user","id");TABLE_ORDER.put("inspection_task","id");TABLE_ORDER.put("task_member","task_id,user_id");TABLE_ORDER.put("drone","id");TABLE_ORDER.put("inspection_subtask","id");TABLE_ORDER.put("material_requirement","id");TABLE_ORDER.put("task_execution","id");TABLE_ORDER.put("upload_session","id");TABLE_ORDER.put("upload_part","session_id,part_no");TABLE_ORDER.put("evidence_version","id");TABLE_ORDER.put("outbox_event","event_id");TABLE_ORDER.put("consumed_event","consumer_code,event_id");TABLE_ORDER.put("chain_submission","id");TABLE_ORDER.put("chain_attempt","id");TABLE_ORDER.put("verification_record","id");TABLE_ORDER.put("device_event","id");TABLE_ORDER.put("execution_adjustment","id");TABLE_ORDER.put("issue","id");TABLE_ORDER.put("review_record","id");TABLE_ORDER.put("access_grant","id");TABLE_ORDER.put("message_rejection","id");TABLE_ORDER.put("anchor_retry_request","id");TABLE_ORDER.put("storage_cleanup_task","id");
    }
    private final String runId=UUID.randomUUID().toString(),sharedTrace="t025-"+runId;
    private final String exchange="inspection.cleanup.probe."+runId,finalizeQueue=exchange+".finalize",anchorQueue=exchange+".anchor",deadExchange=exchange+".dead.events",deadQueue=exchange+".dead";
    private final Path reportPath,runDirectory,tempDirectory;
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<Map<String,Object>> checks=Collections.synchronizedList(new ArrayList<>()),requests=Collections.synchronizedList(new ArrayList<>());
    private final List<Account> accounts=new ArrayList<>();
    private final List<String> secrets=new CopyOnWriteArrayList<>();
    private final Set<String> uploadIds=new LinkedHashSet<>(),actualUploads=new LinkedHashSet<>();
    private final Map<String,byte[]> uploadBytes=new LinkedHashMap<>();
    private final List<CompletableFuture<Void>> workers=new ArrayList<>();
    private JdbcTemplate jdbc;private DataSource dataSource;private S3Client s3;private ObjectStorage storage;private ObjectInventory inventory;
    private RabbitTemplate rabbit;private OutboxPublisher publisher;private FinalizationProcessor processor;private FinalizationTransactions finalPhases;
    private CleanupProcessor cleanup;private CleanupTransactions cleanupPhases;private FaultPlan faults;private HttpClient client;
    private SimpleMessageListenerContainer finalizeContainer;private String baseUrl,bucket,taskId,deviceId;private Scope scope;private byte[] baseline;

    private StorageCleanupProbe(Path output){reportPath=output.toAbsolutePath().normalize();runDirectory=reportPath.getParent();tempDirectory=runDirectory.resolve("managed-temp");report.put("formatVersion",1);report.put("runId",runId);report.put("startedAt",Instant.now().toString());report.put("passed",false);report.put("checks",checks);report.put("requests",requests);}
    public static void main(String[] args){
        try {
            if(args.length>=1 && args[0].startsWith("temp-")){temporaryChild(args);return;}
            if(args.length!=1){System.err.println("Usage: StorageCleanupProbe <private-report-json>");System.exit(2);return;}
            StorageCleanupProbe probe=new StorageCleanupProbe(Path.of(args[0]));
            try{probe.execute();probe.writeReport();System.out.println("[PASS] T025 cleanup report: "+probe.reportPath);}
            catch(Exception failure){probe.report.put("passed",false);probe.report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof ProbeFailure)probe.report.put("failedCheck",failure.getMessage());try{probe.writeReport();}catch(Exception ignored){}System.err.println("[FAIL] T025 cleanup probe; inspect private safe report");System.exit(1);}
        }catch(Exception failure){System.err.println("[FAIL] T025 temporary-file child");System.exit(1);}
    }
    private void execute()throws Exception{
        openDatabase();requireNoForeignWork();baseline=businessSnapshot();report.put("originalBusinessSnapshotSha256",HexFormat.of().formatHex(baseline));report.put("businessCountsBefore",businessCounts());
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,FaultConfiguration.class)
                .profiles("local","database","storage","messaging","cleanup","storage-cleanup-probe")
                .web(WebApplicationType.SERVLET).logStartupInfo(false).run("--server.port=0",
                "--inspection.messaging.exchange="+exchange,"--inspection.messaging.finalize-queue="+finalizeQueue,
                "--inspection.messaging.anchor-queue="+anchorQueue,"--inspection.messaging.dead-letter-exchange="+deadExchange,
                "--inspection.messaging.dead-letter-queue="+deadQueue,"--inspection.messaging.publisher-enabled=true",
                "--inspection.messaging.recovery-enabled=false","--inspection.cleanup.scheduler-enabled=false",
                "--inspection.cleanup.grace-seconds=2","--inspection.cleanup.batch-size=100",
                "--inspection.upload.session-ttl-seconds=20","--inspection.upload.temp-directory="+tempDirectory,
                "--inspection.verification.temp-directory="+tempDirectory.resolve("verification"))){
            jdbc=context.getBean(JdbcTemplate.class);dataSource=context.getBean(DataSource.class);s3=context.getBean(S3Client.class);storage=context.getBean(ObjectStorage.class);inventory=context.getBean(ObjectInventory.class);
            rabbit=context.getBean(RabbitTemplate.class);publisher=context.getBean(OutboxPublisher.class);processor=context.getBean(FinalizationProcessor.class);finalPhases=context.getBean(FinalizationTransactions.class);
            cleanup=context.getBean(CleanupProcessor.class);cleanupPhases=context.getBean(CleanupTransactions.class);faults=context.getBean(FaultPlan.class);finalizeContainer=context.getBean("finalizationContainer",SimpleMessageListenerContainer.class);
            report.put("effectiveCleanupGraceSeconds",context.getBean(CleanupSettings.class).graceSeconds());
            bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            require(!finalizeContainer.isRunning(),"own_consumer_paused_for_controlled_storage_lifecycle");
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
                client=actual;
                try{
                    createFixtures();scope=createTask();taskId=scope.task;
                    verifyObjectLifecycle();verifyTemporaryOwnership();verifyCleanupAuditScope();
                    publisher.publishBatch();await(()->ownedPendingOutbox()==0,"all_owned_business_events_have_real_publish_confirms");
                    report.put("probeGraceSeconds",2);report.put("probeUploadTtlSeconds",20);report.put("productionDefaultGraceSeconds",3600);
                    report.put("realLedgerRegistrations",0);report.put("fabricDisabled",true);
                }finally{
                    faults.clear();for(var worker:workers)try{worker.get(30,TimeUnit.SECONDS);}catch(ExecutionException ignored){}
                    context.getBean(ThreadPoolTaskScheduler.class).shutdown();context.getBean(BusinessRecoveryDispatcher.class).close();context.getBean(ProcessingLeaseKeeper.class).shutdown();finalizeContainer.stop();
                    report.put("cleanupAttempted",true);cleanupOwnedQueues();cleanupFixtures();cleanupTopology();
                    require(MessageDigest.isEqual(baseline,businessSnapshot()),"all_original_twenty_four_table_fields_and_binary_checkpoints_preserved");report.put("originalBusinessDataUnchanged",true);report.put("businessCountsAfter",businessCounts());
                }
            }
        }
        report.put("stage","complete");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }
    private void requireNoForeignWork(){
        require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version='14' AND success=1")==1,"fourteen_existing_migrations_present");
        require(count("SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','SENDING')")==0,"no_foreign_pending_outbox_before_workers");
        require(count("SELECT COUNT(*) FROM upload_session WHERE status<>'COMPLETED'")==0,"no_foreign_expirable_or_processing_upload_before_global_expiry_scan");
        require(count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1 AND next_action_at IS NOT NULL)")==0,"no_foreign_pending_anchor_before_workers");
        require(count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'")==0,"no_foreign_unfinished_consumption_before_workers");
        require(count("SELECT COUNT(*) FROM storage_cleanup_task WHERE status IN ('PENDING','SENDING')")==0,"no_foreign_cleanup_work_before_scoped_inventory");
    }
    private void verifyObjectLifecycle()throws Exception{
        report.put("stage","registered_and_formal_reference_protection");
        String completed=createUpload(0x2569a3);storePart(completed,200);complete(completed);finalizeUpload(completed);
        String uncertain=createUpload(0x7e8c35);faults.afterCommit.set(uncertain);storePart(uncertain,503);
        String uncertainPart=partKey(uncertain);require(objectExists(uncertainPart)&&count("SELECT COUNT(*) FROM upload_part WHERE session_id=?",uncertain)==1,"actual_committed_part_survives_lost_transaction_return");
        storePart(uncertain,200);complete(uncertain);finalizeUpload(uncertain);
        String verifying=createUpload(0xa85839);storePart(verifying,200);complete(verifying);
        var event=fileEvent(verifying);var claim=finalPhases.claim(event,true);require(claim!=null,"real_verifying_claim_has_current_processing_lease");
        String activeCandidate="evidence-files/"+verifying+"/"+claim.token();putFixtureObject(activeCandidate,uploadBytes.get(verifying));
        String uncommitted=createUpload(0x9a4ca3);faults.beforeCommit.set(uncommitted);storePart(uncommitted,503);
        String orphanPart=faults.lastPartPut.get(uncommitted);require(orphanPart!=null&&objectExists(orphanPart)&&count("SELECT COUNT(*) FROM upload_part WHERE session_id=?",uncommitted)==0,"before_commit_failure_leaves_unreferenced_real_object");
        String expired=createUpload(0x3c9b84);storePart(expired,200);String expiringPart=partKey(expired);
        String absent=UUID.randomUUID().toString();ownUpload(absent);String retryKey="evidence-files/"+absent+"/"+UUID.randomUUID();putFixtureObject(retryKey,png(0x2b3c4d));
        String invalid="upload-parts/"+completed+"/unrecognized-object";putFixtureObject(invalid,new byte[]{3,4,5});
        String foreign="foreign-t025/"+runId;putFixtureObject(foreign,new byte[]{7,8,9});faults.extraFixtureKeys.add(foreign);
        awaitOld(retryKey);
        Instant databaseNow=instant(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.time.LocalDateTime.class));
        List<Map<String,Object>> inventoryCheckpoint=new ArrayList<>();
        for(String prefix:List.of("upload-parts/","evidence-files/")){
            var page=inventory.list(prefix,null,100);
            inventoryCheckpoint.add(Map.of("namespace",prefix.startsWith("upload")?"parts":"formal","ownedEntries",page.entries().size(),"hasContinuation",page.continuation()!=null,
                    "retryCandidatePresent",page.entries().stream().anyMatch(e->e.key().equals(retryKey)),
                    "oldEnoughEntries",page.entries().stream().filter(e->e.lastModified()!=null&&!e.lastModified().isAfter(databaseNow.minusSeconds(2))).count()));
        }
        report.put("firstInventoryCheckpoint",inventoryCheckpoint);
        faults.failDeleteOnce.set(retryKey);cleanup.runOnce();
        require(objectExists(partKey(completed))&&objectExists(formalKey(completed))&&objectExists(uncertainPart)&&objectExists(formalKey(uncertain)),"registered_parts_and_formal_versions_survive_collection");
        require(objectExists(partKey(verifying))&&objectExists(activeCandidate),"verifying_source_parts_and_current_live_candidate_are_protected");
        require(objectExists(orphanPart)&&"UPLOADING".equals(status(uncommitted)),"active_upload_protects_unregistered_inflight_candidate");
        require(objectExists(invalid)&&objectExists(foreign),"unrecognized_and_foreign_object_names_are_never_deleted");
        String retryTask=cleanupTaskId(retryKey);
        if(retryTask!=null)report.put("firstDeleteRetryCheckpoint",jdbc.queryForMap("SELECT status,attempt_count,(lease_token IS NULL AND lease_until IS NULL) AS lease_free,next_action_at,updated_at,UTC_TIMESTAMP(3) AS observed_at FROM storage_cleanup_task WHERE id=?",retryTask));
        report.put("deleteFailuresObservedAfterFirstCycle",faults.deleteFailures.get());
        require(retryTask!=null&&count("SELECT COUNT(*) FROM storage_cleanup_task WHERE id=? AND status='PENDING' AND attempt_count=1 AND lease_token IS NULL AND next_action_at>=TIMESTAMPADD(SECOND,2,updated_at)",retryTask)==1,"actual_delete_failure_persists_safe_durable_backoff");
        require(objectExists(retryKey)&&faults.deleteFailures.get()==1,"injected_delete_failure_does_not_claim_object_removed");
        await(()->count("SELECT COUNT(*) FROM storage_cleanup_task WHERE id=? AND next_action_at<=UTC_TIMESTAMP(3)",retryTask)==1,"cleanup_retry_plan_naturally_becomes_due");cleanup.runOnce();
        require(!objectExists(retryKey)&&count("SELECT COUNT(*) FROM storage_cleanup_task WHERE id=? AND status='DONE' AND attempt_count>=2",retryTask)==1,"durable_retry_reissues_real_idempotent_s3_delete");
        putFixtureObject(retryKey,png(0x2b3c4d));awaitOld(retryKey);cleanup.runOnce();require(!objectExists(retryKey)&&retryTask.equals(cleanupTaskId(retryKey)),"reappearing_orphan_rearms_existing_completed_cleanup_intent");
        require(finalPhases.checkpoint(claim,false,"FILE_IO_UNAVAILABLE"),"verifying_worker_commits_recoverable_plan_before_releasing_lease");cleanup.runOnce();
        require(!objectExists(activeCandidate)&&objectExists(partKey(verifying)),"unreferenced_old_candidate_removed_while_retry_source_parts_remain");
        await(()->count("SELECT COUNT(*) FROM upload_session WHERE id=? AND next_action_at<=UTC_TIMESTAMP(3)",verifying)==1,"file_retry_plan_naturally_becomes_due");finalizeUpload(verifying);
        verifyFinalizationRace();
        report.put("stage","natural_expiry_and_retirement");
        awaitSeconds(()->count("SELECT COUNT(*) FROM upload_session WHERE id IN (?,?) AND expires_at<=TIMESTAMPADD(SECOND,-2,UTC_TIMESTAMP(3))",uncommitted,expired)==2,"natural_short_upload_expiry_and_grace_elapsed",35);
        cleanup.runOnce();cleanup.runOnce();
        require("EXPIRED".equals(status(uncommitted))&&"EXPIRED".equals(status(expired)),"expiry_transition_uses_real_database_deadline");
        require(count("SELECT COUNT(*) FROM upload_part WHERE session_id=?",expired)==0&&!objectExists(expiringPart)&&!objectExists(orphanPart),"expired_registered_part_and_unregistered_attempt_are_actually_reclaimed");
        var progress=expect("expired_upload_progress","GET",API+"/upload-sessions/"+expired,uploader(),null,200).data();
        require("EXPIRED".equals(progress.get("processingStatus"))&&objects(progress.get("uploadedParts")).isEmpty()&&number(progress.get("uploadedSize"))==0,"expired_progress_reports_retired_parts_without_private_keys");
        for(String id:List.of(completed,uncertain,verifying))verifyFixedBytes(id);
        report.put("finalizedVersions",count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",taskId));
        report.put("committedPartOutcomeUnknownProtected",true);report.put("beforeCommitOrphanCollectedAfterExpiry",true);report.put("activeUploadAndProcessingLeaseProtected",true);report.put("durableDeleteRetryVerified",true);report.put("reappearingOrphanCollected",true);report.put("naturalExpiryAndRetirementVerified",true);report.put("invalidAndForeignNamespacesPreserved",true);
        report.put("injectedDeleteFailures",faults.deleteFailures.get());report.put("injectedAfterCommitReturnsLost",faults.afterCommitFailures.get());report.put("injectedBeforeCommitFailures",faults.beforeCommitFailures.get());
    }
    private void verifyFinalizationRace()throws Exception{
        String id=createUpload(0x693fba);storePart(id,200);complete(id);Gate putGate=new Gate();faults.finalPutGate=putGate;faults.finalPutUpload.set(id);
        var finalizing=CompletableFuture.runAsync(processor::recoverDue);workers.add(finalizing);require(putGate.entered.await(15,TimeUnit.SECONDS),"real_finalizer_is_paused_after_actual_s3_candidate_write");
        String candidate=faults.finalPutKey.get();require(candidate!=null&&objectExists(candidate),"inflight_candidate_exists_before_final_transaction");awaitOld(candidate);
        var entry=inventory.head(candidate);String staleId=UUID.randomUUID().toString();
        require(jdbc.update("""
                INSERT INTO storage_cleanup_task(id,object_key,object_key_hash,upload_id,object_last_modified,object_size)
                VALUES(?,?,?,?,?,?)
                """,staleId,candidate,sha256(candidate.getBytes(StandardCharsets.UTF_8)),id,java.sql.Timestamp.from(entry.lastModified()),entry.size())==1,"synthetic_stale_cleanup_intent_inserted_only_for_owned_candidate");
        Gate claimGate=new Gate();faults.claimGate=claimGate;faults.claimGateId.set(staleId);
        var collecting=CompletableFuture.runAsync(cleanup::runOnce);workers.add(collecting);require(claimGate.entered.await(15,TimeUnit.SECONDS),"collector_waits_before_real_claim_transaction_without_holding_database_locks");
        putGate.release.countDown();finalizing.get(25,TimeUnit.SECONDS);require(count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=?",id)==1,"actual_final_transaction_commits_reference_before_cleanup_claim");
        claimGate.release.countDown();collecting.get(25,TimeUnit.SECONDS);faults.finalPutUpload.set(null);faults.claimGateId.set(null);
        require(objectExists(candidate)&&count("SELECT COUNT(*) FROM storage_cleanup_task WHERE id=? AND status='SKIPPED'",staleId)==1,"cleanup_claim_rechecks_new_formal_reference_and_skips_stale_intent");verifyFixedBytes(id);
        report.put("staleCleanupIntentRecheckedAfterActualFinalization",true);report.put("syntheticStaleIntentFixtures",1);
    }
    private String createUpload(int color)throws Exception{byte[] bytes=png(color);String id=string(expect("create_upload","POST",API+"/upload-sessions",uploader(),Map.of("clientRequestId",UUID.randomUUID().toString(),"requirementId",scope.requirement,"executionId",scope.execution,"filename","inspection.png","contentType","image/png","size",bytes.length),200).data().get("uploadId"));ownUpload(id);actualUploads.add(id);uploadBytes.put(id,bytes);return id;}
    private void ownUpload(String id){uploadIds.add(id);faults.ownUploads.add(id);}
    private void storePart(String id,int expected)throws Exception{var r=requestHeaders("store_part","PUT",API+"/upload-sessions/"+id+"/parts/1",Map.of("Authorization","Bearer "+uploader().token),uploadBytes.get(id),"application/octet-stream");assertCode(r,expected,expected==200?"OK":"DEPENDENCY_UNAVAILABLE","store_part");}
    private void complete(String id)throws Exception{expect("complete_upload","POST",API+"/upload-sessions/"+id+"/complete",uploader(),null,202);}
    private MessageEvent fileEvent(String id){return MessageEvent.decode(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type='FILE_FINALIZE_REQUESTED' AND biz_id=?",String.class,id).getBytes(StandardCharsets.UTF_8));}
    private void finalizeUpload(String id)throws Exception{processor.recoverDue();require("COMPLETED".equals(status(id)),"production_finalizer_creates_real_fixed_version");verifyFixedBytes(id);}
    private void verifyFixedBytes(String id)throws Exception{String key=formalKey(id);try(InputStream in=storage.open(key)){require(Arrays.equals(in.readAllBytes(),uploadBytes.get(id)),"formal_object_retains_complete_original_bytes");}require(count("SELECT COUNT(*) FROM upload_part WHERE session_id=?",id)==1,"completed_upload_keeps_registered_source_part");}
    private String partKey(String id){return jdbc.queryForObject("SELECT object_key FROM upload_part WHERE session_id=?",String.class,id);}
    private String formalKey(String id){return jdbc.queryForObject("SELECT object_key FROM evidence_version WHERE upload_session_id=?",String.class,id);}
    private String status(String id){return jdbc.queryForObject("SELECT status FROM upload_session WHERE id=?",String.class,id);}
    private String cleanupTaskId(String key){return jdbc.queryForList("SELECT id FROM storage_cleanup_task WHERE object_key_hash=?",String.class,safeHash(key)).stream().findFirst().orElse(null);}
    private static String safeHash(String value){try{return sha256(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new ProbeFailure("hash_unavailable");}}
    private boolean objectExists(String key){try{s3.headObject(r->r.bucket(bucket).key(key));return true;}catch(S3Exception missing){if(missing.statusCode()==404)return false;throw missing;}}
    private void awaitOld(String key)throws Exception{await(()->{
        var entry=inventory.head(key);
        // HEAD's HTTP Last-Modified has whole-second precision; LIST may retain milliseconds.
        // Both production gates must naturally satisfy database UTC grace before invoking a cycle.
        ObjectInventory.Entry listed=null;String continuation=null;
        do {
            var page=inventory.list(key.startsWith("upload-parts/")?"upload-parts/":"evidence-files/",continuation,100);
            listed=page.entries().stream().filter(candidate->key.equals(candidate.key())).findFirst().orElse(null);
            continuation=page.continuation();
        }while(listed==null&&continuation!=null);
        Instant now=instant(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.time.LocalDateTime.class));
        if(entry!=null&&listed!=null&&entry.lastModified()!=null&&listed.lastModified()!=null)
            report.put("lastMetadataPrecisionDifferenceMillis",Duration.between(entry.lastModified(),listed.lastModified()).toMillis());
        return entry!=null&&entry.lastModified()!=null&&listed!=null&&listed.lastModified()!=null
                &&!entry.lastModified().isAfter(now.minusSeconds(2))&&!listed.lastModified().isAfter(now.minusSeconds(2));
    },"real_object_age_passes_database_grace");}
    private void putFixtureObject(String key,byte[] bytes)throws Exception{Path file=Files.createTempFile(runDirectory,"fixture-",".bin",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.write(file,bytes);storage.put(key,file,"application/octet-stream");}finally{Files.deleteIfExists(file);}}
    private long ownedPendingOutbox(){return count("SELECT COUNT(*) FROM outbox_event WHERE (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))) AND status<>'SENT'",taskId,taskId);}

    private void verifyTemporaryOwnership()throws Exception {
        report.put("stage","cross_jvm_temporary_file_ownership");
        Path directory=runDirectory.resolve("cross-jvm-temp");Files.createDirectories(directory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path ready=runDirectory.resolve("temp-active-ready.json"),stop=runDirectory.resolve("temp-active-stop"),sweepReport=runDirectory.resolve("temp-active-sweep.json");
        Process holder=startChild("temp-active",directory,ready,stop);
        try {
            await(()->Files.isRegularFile(ready),"separate_jvm_has_live_managed_temporary_file");
            String filename=string(JSON.readValue(Files.readAllBytes(ready),Map.class).get("file"));
            Process sweep=startChild("temp-sweep",directory,sweepReport,stop);require(sweep.waitFor(20,TimeUnit.SECONDS)&&sweep.exitValue()==0,"second_jvm_active_file_sweep_completes");
            Map<String,Object> result=JSON.readValue(Files.readAllBytes(sweepReport),Map.class);
            require(Files.isRegularFile(directory.resolve(filename))&&number(result.get("locked"))>=1&&number(result.get("deleted"))==0,"cross_jvm_cleanup_cannot_delete_older_active_file");
        }finally{Files.writeString(stop,"stop",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);if(!holder.waitFor(15,TimeUnit.SECONDS)){holder.destroyForcibly();holder.waitFor(5,TimeUnit.SECONDS);}}
        require(holder.exitValue()==0,"owned_holder_closes_without_forced_process_termination");
        Path crashed=runDirectory.resolve("temp-crash-ready.json");Process crash=startChild("temp-crash",directory,crashed,stop);require(crash.waitFor(15,TimeUnit.SECONDS)&&crash.exitValue()==73,"separate_jvm_halts_with_managed_temp_handle_open");
        String crashName=string(JSON.readValue(Files.readAllBytes(crashed),Map.class).get("file"));require(Files.isRegularFile(directory.resolve(crashName)),"halt_retains_actual_temporary_file_residue");
        Path target=runDirectory.resolve("foreign-target.bin");Files.write(target,new byte[]{19,20,21});Path link=directory.resolve("upload-777777.part");Files.createSymbolicLink(link,target);
        Path foreign=directory.resolve("foreign.keep");Files.write(foreign,new byte[]{22,23});Files.setLastModifiedTime(foreign,FileTime.from(Instant.now().minusSeconds(7200)));
        Path finalSweep=runDirectory.resolve("temp-restart-sweep.json");Process restarted=startChild("temp-sweep",directory,finalSweep,stop);require(restarted.waitFor(20,TimeUnit.SECONDS)&&restarted.exitValue()==0,"new_jvm_sweeps_crashed_process_residue");
        var result=JSON.readValue(Files.readAllBytes(finalSweep),Map.class);
        require(!Files.exists(directory.resolve(crashName))&&number(result.get("deleted"))>=1,"released_crash_lock_allows_actual_old_file_removal");
        require(Files.isSymbolicLink(link)&&Arrays.equals(Files.readAllBytes(target),new byte[]{19,20,21})&&Files.isRegularFile(foreign),"symlink_target_and_foreign_temporary_names_are_preserved");
        Files.deleteIfExists(link);Files.deleteIfExists(foreign);Files.deleteIfExists(target);Files.deleteIfExists(stop);
        report.put("activeTemporaryFileProtectedAcrossJvms",true);report.put("crashResidueReclaimedByNewJvm",true);report.put("symlinkAndForeignTemporaryNamesProtected",true);
    }
    private Process startChild(String mode,Path directory,Path output,Path stop)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();Path log=runDirectory.resolve(mode+"-"+UUID.randomUUID()+".log");Files.createFile(log,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),StorageCleanupProbe.class.getName(),mode,directory.toString(),output.toString(),stop.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void temporaryChild(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Invalid child arguments");Path directory=Path.of(args[1]),output=Path.of(args[2]),stop=Path.of(args[3]);var manager=new TemporaryFileManager();
        if(args[0].equals("temp-sweep")){
            var result=manager.sweep(directory,Duration.ofSeconds(2),100);writePrivate(output,Map.of("inspected",result.inspected(),"deleted",result.deleted(),"locked",result.locked(),"skipped",result.skipped(),"failed",result.failed()));return;
        }
        try(var file=manager.create(directory,"upload-",".part")){
            Files.write(file.path(),new byte[]{11,12,13});Files.setLastModifiedTime(file.path(),FileTime.from(Instant.now().minusSeconds(7200)));writePrivate(output,Map.of("file",file.path().getFileName().toString()));
            if(args[0].equals("temp-crash")){System.out.flush();System.err.flush();Runtime.getRuntime().halt(73);}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!Files.exists(stop)&&System.nanoTime()<deadline)Thread.sleep(50);
            if(!Files.exists(stop))throw new IllegalStateException("holder timed out");
        }
    }
    private static void writePrivate(Path path,Object value)throws Exception{Path tmp=Files.createTempFile(path.getParent(),".cleanup-probe-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.write(tmp,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
    private void verifyCleanupAuditScope()throws Exception {
        var audit=expect("task_cleanup_audit_history","GET",API+"/tasks/"+taskId+"/audit-logs?resourceType=CLEANUP_TASK&page=1&pageSize=100",admin(),null,200).data();
        Set<String> expected=new HashSet<>(jdbc.queryForList("SELECT a.id FROM audit_log a JOIN storage_cleanup_task c ON a.resource_type='CLEANUP_TASK' AND a.resource_id=c.id JOIN upload_session u ON u.id=c.upload_id WHERE u.task_id=?",String.class,taskId));
        require(number(audit.get("total"))==expected.size(),"cleanup_audit_scope_matches_actual_upload_task_not_shared_trace");
        Set<String> returned=new HashSet<>();for(var item:objects(audit.get("items")))returned.add(string(item.get("id")));require(returned.equals(expected)&&!returned.isEmpty(),"cleanup_audit_exposes_owned_job_metadata_and_excludes_missing_upload_orphan");
        var rows=jdbc.queryForList("SELECT action,resource_type,resource_id,trace_id,result FROM audit_log WHERE trace_id=? OR resource_id IN (SELECT id FROM upload_session WHERE task_id=?) OR resource_id IN (SELECT id FROM storage_cleanup_task WHERE upload_id IN (SELECT id FROM upload_session WHERE task_id=?))",sharedTrace,taskId,taskId);
        for(var row:rows){String text=JSON.writeValueAsString(row);require(secrets.stream().noneMatch(text::contains)&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("X-Amz-"),"retained_cleanup_audits_exclude_private_locations_and_credentials");}report.put("auditRowsRetained",rows.size());report.put("cleanupAuditTaskScopeVerified",true);
    }
    private void openDatabase(){var source=new DriverManagerDataSource();source.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")+"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8");source.setUsername(env("DB_USERNAME","uav_app"));source.setPassword(env("DB_PASSWORD",null));jdbc=new JdbcTemplate(source);dataSource=source;}
    private void createFixtures()throws Exception{account(OWNER,"owner",List.of("OWNER_TASK_ADMIN"));account(OPERATOR,"admin",List.of("OPERATOR_TASK_ADMIN"));account(OPERATOR,"uploader",List.of("OPERATOR_USER"));for(Account a:accounts)login(a);var result=expect("register_own_device","POST",API+"/drones",admin(),Map.of("code","T025-"+runId.substring(0,12)),200).data();deviceId=string(object(result.get("device")).get("id"));secrets.add(string(result.get("deviceToken")));}
    private void account(String org,String suffix,List<String> roles){byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);String password=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);secrets.add(password);Account a=new Account(UUID.randomUUID().toString(),"t025-"+runId.substring(0,12)+"-"+suffix,password);accounts.add(a);require(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",a.id,org,a.username,new BCryptPasswordEncoder(12).encode(password),JSON.writeValueAsString(roles))==1,"only_own_fixture_account_inserted");}
    private void login(Account a)throws Exception{a.token=string(expect("fixture_login","POST",API+"/auth/login",null,Map.of("username",a.username,"password",a.password),200).data().get("accessToken"));secrets.add(a.token);}
    private Scope createTask()throws Exception{var task=expect("create_task","POST",API+"/tasks",owner(),Map.of("name","T025-"+runId,"scope","reference-aware cleanup","requirements",List.of(Map.of("name","photo","required",true))),200).data();String id=string(task.get("id")),path=API+"/tasks/"+id,requirement=string(objects(task.get("requirements")).getFirst().get("id"));taskId=id;task=expect("assign_task","POST",path+"/assign",owner(),Map.of("operatorOrgId",OPERATOR,"operatorAdminId",admin().id,"expectedLockVersion",version(task)),200).data();task=expect("add_uploader","PUT",path+"/members",admin(),Map.of("userIds",List.of(admin().id,uploader().id),"expectedLockVersion",version(task)),200).data();task=expect("create_subtask","POST",path+"/subtasks",admin(),Map.of("name","flight","requirementIds",List.of(requirement),"expectedLockVersion",version(task)),200).data();var sub=objects(task.get("subtasks")).getFirst();String execution=string(expect("create_execution","POST",API+"/subtasks/"+sub.get("id")+"/executions",admin(),Map.of("droneId",deviceId,"expectedLockVersion",version(sub)),200).data().get("id"));task=expect("refresh_task","GET",path,admin(),null,200).data();expect("start_task","POST",path+"/start",admin(),Map.of("expectedLockVersion",version(task)),200);return new Scope(id,requirement,string(sub.get("id")),execution);}
    private Set<String> objectKeys(String upload){Set<String> keys=new LinkedHashSet<>();for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")){String continuation=null;do{var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(continuation).build());page.contents().forEach(o->keys.add(o.key()));continuation=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;}while(continuation!=null);}return keys;}
    private void cleanupOwnedQueues(){Set<String> events=new HashSet<>(jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,taskId,taskId));rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue))while(true){var delivery=channel.basicGet(queue,false);if(delivery==null)break;MessageEvent e=MessageEvent.decode(delivery.getBody());require(events.contains(e.eventId())&&sharedTrace.equals(e.traceId()),"cleanup_drains_only_owned_business_events");channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);}return null;});}
    private void cleanupFixtures(){
        Set<String> tasks=new LinkedHashSet<>();for(Account a:accounts)tasks.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,a.id));for(String task:tasks)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));Set<String> keys=new LinkedHashSet<>(faults.extraFixtureKeys);for(String upload:uploadIds)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        int jobs=0;for(String upload:uploadIds)jobs+=jdbc.update("DELETE FROM storage_cleanup_task WHERE upload_id=?",upload);
        int uploadsDeleted=0;for(String task:tasks){uploadsDeleted+=(int)count("SELECT COUNT(*) FROM upload_session WHERE task_id=?",task);jdbc.update("DELETE FROM anchor_retry_request WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);jdbc.update("DELETE FROM access_grant WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM issue WHERE task_id=?",task);jdbc.update("DELETE FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",task);jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);List<String> events=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,task,task);for(String event:events){jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);jdbc.update("DELETE FROM device_event WHERE execution_id IN (SELECT id FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?))",task);jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);}
        jdbc.update("DELETE FROM drone WHERE operator_org_id=? AND code=?",OPERATOR,"T025-"+runId.substring(0,12));for(Account a:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",a.id,a.username);for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"all_only_owned_part_and_final_objects_removed");report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",1);report.put("fixtureUploadSessionsDeleted",uploadsDeleted);report.put("fixturePrivateObjectsDeletedAtTeardown",keys.size());report.put("fixtureCleanupTasksDeleted",jobs);report.put("fabricRecordsRetainedAppendOnly",true);
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
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new ProbeFailure("expected_object");return (Map<String,Object>)value;}private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new ProbeFailure("expected_array");return list.stream().map(StorageCleanupProbe::object).toList();}private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private static byte[] png(int color)throws Exception{var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);for(int x=0;x<8;x++)for(int y=0;y<8;y++)image.setRGB(x,y,color);var output=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",output))throw new ProbeFailure("png_encoder_unavailable");return output.toByteArray();}
    private static final class Account{final String id,username,password;String token;Account(String id,String username,String password){this.id=id;this.username=username;this.password=password;}@Override public String toString(){return "CleanupAccount["+id+"]";}}private record Scope(String task,String requirement,String subtask,String execution){}private record Reply(int status,Map<String,Object> body){Map<String,Object> data(){return object(body.get("data"));}@Override public String toString(){return "CleanupReply["+status+"]";}}private static final class ProbeFailure extends RuntimeException{ProbeFailure(String name){super(name);}}
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
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicBoolean used=new AtomicBoolean();
        void await(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new ProbeFailure("external_gate_must_not_hold_database_transaction");entered.countDown();try{if(!release.await(45,TimeUnit.SECONDS))throw new ProbeFailure("controlled_gate_timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ProbeFailure("controlled_gate_interrupted");}}
    }
    static final class FaultPlan {
        final Set<String> ownUploads=ConcurrentHashMap.newKeySet(),extraFixtureKeys=ConcurrentHashMap.newKeySet();
        final Map<String,String> lastPartPut=new ConcurrentHashMap<>();
        final AtomicReference<String> beforeCommit=new AtomicReference<>(),afterCommit=new AtomicReference<>(),failDeleteOnce=new AtomicReference<>(),finalPutUpload=new AtomicReference<>(),finalPutKey=new AtomicReference<>(),claimGateId=new AtomicReference<>();
        final AtomicInteger beforeCommitFailures=new AtomicInteger(),afterCommitFailures=new AtomicInteger(),deleteFailures=new AtomicInteger();
        volatile Gate finalPutGate,claimGate;
        boolean owns(String key){for(String id:ownUploads)if(key.startsWith("upload-parts/"+id+"/")||key.startsWith("evidence-files/"+id+"/"))return true;return false;}
        void clear(){beforeCommit.set(null);afterCommit.set(null);failDeleteOnce.set(null);finalPutUpload.set(null);claimGateId.set(null);if(finalPutGate!=null)finalPutGate.release.countDown();if(claimGate!=null)claimGate.release.countDown();}
    }
    @TestConfiguration(proxyBeanMethods=false) @Profile("storage-cleanup-probe")
    static class FaultConfiguration {
        @Bean FaultPlan cleanupProbeFaultPlan(){return new FaultPlan();}
        @Bean static BeanPostProcessor pauseOnlyOwnBrokerConsumers(){return new BeanPostProcessor(){@Override public Object postProcessBeforeInitialization(Object bean,String name){if(bean instanceof SimpleMessageListenerContainer c)c.setAutoStartup(false);return bean;}};}
        @Bean @Primary ObjectInventory cleanupProbeInventory(@Qualifier("s3ObjectInventory")ObjectInventory real,FaultPlan faults){return new ObjectInventory(){
            @Override public Page list(String prefix,String continuation,int limit){Page page=real.list(prefix,continuation,limit);return new Page(page.entries().stream().filter(e->faults.owns(e.key())).toList(),page.continuation());}
            @Override public Entry head(String key){return faults.owns(key)?real.head(key):null;}
        };}
        @Bean @Primary ObjectStorage cleanupProbeStorage(@Qualifier("objectStorage")ObjectStorage real,FaultPlan faults){return new FaultStorage(real,faults);}
        @Bean @Primary UploadTransactions cleanupProbeUploadTransactions(UploadMapper uploads,ResourcePermissionService permissions,AuditMapper audit,UploadSettings settings,DeviceAuthenticationService devices,PlatformTransactionManager manager,FaultPlan faults){return new FaultUploadTransactions(uploads,permissions,audit,settings,devices,manager,faults);}
        @Bean @Primary CleanupTransactions cleanupProbeCleanupTransactions(CleanupMapper mapper,CleanupSettings settings,@Qualifier("cleanupTransactions")CleanupTransactions real,FaultPlan faults){return new FaultCleanupTransactions(mapper,settings,real,faults);}
    }
    static class FaultUploadTransactions extends UploadTransactions {
        private final TransactionTemplate committed;private final FaultPlan faults;
        FaultUploadTransactions(UploadMapper uploads,ResourcePermissionService permissions,AuditMapper audit,UploadSettings settings,DeviceAuthenticationService devices,PlatformTransactionManager manager,FaultPlan faults){super(uploads,permissions,audit,settings,devices);this.faults=faults;committed=new TransactionTemplate(manager);committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
        @Override @Transactional public PartCommit storePart(CurrentUser actor,String upload,int part,long size,String hash,String key){
            String before=faults.beforeCommit.get();
            if(upload.equals(before)&&faults.beforeCommit.compareAndSet(before,null)){faults.beforeCommitFailures.incrementAndGet();throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);}
            String after=faults.afterCommit.get();
            if(upload.equals(after)&&faults.afterCommit.compareAndSet(after,null)){
                committed.execute(status->super.storePart(actor,upload,part,size,hash,key));faults.afterCommitFailures.incrementAndGet();throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            return super.storePart(actor,upload,part,size,hash,key);
        }
    }
    static class FaultCleanupTransactions extends CleanupTransactions {
        private final CleanupTransactions real;private final FaultPlan faults;
        FaultCleanupTransactions(CleanupMapper mapper,CleanupSettings settings,CleanupTransactions real,FaultPlan faults){super(mapper,settings);this.real=real;this.faults=faults;}
        @Override @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
        public Claim claim(String id){Gate gate=faults.claimGate;if(id.equals(faults.claimGateId.get())&&gate!=null&&gate.used.compareAndSet(false,true))gate.await();return real.claim(id);}
    }
    static final class FaultStorage implements ObjectStorage {
        private final ObjectStorage real;private final FaultPlan faults;FaultStorage(ObjectStorage real,FaultPlan faults){this.real=real;this.faults=faults;}
        @Override public void put(String key,Path file,String type){outsideTransaction();real.put(key,file,type);if(key.startsWith("upload-parts/")){String[] segments=key.split("/");if(segments.length>=2&&faults.ownUploads.contains(segments[1]))faults.lastPartPut.put(segments[1],key);}String upload=faults.finalPutUpload.get();Gate gate=faults.finalPutGate;if(upload!=null&&key.startsWith("evidence-files/"+upload+"/")&&gate!=null&&gate.used.compareAndSet(false,true)){faults.finalPutKey.set(key);gate.await();}}
        @Override public InputStream open(String key){outsideTransaction();return real.open(key);}
        @Override public void delete(String key){outsideTransaction();String selected=faults.failDeleteOnce.get();if(key.equals(selected)&&faults.failDeleteOnce.compareAndSet(selected,null)){faults.deleteFailures.incrementAndGet();throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);}real.delete(key);}
        private static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new ProbeFailure("actual_storage_io_must_not_hold_database_transaction");}
    }
}
