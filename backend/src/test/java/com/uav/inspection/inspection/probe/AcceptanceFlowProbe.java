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
public final class AcceptanceFlowProbe {
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
        TABLE_ORDER.put("issue","id");TABLE_ORDER.put("review_record","id");
    }
    private final String runId=UUID.randomUUID().toString(),exchange="inspection.acceptance.probe."+runId;
    private final String finalizeQueue=exchange+".finalize",anchorQueue=exchange+".anchor",deadExchange=exchange+".dead.events",deadQueue=exchange+".dead";
    private final Path reportPath;private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<Map<String,Object>> checks=Collections.synchronizedList(new ArrayList<>()),requests=Collections.synchronizedList(new ArrayList<>());
    private final List<Account> accounts=new ArrayList<>();private final List<String> secrets=new ArrayList<>();private final Set<String> uploadIds=new LinkedHashSet<>();private final List<Fixed> fixed=new ArrayList<>();
    private final AtomicInteger sequence=new AtomicInteger();private final List<Map<String,Object>> ledgerRecords=new ArrayList<>();
    private JdbcTemplate jdbc;private DataSource dataSource;private S3Client s3;private ObjectStorage storage;private GateStorage storageGate;private GateClients clients;private RabbitTemplate rabbit;private HttpClient client;
    private String baseUrl,bucket,storageEndpoint,taskId,subtaskId,requiredId,optionalId,requiredEvidenceId,currentExecution,originalExecution;private final List<Device> devices=new ArrayList<>();
    private AcceptanceFlowProbe(Path path){reportPath=path.toAbsolutePath().normalize();report.put("formatVersion",1);report.put("runId",runId);report.put("startedAt",Instant.now().toString());report.put("passed",false);report.put("checks",checks);report.put("requests",requests);report.put("ledgerRecordsRetained",ledgerRecords);}
    public static void main(String[] args){if(args.length!=1){System.err.println("Usage: AcceptanceFlowProbe <reportPath>");System.exit(2);return;}var p=new AcceptanceFlowProbe(Path.of(args[0]));try{p.execute();p.writeReport();System.out.println("[PASS] T019 real acceptance probe: "+p.reportPath);}catch(Exception failure){p.report.put("passed",false);p.report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof ProbeFailure)p.report.put("failedCheck",failure.getMessage());try{p.writeReport();}catch(Exception ignored){}System.err.println("[FAIL] T019 acceptance probe; inspect private safe report");System.exit(1);}}
    private void execute()throws Exception{
        report.put("stage","read_only_pending_work_preflight");var source=new DriverManagerDataSource();source.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")+"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8");source.setUsername(env("DB_USERNAME","uav_app"));source.setPassword(env("DB_PASSWORD",null));jdbc=new JdbcTemplate(source);dataSource=source;
        require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version='10' AND success=1")==1,"existing_v1_to_v10_schema_required_no_new_migration");
        require(count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT'")==0&&count("SELECT COUNT(*) FROM upload_session WHERE status='VERIFYING'")==0&&count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'")==0&&count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1)")==0,"global_no_pending_work_before_normal_automatic_workers");
        byte[] baseline=businessSnapshot();report.put("businessCountsBefore",businessCounts());
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,GateConfiguration.class).profiles("local","database","storage","messaging","ledger","acceptance-probe").web(WebApplicationType.SERVLET).logStartupInfo(false)
                .run("--server.port=0","--inspection.messaging.exchange="+exchange,"--inspection.messaging.finalize-queue="+finalizeQueue,"--inspection.messaging.anchor-queue="+anchorQueue,"--inspection.messaging.dead-letter-exchange="+deadExchange,"--inspection.messaging.dead-letter-queue="+deadQueue)){
            jdbc=context.getBean(JdbcTemplate.class);dataSource=context.getBean(DataSource.class);s3=context.getBean(S3Client.class);storage=context.getBean(ObjectStorage.class);storageGate=context.getBean(GateStorage.class);clients=context.getBean(GateClients.class);rabbit=context.getBean(RabbitTemplate.class);
            bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");storageEndpoint=context.getEnvironment().getRequiredProperty("inspection.storage.endpoint");baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
                client=actual;try{report.put("stage","formal_task_fixture_setup");createFixtures();createTask();report.put("stage","formal_acceptance_state_flow");verifyFlow();verifyLedgerAndFiles();verifyAudit();await(()->ownOutboxPending()==0&&count("SELECT COUNT(*) FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))) AND status<>'DONE'",taskId,taskId)==0,"all_own_automatic_deliveries_finish_durably");}
                finally{storageGate.release();clients.operator.release();context.getBean(ThreadPoolTaskScheduler.class).shutdown();context.getBean("finalizationContainer",SimpleMessageListenerContainer.class).stop();context.getBean("anchorContainer",SimpleMessageListenerContainer.class).stop();report.put("cleanupAttempted",true);cleanupFixtures();cleanupTopology();require(MessageDigest.isEqual(baseline,businessSnapshot()),"original_twenty_tables_all_columns_completed_at_and_binary_checkpoints_unchanged");report.put("originalBusinessDataUnchanged",true);report.put("businessCountsAfter",businessCounts());}
            }
        }report.put("stage","complete");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }
    private void createFixtures()throws Exception{
        account(OWNER,"owner",List.of("OWNER_TASK_ADMIN"));account(OPERATOR,"admin",List.of("OPERATOR_TASK_ADMIN"));account(OPERATOR,"uploader",List.of("OPERATOR_USER"));account(OWNER,"reviewer",List.of("OWNER_REVIEWER"));account(OPERATOR,"operator-outsider",List.of("OPERATOR_TASK_ADMIN"));account(OWNER,"owner-outsider",List.of("OWNER_TASK_ADMIN"));
        for(Account a:accounts){a.token=string(expect("fixture_login","POST",API+"/auth/login",null,Map.of("username",a.username,"password",a.password),200).data().get("accessToken"));secrets.add(a.token);}
        for(int i=0;i<2;i++){var created=expect("register_fixture_device","POST",API+"/drones",admin(),Map.of("code","T019-"+runId.substring(0,12)+"-"+i),200).data();String token=string(created.get("deviceToken"));secrets.add(token);devices.add(new Device(string(object(created.get("device")).get("id")),token));}
    }
    private void account(String org,String suffix,List<String> roles){byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);String password=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);secrets.add(password);Account a=new Account(UUID.randomUUID().toString(),"t019-"+runId.substring(0,12)+"-"+suffix,password);accounts.add(a);require(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",a.id,org,a.username,new BCryptPasswordEncoder(12).encode(password),JSON.writeValueAsString(roles))==1,"isolated_account_inserted");}
    private void createTask()throws Exception{
        Map<String,Object> task=expect("create_task","POST",API+"/tasks",owner(),Map.of("name","T019-"+runId,"scope","isolated real acceptance flow","requirements",List.of(Map.of("name","required-photo","required",true),Map.of("name","optional-photo","required",false))),200).data();taskId=string(task.get("id"));
        Map<String,Object> required=objects(task.get("requirements")).stream().filter(r->Boolean.TRUE.equals(r.get("required"))).findFirst().orElseThrow();requiredId=string(required.get("id"));requiredEvidenceId=string(required.get("evidenceId"));optionalId=string(objects(task.get("requirements")).stream().filter(r->!Boolean.TRUE.equals(r.get("required"))).findFirst().orElseThrow().get("id"));
        task=expect("assign_task","POST",taskPath()+"/assign",owner(),Map.of("operatorOrgId",OPERATOR,"operatorAdminId",admin().id,"expectedLockVersion",version(task)),200).data();
        task=expect("set_owner_members","PUT",taskPath()+"/members",owner(),Map.of("userIds",List.of(owner().id,reviewer().id),"expectedLockVersion",version(task)),200).data();
        task=expect("set_operator_members","PUT",taskPath()+"/members",admin(),Map.of("userIds",List.of(admin().id,uploader().id),"expectedLockVersion",version(task)),200).data();
        task=expect("create_subtask","POST",taskPath()+"/subtasks",admin(),Map.of("name","acceptance-flight","requirementIds",List.of(requiredId,optionalId),"expectedLockVersion",version(task)),200).data();var sub=objects(task.get("subtasks")).getFirst();subtaskId=string(sub.get("id"));currentExecution=string(expect("initial_execution","POST",executionPath(),admin(),Map.of("droneId",devices.getFirst().id,"expectedLockVersion",version(sub)),200).data().get("id"));originalExecution=currentExecution;
        task=task();expect("start_task","POST",taskPath()+"/start",admin(),Map.of("expectedLockVersion",version(task)),200);require(task().get("completedAt")==null,"new_task_completion_time_is_null");
    }
    private void verifyFlow()throws Exception{
        expect("uploader_cannot_submit_acceptance","POST",submitPath(),uploader(),lock(task()),403);expect("reviewer_cannot_submit_acceptance","POST",submitPath(),reviewer(),lock(task()),403);
        expect("operator_outsider_cannot_submit","POST",submitPath(),accounts.get(4),lock(task()),403);expect("operator_cannot_complete","POST",completePath(),admin(),lock(task()),403);expect("owner_outsider_cannot_complete","POST",completePath(),accounts.get(5),lock(task()),403);
        expect("submit_requires_nonnegative_version","POST",submitPath(),admin(),Map.of("expectedLockVersion",-1),400);
        expect("submit_rejects_missing_required_file","POST",submitPath(),admin(),lock(task()),409);
        byte[] firstBytes=png(0x145aa1);String firstUpload=createUpload(requiredId,currentExecution,firstBytes);
        expect("updating_draft_is_not_a_fixed_required_version","POST",submitPath(),admin(),lock(task()),409);putPart(firstUpload,firstBytes);
        expect("received_parts_are_not_fixed_required_version","POST",submitPath(),admin(),lock(task()),409);
        clients.operator.arm(requiredEvidenceId,1);completeUpload(firstUpload);Fixed first=awaitFixed(firstUpload,firstBytes);
        require(clients.operator.awaitEntered(),"real_ledger_endorsement_paused_while_local_anchor_pending");require("PENDING".equals(anchor(first.id)),"pending_anchor_is_a_real_durable_state");
        List<Reply> submits=race("concurrent_submit",submitPath(),admin(),lock(task()));assertOneWinner(submits,"concurrent_submit");require("WAITING_ACCEPTANCE".equals(task().get("status")),"submit_moves_task_to_waiting_acceptance");
        expect("pending_review_and_chain_block_completion","POST",completePath(),owner(),lock(task()),409);
        review(first.id,"RETURNED","初版缺少关键点位，请补充巡检图片");require("RUNNING".equals(task().get("status")),"return_of_latest_current_version_reopens_waiting_task");clients.operator.release();awaitConfirmed(first.id);

        byte[] secondBytes=png(0xd34822);clients.operator.arm(requiredEvidenceId,2);Fixed second=upload(requiredId,currentExecution,secondBytes);require(clients.operator.awaitEntered(),"second_real_registration_paused_before_endorsement");
        var returnedBefore=expect("returned_initial_review_before_correction","GET",API+"/evidence-versions/"+first.id+"/review",reviewer(),null,200).data();
        var correctedPending=expect("corrected_version_requires_its_own_review","GET",API+"/evidence-versions/"+second.id+"/review",reviewer(),null,200).data();
        require("RETURNED".equals(returnedBefore.get("reviewStatus"))&&"PENDING".equals(correctedPending.get("reviewStatus"))&&correctedPending.get("review")==null,"correction_does_not_inherit_returned_review");
        submit();review(second.id,"APPROVED",null);require("PENDING".equals(anchor(second.id)),"approved_version_can_still_have_pending_anchor");
        expect("pending_chain_alone_blocks_completion","POST",completePath(),owner(),lock(task()),409);clients.operator.release();awaitConfirmed(second.id);
        var returnedAfter=expect("returned_initial_review_after_correction","GET",API+"/evidence-versions/"+first.id+"/review",reviewer(),null,200).data();
        var approvedAfter=expect("corrected_approved_review_after_confirmation","GET",API+"/evidence-versions/"+second.id+"/review",reviewer(),null,200).data();
        require(returnedBefore.equals(returnedAfter)&&"APPROVED".equals(approvedAfter.get("reviewStatus"))&&first.id.equals(returnedAfter.get("versionId"))&&second.id.equals(approvedAfter.get("versionId")),"returned_and_corrected_review_history_preserved_with_real_confirmations");
        report.put("returnedAndCorrectedReviewHistoryVerified",true);
        byte[] optionalBytes=png(0x3b8636);String optional=createUpload(optionalId,currentExecution,optionalBytes);putPart(optional,optionalBytes);armStorage(optional);completeUpload(optional);
        require(storageGate.awaitEntered(),"current_optional_file_is_verifying_in_real_s3_read");require("VERIFYING".equals(uploadStatus(optional)),"current_optional_session_is_durably_verifying");
        expect("current_optional_verifying_blocks_completion","POST",completePath(),owner(),lock(task()),409);storageGate.release();Fixed optionalVersion=awaitFixed(optional,optionalBytes);awaitConfirmed(optionalVersion.id);
        require("RUNNING".equals(task().get("status")),"new_current_fixed_version_reopens_waiting_task");
        Fixed oldPending=upload(requiredId,currentExecution,png(0x9a48a5));awaitConfirmed(oldPending.id);
        submit();expect("pending_review_alone_blocks_completion_after_real_confirmation","POST",completePath(),owner(),lock(task()),409);
        Fixed fourth=upload(requiredId,currentExecution,png(0xb36c22));awaitConfirmed(fourth.id);require("RUNNING".equals(task().get("status")),"new_latest_required_fixed_version_reopens_waiting_task");review(fourth.id,"APPROVED",null);submit();
        review(oldPending.id,"RETURNED","这是旧版的复核意见，当前已有更新版本");require("WAITING_ACCEPTANCE".equals(task().get("status")),"return_of_nonlatest_version_does_not_reopen_waiting_task");
        Map<String,Object> sub=objects(task().get("subtasks")).getFirst();var replacement=expect("replacement_reopens_waiting_task","POST",executionPath(),admin(),Map.of("droneId",devices.get(1).id,"expectedLockVersion",version(sub),"reason","人工更换设备继续当前批次巡检"),200).data();
        currentExecution=string(replacement.get("id"));require("RUNNING".equals(task().get("status")),"replacement_moves_task_back_to_running");
        expect("historical_fixed_versions_cannot_satisfy_new_current_batch","POST",submitPath(),admin(),lock(task()),409);
        Fixed active=upload(requiredId,currentExecution,png(0x267c91));awaitConfirmed(active.id);review(active.id,"APPROVED",null);
        Fixed largerHistorical=upload(requiredId,originalExecution,png(0x8b2921));awaitConfirmed(largerHistorical.id);
        Map<String,Object> selected=objects(progress().get("requirements")).stream().filter(r->requiredId.equals(r.get("requirementId"))).findFirst().orElseThrow();
        require(active.id.equals(selected.get("versionId"))&&number(selected.get("versionNo"))==5&&number(jdbc.queryForMap("SELECT version_no FROM evidence_version WHERE id=?",largerHistorical.id).get("version_no"))==6,"current_required_version_five_wins_over_larger_historical_six");
        require(Boolean.TRUE.equals(selected.get("acceptanceReady")),"current_latest_is_actually_approved_and_fabric_confirmed");submit();
        byte[] draftBytes=png(0x2d83b9);String draft=createUpload(optionalId,currentExecution,draftBytes);putPart(draft,draftBytes);require("UPLOADING".equals(uploadStatus(draft)),"current_uploaded_draft_has_not_requested_finalization");
        byte[] lateBytes=png(0x535353);String late=createUpload(requiredId,originalExecution,lateBytes);putPart(late,lateBytes);armStorage(late);completeUpload(late);require(storageGate.awaitEntered(),"historical_verifying_session_paused_at_real_s3_input");
        require("VERIFYING".equals(uploadStatus(late)),"historical_session_is_verifying_before_task_completion");
        List<Reply> completes=race("concurrent_complete_task",completePath(),owner(),lock(task()));assertOneWinner(completes,"concurrent_complete_task");
        Map<String,Object> completed=task();require("COMPLETED".equals(completed.get("status"))&&completed.get("completedAt")!=null,"task_completes_with_database_timestamp_despite_historical_verifying_and_current_draft");
        String completedAt=string(completed.get("completedAt"));require(Instant.parse(completedAt).toEpochMilli()==jdbc.queryForObject("SELECT completed_at FROM inspection_task WHERE id=?",java.sql.Timestamp.class,taskId).toInstant().toEpochMilli(),"http_completion_instant_matches_database_utc_milliseconds");
        storageGate.release();await(()->"FAILED".equals(uploadStatus(late)),"historical_late_worker_rejects_new_fixed_version_after_completion");
        require(count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=?",late)==0&&"TASK_NOT_ACTIVE".equals(jdbc.queryForObject("SELECT last_error FROM upload_session WHERE id=?",String.class,late)),"post_completion_worker_creates_no_version_or_anchor");
        verifyTerminalGuards(active,largerHistorical,draft,draftBytes,completedAt);
        require(count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",taskId)==7,"exactly_seven_real_fixed_versions");report.put("completedTaskId",taskId);report.put("databaseCompletedAt",completedAt);report.put("currentVersionId",active.id);report.put("largerHistoricalVersionId",largerHistorical.id);report.put("historicalLateWorkerRejected",true);
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
    private void completeUpload(String id)throws Exception{expect("request_automatic_finalization","POST",uploadPath(id)+"/complete",uploader(),null,202);}
    private Fixed upload(String requirement,String execution,byte[] bytes)throws Exception{String id=createUpload(requirement,execution,bytes);putPart(id,bytes);completeUpload(id);return awaitFixed(id,bytes);}
    private Fixed awaitFixed(String upload,byte[] bytes)throws Exception{await(()->"COMPLETED".equals(uploadStatus(upload)),"real_worker_fixes_png_version");String version=string(expect("read_completed_upload","GET",uploadPath(upload),uploader(),null,200).data().get("versionId"));Fixed value=new Fixed(version,upload,bytes);fixed.add(value);return value;}
    private void armStorage(String upload){storageGate.arm(jdbc.queryForObject("SELECT object_key FROM upload_part WHERE session_id=? AND part_no=1",String.class,upload));}
    private void awaitConfirmed(String id)throws Exception{await(()->"CONFIRMED".equals(anchor(id)),"real_fabric_confirms_original_registration");}
    private String anchor(String id){return jdbc.queryForObject("SELECT status FROM chain_submission WHERE version_id=?",String.class,id);}private String uploadStatus(String id){return jdbc.queryForObject("SELECT status FROM upload_session WHERE id=?",String.class,id);}
    private Map<String,Object> task()throws Exception{return expect("read_task","GET",taskPath(),admin(),null,200).data();}private Map<String,Object> progress()throws Exception{return expect("read_task_progress","GET",taskPath()+"/progress",admin(),null,200).data();}private Map<String,Object> lock(Map<String,Object> task){return Map.of("expectedLockVersion",version(task));}
    private List<Reply> race(String name,String path,Account actor,Map<String,Object> body)throws Exception{CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);List<CompletableFuture<Reply>> futures=new ArrayList<>();for(int i=0;i<2;i++)futures.add(CompletableFuture.supplyAsync(()->{try{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new ProbeFailure("race_timeout");return request(name,"POST",path,actor,body,false);}catch(Exception failure){throw new ProbeFailure("parallel_http_request_failed");}}));require(ready.await(5,TimeUnit.SECONDS),"both_cas_requests_ready");start.countDown();return List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));}
    private void assertOneWinner(List<Reply> replies,String name){require(replies.stream().filter(r->r.status==200).count()==1&&replies.stream().filter(r->r.status==409).count()==1,name+"_has_exactly_one_success_and_one_conflict");for(Reply r:replies)assertCode(r,r.status,r.status==200?"OK":"VERSION_CONFLICT",name);}
    private void verifyLedgerAndFiles()throws Exception{
        for(Fixed value:fixed){Map<String,Object> row=jdbc.queryForMap("SELECT v.evidence_id,v.version_no,v.sha256,v.size,v.object_key,c.id AS submission_id,c.confirmed_tx_id,c.confirmed_by FROM evidence_version v JOIN chain_submission c ON c.version_id=v.id WHERE v.id=? AND c.status='CONFIRMED'",value.id);
            try(InputStream input=storage.open(string(row.get("object_key")))){byte[] bytes=input.readAllBytes();require(java.util.Arrays.equals(bytes,value.bytes)&&bytes.length==number(row.get("size"))&&sha256(bytes).equals(row.get("sha256")),"actual_fixed_object_bytes_and_digest_preserved");}
            List<Map<String,Object>> attempts=jdbc.queryForList("SELECT * FROM chain_attempt WHERE submission_id=?",row.get("submission_id"));require(attempts.size()==1,"one_real_transaction_attempt_per_version");Map<String,Object> attempt=attempts.getFirst();
            byte[] transaction=blob(attempt.get("transaction_bytes")),commit=blob(attempt.get("commit_bytes"));String tx=string(row.get("confirmed_tx_id"));
            require("VALID".equals(attempt.get("phase"))&&tx.equals(attempt.get("tx_id"))&&number(attempt.get("validation_code"))==0&&transaction.length>0&&commit.length>0,"confirmed_version_keeps_original_valid_binary_checkpoints");
            EvidenceLedgerClient.CommitOutcome outcome=clients.operator.queryCommit(new EvidenceLedgerClient.CommitCheckpoint(tx,commit));require(outcome.valid()&&outcome.validationCode()==0&&outcome.transactionId().equals(tx)&&outcome.blockNumber()==number(attempt.get("block_number")),"actual_fabric_requery_of_saved_commit_confirms_first_transaction");
            var owner=clients.forOrganization(OWNER).queryEvidence(string(row.get("evidence_id")),(int)number(row.get("version_no")));var operator=clients.operator.queryEvidence(string(row.get("evidence_id")),(int)number(row.get("version_no")));
            require(owner.found()&&owner.equals(operator)&&tx.equals(owner.record().registeredTxId())&&row.get("sha256").equals(owner.record().fileHash())&&clients.mspId(OPERATOR).equals(owner.record().submitterMsp()),"both_real_organizations_query_the_same_first_five_field_record");
            ledgerRecords.add(Map.of("versionId",value.id,"evidenceId",row.get("evidence_id"),"versionNo",row.get("version_no"),"sha256",row.get("sha256"),"confirmedTxId",tx,"validationCode",0,"blockNumber",outcome.blockNumber(),"transactionCheckpointSha256",sha256(transaction),"commitCheckpointSha256",sha256(commit)));
        }report.put("confirmedVersions",ledgerRecords.size());require(ledgerRecords.size()==7,"seven_real_fabric_registrations_are_retained");
    }
    private static byte[] blob(Object value)throws Exception{if(value instanceof byte[] bytes)return bytes;if(value instanceof java.sql.Blob blob)return blob.getBytes(1,(int)blob.length());throw new ProbeFailure("expected_binary_checkpoint");}
    private long ownOutboxPending(){return count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT' AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",taskId,taskId);}
    private Set<String> objectKeys(String upload){Set<String> keys=new LinkedHashSet<>();for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")){String continuation=null;do{var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(continuation).build());page.contents().forEach(o->keys.add(o.key()));continuation=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;}while(continuation!=null);}return keys;}
    private void cleanupFixtures(){
        Set<String> tasks=new LinkedHashSet<>();for(Account a:accounts)tasks.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,a.id));for(String task:tasks)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        for(String task:tasks){jdbc.update("DELETE FROM issue WHERE task_id=?",task);jdbc.update("DELETE FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",task);jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            List<String> events=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,task,task);for(String event:events){jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}
            jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);
            jdbc.update("DELETE FROM device_event WHERE execution_id IN (SELECT id FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?))",task);jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);}
        for(int i=0;i<2;i++)jdbc.update("DELETE FROM drone WHERE operator_org_id=? AND code=?",OPERATOR,"T019-"+runId.substring(0,12)+"-"+i);for(Account a:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",a.id,a.username);for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"only_owned_parts_final_and_unreferenced_candidates_removed");
        report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",devices.size());report.put("fixtureUploadSessionsDeleted",uploadIds.size());report.put("fixturePrivateObjectsDeleted",keys.size());report.put("fabricRecordsRetainedAppendOnly",true);
    }
    private void cleanupTopology(){rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue)){var state=channel.queueDeclarePassive(queue);require(state.getMessageCount()==0&&state.getConsumerCount()==0,"only_empty_unused_owned_queue_deleted");channel.queueDelete(queue,true,true);}channel.exchangeDelete(exchange,true);channel.exchangeDelete(deadExchange,true);return null;});report.put("ownedRabbitTopologyDeleted",true);}
    private void verifyAudit(){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id","t019-"+runId+"-%");for(var row:rows){String text=String.valueOf(row);require(secrets.stream().noneMatch(text::contains)&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains(storageEndpoint),"audit_excludes_credentials_or_storage_locations");}
        require(count("SELECT COUNT(*) FROM audit_log WHERE trace_id LIKE ? AND action='TASK_COMPLETED'","t019-"+runId+"-%")==1,"completion_cas_has_one_success_audit");require(count("SELECT COUNT(*) FROM audit_log WHERE trace_id LIKE ? AND action='TASK_ACCEPTANCE_SUBMITTED'","t019-"+runId+"-%")==5,"five_successful_acceptance_submissions_only");report.put("auditRowsRetained",rows.size());}
    private Reply expect(String name,String method,String path,Account actor,Object body,int status)throws Exception{return expectCode(name,method,path,actor,body,status,status==200||status==202?"OK":status==400?"INVALID_ARGUMENT":status==401?"UNAUTHORIZED":status==403?"FORBIDDEN":"VERSION_CONFLICT");}
    private Reply expectCode(String name,String method,String path,Account actor,Object body,int status,String code)throws Exception{Reply reply=request(name,method,path,actor,body,false);assertCode(reply,status,code,name);return reply;}
    private void assertCode(Reply reply,int status,String code,String name){require(reply.status==status,name+"_http_"+status);require(code.equals(reply.body.get("code")),name+"_code");}
    private Reply request(String name,String method,String path,Account actor,Object body,boolean raw)throws Exception{String trace="t019-"+runId+"-"+sequence.incrementAndGet();var builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(30)).header("X-Trace-Id",trace).header("Accept","application/json");if(actor!=null&&actor.token!=null)builder.header("Authorization","Bearer "+actor.token);byte[] bytes=body==null?null:raw?(byte[])body:JSON.writeValueAsBytes(body);if(bytes!=null)builder.header("Content-Type",raw?"application/octet-stream":"application/json");builder.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());requests.add(Map.of("case",name,"status",response.statusCode(),"traceId",trace));require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),name+"_json");String text=new String(response.body(),StandardCharsets.UTF_8);boolean secretResponse=path.equals(API+"/drones")&&method.equals("POST")||path.equals(API+"/auth/login");require((secretResponse||secrets.stream().noneMatch(text::contains))&&!text.contains("objectKey")&&!text.contains("object_key")&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("transactionBytes")&&!text.contains("commitBytes")&&!text.contains(storageEndpoint),name+"_safe_response");Map<String,Object> envelope=JSON.readValue(response.body(),Map.class);require(envelope.keySet().equals(Set.of("code","message","data","traceId")),name+"_uniform_envelope");require(trace.equals(envelope.get("traceId"))&&trace.equals(response.headers().firstValue("X-Trace-Id").orElse(null)),name+"_trace_propagated");return new Reply(response.statusCode(),envelope);}
    private void writeReport()throws Exception{report.put("caseCount",requests.size());report.put("checkCount",checks.size());report.put("fixedVersionIds",fixed.stream().map(Fixed::id).toList());byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);String text=new String(bytes,StandardCharsets.UTF_8);if(secrets.stream().anyMatch(text::contains)||text.contains("upload-parts/")||text.contains("evidence-files/"))throw new ProbeFailure("unsafe_report");Files.createDirectories(reportPath.getParent());Path tmp=Files.createTempFile(reportPath.getParent(),".acceptance-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.write(tmp,bytes);Files.move(tmp,reportPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
    private void require(boolean value,String name){checks.add(Map.of("check",name,"passed",value));if(!value)throw new ProbeFailure(name);}
    private Account owner(){return accounts.getFirst();}private Account admin(){return accounts.get(1);}private Account uploader(){return accounts.get(2);}private Account reviewer(){return accounts.get(3);}private String taskPath(){return API+"/tasks/"+taskId;}private String submitPath(){return taskPath()+"/submit-acceptance";}private String completePath(){return taskPath()+"/complete";}private String executionPath(){return API+"/subtasks/"+subtaskId+"/executions";}private static String uploadPath(String id){return API+"/upload-sessions/"+id;}
    private static String env(String key,String fallback){String value=System.getenv(key);if(value==null||value.isBlank()){if(fallback==null)throw new ProbeFailure("missing_required_environment");return fallback;}return value;}private static long version(Map<String,Object> row){return number(row.get("lockVersion"));}private static long number(Object value){if(!(value instanceof Number n))throw new ProbeFailure("expected_number");return n.longValue();}private static String string(Object value){if(!(value instanceof String text))throw new ProbeFailure("expected_string");return text;}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new ProbeFailure("expected_object");return (Map<String,Object>)value;}private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new ProbeFailure("expected_array");return list.stream().map(AcceptanceFlowProbe::object).toList();}private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
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
    @TestConfiguration(proxyBeanMethods=false) @Profile("acceptance-probe")
    static class GateConfiguration{
        @Bean @Primary GateStorage acceptanceGateStorage(@Qualifier("objectStorage") ObjectStorage real){return new GateStorage(real);}
        @Bean(destroyMethod="") @Primary GateClients acceptanceGateClients(@Qualifier("institutionLedgerClients") InstitutionLedgerClients real){return new GateClients(real);}
    }
    private static final class Pause{
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicBoolean armed=new AtomicBoolean(true);
        void waitHere(){entered.countDown();try{if(!release.await(90,TimeUnit.SECONDS))throw new ProbeFailure("controlled_gate_timed_out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new ProbeFailure("controlled_gate_interrupted");}}
        boolean awaitEntered()throws InterruptedException{return entered.await(30,TimeUnit.SECONDS);}
    }
    static final class GateStorage implements ObjectStorage{
        private final ObjectStorage real;private volatile String target;private volatile Pause pause=new Pause();GateStorage(ObjectStorage real){this.real=real;}
        void arm(String key){target=key;pause=new Pause();}boolean awaitEntered()throws InterruptedException{return pause.awaitEntered();}void release(){pause.release.countDown();}
        @Override public void put(String key,Path file,String type){real.put(key,file,type);}@Override public void delete(String key){real.delete(key);}
        @Override public InputStream open(String key){InputStream actual=real.open(key);Pause selected=pause;if(!key.equals(target)||!selected.armed.compareAndSet(true,false))return actual;return new FilterInputStream(actual){private boolean blocked;private void beforeRead()throws IOException{if(blocked)return;blocked=true;try{selected.waitHere();}catch(ProbeFailure failure){throw new IOException("Controlled probe storage pause interrupted");}}@Override public int read()throws IOException{beforeRead();return in.read();}@Override public int read(byte[] bytes,int offset,int length)throws IOException{beforeRead();return in.read(bytes,offset,length);}};}
    }
    static final class GateClients extends InstitutionLedgerClients{
        final GateLedger operator;GateClients(InstitutionLedgerClients real){this(real,new GateLedger(real.forOrganization(OPERATOR)));}private GateClients(InstitutionLedgerClients real,GateLedger operator){super(real.forOrganization(OWNER),operator,real.mspId(OWNER),real.mspId(OPERATOR));this.operator=operator;}
    }
    static final class GateLedger implements EvidenceLedgerClient{
        private final EvidenceLedgerClient real;private volatile String target;private volatile int version;private volatile Pause pause=new Pause();GateLedger(EvidenceLedgerClient real){this.real=real;}
        void arm(String target,int version){this.target=target;this.version=version;pause=new Pause();}boolean awaitEntered()throws InterruptedException{return pause.awaitEntered();}void release(){pause.release.countDown();}
        @Override public PreparedRegistration prepareRegistration(String evidence,int number,String hash)throws EndorseException{Pause selected=pause;if(evidence.equals(target)&&number==version&&selected.armed.compareAndSet(true,false))selected.waitHere();return real.prepareRegistration(evidence,number,hash);}
        @Override public CommitCheckpoint submitPrepared(PreparedRegistration registration)throws SubmitException{return real.submitPrepared(registration);}@Override public CommitOutcome queryCommit(CommitCheckpoint checkpoint)throws CommitStatusException{return real.queryCommit(checkpoint);}@Override public EvidenceQuery queryEvidence(String evidence,int version)throws GatewayException{return real.queryEvidence(evidence,version);}@Override public void close(){/* Real application registry owns both Gateway clients. */}
    }
}
