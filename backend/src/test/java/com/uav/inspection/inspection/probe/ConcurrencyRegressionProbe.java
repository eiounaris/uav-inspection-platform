package com.uav.inspection.inspection.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.InstitutionLedgerClients;
import org.hyperledger.fabric.client.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.io.FilterInputStream;
import java.io.IOException;
import com.uav.inspection.blockchain.anchor.AnchorProcessor;
import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import com.uav.inspection.messaging.*;
import com.uav.inspection.common.lease.ProcessingLeaseKeeper;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import javax.imageio.ImageIO;
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
import java.util.function.BooleanSupplier;

/** Explicit formal-HTTP acceptance with actual Fabric confirmation; fixtures alone are cleaned. */
public final class ConcurrencyRegressionProbe {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String API="/api/v1",OWNER="00000000-0000-4000-8000-000000000001",OPERATOR="00000000-0000-4000-8000-000000000002";
    private static final Map<String,String> TABLE_ORDER=new LinkedHashMap<>();
    static {
        TABLE_ORDER.put("organization","id");TABLE_ORDER.put("app_user","id");TABLE_ORDER.put("inspection_task","id");
        TABLE_ORDER.put("task_member","task_id,user_id");TABLE_ORDER.put("drone","id");TABLE_ORDER.put("inspection_subtask","id");
        TABLE_ORDER.put("material_requirement","id");TABLE_ORDER.put("task_execution","id");TABLE_ORDER.put("upload_session","id");
        TABLE_ORDER.put("upload_part","session_id,part_no");TABLE_ORDER.put("evidence_version","id");TABLE_ORDER.put("outbox_event","event_id");
        TABLE_ORDER.put("consumed_event","consumer_code,event_id");TABLE_ORDER.put("chain_submission","id");TABLE_ORDER.put("chain_attempt","id");
        TABLE_ORDER.put("verification_record","id");TABLE_ORDER.put("device_event","id");TABLE_ORDER.put("execution_adjustment","id");
        TABLE_ORDER.put("issue","id");TABLE_ORDER.put("review_record","id");TABLE_ORDER.put("access_grant","id");TABLE_ORDER.put("message_rejection","id");TABLE_ORDER.put("anchor_retry_request","id");TABLE_ORDER.put("storage_cleanup_task","id");
    }
    private final String runId=UUID.randomUUID().toString(),exchange="inspection.regression.probe."+runId;
    private final String finalizeQueue=exchange+".finalize",anchorQueue=exchange+".anchor",deadExchange=exchange+".dead.events",deadQueue=exchange+".dead";
    private final Path reportPath;private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<Map<String,Object>> checks=Collections.synchronizedList(new ArrayList<>()),requests=Collections.synchronizedList(new ArrayList<>());
    private final List<Account> accounts=new ArrayList<>();private final List<String> secrets=new ArrayList<>();private final Set<String> uploadIds=new LinkedHashSet<>();private final List<Fixed> fixed=new ArrayList<>();
    private final AtomicInteger sequence=new AtomicInteger();private final List<Map<String,Object>> ledgerRecords=new ArrayList<>();
    private JdbcTemplate jdbc;private DataSource dataSource;private S3Client s3;private ObjectStorage storage;private GateStorage storageGate;private InstitutionLedgerClients clients;private FinalizationProcessor finalizer;private AnchorProcessor anchors;private OutboxPublisher publisher;private RabbitTemplate rabbit;private HttpClient client;
    private final List<CompletableFuture<?>> workers=new ArrayList<>();private String foreignTaskId;
    private String baseUrl,bucket,storageEndpoint,taskId,subtaskId,requiredId,optionalId,requiredEvidenceId,currentExecution,originalExecution;private final List<Device> devices=new ArrayList<>();
    private ConcurrencyRegressionProbe(Path path){reportPath=path.toAbsolutePath().normalize();report.put("formatVersion",1);report.put("runId",runId);report.put("startedAt",Instant.now().toString());report.put("passed",false);report.put("checks",checks);report.put("requests",requests);report.put("ledgerRecordsRetained",ledgerRecords);}
    public static void main(String[] args){if(args.length!=1){System.err.println("Usage: ConcurrencyRegressionProbe <reportPath>");System.exit(2);return;}var p=new ConcurrencyRegressionProbe(Path.of(args[0]));try{p.execute();p.writeReport();System.out.println("[PASS] T026 real concurrency regression: "+p.reportPath);}catch(Exception failure){p.report.put("passed",false);p.report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof ProbeFailure)p.report.put("failedCheck",failure.getMessage());try{p.writeReport();}catch(Exception ignored){}System.err.println("[FAIL] T026 concurrency regression; inspect private safe report");System.exit(1);}}
    private void execute()throws Exception{
        report.put("stage","read_only_pending_work_preflight");var source=new DriverManagerDataSource();source.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")+"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8");source.setUsername(env("DB_USERNAME","uav_app"));source.setPassword(env("DB_PASSWORD",null));jdbc=new JdbcTemplate(source);dataSource=source;
        require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL")==14,"existing_v1_to_v14_schema_required_no_new_migration");
        require(count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT'")==0&&count("SELECT COUNT(*) FROM upload_session WHERE status='VERIFYING'")==0&&count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'")==0&&count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1)")==0,"global_no_pending_work_before_controlled_workers");
        require(count("SELECT COUNT(*) FROM storage_cleanup_task WHERE status IN ('PENDING','SENDING')")==0,"no_foreign_pending_cleanup_before_regression");
        byte[] baseline=businessSnapshot();report.put("originalBusinessSnapshotSha256",HexFormat.of().formatHex(baseline));report.put("businessCountsBefore",businessCounts());
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,GateConfiguration.class).profiles("local","database","storage","messaging","ledger","concurrency-regression-probe").web(WebApplicationType.SERVLET).logStartupInfo(false)
                .run("--server.port=0","--inspection.messaging.exchange="+exchange,"--inspection.messaging.finalize-queue="+finalizeQueue,"--inspection.messaging.anchor-queue="+anchorQueue,"--inspection.messaging.dead-letter-exchange="+deadExchange,"--inspection.messaging.dead-letter-queue="+deadQueue,"--inspection.messaging.publisher-enabled=false","--inspection.messaging.recovery-enabled=false","--inspection.ledger.recovery-enabled=false","--inspection.upload.temp-directory="+reportPath.getParent().resolve("managed-temp"),"--inspection.verification.temp-directory="+reportPath.getParent().resolve("verification-temp"))){
            jdbc=context.getBean(JdbcTemplate.class);dataSource=context.getBean(DataSource.class);s3=context.getBean(S3Client.class);storage=context.getBean(ObjectStorage.class);storageGate=context.getBean(GateStorage.class);clients=context.getBean(InstitutionLedgerClients.class);finalizer=context.getBean(FinalizationProcessor.class);anchors=context.getBean(AnchorProcessor.class);publisher=context.getBean(OutboxPublisher.class);rabbit=context.getBean(RabbitTemplate.class);
            bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");storageEndpoint=context.getEnvironment().getRequiredProperty("inspection.storage.endpoint");baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
                client=actual;try{report.put("stage","formal_task_fixture_setup");createFixtures();createTask();report.put("stage","permission_and_concurrency_regression");verifyFlow();verifyLedgerAndFiles();verifyAudit();publishAndDrainOwned();}
                catch(Exception failure){report.put("primaryFailureType",failure.getClass().getSimpleName());if(failure instanceof ProbeFailure)report.put("primaryFailedCheck",failure.getMessage());throw failure;}
                finally{storageGate.release();awaitWorkersBeforeCleanup();context.getBean(ThreadPoolTaskScheduler.class).shutdown();context.getBean(BusinessRecoveryDispatcher.class).close();context.getBean(ProcessingLeaseKeeper.class).shutdown();context.getBean("finalizationContainer",SimpleMessageListenerContainer.class).stop();context.getBean("anchorContainer",SimpleMessageListenerContainer.class).stop();report.put("cleanupAttempted",true);retainAttemptMetadata();publishAndDrainOwned();cleanupFixtures();cleanupTopology();require(MessageDigest.isEqual(baseline,businessSnapshot()),"original_twenty_four_tables_all_columns_and_binary_checkpoints_unchanged");report.put("originalBusinessDataUnchanged",true);report.put("businessCountsAfter",businessCounts());}
            }
        }report.put("stage","complete");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }
    private void createFixtures()throws Exception{
        account(OWNER,"owner",List.of("OWNER_TASK_ADMIN"));account(OPERATOR,"admin",List.of("OPERATOR_TASK_ADMIN"));account(OPERATOR,"uploader",List.of("OPERATOR_USER"));account(OWNER,"reviewer",List.of("OWNER_REVIEWER"));account(OPERATOR,"operator-outsider",List.of("OPERATOR_TASK_ADMIN"));account(OWNER,"owner-outsider",List.of("OWNER_TASK_ADMIN"));account(OWNER,"approver",List.of("OWNER_DATA_APPROVER"));account(OPERATOR,"recipient",List.of("OPERATOR_USER"));
        for(Account a:accounts){a.token=string(expect("fixture_login","POST",API+"/auth/login",null,Map.of("username",a.username,"password",a.password),200).data().get("accessToken"));secrets.add(a.token);}
        for(int i=0;i<2;i++){var created=expect("register_fixture_device","POST",API+"/drones",admin(),Map.of("code","T026-"+runId.substring(0,12)+"-"+i),200).data();String token=string(created.get("deviceToken"));secrets.add(token);devices.add(new Device(string(object(created.get("device")).get("id")),token));}
    }
    private void account(String org,String suffix,List<String> roles){byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);String password=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);secrets.add(password);Account a=new Account(UUID.randomUUID().toString(),"t026-"+runId.substring(0,12)+"-"+suffix,password);accounts.add(a);require(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",a.id,org,a.username,new BCryptPasswordEncoder(12).encode(password),JSON.writeValueAsString(roles))==1,"isolated_account_inserted");}
    private void createTask()throws Exception{
        Map<String,Object> task=expect("create_task","POST",API+"/tasks",owner(),Map.of("name","T026-"+runId,"scope","isolated real acceptance flow","requirements",List.of(Map.of("name","required-photo","required",true),Map.of("name","optional-photo","required",false))),200).data();taskId=string(task.get("id"));
        Map<String,Object> required=objects(task.get("requirements")).stream().filter(r->Boolean.TRUE.equals(r.get("required"))).findFirst().orElseThrow();requiredId=string(required.get("id"));requiredEvidenceId=string(required.get("evidenceId"));optionalId=string(objects(task.get("requirements")).stream().filter(r->!Boolean.TRUE.equals(r.get("required"))).findFirst().orElseThrow().get("id"));
        task=expect("assign_task","POST",taskPath()+"/assign",owner(),Map.of("operatorOrgId",OPERATOR,"operatorAdminId",admin().id,"expectedLockVersion",version(task)),200).data();
        task=expect("set_owner_members","PUT",taskPath()+"/members",owner(),Map.of("userIds",List.of(owner().id,reviewer().id,approver().id),"expectedLockVersion",version(task)),200).data();
        task=expect("set_operator_members","PUT",taskPath()+"/members",admin(),Map.of("userIds",List.of(admin().id,uploader().id),"expectedLockVersion",version(task)),200).data();
        task=expect("create_subtask","POST",taskPath()+"/subtasks",admin(),Map.of("name","acceptance-flight","requirementIds",List.of(requiredId,optionalId),"expectedLockVersion",version(task)),200).data();var sub=objects(task.get("subtasks")).getFirst();subtaskId=string(sub.get("id"));currentExecution=string(expect("initial_execution","POST",executionPath(),admin(),Map.of("droneId",devices.getFirst().id,"expectedLockVersion",version(sub)),200).data().get("id"));originalExecution=currentExecution;
        task=task();expect("start_task","POST",taskPath()+"/start",admin(),Map.of("expectedLockVersion",version(task)),200);require(task().get("completedAt")==null,"new_task_completion_time_is_null");
        foreignTaskId=string(expect("create_foreign_owner_task","POST",API+"/tasks",accounts.get(5),Map.of("name","T026-foreign-"+runId,"scope","foreign fixture","requirements",List.of(Map.of("name","private-other-photo","required",true))),200).data().get("id"));
    }
    private void verifyFlow()throws Exception {
        verifyTaskScopeAndMemberCas();
        Fixed first=upload(requiredId,currentExecution,png(0x145aa1));awaitConfirmed(first.id);
        verifyDirectVersionScope(first);
        var reviewed=race("parallel_review_same_fixed_version",API+"/evidence-versions/"+first.id+"/review",reviewer(),decision("APPROVED",null));
        assertOneWinner(reviewed,"review_compare_and_set");
        require(count("SELECT COUNT(*) FROM review_record WHERE evidence_version_id=?",first.id)==1&&count("SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND action='EVIDENCE_REVIEWED'",first.id)==1,"concurrent_review_has_one_record_and_one_success_audit");
        require("APPROVED".equals(jdbc.queryForObject("SELECT review_status FROM evidence_version WHERE id=?",String.class,first.id)),"winning_review_durably_approves_exact_version");
        verifyInFlightMembership(first);
        Map<String,Object> oldGrant=apply(first.id);
        var decisions=race("parallel_exact_version_grant_approval",grantPath(oldGrant)+"/decision",approver(),approval(oldGrant));
        assertOneWinner(decisions,"grant_decision_compare_and_set");oldGrant=decisions.stream().filter(r->r.status==200).findFirst().orElseThrow().data();
        require(count("SELECT COUNT(*) FROM access_grant WHERE id=? AND status='APPROVED'",oldGrant.get("id"))==1,"one_actual_approved_old_version_grant");
        require(count("SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND action='ACCESS_GRANT_APPROVED'",oldGrant.get("id"))==1,"concurrent_grant_approval_has_one_success_audit");
        expect("temporarily_add_granted_recipient_as_member","PUT",taskPath()+"/members",admin(),Map.of("userIds",List.of(admin().id,uploader().id,recipient().id),"expectedLockVersion",version(task())),200);
        expect("member_recipient_temporarily_reads_parent_task","GET",taskPath(),recipient(),null,200);
        setOperatorMembers(true);
        expect("grant_survives_task_membership_removal","GET",versionPath(first.id),recipient(),null,200);verifyVersionPages(recipient(),Set.of(first.id));
        expect("precise_grant_never_grants_task","GET",taskPath(),recipient(),null,403);
        expect("precise_grant_never_grants_execution_history","GET",executionPath(),recipient(),null,403);
        expect("precise_grant_never_grants_audit","GET",taskPath()+"/audit-logs",recipient(),null,403);
        verifyTaskPages(recipient(),Set.of());

        Fixed newer=upload(requiredId,currentExecution,png(0xe24822));awaitConfirmed(newer.id);
        require("PENDING".equals(jdbc.queryForObject("SELECT review_status FROM evidence_version WHERE id=?",String.class,newer.id)),"new_fixed_version_has_independent_pending_review");
        expect("old_grant_cannot_read_new_version","GET",versionPath(newer.id),recipient(),null,403);verifyVersionPages(recipient(),Set.of(first.id));
        submit();expect("old_approval_cannot_complete_unreviewed_latest","POST",completePath(),owner(),lock(task()),409);
        Map<String,Object> newGrant=apply(newer.id);
        expect("old_approval_cannot_authorize_new_unreviewed_version","POST",grantPath(newGrant)+"/decision",approver(),approval(newGrant),409);
        review(newer.id,"APPROVED",null);
        newGrant=expect("approve_new_version_independently","POST",grantPath(newGrant)+"/decision",approver(),approval(newGrant),200).data();
        verifyVersionPages(recipient(),Set.of(first.id,newer.id));
        expect("revoke_only_old_version_grant","POST",grantPath(oldGrant)+"/revoke",approver(),Map.of("expectedLockVersion",version(oldGrant),"reason","旧版用途授权结束"),200);
        expect("revoked_old_grant_loses_exact_old_read","GET",versionPath(first.id),recipient(),null,403);
        expect("new_grant_remains_independently_readable","GET",versionPath(newer.id),recipient(),null,200);verifyVersionPages(recipient(),Set.of(newer.id));
        require(count("SELECT COUNT(*) FROM access_grant WHERE applicant_id=? AND version_id=? AND status='REVOKED'",recipient().id,first.id)==1&&count("SELECT COUNT(*) FROM access_grant WHERE applicant_id=? AND version_id=? AND status='APPROVED'",recipient().id,newer.id)==1,"old_and_new_grant_rows_keep_independent_terminal_state");

        byte[] historicalBytes=png(0x734fd2);String historicalUpload=createUpload(requiredId,currentExecution,historicalBytes);putPart(historicalUpload,historicalBytes);completeUpload(historicalUpload);armStorage(historicalUpload);
        var fixing=CompletableFuture.runAsync(()->finalizer.process(event(MessageEvent.FILE_FINALIZE_REQUESTED,historicalUpload)));workers.add(fixing);
        require(storageGate.awaitEntered(),"real_current_finalization_pauses_at_s3_input_before_parent_lock");
        require("VERIFYING".equals(uploadStatus(historicalUpload)),"current_upload_is_durably_verifying_during_completion_check");
        expect("current_verifying_blocks_completion","POST",completePath(),owner(),lock(task()),409);
        Map<String,Object> beforeSub=objects(task().get("subtasks")).getFirst();
        var replacement=expect("replace_execution_while_old_finalizer_is_in_io","POST",executionPath(),admin(),Map.of("droneId",devices.get(1).id,"expectedLockVersion",version(beforeSub),"reason","回归验证人工换机与旧批次定版交错"),200).data();
        currentExecution=string(replacement.get("id"));
        require(count("SELECT COUNT(*) FROM task_execution WHERE subtask_id=? AND status='RUNNING'",subtaskId)==1&&count("SELECT COUNT(*) FROM task_execution WHERE id=? AND status='CLOSED'",originalExecution)==1,"replacement_atomically_closes_old_execution_and_leaves_one_running");
        storageGate.release();fixing.get(25,TimeUnit.SECONDS);Fixed historical=awaitFixed(historicalUpload,historicalBytes);awaitConfirmed(historical.id);
        require(count("SELECT COUNT(*) FROM evidence_version WHERE id=? AND execution_id=?",historical.id,originalExecution)==1,"inflight_old_finalization_keeps_original_execution_identity");
        require("RUNNING".equals(task().get("status")),"old_finalization_cannot_resubmit_replaced_task");
        expect("historical_versions_cannot_satisfy_replacement_batch","POST",submitPath(),admin(),lock(task()),409);

        Fixed active=upload(requiredId,currentExecution,png(0x267c91));awaitConfirmed(active.id);review(active.id,"APPROVED",null);
        var selected=objects(progress().get("requirements")).stream().filter(r->requiredId.equals(r.get("requirementId"))).findFirst().orElseThrow();
        require(active.id.equals(selected.get("versionId"))&&Boolean.TRUE.equals(selected.get("acceptanceReady")),"progress_selects_only_ready_latest_current_execution_version");
        var submits=race("parallel_submit_after_replacement",submitPath(),admin(),lock(task()));assertOneWinner(submits,"submit_compare_and_set");
        require("WAITING_ACCEPTANCE".equals(task().get("status")),"one_submission_reaches_waiting_acceptance");
        byte[] draftBytes=png(0x2d83b9);String draft=createUpload(optionalId,currentExecution,draftBytes);putPart(draft,draftBytes);
        byte[] lateBytes=png(0x535353);String late=createUpload(requiredId,originalExecution,lateBytes);putPart(late,lateBytes);completeUpload(late);armStorage(late);
        var lateFixing=CompletableFuture.runAsync(()->finalizer.process(event(MessageEvent.FILE_FINALIZE_REQUESTED,late)));workers.add(lateFixing);
        require(storageGate.awaitEntered(),"historical_finalization_is_in_real_io_when_completion_begins");
        var completes=race("parallel_complete_with_historical_worker_inflight",completePath(),owner(),lock(task()));assertOneWinner(completes,"complete_compare_and_set");
        var completed=task();String completedAt=string(completed.get("completedAt"));
        require("COMPLETED".equals(completed.get("status"))&&count("SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND action='TASK_COMPLETED'",taskId)==1,"completion_commits_once_with_one_success_audit");
        storageGate.release();lateFixing.get(25,TimeUnit.SECONDS);
        require("FAILED".equals(uploadStatus(late))&&count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=?",late)==0&&"TASK_NOT_ACTIVE".equals(jdbc.queryForObject("SELECT last_error FROM upload_session WHERE id=?",String.class,late)),"inflight_historical_worker_rechecks_terminal_task_before_new_version_or_anchor");
        verifyTerminalGuards(active,historical,draft,draftBytes,completedAt);
        Map<String,Object> terminalGrant=apply(active.id);
        terminalGrant=expect("completed_task_allows_precise_grant_approval","POST",grantPath(terminalGrant)+"/decision",approver(),approval(terminalGrant),200).data();
        expect("completed_task_allows_existing_grant_revocation","POST",grantPath(newGrant)+"/revoke",approver(),Map.of("expectedLockVersion",version(newGrant),"reason","任务完成后撤销旧批次资料用途"),200);
        expect("completed_task_new_grant_reads_only_active_version","GET",versionPath(active.id),recipient(),null,200);
        expect("completed_task_revoked_earlier_grant_loses_old_read","GET",versionPath(newer.id),recipient(),null,403);
        expect("completed_task_grant_does_not_restore_parent_read","GET",taskPath(),recipient(),null,403);verifyVersionPages(recipient(),Set.of(active.id));
        require(count("SELECT COUNT(*) FROM access_grant WHERE applicant_id=? AND status='APPROVED'",recipient().id)==1,"only_terminal_exact_version_authorization_remains_approved");
        report.put("completedTaskSharingApprovalAndRevocationVerified",true);
        require(count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",taskId)==4,"four_real_fixed_versions_without_duplicate_or_late_terminal_version");
        report.put("permissionAndPaginationVerified",true);report.put("memberCasAndSameJwtRevocationVerified",true);report.put("inFlightMembershipRecheckedBeforePersistence",true);report.put("parallelReviewUniqueRecordVerified",true);report.put("exactOldNewVersionGrantsVerified",true);report.put("replacementFinalizationInterleavingVerified",true);report.put("parallelSubmitAndCompleteVerified",true);report.put("terminalWorkerAndMemberRevocationVerified",true);
        report.put("completedTaskId",taskId);report.put("databaseCompletedAt",completedAt);report.put("currentVersionId",active.id);report.put("historicalVersionId",historical.id);
    }
    private void verifyTaskScopeAndMemberCas()throws Exception {
        for(Account outsider:List.of(accounts.get(4),accounts.get(5))){
            expect("nonmember_task_id_forbidden","GET",taskPath(),outsider,null,403);
            expect("nonmember_progress_id_forbidden","GET",taskPath()+"/progress",outsider,null,403);
            expect("nonmember_execution_id_forbidden","GET",executionPath(),outsider,null,403);
            expect("nonmember_audit_id_forbidden","GET",taskPath()+"/audit-logs",outsider,null,403);
        }
        expect("other_owner_task_id_forbidden","GET",API+"/tasks/"+foreignTaskId,owner(),null,403);
        verifyTaskPages(owner(),Set.of(taskId));verifyTaskPages(admin(),Set.of(taskId));verifyTaskPages(accounts.get(5),Set.of(foreignTaskId));verifyTaskPages(accounts.get(4),Set.of());
        Map<String,Object> remove=Map.of("userIds",List.of(admin().id),"expectedLockVersion",version(task()));
        var edits=raceRequests("parallel_member_replacement","PUT",taskPath()+"/members",admin(),remove,remove);assertOneWinner(edits,"members_compare_and_set");
        require(count("SELECT COUNT(*) FROM task_member WHERE task_id=? AND user_id=?",taskId,uploader().id)==0&&count("SELECT COUNT(*) FROM task_member WHERE task_id=? AND user_id=?",taskId,owner().id)==1,"member_cas_removes_own_org_member_and_preserves_other_org_creator");
        expect("same_jwt_loses_task_after_membership_removal","GET",taskPath(),uploader(),null,403);verifyTaskPages(uploader(),Set.of());
        setOperatorMembers(true);expect("same_jwt_gains_task_after_formal_restore","GET",taskPath(),uploader(),null,200);verifyTaskPages(uploader(),Set.of(taskId));
    }
    private void verifyDirectVersionScope(Fixed first)throws Exception {
        for(Account outsider:List.of(accounts.get(4),accounts.get(5))){
            for(String suffix:List.of("","/anchor","/issues","/review","/verifications"))expect("nonmember_version_related_id_forbidden","GET",versionPath(first.id)+suffix,outsider,null,403);
            expect("nonmember_upload_id_forbidden","GET",uploadPath(first.upload),outsider,null,403);
            expect("nonmember_cannot_issue_download","POST",versionPath(first.id)+"/download-url",outsider,null,403);
            expect("nonmember_cannot_verify_stored_bytes","POST",versionPath(first.id)+"/verifications?source=STORED",outsider,null,403);
            verifyVersionPages(outsider,Set.of());
        }
        expect("operator_role_cannot_review_owner_version","POST",versionPath(first.id)+"/review",admin(),decision("APPROVED",null),403);
        require(count("SELECT COUNT(*) FROM review_record WHERE evidence_version_id=?",first.id)==0,"forbidden_requests_create_no_review_record");
    }
    private void verifyInFlightMembership(Fixed first)throws Exception {
        long before=count("SELECT COUNT(*) FROM verification_record WHERE user_id=? AND version_id=?",uploader().id,first.id);
        storageGate.arm(jdbc.queryForObject("SELECT object_key FROM evidence_version WHERE id=?",String.class,first.id));
        var reading=CompletableFuture.supplyAsync(()->{try{return request("member_verification_vs_revocation","POST",versionPath(first.id)+"/verifications?source=STORED",uploader(),null,false);}catch(Exception failure){throw new ProbeFailure("member_verification_http_failed");}});workers.add(reading);
        require(storageGate.awaitEntered(),"actual_stored_verification_pauses_outside_database_transaction");setOperatorMembers(false);storageGate.release();
        assertCode(reading.get(25,TimeUnit.SECONDS),403,"FORBIDDEN","revoked_member_inflight_verification");
        require(count("SELECT COUNT(*) FROM verification_record WHERE user_id=? AND version_id=?",uploader().id,first.id)==before,"revoked_inflight_member_has_no_persisted_verification_result");
        expect("same_jwt_loses_version_after_member_removal","GET",versionPath(first.id),uploader(),null,403);verifyVersionPages(uploader(),Set.of());setOperatorMembers(true);
    }
    private void setOperatorMembers(boolean includeUploader)throws Exception {
        expect("formal_operator_member_change","PUT",taskPath()+"/members",admin(),Map.of("userIds",includeUploader?List.of(admin().id,uploader().id):List.of(admin().id),"expectedLockVersion",version(task())),200);
    }
    private void verifyTaskPages(Account actor,Set<String> expected)throws Exception {
        Set<String> found=new LinkedHashSet<>();
        for(int page=1;page<=Math.max(2,expected.size()+1);page++){
            var result=expect("task_list_sql_filter_before_page","GET",API+"/tasks?page="+page+"&pageSize=1",actor,null,200).data();
            require(number(result.get("total"))==expected.size(),"task_total_counts_only_current_authority");
            for(var row:objects(result.get("items"))){String id=string(row.get("id"));require(expected.contains(id),"task_page_never_contains_foreign_task");found.add(id);}
        }
        require(found.equals(expected),"task_pages_have_no_missing_or_unauthorized_rows");
    }
    private void verifyVersionPages(Account actor,Set<String> expected)throws Exception {
        Set<String> found=new LinkedHashSet<>();
        for(int page=1;page<=Math.max(2,expected.size()+1);page++){
            var result=expect("version_list_sql_filter_before_page","GET",API+"/evidence-versions?taskId="+taskId+"&page="+page+"&pageSize=1",actor,null,200).data();
            require(number(result.get("total"))==expected.size(),"version_total_counts_only_current_exact_authority");
            for(var row:objects(result.get("items"))){String id=string(row.get("id"));require(expected.contains(id),"version_page_never_contains_ungranted_version");found.add(id);}
        }
        require(found.equals(expected),"version_pages_have_no_missing_or_unauthorized_rows");
    }
    private Map<String,Object> apply(String versionId)throws Exception {
        var result=expect("apply_precise_version","POST",API+"/access-grants",recipient(),Map.of("clientRequestId",UUID.randomUUID().toString(),"versionId",versionId,"reason","并发回归指定版本资料共享","requestedValidUntil",Instant.now().plusSeconds(900).toString()),200).data();
        require(!result.containsKey("taskId")&&!result.containsKey("sha256")&&!result.containsKey("filename"),"grant_application_is_safe_metadata_only");return result;
    }
    private Map<String,Object> approval(Map<String,Object> grant){return Map.of("decision","APPROVED","validUntil",grant.get("requestedValidUntil"),"expectedLockVersion",version(grant));}
    private String grantPath(Map<String,Object> grant){return API+"/access-grants/"+grant.get("id");}
    private String versionPath(String id){return API+"/evidence-versions/"+id;}
    private MessageEvent event(String type,String biz){String payload=jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type=? AND biz_id=?",String.class,type,biz);return MessageEvent.decode(payload.getBytes(StandardCharsets.UTF_8));}
    private List<Reply> raceRequests(String name,String method,String path,Account actor,Object first,Object second)throws Exception {
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);List<CompletableFuture<Reply>> futures=new ArrayList<>();
        for(Object body:List.of(first,second))futures.add(CompletableFuture.supplyAsync(()->{try{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new ProbeFailure("race_timeout");return request(name,method,path,actor,body,false);}catch(Exception failure){throw new ProbeFailure("parallel_http_request_failed");}}));
        workers.addAll(futures);require(ready.await(5,TimeUnit.SECONDS),"both_actual_http_requests_ready");start.countDown();return List.of(futures.get(0).get(25,TimeUnit.SECONDS),futures.get(1).get(25,TimeUnit.SECONDS));
    }
    private void publishAndDrainOwned(){
        publisher.publishBatch();require(ownOutboxPending()==0,"own_events_have_real_publish_confirms");
        Set<String> events=new LinkedHashSet<>(jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,taskId,taskId));
        rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue))while(true){var delivery=channel.basicGet(queue,false);if(delivery==null)break;var event=MessageEvent.decode(delivery.getBody());require(events.contains(event.eventId())&&event.traceId().startsWith("t026-"+runId+"-"),"drain_only_exact_owned_event_identifiers");channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);}return null;});
    }
    private void awaitWorkersBeforeCleanup(){
        for(var worker:workers)try{worker.get(30,TimeUnit.SECONDS);}
        catch(java.util.concurrent.ExecutionException completedFailure){report.put("completedWorkerFailureObserved",true);}
        catch(java.util.concurrent.TimeoutException stillRunning){report.put("cleanupSkippedDueToActiveWorker",true);throw new ProbeFailure("active_worker_prevents_destructive_fixture_cleanup");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();report.put("cleanupSkippedDueToActiveWorker",true);throw new ProbeFailure("interrupted_before_worker_quiescence");}
        report.put("allControlledWorkersStoppedBeforeCleanup",true);
    }
    private void retainAttemptMetadata(){
        report.put("retainedAttemptMetadata",jdbc.queryForList("SELECT v.id AS versionId,v.evidence_id AS evidenceId,v.version_no AS versionNo,v.sha256,a.tx_id AS transactionId,a.phase,a.validation_code AS validationCode,a.block_number AS blockNumber FROM chain_attempt a JOIN chain_submission c ON c.id=a.submission_id JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=? ORDER BY v.version_no,a.attempt_no",taskId));
    }

    private void verifyTerminalGuards(Fixed active,Fixed historical,String draft,byte[] draftBytes,String completedAt)throws Exception{
        expect("completed_task_rejects_resubmit","POST",submitPath(),admin(),lock(task()),409);expect("completed_task_rejects_second_completion","POST",completePath(),owner(),lock(task()),409);
        Map<String,Object> sub=objects(task().get("subtasks")).getFirst();expect("completed_task_rejects_replacement","POST",executionPath(),admin(),Map.of("droneId",devices.getFirst().id,"expectedLockVersion",version(sub),"reason","terminal attempt"),409);
        expect("completed_task_rejects_start","POST",taskPath()+"/start",admin(),lock(task()),409);
        expect("completed_task_rejects_requirement_edit","PUT",taskPath()+"/requirements",owner(),Map.of("expectedLockVersion",version(task()),"requirements",List.of(Map.of("id",requiredId,"name","required-photo","required",true),Map.of("id",optionalId,"name","optional-photo","required",false))),409);
        expect("completed_task_rejects_subtask_creation","POST",taskPath()+"/subtasks",admin(),Map.of("expectedLockVersion",version(task()),"name","terminal-extra","requirementIds",List.of(requiredId)),409);
        expectCode("completed_task_rejects_new_upload","POST",API+"/upload-sessions",uploader(),uploadBody(requiredId,currentExecution,draftBytes),409,"TASK_COMPLETED");
        assertCode(request("completed_task_rejects_draft_part","PUT",uploadPath(draft)+"/parts/1",uploader(),draftBytes,true),409,"TASK_COMPLETED","terminal_part");
        expectCode("completed_task_rejects_draft_completion","POST",uploadPath(draft)+"/complete",uploader(),null,409,"TASK_COMPLETED");
        expectCode("completed_task_rejects_new_issue","POST",API+"/issues",uploader(),Map.of("versionId",active.id,"description","terminal issue"),409,"TASK_COMPLETED");
        expectCode("completed_task_rejects_unreviewed_historical_version_review","POST",API+"/evidence-versions/"+historical.id+"/review",reviewer(),decision("RETURNED","terminal review"),409,"TASK_COMPLETED");
        long versions=count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",taskId);expect("completed_upload_completion_keeps_original_idempotent_result","POST",uploadPath(active.upload)+"/complete",uploader(),null,202);require(versions==count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",taskId),"idempotent_completed_upload_does_not_add_a_terminal_version");
        expect("completed_task_allows_member_revocation","PUT",taskPath()+"/members",admin(),Map.of("userIds",List.of(admin().id),"expectedLockVersion",version(task())),200);
        expect("revoked_member_cannot_read_completed_task","GET",taskPath(),uploader(),null,403);require(completedAt.equals(task().get("completedAt")),"allowed_terminal_member_change_preserves_original_completed_at");
    }
    private void submit()throws Exception{expect("submit_acceptance","POST",submitPath(),admin(),lock(task()),200);require("WAITING_ACCEPTANCE".equals(task().get("status")),"formal_submit_waits_for_owner_acceptance");}
    private void review(String id,String decision,String reason)throws Exception{expect("owner_reviews_fixed_version","POST",API+"/evidence-versions/"+id+"/review",reviewer(),decision(decision,reason),200);}
    private Map<String,Object> decision(String value,String reason){Map<String,Object> body=new LinkedHashMap<>();body.put("decision",value);body.put("expectedReviewStatus","PENDING");if(reason!=null)body.put("reason",reason);return body;}
    private String createUpload(String requirement,String execution,byte[] bytes)throws Exception{String id=string(expect("create_upload_session","POST",API+"/upload-sessions",uploader(),uploadBody(requirement,execution,bytes),200).data().get("uploadId"));uploadIds.add(id);return id;}
    private Map<String,Object> uploadBody(String requirement,String execution,byte[] bytes){return Map.of("clientRequestId",UUID.randomUUID().toString(),"requirementId",requirement,"executionId",execution,"filename","inspection.png","contentType","image/png","size",bytes.length);}
    private void putPart(String upload,byte[] bytes)throws Exception{assertCode(request("store_png_part","PUT",uploadPath(upload)+"/parts/1",uploader(),bytes,true),200,"OK","store_png_part");}
    private void completeUpload(String id)throws Exception{expect("request_durable_finalization","POST",uploadPath(id)+"/complete",uploader(),null,202);}
    private Fixed upload(String requirement,String execution,byte[] bytes)throws Exception{String id=createUpload(requirement,execution,bytes);putPart(id,bytes);completeUpload(id);finalizer.process(event(MessageEvent.FILE_FINALIZE_REQUESTED,id));return awaitFixed(id,bytes);}
    private Fixed awaitFixed(String upload,byte[] bytes)throws Exception{await(()->"COMPLETED".equals(uploadStatus(upload)),"real_worker_fixes_png_version");String version=string(expect("read_completed_upload","GET",uploadPath(upload),uploader(),null,200).data().get("versionId"));Fixed value=new Fixed(version,upload,bytes);fixed.add(value);return value;}
    private void armStorage(String upload){storageGate.arm(jdbc.queryForObject("SELECT object_key FROM upload_part WHERE session_id=? AND part_no=1",String.class,upload));}
    private void awaitConfirmed(String id)throws Exception{String submission=jdbc.queryForObject("SELECT id FROM chain_submission WHERE version_id=?",String.class,id);anchors.process(event(MessageEvent.EVIDENCE_ANCHOR_REQUESTED,submission));await(()->"CONFIRMED".equals(anchor(id)),"real_fabric_confirms_original_registration");}
    private String anchor(String id){return jdbc.queryForObject("SELECT status FROM chain_submission WHERE version_id=?",String.class,id);}private String uploadStatus(String id){return jdbc.queryForObject("SELECT status FROM upload_session WHERE id=?",String.class,id);}
    private Map<String,Object> task()throws Exception{return expect("read_task","GET",taskPath(),admin(),null,200).data();}private Map<String,Object> progress()throws Exception{return expect("read_task_progress","GET",taskPath()+"/progress",admin(),null,200).data();}private Map<String,Object> lock(Map<String,Object> task){return Map.of("expectedLockVersion",version(task));}
    private List<Reply> race(String name,String path,Account actor,Map<String,Object> body)throws Exception{return raceRequests(name,"POST",path,actor,body,body);}
    private void assertOneWinner(List<Reply> replies,String name){require(replies.stream().filter(r->r.status==200).count()==1&&replies.stream().filter(r->r.status==409).count()==1,name+"_has_exactly_one_success_and_one_conflict");for(Reply r:replies)assertCode(r,r.status,r.status==200?"OK":"VERSION_CONFLICT",name);}
    private void verifyLedgerAndFiles()throws Exception{
        for(Fixed value:fixed){Map<String,Object> row=jdbc.queryForMap("SELECT v.evidence_id,v.version_no,v.sha256,v.size,v.object_key,c.id AS submission_id,c.confirmed_tx_id,c.confirmed_by FROM evidence_version v JOIN chain_submission c ON c.version_id=v.id WHERE v.id=? AND c.status='CONFIRMED'",value.id);
            try(InputStream input=storage.open(string(row.get("object_key")))){byte[] bytes=input.readAllBytes();require(java.util.Arrays.equals(bytes,value.bytes)&&bytes.length==number(row.get("size"))&&sha256(bytes).equals(row.get("sha256")),"actual_fixed_object_bytes_and_digest_preserved");}
            List<Map<String,Object>> attempts=jdbc.queryForList("SELECT * FROM chain_attempt WHERE submission_id=?",row.get("submission_id"));require(attempts.size()==1,"one_real_transaction_attempt_per_version");Map<String,Object> attempt=attempts.getFirst();
            byte[] transaction=blob(attempt.get("transaction_bytes")),commit=blob(attempt.get("commit_bytes"));String tx=string(row.get("confirmed_tx_id"));
            require("VALID".equals(attempt.get("phase"))&&tx.equals(attempt.get("tx_id"))&&number(attempt.get("validation_code"))==0&&transaction.length>0&&commit.length>0,"confirmed_version_keeps_original_valid_binary_checkpoints");
            EvidenceLedgerClient.CommitOutcome outcome=clients.forOrganization(OPERATOR).queryCommit(new EvidenceLedgerClient.CommitCheckpoint(tx,commit));require(outcome.valid()&&outcome.validationCode()==0&&outcome.transactionId().equals(tx)&&outcome.blockNumber()==number(attempt.get("block_number")),"actual_fabric_requery_of_saved_commit_confirms_first_transaction");
            var owner=clients.forOrganization(OWNER).queryEvidence(string(row.get("evidence_id")),(int)number(row.get("version_no")));var operator=clients.forOrganization(OPERATOR).queryEvidence(string(row.get("evidence_id")),(int)number(row.get("version_no")));
            require(owner.found()&&owner.equals(operator)&&tx.equals(owner.record().registeredTxId())&&row.get("sha256").equals(owner.record().fileHash())&&clients.mspId(OPERATOR).equals(owner.record().submitterMsp()),"both_real_organizations_query_the_same_first_five_field_record");
            ledgerRecords.add(Map.of("versionId",value.id,"evidenceId",row.get("evidence_id"),"versionNo",row.get("version_no"),"sha256",row.get("sha256"),"confirmedTxId",tx,"validationCode",0,"blockNumber",outcome.blockNumber(),"transactionCheckpointSha256",sha256(transaction),"commitCheckpointSha256",sha256(commit)));
        }report.put("confirmedVersions",ledgerRecords.size());require(ledgerRecords.size()==4,"four_real_fabric_registrations_are_retained");
    }
    private static byte[] blob(Object value)throws Exception{if(value instanceof byte[] bytes)return bytes;if(value instanceof java.sql.Blob blob)return blob.getBytes(1,(int)blob.length());throw new ProbeFailure("expected_binary_checkpoint");}
    private long ownOutboxPending(){return count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT' AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",taskId,taskId);}
    private Set<String> objectKeys(String upload){Set<String> keys=new LinkedHashSet<>();for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")){String continuation=null;do{var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(continuation).build());page.contents().forEach(o->keys.add(o.key()));continuation=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;}while(continuation!=null);}return keys;}
    private void cleanupFixtures(){
        Set<String> tasks=new LinkedHashSet<>();for(Account a:accounts)tasks.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,a.id));for(String task:tasks)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        for(String upload:uploadIds)jdbc.update("DELETE FROM storage_cleanup_task WHERE upload_id=?",upload);
        for(String task:tasks){jdbc.update("DELETE FROM access_grant WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM anchor_retry_request WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);jdbc.update("DELETE FROM issue WHERE task_id=?",task);jdbc.update("DELETE FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",task);jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            List<String> events=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,task,task);for(String event:events){jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}
            jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);
            jdbc.update("DELETE FROM device_event WHERE execution_id IN (SELECT id FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?))",task);jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);}
        for(int i=0;i<2;i++)jdbc.update("DELETE FROM drone WHERE operator_org_id=? AND code=?",OPERATOR,"T026-"+runId.substring(0,12)+"-"+i);for(Account a:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",a.id,a.username);for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"only_owned_parts_final_and_unreferenced_candidates_removed");
        report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",devices.size());report.put("fixtureUploadSessionsDeleted",uploadIds.size());report.put("fixturePrivateObjectsDeleted",keys.size());report.put("fabricRecordsRetainedAppendOnly",true);
    }
    private void cleanupTopology(){rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue)){var state=channel.queueDeclarePassive(queue);require(state.getMessageCount()==0&&state.getConsumerCount()==0,"only_empty_unused_owned_queue_deleted");channel.queueDelete(queue,true,true);}channel.exchangeDelete(exchange,true);channel.exchangeDelete(deadExchange,true);return null;});report.put("ownedRabbitTopologyDeleted",true);}
    private void verifyAudit(){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT action,resource_type,resource_id,trace_id,result FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id","t026-"+runId+"-%");
        for(var row:rows){String text=String.valueOf(row);require(secrets.stream().noneMatch(text::contains)&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains(storageEndpoint),"retained_audit_excludes_secrets_and_private_locations");}
        require(count("SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND action='TASK_COMPLETED'",taskId)==1,"one_task_completion_audit_after_competing_requests");
        require(count("SELECT COUNT(*) FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",taskId)==3,"three_successful_independent_reviews_only");
        report.put("auditRowsRetained",rows.size());
    }
    private Reply expect(String name,String method,String path,Account actor,Object body,int status)throws Exception{return expectCode(name,method,path,actor,body,status,status==200||status==202?"OK":status==400?"INVALID_ARGUMENT":status==401?"UNAUTHORIZED":status==403?"FORBIDDEN":"VERSION_CONFLICT");}
    private Reply expectCode(String name,String method,String path,Account actor,Object body,int status,String code)throws Exception{Reply reply=request(name,method,path,actor,body,false);assertCode(reply,status,code,name);return reply;}
    private void assertCode(Reply reply,int status,String code,String name){require(reply.status==status,name+"_http_"+status);require(code.equals(reply.body.get("code")),name+"_code");}
    private Reply request(String name,String method,String path,Account actor,Object body,boolean raw)throws Exception{String trace="t026-"+runId+"-"+sequence.incrementAndGet();var builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(30)).header("X-Trace-Id",trace).header("Accept","application/json");if(actor!=null&&actor.token!=null)builder.header("Authorization","Bearer "+actor.token);byte[] bytes=body==null?null:raw?(byte[])body:JSON.writeValueAsBytes(body);if(bytes!=null)builder.header("Content-Type",raw?"application/octet-stream":"application/json");builder.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());requests.add(Map.of("case",name,"status",response.statusCode(),"traceId",trace));require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),name+"_json");String text=new String(response.body(),StandardCharsets.UTF_8);boolean secretResponse=path.equals(API+"/drones")&&method.equals("POST")||path.equals(API+"/auth/login");require((secretResponse||path.endsWith("/download-url")&&response.statusCode()==200||secrets.stream().noneMatch(text::contains))&&!text.contains("objectKey")&&!text.contains("object_key")&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("transactionBytes")&&!text.contains("commitBytes")&&!text.contains(storageEndpoint),name+"_safe_response");Map<String,Object> envelope=JSON.readValue(response.body(),Map.class);require(envelope.keySet().equals(Set.of("code","message","data","traceId")),name+"_uniform_envelope");require(trace.equals(envelope.get("traceId"))&&trace.equals(response.headers().firstValue("X-Trace-Id").orElse(null)),name+"_trace_propagated");return new Reply(response.statusCode(),envelope);}
    private void writeReport()throws Exception{report.put("caseCount",requests.size());report.put("checkCount",checks.size());report.put("fixedVersionIds",fixed.stream().map(Fixed::id).toList());byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);String text=new String(bytes,StandardCharsets.UTF_8);if(secrets.stream().anyMatch(text::contains)||text.contains("upload-parts/")||text.contains("evidence-files/"))throw new ProbeFailure("unsafe_report");Files.createDirectories(reportPath.getParent());Path tmp=Files.createTempFile(reportPath.getParent(),".acceptance-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.write(tmp,bytes);Files.move(tmp,reportPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
    private void require(boolean value,String name){checks.add(Map.of("check",name,"passed",value));if(!value)throw new ProbeFailure(name);}
    private Account owner(){return accounts.getFirst();}private Account admin(){return accounts.get(1);}private Account uploader(){return accounts.get(2);}private Account reviewer(){return accounts.get(3);}private Account approver(){return accounts.get(6);}private Account recipient(){return accounts.get(7);}private String taskPath(){return API+"/tasks/"+taskId;}private String submitPath(){return taskPath()+"/submit-acceptance";}private String completePath(){return taskPath()+"/complete";}private String executionPath(){return API+"/subtasks/"+subtaskId+"/executions";}private static String uploadPath(String id){return API+"/upload-sessions/"+id;}
    private static String env(String key,String fallback){String value=System.getenv(key);if(value==null||value.isBlank()){if(fallback==null)throw new ProbeFailure("missing_required_environment");return fallback;}return value;}private static long version(Map<String,Object> row){return number(row.get("lockVersion"));}private static long number(Object value){if(!(value instanceof Number n))throw new ProbeFailure("expected_number");return n.longValue();}private static String string(Object value){if(!(value instanceof String text))throw new ProbeFailure("expected_string");return text;}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new ProbeFailure("expected_object");return (Map<String,Object>)value;}private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new ProbeFailure("expected_array");return list.stream().map(ConcurrencyRegressionProbe::object).toList();}private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private static byte[] png(int color)throws Exception{var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);for(int x=0;x<8;x++)for(int y=0;y<8;y++)image.setRGB(x,y,color);var bytes=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",bytes))throw new ProbeFailure("png_encoder_unavailable");return bytes.toByteArray();}
    private static final class Account{final String id,username,password;String token;Account(String id,String username,String password){this.id=id;this.username=username;this.password=password;}@Override public String toString(){return "AcceptanceAccount["+id+"]";}}private record Device(String id,String token){@Override public String toString(){return "AcceptanceDevice["+id+"]";}}private record Fixed(String id,String upload,byte[] bytes){}private record Reply(int status,Map<String,Object> body){Map<String,Object> data(){return object(body.get("data"));}}private static final class ProbeFailure extends RuntimeException{ProbeFailure(String name){super(name);}}
    private void await(BooleanSupplier condition,String name)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(System.nanoTime()<deadline){if(condition.getAsBoolean()){require(true,name);return;}Thread.sleep(100);}require(false,name);}
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
    @TestConfiguration(proxyBeanMethods=false) @Profile("concurrency-regression-probe")
    static class GateConfiguration{
        @Bean @Primary GateStorage regressionGateStorage(@Qualifier("objectStorage") ObjectStorage real){return new GateStorage(real);}
        @Bean static BeanPostProcessor pauseOwnConsumers(){return new BeanPostProcessor(){@Override public Object postProcessBeforeInitialization(Object bean,String name){if(bean instanceof SimpleMessageListenerContainer c)c.setAutoStartup(false);return bean;}};}
    }
    private static final class Pause{
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicBoolean armed=new AtomicBoolean(true);
        void waitHere(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new ProbeFailure("storage_gate_must_not_hold_database_locks");entered.countDown();try{if(!release.await(45,TimeUnit.SECONDS))throw new ProbeFailure("controlled_gate_timed_out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new ProbeFailure("controlled_gate_interrupted");}}
        boolean awaitEntered()throws InterruptedException{return entered.await(15,TimeUnit.SECONDS);}
    }
    static final class GateStorage implements ObjectStorage{
        private final ObjectStorage real;private volatile String target;private volatile Pause pause=new Pause();GateStorage(ObjectStorage real){this.real=real;}
        void arm(String key){target=key;pause=new Pause();}boolean awaitEntered()throws InterruptedException{return pause.awaitEntered();}void release(){pause.release.countDown();}
        @Override public void put(String key,Path file,String type){outsideTransaction();real.put(key,file,type);}@Override public void delete(String key){outsideTransaction();real.delete(key);}
        @Override public InputStream open(String key){outsideTransaction();InputStream actual=real.open(key);Pause selected=pause;if(!key.equals(target)||!selected.armed.compareAndSet(true,false))return actual;return new FilterInputStream(actual){private boolean blocked;private void beforeRead()throws IOException{if(blocked)return;blocked=true;try{selected.waitHere();}catch(ProbeFailure failure){throw new IOException("Controlled probe storage pause interrupted");}}@Override public int read()throws IOException{beforeRead();return in.read();}@Override public int read(byte[] bytes,int offset,int length)throws IOException{beforeRead();return in.read(bytes,offset,length);}};}
        private static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new ProbeFailure("object_io_must_not_hold_database_locks");}
    }
}
