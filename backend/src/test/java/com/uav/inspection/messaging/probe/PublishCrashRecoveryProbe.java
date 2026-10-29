package com.uav.inspection.messaging.probe;

import com.rabbitmq.client.Channel;
import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.InstitutionLedgerClients;
import com.uav.inspection.common.lease.ProcessingLeaseKeeper;
import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.messaging.*;
import com.uav.inspection.messaging.persistence.OutboxMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real process halts before publish, after confirm and after candidate PUT; recovery is a new JVM. */
public final class PublishCrashRecoveryProbe {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String OWNER="00000000-0000-4000-8000-000000000001",OPERATOR="00000000-0000-4000-8000-000000000002",API="/api/v1";
    private static final Map<String,String> TABLES=new LinkedHashMap<>();
    static{String[] names={"organization","app_user","inspection_task","task_member","drone","inspection_subtask","material_requirement","task_execution","upload_session","upload_part","evidence_version","outbox_event","consumed_event","chain_submission","chain_attempt","verification_record","device_event","execution_adjustment","issue","review_record","access_grant","message_rejection","anchor_retry_request","storage_cleanup_task"};for(String name:names)TABLES.put(name,name.equals("task_member")?"task_id,user_id":name.equals("upload_part")?"session_id,part_no":name.equals("outbox_event")?"event_id":name.equals("consumed_event")?"consumer_code,event_id":"id");}
    private static volatile Child active;
    private final Path directory,checkpointPath;
    private final String runId=UUID.randomUUID().toString(),trace="t027-publish-"+runId;
    private final Map<String,Object> checkpoint=new LinkedHashMap<>(),report=new LinkedHashMap<>();
    private final List<Map<String,Object>> checks=new ArrayList<>(),requests=new ArrayList<>(),ledger=new ArrayList<>();
    private final List<Account> accounts=new ArrayList<>();private final List<String> secrets=new ArrayList<>(),uploads=new ArrayList<>();
    private final List<Process> children=new ArrayList<>();private final Set<String> ownedEventIds=new LinkedHashSet<>();
    private JdbcTemplate jdbc;private DataSource source;private HttpClient http;private ObjectStorage storage;private S3Client s3;private InstitutionLedgerClients clients;private RabbitTemplate rabbit;
    private String baseUrl,bucket,taskId,deviceId,requirementId,subtaskId,executionId;

    private PublishCrashRecoveryProbe(Path directory){this.directory=directory.toAbsolutePath().normalize();checkpointPath=this.directory.resolve("checkpoint.json");report.put("formatVersion",1);report.put("coordinatorPid",ProcessHandle.current().pid());report.put("runId",runId);report.put("startedAt",Instant.now().toString());report.put("passed",false);report.put("checks",checks);report.put("requests",requests);report.put("ledgerRecordsRetained",ledger);}
    public static void main(String[] args){
        if(args.length!=2){System.err.println("Usage: PublishCrashRecoveryProbe coordinator|before-publish|after-confirm|file-candidate|recover <private-directory>");System.exit(2);return;}
        if(!args[0].equals("coordinator")){try{new Child(args[0],Path.of(args[1])).execute();}catch(Exception failure){System.err.println("[FAIL] T027 publishing child; inspect private report");System.exit(1);}return;}
        var probe=new PublishCrashRecoveryProbe(Path.of(args[1]));
        try{probe.execute();probe.writeReport();System.out.println("[PASS] T027 publish and candidate crash recovery");}
        catch(Exception failure){probe.report.put("passed",false);probe.report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof Failure)probe.report.put("failedCheck",failure.getMessage());try{probe.writeReport();}catch(Exception ignored){}System.err.println("[FAIL] T027 publish crash coordinator; inspect private report");System.exit(1);}
    }
    private void execute()throws Exception{
        openDatabase();require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL")==14,"original_fourteen_migrations_required");
        require(count("SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','SENDING')")==0&&count("SELECT COUNT(*) FROM upload_session WHERE status<>'COMPLETED'")==0&&count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1 AND next_action_at IS NOT NULL)")==0&&count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'")==0&&count("SELECT COUNT(*) FROM storage_cleanup_task WHERE status IN ('PENDING','SENDING')")==0,"no_foreign_pending_business_before_test_workers");
        byte[] baseline=snapshot();report.put("originalBusinessSnapshotSha256",HexFormat.of().formatHex(baseline));report.put("businessCountsBefore",counts());
        Exception primary=null;
        try{
            try(var context=context("setup",directory,runId,true);HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
                attach(context);http=client;setup();checkpoint.put("runId",runId);checkpoint.put("taskId",taskId);checkpoint.put("deviceId",deviceId);checkpoint.put("accountIds",accounts.stream().map(a->a.id).toList());List<Map<String,Object>> cases=new ArrayList<>();
                checkpoint.put("uploads",cases);
                for(int color:List.of(0x235ea1,0xb6502a,0x4c9632)){byte[] bytes=png(color);String id=createUpload(bytes);String event=jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE event_type='FILE_FINALIZE_REQUESTED' AND biz_id=?",String.class,id);cases.add(Map.of("uploadId",id,"eventId",event,"sha256",hash(bytes),"size",bytes.length));}
                privateJson(checkpointPath,checkpoint);stopWorkers(context);
            }
            openDatabase();
            child("before-publish",73);child("after-confirm",74);child("file-candidate",75);
            report.put("stage","natural_120_second_lease_expiry");writeReport();
            long began=System.nanoTime();await(()->count("SELECT COUNT(*) FROM outbox_event WHERE event_id IN (?,?) AND status='SENDING' AND lease_until<=UTC_TIMESTAMP(3)",caseAt(0).get("eventId"),caseAt(1).get("eventId"))==2&&count("SELECT COUNT(*) FROM upload_session WHERE id=? AND status='VERIFYING' AND lease_until<=UTC_TIMESTAMP(3)",uploads.get(2))==1,"all_original_production_leases_expire_naturally_without_sql_changes",150);
            report.put("leaseWaitMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));report.put("naturalLeaseExpiryVerified",true);
            child("recover",0);report.put("stage","final_oracles_and_fixture_cleanup");
        }catch(Exception failure){primary=failure;report.put("primaryFailureType",failure.getClass().getSimpleName());if(failure instanceof Failure)report.put("primaryFailedCheck",failure.getMessage());}
        finally{
            boolean quiescent=true;for(Process child:children)if(child.isAlive()){quiescent=false;child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}
            if(!quiescent){report.put("cleanupSkippedDueToActiveChild",true);throw new Failure("unexpected_live_child_prevents_destructive_cleanup");}
            try(var context=context("finish",directory,runId,true);HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
                attach(context);http=client;
                try{if(primary==null){verifyResults();report.put("beforePublishCrashVerified",true);report.put("afterConfirmCrashVerified",true);report.put("afterCandidatePutCrashVerified",true);report.put("defaultAutomaticRecoveryVerified",true);}}
                finally{stopWorkers(context);retainAttempts();cleanupFixtures();cleanupTopology();require(MessageDigest.isEqual(baseline,snapshot()),"all_original_twenty_four_table_fields_and_binary_checkpoints_preserved");report.put("originalBusinessDataUnchanged",true);report.put("businessCountsAfter",counts());}
            }
        }
        if(primary!=null)throw primary;
        report.put("completedAt",Instant.now().toString());report.put("stage","complete");report.put("realLedgerRegistrations",3);report.put("passed",true);
    }
    private static ConfigurableApplicationContext context(String phase,Path directory,String runId,boolean web){
        List<String> args=new ArrayList<>(List.of("--server.port=0","--inspection.upload.temp-directory="+directory.resolve("managed-temp"),"--inspection.verification.temp-directory="+directory.resolve("verification-temp"),"--inspection.probe.publish.phase="+phase,"--inspection.probe.publish.directory="+directory,"--inspection.messaging.exchange="+exchange(runId),"--inspection.messaging.finalize-queue="+exchange(runId)+".finalize","--inspection.messaging.anchor-queue="+exchange(runId)+".anchor","--inspection.messaging.dead-letter-exchange="+exchange(runId)+".dead.events","--inspection.messaging.dead-letter-queue="+exchange(runId)+".dead"));
        if(phase.equals("recover"))args.addAll(List.of("--inspection.messaging.publisher-enabled=true","--inspection.messaging.recovery-enabled=true","--inspection.ledger.recovery-enabled=true"));
        else args.addAll(List.of("--inspection.messaging.publisher-enabled=false","--inspection.messaging.recovery-enabled=false","--inspection.ledger.recovery-enabled=false"));
        return new SpringApplicationBuilder(UavInspectionApplication.class,ProbeConfiguration.class).profiles("local","database","storage","messaging","ledger","publish-crash-probe").web(web?WebApplicationType.SERVLET:WebApplicationType.NONE).logStartupInfo(false).run(args.toArray(String[]::new));
    }
    private static String exchange(String id){return "inspection.publish.crash.probe."+id;}
    private void attach(ConfigurableApplicationContext context){jdbc=context.getBean(JdbcTemplate.class);source=context.getBean(DataSource.class);storage=context.getBean(ObjectStorage.class);s3=context.getBean(S3Client.class);clients=context.getBean(InstitutionLedgerClients.class);rabbit=context.getBean(RabbitTemplate.class);bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();}
    private static void stopWorkers(ConfigurableApplicationContext context){context.getBean(ThreadPoolTaskScheduler.class).shutdown();context.getBean(BusinessRecoveryDispatcher.class).close();context.getBean(ProcessingLeaseKeeper.class).shutdown();context.getBean("finalizationContainer",SimpleMessageListenerContainer.class).stop();context.getBean("anchorContainer",SimpleMessageListenerContainer.class).stop();}
    private void setup()throws Exception{
        account(OWNER,"owner","OWNER_TASK_ADMIN");account(OPERATOR,"admin","OPERATOR_TASK_ADMIN");account(OPERATOR,"uploader","OPERATOR_USER");
        for(Account a:accounts){a.token=string(expect("fixture_login","POST",API+"/auth/login",null,Map.of("username",a.username,"password",a.password),200).get("accessToken"));secrets.add(a.token);}
        var device=expect("create_fixture_device","POST",API+"/drones",accounts.get(1),Map.of("code","T027-publish-"+runId.substring(0,12)),200);deviceId=string(object(device.get("device")).get("id"));secrets.add(string(device.get("deviceToken")));
        var task=expect("create_task","POST",API+"/tasks",accounts.getFirst(),Map.of("name","T027 publish "+runId,"scope","Isolated real publish and candidate crashes","requirements",List.of(Map.of("name","inspection-photo","required",true))),200);taskId=string(task.get("id"));requirementId=string(objects(task.get("requirements")).getFirst().get("id"));
        task=expect("assign_task","POST",API+"/tasks/"+taskId+"/assign",accounts.getFirst(),Map.of("operatorOrgId",OPERATOR,"operatorAdminId",accounts.get(1).id,"expectedLockVersion",number(task.get("lockVersion"))),200);
        task=expect("add_uploader","PUT",API+"/tasks/"+taskId+"/members",accounts.get(1),Map.of("userIds",List.of(accounts.get(1).id,accounts.get(2).id),"expectedLockVersion",number(task.get("lockVersion"))),200);
        task=expect("create_subtask","POST",API+"/tasks/"+taskId+"/subtasks",accounts.get(1),Map.of("name","flight","requirementIds",List.of(requirementId),"expectedLockVersion",number(task.get("lockVersion"))),200);var sub=objects(task.get("subtasks")).getFirst();subtaskId=string(sub.get("id"));
        executionId=string(expect("create_execution","POST",API+"/subtasks/"+subtaskId+"/executions",accounts.get(1),Map.of("droneId",deviceId,"expectedLockVersion",number(sub.get("lockVersion"))),200).get("id"));task=expect("read_task","GET",API+"/tasks/"+taskId,accounts.get(1),null,200);expect("start_task","POST",API+"/tasks/"+taskId+"/start",accounts.get(1),Map.of("expectedLockVersion",number(task.get("lockVersion"))),200);
    }
    private void account(String org,String suffix,String role){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);Account a=new Account(UUID.randomUUID().toString(),"t027-pub-"+runId.substring(0,12)+"-"+suffix,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));accounts.add(a);secrets.add(a.password);require(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",a.id,org,a.username,new BCryptPasswordEncoder(12).encode(a.password),"[\""+role+"\"]")==1,"isolated_account_created");}
    private String createUpload(byte[] bytes)throws Exception{String id=string(expect("create_upload","POST",API+"/upload-sessions",accounts.get(2),Map.of("clientRequestId",UUID.randomUUID().toString(),"requirementId",requirementId,"executionId",executionId,"filename","inspection.png","contentType","image/png","size",bytes.length),200).get("uploadId"));uploads.add(id);request("store_part","PUT",API+"/upload-sessions/"+id+"/parts/1",accounts.get(2),bytes,200);expect("complete_upload","POST",API+"/upload-sessions/"+id+"/complete",accounts.get(2),null,202);return id;}
    private Map<String,Object> caseAt(int index){return object(((List<?>)checkpoint.get("uploads")).get(index));}
    private void child(String phase,int exit)throws Exception{
        Path log=directory.resolve(phase+".log");Files.createFile(log,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Djava.awt.headless=true","-Duser.timezone=Asia/Shanghai","-cp",System.getProperty("java.class.path"),PublishCrashRecoveryProbe.class.getName(),phase,directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();children.add(process);
        require(process.waitFor(360,TimeUnit.SECONDS),phase+"_child_exits_within_bound");require(process.exitValue()==exit,phase+"_actual_process_exit_code");Map<String,Object> data=JSON.readValue(Files.readAllBytes(directory.resolve(phase+".json")),new TypeReference<Map<String,Object>>() {});require(Boolean.TRUE.equals(data.get("passed")),phase+"_safe_report_passes");report.put(phase+"Pid",data.get("pid"));
        if(phase.equals("recover")){report.put("duplicateDeliveryAfterDoneAcknowledged",data.get("duplicateDeliveryAfterDoneAcknowledged"));report.put("recoveredOriginalEventDeliveries",data.get("originalEventDeliveries"));report.put("explicitPostDoneDuplicatePublishes",data.get("explicitPostDoneDuplicatePublishes"));}
    }
    private void verifyResults()throws Exception{
        Set<Long> childPids=new HashSet<>();for(String phase:List.of("before-publish","after-confirm","file-candidate","recover"))childPids.add(number(report.get(phase+"Pid")));require(childPids.size()==4&&!childPids.contains(ProcessHandle.current().pid()),"faults_and_automatic_recovery_use_four_distinct_child_jvms");report.put("distinctChildJvmProcesses",true);
        require(count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",taskId)==3&&count("SELECT COUNT(*) FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",taskId)==3,"three_unique_fixed_versions_and_submissions");
        require(count("SELECT COUNT(*) FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?)",taskId)==3&&count("SELECT COUNT(*) FROM outbox_event WHERE biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",taskId)==3,"exact_original_file_events_and_one_anchor_event_per_version");
        for(int i=0;i<uploads.size();i++){
            Map<String,Object> input=caseAt(i);String upload=uploads.get(i),event=string(input.get("eventId"));
            require(count("SELECT COUNT(*) FROM outbox_event WHERE event_id=? AND biz_id=? AND status='SENT'",event,upload)==1,"same_original_event_identifier_is_sent");require(count("SELECT COUNT(*) FROM consumed_event WHERE consumer_code='FILE_FINALIZE' AND event_id=? AND status='DONE'",event)==1,"one_consumed_done_receipt_for_original_event");
            if(i<2)require(count("SELECT attempt_count FROM outbox_event WHERE event_id=?",event)==2,"expired_sending_reuses_original_event_exactly_once");
            var row=jdbc.queryForMap("SELECT v.*,c.id AS submission_id,c.confirmed_tx_id FROM evidence_version v JOIN chain_submission c ON c.version_id=v.id WHERE v.upload_session_id=? AND c.status='CONFIRMED'",upload);String version=string(row.get("id")),tx=string(row.get("confirmed_tx_id"));
            require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id=?",row.get("submission_id"))==1,"duplicate_delivery_never_creates_second_transaction_attempt");
            try(InputStream stream=storage.open(string(row.get("object_key")))){byte[] data=stream.readAllBytes();require(hash(data).equals(input.get("sha256"))&&data.length==number(input.get("size")),"actual_original_file_bytes_preserved");}
            var attempt=jdbc.queryForMap("SELECT * FROM chain_attempt WHERE submission_id=?",row.get("submission_id"));byte[] transaction=blob(attempt.get("transaction_bytes")),commit=blob(attempt.get("commit_bytes"));require("VALID".equals(attempt.get("phase"))&&tx.equals(attempt.get("tx_id"))&&number(attempt.get("validation_code"))==0,"original_attempt_is_valid");
            var outcome=clients.forOrganization(OPERATOR).queryCommit(new EvidenceLedgerClient.CommitCheckpoint(tx,commit));var owner=clients.forOrganization(OWNER).queryEvidence(string(row.get("evidence_id")),(int)number(row.get("version_no")));var operator=clients.forOrganization(OPERATOR).queryEvidence(string(row.get("evidence_id")),(int)number(row.get("version_no")));
            require(outcome.valid()&&owner.found()&&owner.equals(operator)&&tx.equals(owner.record().registeredTxId())&&input.get("sha256").equals(owner.record().fileHash()),"real_saved_commit_and_two_organizations_agree");
            ledger.add(Map.of("versionId",version,"evidenceId",row.get("evidence_id"),"versionNo",row.get("version_no"),"sha256",input.get("sha256"),"confirmedTxId",tx,"blockNumber",outcome.blockNumber(),"validationCode",0,"transactionCheckpointSha256",hash(transaction),"commitCheckpointSha256",hash(commit)));
            expect("recovered_upload_progress","GET",API+"/upload-sessions/"+upload,accounts.get(2),null,200);
        }
        Map<String,Object> candidate=JSON.readValue(Files.readAllBytes(directory.resolve("file-candidate.json")),new TypeReference<Map<String,Object>>() {});String keyHash=string(candidate.get("candidateObjectKeySha256"));Set<String> oldKeys=objectKeys(uploads.get(2));String formal=jdbc.queryForObject("SELECT object_key FROM evidence_version WHERE upload_session_id=?",String.class,uploads.get(2));require(!hash(formal.getBytes(StandardCharsets.UTF_8)).equals(keyHash),"recovered_formal_version_uses_new_candidate_identity");String orphan=oldKeys.stream().filter(k->hash(k.getBytes(StandardCharsets.UTF_8)).equals(keyHash)).findFirst().orElseThrow(()->new Failure("original_candidate_orphan_missing"));require(count("SELECT COUNT(*) FROM evidence_version WHERE object_key=?",orphan)==0,"crashed_candidate_has_no_formal_database_reference_before_teardown");report.put("orphanCandidatePreservedBeforeOwnedTeardown",true);try(var files=Files.list(directory.resolve("managed-temp"))){report.put("crashedLocalTemporaryResidueRetainedForEvidence",files.anyMatch(p->p.getFileName().toString().startsWith("finalize-")&&p.getFileName().toString().endsWith(".tmp")));}
        require(Boolean.TRUE.equals(report.get("duplicateDeliveryAfterDoneAcknowledged")),"real_duplicate_delivery_after_done_is_acknowledged");report.put("originalEventIdsPreserved",true);report.put("uniqueBusinessResultsVerified",true);report.put("confirmedVersions",3);report.put("auditRowsRetained",count("SELECT COUNT(*) FROM audit_log WHERE trace_id=?",trace));
    }
    private void retainAttempts(){report.put("retainedAttemptMetadata",jdbc.queryForList("SELECT v.id AS versionId,v.evidence_id AS evidenceId,v.version_no AS versionNo,v.sha256,a.tx_id AS transactionId,a.phase,a.validation_code AS validationCode,a.block_number AS blockNumber FROM chain_attempt a JOIN chain_submission c ON c.id=a.submission_id JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=? ORDER BY v.version_no,a.attempt_no",taskId));}
    private Set<String> objectKeys(String upload){Set<String> keys=new LinkedHashSet<>();for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")){String next=null;do{var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(next).build());page.contents().forEach(o->keys.add(o.key()));next=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;}while(next!=null);}return keys;}
    private void cleanupFixtures(){
        if(taskId!=null)for(String id:jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,taskId))if(!uploads.contains(id))uploads.add(id);
        Set<String> keys=new LinkedHashSet<>();for(String upload:uploads)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        for(String upload:uploads)jdbc.update("DELETE FROM storage_cleanup_task WHERE upload_id=?",upload);
        if(taskId!=null){jdbc.update("DELETE FROM access_grant WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",taskId);jdbc.update("DELETE FROM anchor_retry_request WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",taskId);jdbc.update("DELETE FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",taskId);jdbc.update("DELETE FROM issue WHERE task_id=?",taskId);jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",taskId);jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",taskId);
            List<String> events=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,taskId,taskId);for(String event:events){ownedEventIds.add(event);jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",taskId);jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",taskId);jdbc.update("DELETE FROM evidence_version WHERE task_id=?",taskId);jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",taskId);jdbc.update("DELETE FROM upload_session WHERE task_id=?",taskId);jdbc.update("DELETE FROM device_event WHERE execution_id IN (SELECT id FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?))",taskId);jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",taskId);jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",taskId);jdbc.update("DELETE FROM material_requirement WHERE task_id=?",taskId);jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",taskId);jdbc.update("DELETE FROM task_member WHERE task_id=?",taskId);jdbc.update("DELETE FROM inspection_task WHERE id=?",taskId);}
        if(deviceId!=null)jdbc.update("DELETE FROM drone WHERE id=? AND code=?",deviceId,"T027-publish-"+runId.substring(0,12));for(Account a:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",a.id,a.username);for(String id:uploads)require(objectKeys(id).isEmpty(),"only_owned_object_namespaces_removed");report.put("fixtureTasksDeleted",taskId==null?0:1);report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",deviceId==null?0:1);report.put("fixtureUploadSessionsDeleted",uploads.size());report.put("fixturePrivateObjectsDeleted",keys.size());report.put("fabricRecordsRetainedAppendOnly",true);
    }
    private void cleanupTopology(){rabbit.execute(channel->{for(String suffix:List.of(".finalize",".anchor",".dead")){String queue=exchange(runId)+suffix;var state=channel.queueDeclarePassive(queue);require(state.getConsumerCount()==0,"own_queue_has_no_active_consumers_at_teardown");while(true){var delivery=channel.basicGet(queue,false);if(delivery==null)break;var event=MessageEvent.decode(delivery.getBody());require(ownedEventIds.contains(event.eventId())&&trace.equals(event.traceId()),"teardown_drains_only_exact_owned_original_events");channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);}channel.queueDelete(queue,true,true);}channel.exchangeDelete(exchange(runId),true);channel.exchangeDelete(exchange(runId)+".dead.events",true);return null;});report.put("ownedRabbitTopologyDeleted",true);}
    private void openDatabase(){var actual=new DriverManagerDataSource();actual.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")+"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true");actual.setUsername(env("DB_USERNAME","uav_app"));actual.setPassword(env("DB_PASSWORD",null));source=actual;jdbc=new JdbcTemplate(actual);}
    private Map<String,Object> expect(String name,String method,String path,Account actor,Object body,int status)throws Exception{return request(name,method,path,actor,body==null?null:JSON.writeValueAsBytes(body),status);}
    private Map<String,Object> request(String name,String method,String path,Account actor,byte[] body,int status)throws Exception{var request=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(30)).header("X-Trace-Id",trace).header("Accept","application/json");if(actor!=null)request.header("Authorization","Bearer "+actor.token);if(body!=null)request.header("Content-Type",path.endsWith("/parts/1")?"application/octet-stream":"application/json");var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());requests.add(Map.of("case",name,"status",response.statusCode(),"traceId",trace));require(response.statusCode()==status,name+"_http_"+status);String text=new String(response.body(),StandardCharsets.UTF_8);boolean credential=path.endsWith("/auth/login")||path.equals(API+"/drones")&&method.equals("POST");require((credential||secrets.stream().noneMatch(text::contains))&&!text.contains("objectKey")&&!text.contains("object_key")&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("transactionBytes")&&!text.contains("commitBytes")&&!text.contains("X-Amz-"),name+"_safe_response");var envelope=JSON.readValue(response.body(),new TypeReference<Map<String,Object>>() {});require("OK".equals(envelope.get("code"))&&trace.equals(envelope.get("traceId")),name+"_success_and_trace");return object(envelope.get("data"));}
    private void require(boolean condition,String name){checks.add(Map.of("check",name,"passed",condition));if(!condition)throw new Failure(name);}
    private void await(BooleanSupplier condition,String name,int seconds)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<until){if(condition.getAsBoolean()){require(true,name);return;}Thread.sleep(200);}require(false,name);}
    private long count(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);if(value==null)throw new Failure("missing_sql_count");return value;}
    private Map<String,Long> counts(){Map<String,Long> result=new LinkedHashMap<>();TABLES.keySet().forEach(t->result.put(t,count("SELECT COUNT(*) FROM "+t)));return result;}
    private byte[] snapshot()throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(Connection connection=source.getConnection()){for(var table:TABLES.entrySet()){frame(digest,table.getKey());try(var query=connection.createStatement();ResultSet rows=query.executeQuery("SELECT * FROM "+table.getKey()+" ORDER BY "+table.getValue())){while(rows.next()){digest.update((byte)1);for(int column=1;column<=rows.getMetaData().getColumnCount();column++){int type=rows.getMetaData().getColumnType(column);if(type==java.sql.Types.BINARY||type==java.sql.Types.VARBINARY||type==java.sql.Types.LONGVARBINARY||type==java.sql.Types.BLOB){byte[] bytes=rows.getBytes(column);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}else frame(digest,rows.getString(column));}}digest.update((byte)0);}}}return digest.digest();}
    private void writeReport()throws Exception{report.put("caseCount",requests.size());report.put("checkCount",checks.size());String value=JSON.writeValueAsString(report);if(secrets.stream().anyMatch(value::contains)||value.contains("upload-parts/")||value.contains("evidence-files/"))throw new Failure("unsafe_private_report");privateJson(directory.resolve("report.json"),report);}
    private static void privateJson(Path output,Object value)throws Exception{Path tmp=Files.createTempFile(output.getParent(),".publish-crash-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.writeString(tmp,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));Files.move(tmp,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
    private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException("SHA-256 unavailable");}}
    private static byte[] png(int color)throws Exception{var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);for(int x=0;x<8;x++)for(int y=0;y<8;y++)image.setRGB(x,y,color);var bytes=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",bytes))throw new Failure("png_encoder_unavailable");return bytes.toByteArray();}
    private static byte[] blob(Object value)throws Exception{if(value instanceof byte[] data)return data;if(value instanceof java.sql.Blob data)return data.getBytes(1,(int)data.length());throw new Failure("missing_binary_checkpoint");}
    private static String string(Object value){if(!(value instanceof String text))throw new Failure("expected_string");return text;}
    private static long number(Object value){if(!(value instanceof Number n))throw new Failure("expected_number");return n.longValue();}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new Failure("expected_object");return (Map<String,Object>)value;}
    private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new Failure("expected_array");return list.stream().map(PublishCrashRecoveryProbe::object).toList();}
    private static String env(String name,String fallback){String value=System.getenv(name);if(value==null||value.isBlank()){if(fallback==null)throw new Failure("missing_required_environment");return fallback;}return value;}
    private static final class Account{final String id,username,password;String token;Account(String id,String username,String password){this.id=id;this.username=username;this.password=password;}@Override public String toString(){return "PublishFixture["+id+"]";}}
    private static final class Failure extends RuntimeException{Failure(String name){super(name);}}

    static final class Child{
        final String phase,runId;final Path directory;final Map<String,Object> input,report=new LinkedHashMap<>();
        final Map<String,AtomicInteger> deliveries=new ConcurrentHashMap<>();final AtomicInteger duplicateDoneAcks=new AtomicInteger(),inflight=new AtomicInteger();
        JdbcTemplate jdbc;RabbitTemplate rabbit;ObjectStorage storage;S3Client s3;String bucket;
        Child(String phase,Path directory)throws Exception{
            if(!Set.of("before-publish","after-confirm","file-candidate","recover").contains(phase))throw new Failure("invalid_child_phase");
            this.phase=phase;this.directory=directory.toAbsolutePath().normalize();input=JSON.readValue(Files.readAllBytes(this.directory.resolve("checkpoint.json")),new TypeReference<Map<String,Object>>() {});runId=string(input.get("runId"));
            var actual=new DriverManagerDataSource();actual.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")+"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true");actual.setUsername(env("DB_USERNAME","uav_app"));actual.setPassword(env("DB_PASSWORD",null));jdbc=new JdbcTemplate(actual);
            report.put("formatVersion",1);report.put("phase",phase);report.put("pid",ProcessHandle.current().pid());report.put("startedAt",Instant.now().toString());report.put("passed",false);active=this;
        }
        Map<String,Object> item(int index){return object(((List<?>)input.get("uploads")).get(index));}
        long count(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);if(value==null)throw new Failure("missing_child_sql_result");return value;}
        void check(boolean condition,String name){if(!condition)throw new Failure(name);}
        int ready(String suffix){return rabbit.execute(channel->channel.queueDeclarePassive(exchange(runId)+suffix).getMessageCount());}
        void execute()throws Exception{
            try(var context=context(phase,directory,runId,false)){
                jdbc=context.getBean(JdbcTemplate.class);rabbit=context.getBean(RabbitTemplate.class);storage=context.getBean(ObjectStorage.class);s3=context.getBean(S3Client.class);bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");
                try{
                    if(phase.equals("recover"))recover(context);
                    else if(phase.equals("file-candidate")){
                        String event=string(item(2).get("eventId"));String payload=jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?",String.class,event);context.getBean(FinalizationProcessor.class).process(MessageEvent.decode(payload.getBytes(StandardCharsets.UTF_8)));throw new Failure("expected_candidate_put_process_halt_missing");
                    }else{context.getBean(OutboxPublisher.class).publishBatch();throw new Failure("expected_publisher_process_halt_missing");}
                }finally{stopWorkers(context);}
                report.put("completedAt",Instant.now().toString());report.put("passed",true);write();
            }catch(Exception failure){report.put("passed",false);report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof Failure)report.put("failedCheck",failure.getMessage());write();throw failure;}
        }
        void crash(OutboxTransactions.Delivery delivery)throws Exception{
            check(!TransactionSynchronizationManager.isActualTransactionActive(),"crash_boundary_must_be_after_committed_claim_without_outer_transaction");
            int index=phase.equals("before-publish")?0:1;var selected=item(index);check(string(selected.get("eventId")).equals(delivery.eventId())&&string(selected.get("uploadId")).equals(delivery.bizId())&&MessageEvent.FILE_FINALIZE_REQUESTED.equals(delivery.eventType()),"only_expected_original_file_event_can_trigger_halt");
            var row=jdbc.queryForMap("SELECT status,attempt_count,lease_until,sent_at FROM outbox_event WHERE event_id=?",delivery.eventId());check("SENDING".equals(row.get("status"))&&number(row.get("attempt_count"))==1&&row.get("sent_at")==null,"original_claim_committed_before_crash");
            int expected=phase.equals("before-publish")?0:1;check(ready(".finalize")==expected,"exact_real_broker_queue_state_at_crash");check(count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",input.get("taskId"))==0,"no_file_version_committed_before_publisher_crash");
            report.put("eventId",delivery.eventId());report.put("uploadId",delivery.bizId());report.put("sendingStateDurable",true);report.put("queueReadyMessagesAtBoundary",expected);report.put("sendingLeaseUntil",String.valueOf(row.get("lease_until")));report.put("productionSendingLeaseSeconds",120);report.put("beforeActualPublish",phase.equals("before-publish"));report.put("realConfirmBeforeDatabaseSent",phase.equals("after-confirm"));halt(phase.equals("before-publish")?73:74);
        }
        void candidate(String key)throws Exception{
            check(!TransactionSynchronizationManager.isActualTransactionActive(),"candidate_put_outside_database_transaction");String upload=string(item(2).get("uploadId"));check(key.startsWith("evidence-files/"+upload+"/"),"candidate_belongs_to_third_owned_upload");
            check(count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=?",upload)==0&&count("SELECT COUNT(*) FROM upload_session WHERE id=? AND status='VERIFYING' AND lease_token IS NOT NULL AND lease_until>UTC_TIMESTAMP(3)",upload)==1,"candidate_saved_before_any_fixed_version_transaction");
            var metadata=s3.headObject(builder->builder.bucket(bucket).key(key));check(metadata.contentLength()==number(item(2).get("size")),"real_candidate_object_exists_before_halt");report.put("uploadId",upload);report.put("candidateObjectKeySha256",hash(key.getBytes(StandardCharsets.UTF_8)));report.put("candidateSize",metadata.contentLength());report.put("candidateObjectWritten",true);report.put("noFormalVersionBeforeHalt",true);report.put("productionProcessingLeaseSeconds",120);halt(75);
        }
        void halt(int exit)throws Exception{report.put("intentionalHaltExitCode",exit);report.put("passed",true);report.put("boundaryAt",Instant.now().toString());write();System.out.flush();System.err.flush();Runtime.getRuntime().halt(exit);}
        void recover(ConfigurableApplicationContext context)throws Exception{
            String task=string(input.get("taskId"));check(context.getBean("finalizationContainer",SimpleMessageListenerContainer.class).isRunning()&&context.getBean("anchorContainer",SimpleMessageListenerContainer.class).isRunning(),"default_production_consumers_running_in_new_jvm");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            while(System.nanoTime()<deadline){if(count("SELECT COUNT(*) FROM evidence_version v JOIN chain_submission c ON c.version_id=v.id WHERE v.task_id=? AND c.status='CONFIRMED'",task)==3&&count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT' AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",task,task)==0)break;Thread.sleep(200);}
            check(count("SELECT COUNT(*) FROM evidence_version v JOIN chain_submission c ON c.version_id=v.id WHERE v.task_id=? AND c.status='CONFIRMED'",task)==3,"default_automatic_workers_confirm_all_three_original_uploads");
            check(count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT' AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",task,task)==0,"all_owned_outbox_events_are_sent_before_replay");
            String original=string(item(1).get("eventId"));String payload=jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?",String.class,original);MessageEvent event=MessageEvent.decode(payload.getBytes(StandardCharsets.UTF_8));
            check(count("SELECT COUNT(*) FROM consumed_event WHERE consumer_code='FILE_FINALIZE' AND event_id=? AND status='DONE'",original)==1,"source_event_done_before_explicit_post_done_replay");
            long automaticDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(System.nanoTime()<automaticDeadline){if(deliveries.getOrDefault(original,new AtomicInteger()).get()>=2&&inflight.get()==0&&ready(".finalize")==0&&ready(".anchor")==0)break;Thread.sleep(200);}
            check(deliveries.getOrDefault(original,new AtomicInteger()).get()>=2&&inflight.get()==0&&ready(".finalize")==0&&ready(".anchor")==0,"automatic_outbox_replay_reaches_real_listener_twice_before_explicit_replay");
            int acknowledgementsBeforeReplay=duplicateDoneAcks.get(),deliveriesBeforeReplay=deliveries.get(original).get();
            MessageProperties properties=new MessageProperties();properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);properties.setContentEncoding("UTF-8");properties.setMessageId(original);CorrelationData correlation=new CorrelationData(original+":t027-post-done");rabbit.send(exchange(runId),event.routingKey(),new Message(payload.getBytes(StandardCharsets.UTF_8),properties),correlation);check(correlation.getFuture().get(10,TimeUnit.SECONDS).ack()&&correlation.getReturned()==null,"post_done_original_event_replay_is_actually_confirmed_by_broker");
            long duplicateDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(System.nanoTime()<duplicateDeadline){if(duplicateDoneAcks.get()>acknowledgementsBeforeReplay&&deliveries.getOrDefault(original,new AtomicInteger()).get()>deliveriesBeforeReplay&&inflight.get()==0&&ready(".finalize")==0&&ready(".anchor")==0)break;Thread.sleep(200);}
            check(duplicateDoneAcks.get()>acknowledgementsBeforeReplay&&deliveries.getOrDefault(original,new AtomicInteger()).get()>deliveriesBeforeReplay,"real_listener_acknowledges_original_duplicate_after_done");check(inflight.get()==0&&ready(".finalize")==0&&ready(".anchor")==0,"all_owned_real_deliveries_drained_after_duplicate_ack");
            check(count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",task)==3&&count("SELECT COUNT(*) FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task)==3,"duplicate_replay_preserves_unique_business_versions_and_submissions");
            Map<String,Integer> actual=new LinkedHashMap<>();deliveries.forEach((id,value)->actual.put(id,value.get()));report.put("originalEventDeliveries",actual);report.put("duplicateDeliveryAfterDoneAcknowledged",true);report.put("duplicateDoneAcknowledgements",duplicateDoneAcks.get());report.put("postReplayNewDuplicateAcknowledgements",duplicateDoneAcks.get()-acknowledgementsBeforeReplay);report.put("explicitPostDoneDuplicatePublishes",1);report.put("defaultAutomaticRecoveryVerified",true);report.put("confirmedVersions",3);
        }
        void write()throws Exception{privateJson(directory.resolve(phase+".json"),report);}
    }
    @TestConfiguration(proxyBeanMethods=false) @Profile("publish-crash-probe")
    static class ProbeConfiguration{
        @Bean @Primary OutboxTransactions publishCrashTransactions(OutboxMapper mapper,@Qualifier("outboxTransactions")OutboxTransactions real){return new CrashTransactions(mapper,real);}
        @Bean @Primary ObjectStorage publishCrashStorage(@Qualifier("objectStorage")ObjectStorage real){return new ObjectStorage(){
            @Override public void put(String key,Path file,String type){real.put(key,file,type);Child child=active;if(child!=null&&child.phase.equals("file-candidate")&&key.startsWith("evidence-files/"+child.item(2).get("uploadId")+"/"))try{child.candidate(key);}catch(Failure failure){throw failure;}catch(Exception failure){throw new Failure("cannot_write_candidate_crash_checkpoint");}}
            @Override public InputStream open(String key){return real.open(key);}@Override public void delete(String key){real.delete(key);}
        };}
        @Bean static BeanPostProcessor ownConsumerObservation(){return new BeanPostProcessor(){@Override public Object postProcessBeforeInitialization(Object bean,String name){
            if(bean instanceof SimpleMessageListenerContainer container){Child child=active;if(child==null||!child.phase.equals("recover"))container.setAutoStartup(false);
                else if(name.equals("finalizationContainer")&&container.getMessageListener() instanceof ChannelAwareMessageListener real){container.setMessageListener((ChannelAwareMessageListener)(message,channel)->{
                    MessageEvent event=MessageEvent.decode(message.getBody());child.inflight.incrementAndGet();try{child.deliveries.computeIfAbsent(event.eventId(),ignored->new AtomicInteger()).incrementAndGet();boolean alreadyDone=child.count("SELECT COUNT(*) FROM consumed_event WHERE consumer_code='FILE_FINALIZE' AND event_id=? AND status='DONE'",event.eventId())==1;
                    Channel observed=(Channel)Proxy.newProxyInstance(Channel.class.getClassLoader(),new Class<?>[]{Channel.class},(proxy,method,args)->{Object result;try{result=method.invoke(channel,args);}catch(InvocationTargetException wrapped){throw wrapped.getCause();}if(method.getName().equals("basicAck")&&alreadyDone)child.duplicateDoneAcks.incrementAndGet();return result;});
                    real.onMessage(message,observed);}finally{child.inflight.decrementAndGet();}
                });}
            }return bean;
        }};}
    }
    static class CrashTransactions extends OutboxTransactions{
        final OutboxTransactions real;CrashTransactions(OutboxMapper mapper,OutboxTransactions real){super(mapper);this.real=real;}
        @Override @Transactional(propagation=Propagation.NOT_SUPPORTED) public Delivery claim(){Delivery delivery=real.claim();Child child=active;if(delivery!=null&&child!=null&&child.phase.equals("before-publish"))try{child.crash(delivery);}catch(Failure failure){throw failure;}catch(Exception failure){throw new Failure("cannot_write_before_publish_crash_checkpoint");}return delivery;}
        @Override @Transactional(propagation=Propagation.NOT_SUPPORTED) public boolean sent(Delivery delivery){Child child=active;if(child!=null&&child.phase.equals("after-confirm"))try{child.crash(delivery);}catch(Failure failure){throw failure;}catch(Exception failure){throw new Failure("cannot_write_confirm_crash_checkpoint");}return real.sent(delivery);}
    }
}
