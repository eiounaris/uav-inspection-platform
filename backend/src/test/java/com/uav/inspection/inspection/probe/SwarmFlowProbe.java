package com.uav.inspection.inspection.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.messaging.MessageEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.FilterInputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Explicit real swarm probe. HTTP business actions, automatic default workers, no Fabric writes. */
public final class SwarmFlowProbe {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String API="/api/v1", OWNER="00000000-0000-4000-8000-000000000001", OPERATOR="00000000-0000-4000-8000-000000000002";
    private static final Map<String,String> TABLE_ORDER=new LinkedHashMap<>();
    static {
        TABLE_ORDER.put("organization","id");TABLE_ORDER.put("app_user","id");TABLE_ORDER.put("inspection_task","id");
        TABLE_ORDER.put("task_member","task_id,user_id");TABLE_ORDER.put("drone","id");TABLE_ORDER.put("inspection_subtask","id");
        TABLE_ORDER.put("material_requirement","id");TABLE_ORDER.put("task_execution","id");TABLE_ORDER.put("upload_session","id");
        TABLE_ORDER.put("upload_part","session_id,part_no");TABLE_ORDER.put("evidence_version","id");TABLE_ORDER.put("outbox_event","event_id");
        TABLE_ORDER.put("consumed_event","consumer_code,event_id");TABLE_ORDER.put("chain_submission","id");TABLE_ORDER.put("chain_attempt","id");
        TABLE_ORDER.put("verification_record","id");TABLE_ORDER.put("device_event","id");TABLE_ORDER.put("execution_adjustment","id");
    }
    private final String runId=UUID.randomUUID().toString(), exchange="inspection.swarm.probe."+runId;
    private final String finalizeQueue=exchange+".finalize", anchorQueue=exchange+".anchor",deadExchange=exchange+".dead.events",deadQueue=exchange+".dead";
    private final String redisPrefix="inspection:swarm:probe:"+runId+":";
    private final Path reportPath;
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<Map<String,Object>> checks=Collections.synchronizedList(new ArrayList<>()),requests=Collections.synchronizedList(new ArrayList<>());
    private final List<Account> accounts=new ArrayList<>();
    private final List<Device> devices=new ArrayList<>();
    private final List<Scope> scopes=new ArrayList<>();
    private final List<String> secrets=new ArrayList<>();
    private final Set<String> uploadIds=new LinkedHashSet<>();
    private final AtomicInteger sequence=new AtomicInteger();
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private S3Client s3;
    private ObjectStorage storage;
    private FaultStorage faults;
    private RabbitTemplate rabbit;
    private StringRedisTemplate redis;
    private HttpClient client;
    private String baseUrl,bucket,storageEndpoint,taskId;
    private byte[] sample;

    private SwarmFlowProbe(Path path) {
        reportPath=path.toAbsolutePath().normalize();
        report.put("formatVersion",1);report.put("runId",runId);report.put("startedAt",Instant.now().toString());
        report.put("passed",false);report.put("checks",checks);report.put("requests",requests);
    }
    public static void main(String[] args) {
        if(args.length!=1){System.err.println("Usage: SwarmFlowProbe <reportPath>");System.exit(2);return;}
        var probe=new SwarmFlowProbe(Path.of(args[0]));
        try{probe.execute();probe.writeReport();System.out.println("[PASS] T016 real swarm probe: "+probe.reportPath);}
        catch(Exception failure){
            probe.report.put("passed",false);probe.report.put("failureType",failure.getClass().getSimpleName());
            if(failure instanceof ProbeFailure)probe.report.put("failedCheck",failure.getMessage());
            try{probe.writeReport();}catch(Exception ignored){System.err.println("Could not persist safe swarm report");}
            System.err.println("[FAIL] T016 swarm probe; inspect private report");System.exit(1);
        }
    }
    private void execute()throws Exception {
        report.put("stage","read_only_pending_work_preflight");
        var source=new DriverManagerDataSource();
        source.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")
                +"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8");
        source.setUsername(env("DB_USERNAME","uav_app"));source.setPassword(env("DB_PASSWORD",null));
        jdbc=new JdbcTemplate(source);dataSource=source;
        require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version='9' AND success=1")==1,"v9_migrated_before_probe");
        require(count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT'")==0
                &&count("SELECT COUNT(*) FROM upload_session WHERE status='VERIFYING'")==0
                &&count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'")==0
                &&count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1)")==0,
                "global_no_pending_work_before_automatic_workers");
        byte[] baseline=businessSnapshot();report.put("businessCountsBefore",businessCounts());
        report.put("stage","isolated_application_startup");
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,FaultInjection.class)
                .profiles("local","database","storage","messaging","swarm-probe").web(WebApplicationType.SERVLET).logStartupInfo(false)
                .run("--server.port=0","--inspection.messaging.exchange="+exchange,"--inspection.messaging.finalize-queue="+finalizeQueue,
                        "--inspection.messaging.anchor-queue="+anchorQueue,"--inspection.messaging.dead-letter-exchange="+deadExchange,
                        "--inspection.messaging.dead-letter-queue="+deadQueue,"--inspection.messaging.recovery-enabled=false",
                        "--inspection.ledger.recovery-enabled=false","--inspection.device.presence-key-prefix="+redisPrefix)) {
            jdbc=context.getBean(JdbcTemplate.class);dataSource=context.getBean(DataSource.class);
            s3=context.getBean(S3Client.class);storage=context.getBean(ObjectStorage.class);faults=context.getBean(FaultStorage.class);
            rabbit=context.getBean(RabbitTemplate.class);redis=context.getBean(StringRedisTemplate.class);
            bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");storageEndpoint=context.getEnvironment().getRequiredProperty("inspection.storage.endpoint");
            baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            require(!context.getEnvironment().acceptsProfiles(org.springframework.core.env.Profiles.of("ledger")),"no_ledger_profile_or_anchor_consumer");
            await(()->context.getBean("finalizationContainer",SimpleMessageListenerContainer.class).getActiveConsumerCount()==1,"production_default_single_finalizer");
            sample=Files.readAllBytes(Path.of("examples/files/inspection-sample.png"));
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                client=actual;
                try {
                    report.put("stage","fixture_setup");createFixtures();createTask();
                    report.put("stage","real_parallel_simulator");runSimulator();
                    report.put("stage","replacement_and_historical_versions");verifyReplacement();verifyFixedBytes();verifyAudit();
                    await(()->ownOutboxPending()==0,"all_owned_events_published_automatically");
                    require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",taskId)==0,"no_fabric_attempt_created");
                } finally {
                    faults.release.countDown();context.getBean(ThreadPoolTaskScheduler.class).shutdown();
                    context.getBean("finalizationContainer",SimpleMessageListenerContainer.class).stop();
                    report.put("cleanupAttempted",true);
                    drainOwnedAnchors();cleanupFixtures();cleanupTopology();
                    require(MessageDigest.isEqual(baseline,businessSnapshot()),"original_eighteen_business_tables_unchanged");
                    report.put("originalBusinessDataUnchanged",true);report.put("businessCountsAfter",businessCounts());
                }
            }
        }
        report.put("stage","complete");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }
    private void createFixtures()throws Exception {
        account(OWNER,"creator",List.of("OWNER_TASK_ADMIN"));account(OPERATOR,"admin",List.of("OPERATOR_TASK_ADMIN"));
        account(OPERATOR,"member",List.of("OPERATOR_USER"));account(OPERATOR,"outsider",List.of("OPERATOR_USER"));
        account(OPERATOR,"org-admin-only",List.of("ORG_ADMIN"));
        for(Account a:accounts){a.token=string(expect("fixture_login","POST",API+"/auth/login",a,null,Map.of("username",a.username,"password",a.password),200).data().get("accessToken"));secrets.add(a.token);}
        for(int index=0;index<5;index++) {
            String code="T016-"+runId.substring(0,12)+"-"+index;
            Map<String,Object> created=expect("register_swarm_device","POST",API+"/drones",admin(),null,Map.of("code",code),200).data();
            String token=string(created.get("deviceToken"));secrets.add(token);secrets.add(sha256(token.getBytes(StandardCharsets.UTF_8)));
            devices.add(new Device(string(object(created.get("device")).get("id")),code,token));
        }
        report.put("fixtureAccountIds",accounts.stream().map(a->a.id).toList());report.put("fixtureDeviceIds",devices.stream().map(Device::id).toList());
    }
    private void account(String org,String suffix,List<String> roles) {
        byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);String password=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);secrets.add(password);
        Account a=new Account(UUID.randomUUID().toString(),"t016-"+runId.substring(0,12)+"-"+suffix,password);accounts.add(a);
        require(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",a.id,org,a.username,
                new BCryptPasswordEncoder(12).encode(password),JSON.writeValueAsString(roles))==1,"isolated_account_inserted");
    }
    private void createTask()throws Exception {
        var task=expect("create_three_subtask_task","POST",API+"/tasks",accounts.getFirst(),null,
                Map.of("name","T016-"+runId,"scope","isolated swarm acceptance", "requirements",List.of(
                        Map.of("name","zone-A","required",true),Map.of("name","zone-B","required",true),Map.of("name","zone-C","required",true))),200).data();
        taskId=string(task.get("id"));
        task=expect("assign_task","POST",taskPath()+"/assign",accounts.getFirst(),null,Map.of("operatorOrgId",OPERATOR,"operatorAdminId",admin().id,"expectedLockVersion",version(task)),200).data();
        task=expect("set_task_members","PUT",taskPath()+"/members",admin(),null,Map.of("userIds",List.of(admin().id,accounts.get(2).id),"expectedLockVersion",version(task)),200).data();
        List<Map<String,Object>> requirements=objects(task.get("requirements"));
        for(int index=0;index<3;index++) {
            String req=string(requirements.get(index).get("id"));
            task=expect("create_subtask","POST",taskPath()+"/subtasks",admin(),null,Map.of("name","flight-"+index,"requirementIds",List.of(req),"expectedLockVersion",version(task)),200).data();
            Map<String,Object> sub=objects(task.get("subtasks")).stream().filter(s->("flight-"+scopes.size()).equals(s.get("name"))).findFirst().orElseThrow();
            Map<String,Object> execution=expect("initial_execution","POST",executionPath(string(sub.get("id"))),admin(),null,Map.of("droneId",devices.get(index).id,"expectedLockVersion",version(sub)),200).data();
            scopes.add(new Scope(req,string(sub.get("id")),string(execution.get("id"))));task=task();
        }
        task=expect("start_task","POST",taskPath()+"/start",admin(),null,Map.of("expectedLockVersion",version(task)),200).data();
        require("RUNNING".equals(task.get("status")),"task_started_before_reports");
        Map<String,Object> empty=summary();require(number(empty.get("missingRequiredCount"))==3&&number(empty.get("presentRequiredCount"))==0,"initial_summary_has_three_missing_requirements");
        report.put("fixtureTaskId",taskId);
    }
    private void runSimulator()throws Exception {
        Path run=reportPath.getParent(),config=run.resolve("simulator-config.json"),state=run.resolve("simulator-state");
        var options=new LinkedHashMap<String,Object>();options.put("formatVersion",1);options.put("baseUrl",baseUrl);options.put("count",3);
        options.put("intervalSeconds",0.1);options.put("cycles",6);options.put("progressStep",30);options.put("uploadEveryCycles",4);
        options.put("repeatEveryCycles",2);options.put("networkOutageCycles",List.of(2,3));options.put("completeUploads",true);
        options.put("pngPath","examples/files/inspection-sample.png");options.put("stateDirectory",state.toString());
        List<Map<String,Object>> settings=new ArrayList<>();
        for(int i=0;i<3;i++)settings.add(Map.of("deviceId",devices.get(i).id,"deviceToken",devices.get(i).token,"executionId",scopes.get(i).execution,"requirementId",scopes.get(i).requirement));
        options.put("devices",settings);privateWrite(config,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(options));
        Path log=run.resolve("simulator.log");Files.createFile(log,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Process process=new ProcessBuilder("python3","simulator/swarm.py","--config",config.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();throw new ProbeFailure("simulator_timeout");}
            require(process.exitValue()==0,"three_parallel_devices_survive_outage_and_replay");
        } finally {Files.deleteIfExists(config);}
        Map<String,Object> result=JSON.readValue(Files.readAllBytes(state.resolve("summary.json")),Map.class);
        require(Boolean.TRUE.equals(result.get("passed"))&&number(result.get("deviceCount"))==3,"real_simulator_summary_passed");
        for(var device:objects(result.get("devices")))require(number(device.get("duplicates"))>=2&&number(device.get("simulatedOfflineCycles"))==2
                &&number(device.get("uploadsSubmitted"))==1&&number(device.get("partsUploaded"))==1,"each_device_persisted_replayed_and_uploaded");
        report.put("simulator",result);
        await(()->count("SELECT COUNT(*) FROM upload_session WHERE task_id=? AND status='COMPLETED'",taskId)==3,"three_simulator_uploads_automatically_finalized");
        Map<String,Object> progress=summary();
        require(number(progress.get("presentRequiredCount"))==3&&number(progress.get("missingRequiredCount"))==0
                &&number(progress.get("acceptanceReadyRequiredCount"))==0&&number(progress.get("historicalVersionCount"))==0,"current_pending_versions_present_but_not_acceptable");
        require(((Number)progress.get("averageProgress")).doubleValue()==60.0,"three_current_progress_values_average_sixty");
        require(count("SELECT COUNT(*) FROM device_event WHERE drone_id IN (?,?,?)",devices.get(0).id,devices.get(1).id,devices.get(2).id)==12,"parallel_replays_create_only_twelve_distinct_events");
        for(int i=0;i<3;i++)require(redis.opsForValue().get(redisPrefix+devices.get(i).id)!=null,"each_active_device_has_real_redis_presence");
    }
    private void verifyReplacement()throws Exception {
        Scope old=scopes.getFirst();Device oldDevice=devices.getFirst();
        expect("outsider_progress_forbidden","GET",taskPath()+"/progress",accounts.get(3),null,null,403);
        expect("org_admin_without_business_role_forbidden","GET",taskPath()+"/progress",accounts.get(4),null,null,403);
        expect("outsider_history_forbidden","GET",executionPath(old.subtask),accounts.get(3),null,null,403);
        Map<String,Object> current=subtask(old.subtask);
        expect("replacement_requires_reason","POST",executionPath(old.subtask),admin(),null,Map.of("droneId",devices.get(3).id,"expectedLockVersion",version(current)),400);
        expect("replacement_same_drone_rejected","POST",executionPath(old.subtask),admin(),null,Map.of("droneId",oldDevice.id,"reason","same drone","expectedLockVersion",version(current)),409);
        expect("member_cannot_adjust_execution","POST",executionPath(old.subtask),accounts.get(2),null,Map.of("droneId",devices.get(3).id,"reason","forbidden","expectedLockVersion",version(current)),403);
        String delayed=createUpload(oldDevice,old,"before-switch.png");
        String key=jdbc.queryForObject("SELECT object_key FROM upload_part WHERE session_id=? AND part_no=1",String.class,delayed);
        faults.arm(key);complete(oldDevice,delayed);
        require(faults.entered.await(20,TimeUnit.SECONDS),"real_s3_read_paused_after_verify_claim");
        require("VERIFYING".equals(jdbc.queryForObject("SELECT status FROM upload_session WHERE id=?",String.class,delayed)),"old_batch_is_verifying_before_replacement");
        long lock=version(current);
        CountDownLatch start=new CountDownLatch(1);
        CompletableFuture<Reply> a=replaceParallel(start,old,devices.get(3),lock,"battery replacement");
        CompletableFuture<Reply> b=replaceParallel(start,old,devices.get(4),lock,"radio replacement");start.countDown();
        Reply first=a.get(20,TimeUnit.SECONDS),second=b.get(20,TimeUnit.SECONDS);
        require(List.of(first.status,second.status).stream().filter(s->s==200).count()==1&&List.of(first.status,second.status).stream().filter(s->s==409).count()==1,"concurrent_cas_creates_exactly_one_new_current_execution");
        assertStatus(first,first.status,"first_cas_response");assertStatus(second,second.status,"second_cas_response");
        Map<String,Object> replacement=(first.status==200?first:second).data();
        String newExecution=string(replacement.get("id"));Device newDevice=devices.stream().filter(d->d.id.equals(replacement.get("droneId"))).findFirst().orElseThrow();
        Scope active=new Scope(old.requirement,old.subtask,newExecution);
        require("RUNNING".equals(replacement.get("status"))&&number(replacement.get("progress"))==0,"replacement_starts_running_with_zero_progress");
        require(count("SELECT COUNT(*) FROM execution_adjustment WHERE task_id=?",taskId)==1,"exactly_one_durable_adjustment");
        require("CLOSED".equals(jdbc.queryForObject("SELECT status FROM task_execution WHERE id=?",String.class,old.execution)),"old_execution_closed_atomically");
        Map<String,Object> summary=summary();require(number(summary.get("missingRequiredCount"))==1&&number(summary.get("presentRequiredCount"))==2
                &&((Number)summary.get("averageProgress")).doubleValue()==40.0,"replacement_resets_current_summary_and_excludes_old_material");
        require(((List<?>)summary.get("missingRequiredIds")).contains(old.requirement),"missing_requirement_identifies_replaced_subtask");
        Map<String,Object> state=JSON.readValue(Files.readAllBytes(reportPath.getParent().resolve("simulator-state").resolve(oldDevice.id+".json")),Map.class);
        Map<String,Object> accepted=object(object(state.get("lastAck")).get("payload"));
        String beforeSeen=jdbc.queryForObject("SELECT CAST(last_seen_at AS CHAR) FROM drone WHERE id=?",String.class,oldDevice.id);
        long beforeEvents=count("SELECT COUNT(*) FROM device_event WHERE drone_id=?",oldDevice.id);
        Map<String,Object> replay=expect("closed_batch_accepted_report_replay","POST",API+"/device/reports",null,oldDevice,accepted,200).data();
        require(Boolean.TRUE.equals(replay.get("duplicate"))&&beforeSeen.equals(jdbc.queryForObject("SELECT CAST(last_seen_at AS CHAR) FROM drone WHERE id=?",String.class,oldDevice.id))
                &&beforeEvents==count("SELECT COUNT(*) FROM device_event WHERE drone_id=?",oldDevice.id),"old_duplicate_replays_without_presence_or_event_mutation");
        Map<String,Object> oldReport=Map.of("bootId",UUID.randomUUID().toString(),"seq",0,"eventId",UUID.randomUUID().toString(),"executionId",old.execution,"type","PROGRESS","progress",100);
        long frozenOld=count("SELECT progress FROM task_execution WHERE id=?",old.execution);
        Map<String,Object> historicalReport=expect("new_closed_batch_report_records_liveness","POST",API+"/device/reports",null,oldDevice,oldReport,200).data();
        require(number(historicalReport.get("executionProgress"))==frozenOld&&count("SELECT progress FROM task_execution WHERE id=?",old.execution)==frozenOld
                &&Boolean.TRUE.equals(expect("old_device_recovers_online","GET",API+"/drones/"+oldDevice.id,admin(),null,null,200).data().get("online")),"closed_batch_event_refreshes_only_original_device_presence_and_freezes_progress");
        expect("new_device_cannot_report_old_execution","POST",API+"/device/reports",null,newDevice,oldReport,403);
        expect("new_device_cannot_upload_old_execution","POST",API+"/device/upload-sessions",null,newDevice,uploadBody(old,"forbidden.png"),403);
        require(count("SELECT progress FROM task_execution WHERE id=?",newExecution)==0,"old_reports_do_not_advance_current_execution");
        faults.release.countDown();await(()->completed(delayed),"pre_switch_verifying_upload_finishes_as_history");
        require(count("SELECT version_no FROM evidence_version WHERE upload_session_id=?",delayed)==2,"delayed_old_batch_has_historical_version_two");
        String activeUpload=createUpload(newDevice,active,"current.png");complete(newDevice,activeUpload);await(()->completed(activeUpload),"replacement_current_upload_finalized");
        String activeVersion=jdbc.queryForObject("SELECT id FROM evidence_version WHERE upload_session_id=?",String.class,activeUpload);
        require(count("SELECT version_no FROM evidence_version WHERE id=?",activeVersion)==3,"current_execution_has_version_three");
        String late=createUpload(oldDevice,old,"closed-late.png");complete(oldDevice,late);await(()->completed(late),"closed_execution_can_append_original_device_history");
        require(count("SELECT version_no FROM evidence_version WHERE upload_session_id=?",late)==4,"late_historical_version_is_larger_than_current");
        summary=summary();Map<String,Object> material=objects(summary.get("requirements")).stream().filter(r->old.requirement.equals(r.get("requirementId"))).findFirst().orElseThrow();
        require(activeVersion.equals(material.get("versionId"))&&number(material.get("versionNo"))==3&&newExecution.equals(material.get("currentExecutionId"))
                &&number(material.get("historicalVersionCount"))==3,"summary_selects_current_batch_version_not_global_maximum");
        require(number(summary.get("presentRequiredCount"))==3&&number(summary.get("historicalVersionCount"))==3&&number(summary.get("acceptanceReadyRequiredCount"))==0,"historical_pending_versions_never_satisfy_acceptance");
        Map<String,Object> history=expect("member_execution_history","GET",executionPath(old.subtask)+"?page=1&pageSize=20",accounts.get(2),null,null,200).data();
        require(number(history.get("total"))==2,"history_contains_old_and_new_execution");
        List<Map<String,Object>> items=objects(history.get("items"));Map<String,Object> newRow=items.stream().filter(i->newExecution.equals(i.get("id"))).findFirst().orElseThrow();
        Map<String,Object> oldRow=items.stream().filter(i->old.execution.equals(i.get("id"))).findFirst().orElseThrow();
        require(Boolean.TRUE.equals(newRow.get("current"))&&old.execution.equals(newRow.get("previousExecutionId"))&&admin().id.equals(newRow.get("adjustedBy"))
                &&newRow.get("adjustedAt")!=null&&newRow.get("adjustmentReason")!=null&&!Boolean.TRUE.equals(oldRow.get("current"))&&oldRow.get("closedAt")!=null,"history_preserves_current_flag_actor_reason_and_timestamps");
        expect("invalid_history_page","GET",executionPath(old.subtask)+"?page=0",admin(),null,null,400);
        Map<String,Object> hundred=Map.of("bootId",UUID.randomUUID().toString(),"seq",0,"eventId",UUID.randomUUID().toString(),"executionId",newExecution,"type","PROGRESS","progress",100);
        expect("replacement_progress_hundred","POST",API+"/device/reports",null,newDevice,hundred,200);
        require("RUNNING".equals(summary().get("status")),"progress_hundred_does_not_complete_task_or_accept_material");
        Map<String,Object> device=expect("read_old_device","GET",API+"/drones/"+oldDevice.id,admin(),null,null,200).data();
        expect("disable_old_device","PATCH",API+"/drones/"+oldDevice.id+"/enabled",admin(),null,Map.of("enabled",false,"expectedLockVersion",version(device)),200);
        expect("disabled_old_device_cannot_read_history_upload","GET",API+"/device/upload-sessions/"+late,null,oldDevice,null,401);
        report.put("replacementExecutionId",newExecution);report.put("currentVersionId",activeVersion);report.put("currentVersionNo",3);report.put("largerHistoricalVersionNo",4);
    }
    private CompletableFuture<Reply> replaceParallel(CountDownLatch start,Scope scope,Device target,long lock,String reason) {
        return CompletableFuture.supplyAsync(()->{try{if(!start.await(5,TimeUnit.SECONDS))throw new ProbeFailure("race_start_timeout");
            return request("concurrent_replace","POST",executionPath(scope.subtask),admin(),null,Map.of("droneId",target.id,"expectedLockVersion",lock,"reason",reason),false);
        }catch(Exception failure){throw new ProbeFailure("replacement_http_race_failed");}});
    }
    private String createUpload(Device device,Scope scope,String filename)throws Exception {
        Map<String,Object> created=expect("create_device_upload","POST",API+"/device/upload-sessions",null,device,uploadBody(scope,filename),200).data();
        String id=string(created.get("uploadId"));uploadIds.add(id);
        Reply part=request("store_device_part","PUT",API+"/device/upload-sessions/"+id+"/parts/1",null,device,sample,true);assertStatus(part,200,"store_device_part");
        return id;
    }
    private Map<String,Object> uploadBody(Scope scope,String name){return Map.of("clientRequestId",UUID.randomUUID().toString(),"requirementId",scope.requirement,"executionId",scope.execution,"filename",name,"contentType","image/png","size",sample.length);}
    private void complete(Device device,String id)throws Exception {expect("request_automatic_finalize","POST",API+"/device/upload-sessions/"+id+"/complete",null,device,null,202);}
    private boolean completed(String id){return "COMPLETED".equals(jdbc.queryForObject("SELECT status FROM upload_session WHERE id=?",String.class,id));}
    private long ownOutboxPending(){return count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT' AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",taskId,taskId);}
    private Map<String,Object> summary()throws Exception{return expect("read_current_task_progress","GET",taskPath()+"/progress",admin(),null,null,200).data();}
    private Map<String,Object> task()throws Exception{return expect("read_task","GET",taskPath(),admin(),null,null,200).data();}
    private Map<String,Object> subtask(String id)throws Exception{return objects(task().get("subtasks")).stream().filter(s->id.equals(s.get("id"))).findFirst().orElseThrow();}
    private void drainOwnedAnchors() {
        if(taskId==null)return;
        Set<String> expected=new LinkedHashSet<>(jdbc.queryForList("SELECT event_id FROM outbox_event WHERE event_type='EVIDENCE_ANCHOR_REQUESTED' AND biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,taskId));
        Set<String> seen=new LinkedHashSet<>();
        rabbit.execute(channel->{
            while(true){var delivery=channel.basicGet(anchorQueue,false);if(delivery==null)break;
                MessageEvent event=JSON.readValue(delivery.getBody(),MessageEvent.class);
                require(expected.contains(event.eventId())&&MessageEvent.EVIDENCE_ANCHOR_REQUESTED.equals(event.eventType()),"only_owned_anchor_event_is_acknowledged_without_ledger");
                require(count("SELECT COUNT(*) FROM outbox_event WHERE event_id=? AND biz_id=? AND status='SENT'",event.eventId(),event.bizId())==1,"anchor_envelope_matches_own_durable_sent_outbox");
                require(Integer.valueOf(2).equals(delivery.getProps().getDeliveryMode()),"anchor_message_persistent");
                seen.add(event.eventId());channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
            }return null;
        });
        require(seen.equals(expected),"all_owned_anchor_messages_accounted_for_without_chain_writes");
        report.put("anchorMessagesAcknowledgedWithoutLedger",seen.size());report.put("anchorStatusesRemainPending",count("SELECT COUNT(*) FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?) AND status='PENDING'",taskId));
    }
    private Set<String> objectKeys(String upload) {
        Set<String> keys=new LinkedHashSet<>();for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")) {
            String continuation=null;do{var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(continuation).build());page.contents().forEach(o->keys.add(o.key()));continuation=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;}while(continuation!=null);
        }return keys;
    }
    private void cleanupFixtures() {
        List<String> tasks=new ArrayList<>();for(Account a:accounts)tasks.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,a.id));
        Set<String> keys=new LinkedHashSet<>();
        for(String task:tasks){uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));}
        for(String upload:uploadIds)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        for(String task:tasks){
            jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",task);
            jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            List<String> eventIds=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,task,task);
            for(String event:eventIds){jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}
            jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);
            jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);
            jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);
            jdbc.update("DELETE FROM device_event WHERE execution_id IN (SELECT id FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?))",task);
            jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);
            jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);
            jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);
            jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);
        }
        for(Device d:devices){jdbc.update("DELETE FROM device_event WHERE drone_id=?",d.id);jdbc.update("DELETE FROM drone WHERE id=? AND code=?",d.id,d.code);}
        for(Account a:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",a.id,a.username);
        Set<String> redisKeys=redis.keys(redisPrefix+"*");if(redisKeys!=null)for(String key:redisKeys){require(key.startsWith(redisPrefix),"redis_cleanup_owned_namespace");redis.delete(key);}
        for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"only_own_upload_objects_cleaned");
        report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",devices.size());
        report.put("fixtureUploadSessionsDeleted",uploadIds.size());report.put("fixturePrivateObjectsDeleted",keys.size());report.put("fixtureRedisKeysDeleted",redisKeys==null?0:redisKeys.size());
    }
    private void cleanupTopology() {
        rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue)){
            var state=channel.queueDeclarePassive(queue);require(state.getMessageCount()==0&&state.getConsumerCount()==0,"only_empty_unused_owned_queue_deleted");channel.queueDelete(queue,true,true);
        }channel.exchangeDelete(exchange,true);channel.exchangeDelete(deadExchange,true);return null;});report.put("ownedRabbitTopologyDeleted",true);
    }
    private void verifyFixedBytes()throws Exception {
        List<Map<String,Object>> versions=jdbc.queryForList("SELECT id,execution_id,object_key,size,sha256 FROM evidence_version WHERE task_id=? ORDER BY version_no,id",taskId);
        require(versions.size()==6,"six_real_fixed_versions_across_current_and_historical_batches");
        for(var version:versions){
            try(InputStream input=storage.open(string(version.get("object_key")))){
                byte[] bytes=input.readAllBytes();require(java.util.Arrays.equals(sample,bytes)&&number(version.get("size"))==sample.length
                        &&sha256(bytes).equals(version.get("sha256")),"fixed_object_bytes_size_and_sha256_match_original_png");
            }
            require(count("SELECT COUNT(*) FROM chain_submission WHERE version_id=? AND status='PENDING' AND confirmed_tx_id IS NULL",version.get("id"))==1,"fixed_version_anchor_stays_pending_without_fabric");
        }
        report.put("fixedVersionsVerified",versions.size());report.put("sampleSize",sample.length);report.put("sampleSha256",sha256(sample));
    }
    private void verifyAudit() {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id","t016-"+runId+"-%");
        require(!rows.isEmpty(),"formal_http_actions_have_append_only_audit");
        for(var row:rows){String text=String.valueOf(row);require(secrets.stream().noneMatch(text::contains)&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains(storageEndpoint),"audit_does_not_expose_credentials_or_object_keys");}
        require(count("SELECT COUNT(*) FROM audit_log WHERE trace_id LIKE ? AND action='EXECUTION_ADJUSTED'","t016-"+runId+"-%")==1,"one_adjustment_success_audit_after_cas_race");report.put("auditRowsRetained",rows.size());
    }
    private Reply expect(String name,String method,String path,Account actor,Device device,Object body,int status)throws Exception {Reply reply=request(name,method,path,actor,device,body,false);assertStatus(reply,status,name);return reply;}
    private void assertStatus(Reply reply,int status,String name){require(reply.status==status,name+"_http_"+status);String code=switch(status){case 200,202->"OK";case 400->"INVALID_ARGUMENT";case 401->"UNAUTHORIZED";case 403->"FORBIDDEN";case 409->"VERSION_CONFLICT";default->"UNSUPPORTED";};require(code.equals(reply.body.get("code")),name+"_code");}
    private Reply request(String name,String method,String path,Account actor,Device device,Object body,boolean raw)throws Exception {
        String trace="t016-"+runId+"-"+sequence.incrementAndGet();var builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(25)).header("X-Trace-Id",trace).header("Accept","application/json");
        if(actor!=null&&actor.token!=null)builder.header("Authorization","Bearer "+actor.token);if(device!=null){builder.header("X-Device-Id",device.id).header("X-Device-Token",device.token);}
        byte[] bytes=body==null?null:raw?(byte[])body:JSON.writeValueAsBytes(body);if(bytes!=null)builder.header("Content-Type",raw?"application/octet-stream":"application/json");
        builder.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        requests.add(Map.of("case",name,"status",response.statusCode(),"traceId",trace));
        require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),name+"_json");
        String text=new String(response.body(),StandardCharsets.UTF_8);
        // Registration and login are the only intentionally secret-bearing response contracts.
        boolean secretResponse=path.equals(API+"/drones")&&method.equals("POST")||path.equals(API+"/auth/login");
        require((secretResponse||secrets.stream().noneMatch(text::contains))&&!text.contains("objectKey")&&!text.contains("object_key")&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")
                &&!text.contains("transactionBytes")&&!text.contains("commitBytes")&&!text.contains(storageEndpoint),name+"_safe_response");
        Map<String,Object> envelope=JSON.readValue(response.body(),Map.class);require(envelope.keySet().equals(Set.of("code","message","data","traceId")),name+"_uniform_envelope");
        require(trace.equals(envelope.get("traceId"))&&trace.equals(response.headers().firstValue("X-Trace-Id").orElse(null)),name+"_trace_propagated");
        return new Reply(response.statusCode(),envelope);
    }
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
    private void writeReport()throws Exception {
        report.put("caseCount",requests.size());report.put("checkCount",checks.size());byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);String text=new String(bytes,StandardCharsets.UTF_8);
        if(secrets.stream().anyMatch(text::contains)||text.contains("upload-parts/")||text.contains("evidence-files/"))throw new ProbeFailure("unsafe_report");privateWrite(reportPath,bytes);
    }
    private static void privateWrite(Path path,byte[] content)throws Exception {
        Files.createDirectories(path.getParent());Path temporary=Files.createTempFile(path.getParent(),".swarm-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{Files.write(temporary,content);Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
    private void require(boolean value,String name){checks.add(Map.of("check",name,"passed",value));if(!value)throw new ProbeFailure(name);}
    private Account admin(){return accounts.get(1);}private String taskPath(){return API+"/tasks/"+taskId;}private static String executionPath(String subtask){return API+"/subtasks/"+subtask+"/executions";}
    private static String env(String key,String fallback){String value=System.getenv(key);if(value==null||value.isBlank()){if(fallback==null)throw new ProbeFailure("missing_required_environment");return fallback;}return value;}
    private static long version(Map<String,Object> row){return number(row.get("lockVersion"));}
    private static long number(Object value){if(!(value instanceof Number n))throw new ProbeFailure("expected_number");return n.longValue();}
    private static String string(Object value){if(!(value instanceof String text))throw new ProbeFailure("expected_string");return text;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new ProbeFailure("expected_object");return (Map<String,Object>)value;}
    private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new ProbeFailure("expected_array");return list.stream().map(SwarmFlowProbe::object).toList();}
    private static String sha256(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private static final class Account {final String id,username,password;String token;Account(String id,String username,String password){this.id=id;this.username=username;this.password=password;}@Override public String toString(){return "SwarmAccount["+id+"]";}}
    private record Device(String id,String code,String token){@Override public String toString(){return "SwarmDevice["+id+"]";}}
    private record Scope(String requirement,String subtask,String execution){}
    private record Reply(int status,Map<String,Object> body){Map<String,Object> data(){return object(body.get("data"));}}
    private static final class ProbeFailure extends RuntimeException{ProbeFailure(String name){super(name);}}
    @TestConfiguration(proxyBeanMethods=false) @Profile("swarm-probe")
    static class FaultInjection {@Bean @Primary FaultStorage swarmFaultStorage(@Qualifier("objectStorage") ObjectStorage delegate){return new FaultStorage(delegate);}}
    /** Passes through real S3 bytes, pausing one stream after claim commits; never manufactures successful bytes. */
    static final class FaultStorage implements ObjectStorage {
        private final ObjectStorage delegate;private volatile String target;private final AtomicBoolean armed=new AtomicBoolean();
        private final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        FaultStorage(ObjectStorage delegate){this.delegate=delegate;}void arm(String key){target=key;armed.set(true);}
        @Override public void put(String key,Path file,String type){delegate.put(key,file,type);}@Override public void delete(String key){delegate.delete(key);}
        @Override public InputStream open(String key){InputStream real=delegate.open(key);if(!key.equals(target)||!armed.compareAndSet(true,false))return real;
            return new FilterInputStream(real){private boolean waited;private void waitOnce()throws IOException {if(waited)return;waited=true;entered.countDown();try{if(!release.await(90,TimeUnit.SECONDS))throw new IOException("Probe latch timeout");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException("Probe interrupted");}}
                @Override public int read()throws IOException{waitOnce();return in.read();}@Override public int read(byte[] bytes,int offset,int length)throws IOException{waitOnce();return in.read(bytes,offset,length);}};
        }
    }
}
