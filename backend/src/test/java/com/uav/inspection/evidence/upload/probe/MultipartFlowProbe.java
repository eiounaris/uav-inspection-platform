package com.uav.inspection.evidence.upload.probe;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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
import java.util.Arrays;
import java.util.zip.CRC32;
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

/** Two explicit JVM phases prove durable multipart resume against real HTTP/MySQL/Garage/RabbitMQ. */
public final class MultipartFlowProbe {
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
    }
    private final String phase,runId,exchange,finalizeQueue,anchorQueue,deadExchange,deadQueue;
    private final Path directory,reportPath,checkpointPath,credentialsPath;
    private final Map<String,Object> report=new LinkedHashMap<>(),resources=new LinkedHashMap<>();
    private final List<Map<String,Object>> checks=Collections.synchronizedList(new ArrayList<>()),requests=Collections.synchronizedList(new ArrayList<>());
    private final List<Account> accounts=new ArrayList<>();
    private final List<String> secrets=new ArrayList<>();
    private final Set<String> uploadIds=new LinkedHashSet<>();
    private final AtomicInteger sequence=new AtomicInteger();
    private JdbcTemplate jdbc;private DataSource dataSource;private S3Client s3;private ObjectStorage storage;private FaultStorage faults;private RabbitTemplate rabbit;
    private HttpClient client;private String baseUrl,bucket,storageEndpoint,taskId,executionId,subtaskId,droneId;
    private List<String> requirementIds=new ArrayList<>();
    private byte[] baseline;

    private MultipartFlowProbe(String phase,Path directory)throws Exception {
        this.phase=phase;this.directory=directory.toAbsolutePath().normalize();reportPath=this.directory.resolve(phase.equals("prepare")?"prepare.json":"report.json");
        checkpointPath=this.directory.resolve("checkpoint.json");credentialsPath=this.directory.resolve("credentials.json");
        if(phase.equals("resume"))resources.putAll(JSON.readValue(Files.readAllBytes(checkpointPath),new TypeReference<Map<String,Object>>() {}));
        runId=phase.equals("prepare")?UUID.randomUUID().toString():string(resources.get("runId"));
        exchange="inspection.multipart.probe."+runId;finalizeQueue=exchange+".finalize";anchorQueue=exchange+".anchor";deadExchange=exchange+".dead.events";deadQueue=exchange+".dead";
        report.put("formatVersion",1);report.put("phase",phase);report.put("runId",runId);report.put("pid",ProcessHandle.current().pid());
        report.put("startedAt",Instant.now().toString());report.put("passed",false);report.put("checks",checks);report.put("requests",requests);
        for(var entry:System.getenv().entrySet())if(entry.getKey().matches(".*(PASSWORD|SECRET|ACCESS_KEY)$")&&!entry.getValue().isBlank())secrets.add(entry.getValue());
    }
    public static void main(String[] args) {
        if(args.length!=2||!Set.of("prepare","resume").contains(args[0])){System.err.println("Usage: MultipartFlowProbe <prepare|resume> <private-run-directory>");System.exit(2);return;}
        MultipartFlowProbe probe=null;
        try{probe=new MultipartFlowProbe(args[0],Path.of(args[1]));probe.execute();probe.writeReport();System.out.println("[PASS] T017 "+args[0]+" phase");}
        catch(Exception failure){
            if(probe!=null){probe.report.put("passed",false);probe.report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof ProbeFailure)probe.report.put("failedCheck",failure.getMessage());try{probe.writeReport();}catch(Exception ignored){}}
            System.err.println("[FAIL] T017 phase; inspect its private safe report");System.exit(1);
        }
    }
    private void execute()throws Exception {
        report.put("stage","read_only_pending_work_preflight");
        var source=new DriverManagerDataSource();source.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")
                +"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8");
        source.setUsername(env("DB_USERNAME","uav_app"));source.setPassword(env("DB_PASSWORD",null));jdbc=new JdbcTemplate(source);dataSource=source;
        require(count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT'")==0&&count("SELECT COUNT(*) FROM upload_session WHERE status='VERIFYING'")==0
                &&count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'")==0
                &&count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1)")==0,
                "global_no_pending_work_before_isolated_probe");
        if(phase.equals("prepare")){baseline=businessSnapshot();resources.put("baselineSha256",HexFormat.of().formatHex(baseline));resources.put("businessCountsBefore",businessCounts());prepare();}
        else {baseline=HexFormat.of().parseHex(string(resources.get("baselineSha256")));restoreFixtures();resume();}
        report.put("stage","complete");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }
    private org.springframework.context.ConfigurableApplicationContext start(boolean messaging,int partSize,int ttl)throws Exception {
        var builder=new SpringApplicationBuilder(UavInspectionApplication.class,FaultInjection.class).web(WebApplicationType.SERVLET).logStartupInfo(false);
        builder.profiles(messaging?new String[]{"local","database","storage","messaging","multipart-probe"}:new String[]{"local","database","storage","multipart-probe"});
        var context=builder.run("--server.port=0","--inspection.upload.part-size="+partSize,"--inspection.upload.max-file-size=8192",
                "--inspection.upload.allowed-extensions=png,pdf,mp4","--inspection.upload.session-ttl-seconds="+ttl,
                "--inspection.upload.temp-directory="+directory.resolve("temporary-parts"),
                "--inspection.messaging.exchange="+exchange,"--inspection.messaging.finalize-queue="+finalizeQueue,"--inspection.messaging.anchor-queue="+anchorQueue,
                "--inspection.messaging.dead-letter-exchange="+deadExchange,"--inspection.messaging.dead-letter-queue="+deadQueue,
                "--inspection.messaging.recovery-enabled=false","--inspection.ledger.recovery-enabled=false");
        jdbc=context.getBean(JdbcTemplate.class);dataSource=context.getBean(DataSource.class);s3=context.getBean(S3Client.class);storage=context.getBean(ObjectStorage.class);faults=context.getBean(FaultStorage.class);
        bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");storageEndpoint=context.getEnvironment().getRequiredProperty("inspection.storage.endpoint");
        baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();if(messaging)rabbit=context.getBean(RabbitTemplate.class);
        require(!context.getEnvironment().acceptsProfiles(org.springframework.core.env.Profiles.of("ledger")),"no_ledger_profile_or_fabric_writes");return context;
    }
    private void prepare()throws Exception {
        boolean retained=false;
        try(var context=start(false,1024,900);HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            client=actual;
            try {
                report.put("stage","isolated_fixture_setup");createFixtures();login();createTask();
                byte[] png=png(0x145aa1,2500);
                Map<String,Object> body=body(requirementIds.getFirst(),"inspection-old.png","image/png",png.length,UUID.randomUUID().toString());
                String upload=create("create_restart_session",body);Map<String,Object> original=progress(upload);
                require(number(original.get("partSize"))==1024&&number(original.get("partCount"))==3,"prepare_persists_three_part_geometry");
                part("out_of_order_part_three",upload,3,slice(png,3,1024),200,"OK");part("out_of_order_part_one",upload,1,slice(png,1,1024),200,"OK");
                Map<String,Object> partial=progress(upload);assertParts(partial,List.of(1L,3L),1476,Set.of(2));
                part("same_part_same_content",upload,3,slice(png,3,1024),200,"OK");
                byte[] changed=slice(png,3,1024);changed[0]^=1;part("same_part_different_content",upload,3,changed,409,"PART_CONTENT_CONFLICT");
                // This first application intentionally has no messaging endpoint; no finalization can run before restart.
                expectCode("configured_file_size_limit","POST",uploads(),uploader(),body(requirementIds.getFirst(),"oversize.png","image/png",8193,UUID.randomUUID().toString()),413,"FILE_TOO_LARGE");
                expectCode("configured_extension_disabled","POST",uploads(),uploader(),body(requirementIds.getFirst(),"disabled.jpg","image/jpeg",64,UUID.randomUUID().toString()),400,"INVALID_ARGUMENT");
                expectCode("extension_mime_mismatch","POST",uploads(),uploader(),body(requirementIds.getFirst(),"mismatch.png","video/mp4",64,UUID.randomUUID().toString()),400,"INVALID_ARGUMENT");
                expectCode("nonmember_cannot_read_upload","GET",session(upload),accounts.get(3),null,403,"FORBIDDEN");
                require(count("SELECT COUNT(*) FROM upload_part WHERE session_id=?",upload)==2,"only_two_parts_before_process_exit");
                verifyPartBytes(upload,png,1024);
                resources.put("formatVersion",1);resources.put("runId",runId);resources.put("preparePid",ProcessHandle.current().pid());resources.put("taskId",taskId);resources.put("subtaskId",subtaskId);
                resources.put("executionId",executionId);resources.put("droneId",droneId);resources.put("requirementIds",requirementIds);resources.put("uploadId",upload);
                resources.put("clientRequestBody",body);resources.put("expiresAt",original.get("expiresAt"));resources.put("originalSha256",sha256(png));resources.put("originalSize",png.length);
                resources.put("accounts",accounts.stream().map(a->Map.of("id",a.id,"username",a.username)).toList());
                privateWrite(credentialsPath,JSON.writeValueAsBytes(accounts.stream().collect(java.util.stream.Collectors.toMap(a->a.id,a->a.password))));
                safeCheckpoint();report.put("retainedForSecondJvm",true);report.put("uploadId",upload);report.put("receivedPartNumbers",List.of(1,3));report.put("missingPartNumbers",List.of(2));
                report.put("uploadedSize",1476);report.put("originalSha256",sha256(png));retained=true;
            } finally {
                if(!retained){cleanupFixtures();require(MessageDigest.isEqual(baseline,businessSnapshot()),"prepare_failure_restored_original_business_rows");Files.deleteIfExists(credentialsPath);}
            }
        }
    }
    private void restoreFixtures()throws Exception {
        taskId=string(resources.get("taskId"));subtaskId=string(resources.get("subtaskId"));executionId=string(resources.get("executionId"));droneId=string(resources.get("droneId"));
        requirementIds=((List<?>)resources.get("requirementIds")).stream().map(MultipartFlowProbe::string).toList();uploadIds.add(string(resources.get("uploadId")));
        Map<String,Object> passwords=JSON.readValue(Files.readAllBytes(credentialsPath),new TypeReference<Map<String,Object>>() {});
        for(var row:objects(resources.get("accounts"))){String id=string(row.get("id"));String password=string(passwords.get(id));accounts.add(new Account(id,string(row.get("username")),password));secrets.add(password);}
        require(number(resources.get("preparePid"))!=ProcessHandle.current().pid(),"resume_uses_a_different_real_jvm_process");
        report.put("preparePid",resources.get("preparePid"));report.put("businessCountsBefore",resources.get("businessCountsBefore"));
    }
    private void resume()throws Exception {
        // Natural expiry uses a separate short-lived application; immutable old geometry/TTL must survive both restarts.
        try(var context=start(true,512,2);HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            boolean expiryVerified=false;
            try {
            client=actual;login();verifyRestartProgress();
            String expiring=create("create_two_second_ttl",body(requirementIds.getFirst(),"expires.png","image/png",79,UUID.randomUUID().toString()));
            Map<String,Object> before=progress(expiring);require(number(before.get("partSize"))==512,"new_session_uses_new_part_configuration");
            require(count("SELECT TIMESTAMPDIFF(MICROSECOND,created_at,expires_at) FROM upload_session WHERE id=?",expiring)==2_000_000,"configured_ttl_is_persisted_in_mysql");
            Thread.sleep(2200);
            require("EXPIRED".equals(progress(expiring).get("processingStatus")),"natural_expiry_is_visible_in_http_progress");
            part("expired_part_rejected",expiring,1,Files.readAllBytes(Path.of("examples/files/inspection-sample.png")),409,"UPLOAD_SESSION_EXPIRED");
            expectCode("expired_complete_rejected","POST",session(expiring)+"/complete",uploader(),null,409,"UPLOAD_SESSION_EXPIRED");
            expiryVerified=true;
            } finally {
                stopWorkers(context);
                if(!expiryVerified){drainOwnedAnchors();cleanupFixtures();cleanupTopology();Files.deleteIfExists(credentialsPath);
                    require(MessageDigest.isEqual(baseline,businessSnapshot()),"expiry_failure_restored_original_business_rows");}
            }
        }
        try(var context=start(true,512,900);HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            client=actual;login();
            try {
                verifyRestartProgress();verifyMultipart();verifyAudit();await(()->ownOutboxPending()==0,"all_owned_events_published_automatically");
                require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",taskId)==0,"no_fabric_attempts_created");
            } finally {
                faults.release.countDown();stopWorkers(context);report.put("cleanupAttempted",true);
                drainOwnedAnchors();cleanupFixtures();cleanupTopology();Files.deleteIfExists(credentialsPath);
                require(MessageDigest.isEqual(baseline,businessSnapshot()),"original_eighteen_business_tables_and_binary_checkpoints_unchanged");
                report.put("originalBusinessDataUnchanged",true);report.put("businessCountsAfter",businessCounts());
            }
        }
    }
    private void verifyRestartProgress()throws Exception {
        String id=string(resources.get("uploadId"));Map<String,Object> progress=progress(id);
        require(number(progress.get("partSize"))==1024&&number(progress.get("partCount"))==3&&resources.get("expiresAt").equals(progress.get("expiresAt")),"old_geometry_and_expiry_survive_restarted_application_with_new_configuration");
        assertParts(progress,List.of(1L,3L),1476,Set.of(2));
        Map<String,Object> replay=expectCode("same_client_request_after_restart","POST",uploads(),uploader(),resources.get("clientRequestBody"),200,"OK").data();
        require(id.equals(replay.get("uploadId"))&&resources.get("expiresAt").equals(replay.get("expiresAt")),"request_replay_keeps_original_session_and_does_not_extend_ttl");
        verifyPartBytes(id,png(0x145aa1,2500),1024);
    }
    private void verifyMultipart()throws Exception {
        String original=string(resources.get("uploadId"));byte[] oldBytes=png(0x145aa1,2500);
        expectCode("complete_still_missing_part","POST",session(original)+"/complete",uploader(),null,409,"UPLOAD_PARTS_INCOMPLETE");
        part("old_session_rejects_new_config_geometry",original,2,Arrays.copyOf(slice(oldBytes,2,1024),512),400,"INVALID_ARGUMENT");
        part("overlong_old_part",original,2,new byte[1025],413,"FILE_TOO_LARGE");
        part("out_of_range_part",original,4,new byte[1],400,"INVALID_ARGUMENT");
        var partRace=race("same_part_concurrent","PUT",session(original)+"/parts/2",slice(oldBytes,2,1024),true);
        for(Reply reply:partRace)assertCode(reply,200,"OK","concurrent_identical_part");
        require(count("SELECT COUNT(*) FROM upload_part WHERE session_id=?",original)==3&&objectKeys(original).size()==3,"concurrent_retry_keeps_one_part_row_and_object_per_number");
        assertParts(progress(original),List.of(1L,2L,3L),2500,Set.of());verifyPartBytes(original,oldBytes,1024);
        String firstKey=jdbc.queryForObject("SELECT object_key FROM upload_part WHERE session_id=? AND part_no=1",String.class,original);
        faults.arm(firstKey);
        List<Reply> completions=race("concurrent_complete","POST",session(original)+"/complete",null,false);
        for(Reply reply:completions)assertCode(reply,202,"OK","concurrent_complete");
        require(faults.entered.await(20,TimeUnit.SECONDS),"real_storage_read_paused_while_frozen");
        require("VERIFYING".equals(progress(original).get("processingStatus")),"http_progress_reports_frozen_verifying");
        require(count("SELECT COUNT(*) FROM outbox_event WHERE event_type='FILE_FINALIZE_REQUESTED' AND biz_id=?",original)==1,"concurrent_complete_creates_exactly_one_durable_finalize_event");
        part("frozen_session_rejects_even_identical_part",original,1,slice(oldBytes,1,1024),409,"UPLOAD_NOT_WRITABLE");
        expectCode("frozen_complete_idempotent","POST",session(original)+"/complete",uploader(),null,202,"OK");
        faults.release.countDown();await(()->completed(original),"old_file_finalizes_after_read_resumes");
        String oldVersion=verifyVersion(original,oldBytes,1);
        expectCode("completed_complete_idempotent","POST",session(original)+"/complete",uploader(),null,202,"OK");
        part("completed_session_rejects_parts",original,1,slice(oldBytes,1,1024),409,"UPLOAD_NOT_WRITABLE");
        require(count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=?",original)==1&&count("SELECT COUNT(*) FROM chain_submission WHERE version_id=?",oldVersion)==1,"repeated_completion_has_one_fixed_version_and_submission");

        byte[] newBytes=png(0xd34822,3000);
        String newer=uploadUsingRealCli(newBytes);
        await(()->completed(newer),"new_client_resumed_png_finalizes");String newVersion=verifyVersion(newer,newBytes,2);
        require(!oldVersion.equals(newVersion)&&!sha256(oldBytes).equals(sha256(newBytes)),"new_png_has_its_own_fixed_version_and_different_digest");
        verifyVersion(original,oldBytes,1);
        require(count("SELECT COUNT(*) FROM evidence_version WHERE requirement_id=? AND review_status='PENDING'",requirementIds.getFirst())==2,"new_version_does_not_inherit_review_status");

        byte[] document=pdf(2700);String pdf=create("create_multipart_pdf",body(requirementIds.get(1),"inspection.pdf","application/pdf",document.length,UUID.randomUUID().toString()));
        uploadAll(pdf,document,512);complete(pdf);await(()->completed(pdf),"multipart_pdf_really_finalizes");verifyVersion(pdf,document,1);
        byte[] video=mp4();String mp4=create("create_video_as_ordinary_file",body(requirementIds.get(2),"inspection.mp4","video/mp4",video.length,UUID.randomUUID().toString()));
        uploadAll(mp4,video,512);complete(mp4);await(()->completed(mp4),"mp4_really_finalizes_as_ordinary_file");verifyVersion(mp4,video,1);
        require(count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",taskId)==4,"four_positive_fixed_versions_retained_independently");

        String damaged=create("create_corruption_fixture",body(requirementIds.get(1),"damaged.png","image/png",oldBytes.length,UUID.randomUUID().toString()));
        uploadAll(damaged,oldBytes,512);
        String corruptKey=jdbc.queryForObject("SELECT object_key FROM upload_part WHERE session_id=? AND part_no=2",String.class,damaged);
        byte[] corrupt=slice(oldBytes,2,512);corrupt[0]^=1;Path temporary=directory.resolve("controlled-corrupt-part.bin");privateWrite(temporary,corrupt);
        try{storage.put(corruptKey,temporary,"image/png");}finally{Files.deleteIfExists(temporary);}
        complete(damaged);await(()->"FAILED".equals(status(damaged)),"corrupt_stored_part_fails_real_finalization");
        require("PART_HASH_MISMATCH".equals(jdbc.queryForObject("SELECT last_error FROM upload_session WHERE id=?",String.class,damaged))
                &&count("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=?",damaged)==0,"corrupt_part_has_no_fixed_version_or_false_positive");
        require(count("SELECT COUNT(*) FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?) AND status='PENDING' AND confirmed_tx_id IS NULL",taskId)==4,"all_positive_versions_remain_pending_without_ledger");
        report.put("fixedVersionsVerified",4);report.put("positiveFormats",List.of("PNG","PDF","MP4"));report.put("originalVersionId",oldVersion);report.put("newVersionId",newVersion);
        report.put("originalSha256",sha256(oldBytes));report.put("newSha256",sha256(newBytes));report.put("pdfSha256",sha256(document));report.put("mp4Sha256",sha256(video));
        report.put("corruptionRejected",true);
    }
    private String uploadUsingRealCli(byte[] bytes)throws Exception {
        Path file=directory.resolve("inspection-new.png"),config=directory.resolve("uploader-config.json"),state=directory.resolve("uploader-state.json");privateWrite(file,bytes);
        Map<String,Object> options=Map.of("formatVersion",1,"baseUrl",baseUrl,"filePath",file.toString(),"requirementId",requirementIds.getFirst(),"executionId",executionId,
                "stateFile",state.toString(),"auth",Map.of("type","USER","accessToken",uploader().token));
        privateWrite(config,JSON.writeValueAsBytes(options));
        try {
            Map<String,Object> first=runCli(config,"cli-first",true);String id=string(first.get("uploadId"));uploadIds.add(id);
            require(number(first.get("partsUploadedThisRun"))==1&&count("SELECT COUNT(*) FROM upload_part WHERE session_id=?",id)==1,"first_client_process_stops_after_one_real_part");
            Map<String,Object> pending=progress(id);require(number(pending.get("partSize"))==512&&number(pending.get("partCount"))==6&&number(pending.get("uploadedSize"))==512,"new_client_session_uses_server_geometry_and_progress");
            Map<String,Object> second=runCli(config,"cli-resume",false);
            require(id.equals(second.get("uploadId"))&&number(second.get("partsUploadedThisRun"))==5,"second_client_process_only_sends_missing_five_parts");
            report.put("cliRuns",List.of(first,second));return id;
        } finally {Files.deleteIfExists(config);}
    }
    private Map<String,Object> runCli(Path config,String name,boolean interrupt)throws Exception {
        var arguments=new ArrayList<>(List.of("python3","simulator/upload.py","--config",config.toString()));if(interrupt)arguments.addAll(List.of("--max-parts","1"));
        Path log=directory.resolve(name+".log");Files.createFile(log,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Process process=new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();throw new ProbeFailure("upload_cli_timeout");}
        require(process.exitValue()==0,"real_upload_client_process_exits_successfully");
        String output=Files.readString(log);require(secrets.stream().noneMatch(output::contains),"upload_client_log_contains_no_credentials");
        return JSON.readValue(output,new TypeReference<Map<String,Object>>() {});
    }
    private void assertParts(Map<String,Object> progress,List<Long> received,long uploaded,Set<Integer> missing) {
        List<Map<String,Object>> parts=objects(progress.get("uploadedParts"));List<Long> actual=parts.stream().map(p->number(p.get("partNo"))).toList();
        Set<Integer> derived=new LinkedHashSet<>();for(int i=1;i<=number(progress.get("partCount"));i++)if(!actual.contains((long)i))derived.add(i);
        require(actual.equals(received)&&number(progress.get("uploadedSize"))==uploaded&&derived.equals(missing),"get_progress_has_sorted_received_parts_correct_bytes_and_derived_missing_set");
        for(var part:parts)require(string(part.get("sha256")).matches("[0-9a-f]{64}")&&number(part.get("size"))>0,"each_http_part_contains_safe_size_and_sha256");
    }
    private void verifyPartBytes(String upload,byte[] bytes,int partSize)throws Exception {
        for(var row:jdbc.queryForList("SELECT part_no,size,sha256,object_key FROM upload_part WHERE session_id=? ORDER BY part_no",upload)) {
            byte[] expected=slice(bytes,(int)number(row.get("part_no")),partSize);
            try(InputStream input=storage.open(string(row.get("object_key")))){byte[] actual=input.readAllBytes();require(Arrays.equals(expected,actual)&&actual.length==number(row.get("size"))&&sha256(actual).equals(row.get("sha256")),"real_stored_part_bytes_length_and_digest_match_after_restart");}
        }
    }
    private String verifyVersion(String upload,byte[] expected,int version)throws Exception {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,object_key,size,sha256,version_no,review_status FROM evidence_version WHERE upload_session_id=?",upload);
        require(rows.size()==1,"one_fixed_version_per_session");Map<String,Object> row=rows.getFirst();
        try(InputStream input=storage.open(string(row.get("object_key")))){byte[] actual=input.readAllBytes();require(Arrays.equals(expected,actual)&&number(row.get("size"))==expected.length&&sha256(actual).equals(row.get("sha256")),"full_final_object_bytes_size_and_sha256_match_source");}
        require(number(row.get("version_no"))==version&&"PENDING".equals(row.get("review_status")),"fixed_version_number_and_fresh_pending_review");
        return string(row.get("id"));
    }
    private void uploadAll(String id,byte[] bytes,int geometry)throws Exception {int count=1+(bytes.length-1)/geometry;for(int part=count;part>=1;part--)part("reverse_order_part",id,part,slice(bytes,part,geometry),200,"OK");assertParts(progress(id),java.util.stream.LongStream.rangeClosed(1,count).boxed().toList(),bytes.length,Set.of());}
    private void complete(String id)throws Exception {expectCode("request_automatic_finalization","POST",session(id)+"/complete",uploader(),null,202,"OK");}
    private void part(String name,String id,int number,byte[] bytes,int status,String code)throws Exception {assertCode(request(name,"PUT",session(id)+"/parts/"+number,uploader(),bytes,true),status,code,name);}
    private String create(String name,Map<String,Object> body)throws Exception {String id=string(expectCode(name,"POST",uploads(),uploader(),body,200,"OK").data().get("uploadId"));uploadIds.add(id);return id;}
    private Map<String,Object> body(String requirement,String name,String type,long size,String request){return Map.of("clientRequestId",request,"requirementId",requirement,"executionId",executionId,"filename",name,"contentType",type,"size",size);}
    private Map<String,Object> progress(String id)throws Exception{return expectCode("read_durable_upload_progress","GET",session(id),uploader(),null,200,"OK").data();}
    private String status(String id){return jdbc.queryForObject("SELECT status FROM upload_session WHERE id=?",String.class,id);}private boolean completed(String id){return "COMPLETED".equals(status(id));}
    private List<Reply> race(String name,String method,String path,Object body,boolean raw)throws Exception {
        CountDownLatch start=new CountDownLatch(1),ready=new CountDownLatch(2);List<CompletableFuture<Reply>> futures=new ArrayList<>();
        for(int i=0;i<2;i++){final int index=i;futures.add(CompletableFuture.supplyAsync(()->{try{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new ProbeFailure("race_start_timeout");return request(name+"_"+index,method,path,uploader(),body,raw);}catch(Exception failure){throw new ProbeFailure("parallel_http_request_failed");}}));}
        require(ready.await(5,TimeUnit.SECONDS),"both_concurrent_requests_ready");start.countDown();return List.of(futures.get(0).get(30,TimeUnit.SECONDS),futures.get(1).get(30,TimeUnit.SECONDS));
    }
    private void createFixtures()throws Exception {
        account(OWNER,"creator",List.of("OWNER_TASK_ADMIN"));account(OPERATOR,"admin",List.of("OPERATOR_TASK_ADMIN"));
        account(OPERATOR,"uploader",List.of("OPERATOR_USER"));account(OPERATOR,"outsider",List.of("OPERATOR_USER"));
    }
    private void account(String org,String suffix,List<String> roles) {
        byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);String password=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);secrets.add(password);
        Account a=new Account(UUID.randomUUID().toString(),"t017-"+runId.substring(0,12)+"-"+suffix,password);accounts.add(a);
        require(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",a.id,org,a.username,new BCryptPasswordEncoder(12).encode(password),JSON.writeValueAsString(roles))==1,"isolated_account_inserted");
    }
    private void login()throws Exception {for(Account a:accounts){a.token=string(expectCode("fixture_login","POST",API+"/auth/login",null,Map.of("username",a.username,"password",a.password),200,"OK").data().get("accessToken"));secrets.add(a.token);}}
    private void createTask()throws Exception {
        Map<String,Object> registration=expectCode("register_fixture_device","POST",API+"/drones",admin(),Map.of("code","T017-"+runId.substring(0,12)),200,"OK").data();
        droneId=string(object(registration.get("device")).get("id"));secrets.add(string(registration.get("deviceToken")));
        Map<String,Object> task=expectCode("create_task","POST",API+"/tasks",accounts.getFirst(),Map.of("name","T017-"+runId,"scope","isolated durable multipart acceptance",
                "requirements",List.of(Map.of("name","photos","required",true),Map.of("name","pdf-report","required",true),Map.of("name","video","required",true))),200,"OK").data();
        taskId=string(task.get("id"));requirementIds=objects(task.get("requirements")).stream().map(r->string(r.get("id"))).toList();
        task=expectCode("assign_task","POST",taskPath()+"/assign",accounts.getFirst(),Map.of("operatorOrgId",OPERATOR,"operatorAdminId",admin().id,"expectedLockVersion",version(task)),200,"OK").data();
        task=expectCode("set_members","PUT",taskPath()+"/members",admin(),Map.of("userIds",List.of(admin().id,uploader().id),"expectedLockVersion",version(task)),200,"OK").data();
        task=expectCode("create_subtask","POST",taskPath()+"/subtasks",admin(),Map.of("name","multipart-flight","requirementIds",requirementIds,"expectedLockVersion",version(task)),200,"OK").data();
        Map<String,Object> sub=objects(task.get("subtasks")).getFirst();subtaskId=string(sub.get("id"));
        executionId=string(expectCode("create_execution","POST",API+"/subtasks/"+subtaskId+"/executions",admin(),Map.of("droneId",droneId,"expectedLockVersion",version(sub)),200,"OK").data().get("id"));
        task=expectCode("refresh_task","GET",taskPath(),admin(),null,200,"OK").data();
        expectCode("start_task","POST",taskPath()+"/start",admin(),Map.of("expectedLockVersion",version(task)),200,"OK");
    }
    private void stopWorkers(org.springframework.context.ConfigurableApplicationContext context) {
        context.getBean(ThreadPoolTaskScheduler.class).shutdown();context.getBean("finalizationContainer",SimpleMessageListenerContainer.class).stop();
    }
    private long ownOutboxPending(){return count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT' AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",taskId,taskId);}
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
    private void cleanupTopology() {
        rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue)){
            var state=channel.queueDeclarePassive(queue);require(state.getMessageCount()==0&&state.getConsumerCount()==0,"only_empty_unused_owned_queue_deleted");channel.queueDelete(queue,true,true);
        }channel.exchangeDelete(exchange,true);channel.exchangeDelete(deadExchange,true);return null;});report.put("ownedRabbitTopologyDeleted",true);
    }
    private void cleanupFixtures() {
        Set<String> tasks=new LinkedHashSet<>();for(Account a:accounts)tasks.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,a.id));
        for(String task:tasks)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));
        Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        for(String task:tasks){
            jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",task);
            jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            List<String> events=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,task,task);
            for(String event:events){jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}
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
        int drones=jdbc.update("DELETE FROM drone WHERE operator_org_id=? AND code=?",OPERATOR,"T017-"+runId.substring(0,12));
        for(Account a:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",a.id,a.username);
        for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"only_own_upload_parts_and_final_objects_cleaned");
        report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",drones);
        report.put("fixtureUploadSessionsDeleted",uploadIds.size());report.put("fixturePrivateObjectsDeleted",keys.size());
    }
    private void verifyAudit() {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id","t017-"+runId+"-%");
        require(!rows.isEmpty(),"formal_http_actions_have_append_only_audit");
        for(var row:rows){String text=String.valueOf(row);require(secrets.stream().noneMatch(text::contains)&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains(storageEndpoint),"audit_excludes_credentials_and_storage_locations");}
        report.put("auditRowsRetained",rows.size());
    }
    private Reply expectCode(String name,String method,String path,Account actor,Object body,int status,String code)throws Exception {Reply reply=request(name,method,path,actor,body,false);assertCode(reply,status,code,name);return reply;}
    private void assertCode(Reply reply,int status,String code,String name){require(reply.status==status,name+"_http_"+status);require(code.equals(reply.body.get("code")),name+"_code");}
    private Reply request(String name,String method,String path,Account actor,Object body,boolean raw)throws Exception {
        String trace="t017-"+runId+"-"+phase+"-"+sequence.incrementAndGet();var builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(25)).header("X-Trace-Id",trace).header("Accept","application/json");
        if(actor!=null&&actor.token!=null)builder.header("Authorization","Bearer "+actor.token);
        byte[] bytes=body==null?null:raw?(byte[])body:JSON.writeValueAsBytes(body);if(bytes!=null)builder.header("Content-Type",raw?"application/octet-stream":"application/json");
        builder.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        requests.add(Map.of("case",name,"status",response.statusCode(),"traceId",trace));require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),name+"_json");
        String text=new String(response.body(),StandardCharsets.UTF_8);boolean secretResponse=path.equals(API+"/drones")&&method.equals("POST")||path.equals(API+"/auth/login");
        require((secretResponse||secrets.stream().noneMatch(text::contains))&&!text.contains("objectKey")&&!text.contains("object_key")&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")
                &&!text.contains("transactionBytes")&&!text.contains("commitBytes")&&!text.contains(storageEndpoint),name+"_safe_response");
        Map<String,Object> envelope=JSON.readValue(response.body(),new TypeReference<Map<String,Object>>() {});require(envelope.keySet().equals(Set.of("code","message","data","traceId")),name+"_uniform_envelope");
        require(trace.equals(envelope.get("traceId"))&&trace.equals(response.headers().firstValue("X-Trace-Id").orElse(null)),name+"_trace_propagated");return new Reply(response.statusCode(),envelope);
    }
    private void require(boolean value,String name){checks.add(Map.of("check",name,"passed",value));if(!value)throw new ProbeFailure(name);}
    private void safeCheckpoint()throws Exception {byte[] content=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(resources);safeBytes(content);privateWrite(checkpointPath,content);}
    private void safeBytes(byte[] content){String text=new String(content,StandardCharsets.UTF_8);if(secrets.stream().anyMatch(text::contains)||text.contains("upload-parts/")||text.contains("evidence-files/")||text.contains("object_key")||text.contains("objectKey"))throw new ProbeFailure("unsafe_public_metadata");}
    private void writeReport()throws Exception {report.put("caseCount",requests.size());report.put("checkCount",checks.size());byte[] content=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);safeBytes(content);privateWrite(reportPath,content);}
    private static void privateWrite(Path path,byte[] content)throws Exception {Files.createDirectories(path.getParent());Path temporary=Files.createTempFile(path.getParent(),".multipart-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.write(temporary,content);Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    private static byte[] png(int color,int length)throws Exception {
        var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);for(int x=0;x<8;x++)for(int y=0;y<8;y++)image.setRGB(x,y,color);
        var encoded=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",encoded))throw new ProbeFailure("png_encoder_unavailable");byte[] png=encoded.toByteArray();
        byte[] text=new byte[length-png.length-12];Arrays.fill(text,(byte)'p');System.arraycopy("probe\0".getBytes(StandardCharsets.ISO_8859_1),0,text,0,6);byte[] type="tEXt".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc=new CRC32();crc.update(type);crc.update(text);var result=new ByteArrayOutputStream();try(var output=new DataOutputStream(result)){output.write(png,0,png.length-12);output.writeInt(text.length);output.write(type);output.write(text);output.writeInt((int)crc.getValue());output.write(png,png.length-12,12);}return result.toByteArray();
    }
    private static byte[] pdf(int target)throws Exception {
        int padding=target-500;for(int attempt=0;attempt<8;attempt++){byte[] bytes=pdfPadding(padding);if(bytes.length==target)return bytes;padding+=target-bytes.length;}throw new ProbeFailure("pdf_fixture_length_failed");
    }
    private static byte[] pdfPadding(int padding) {
        String content="BT /F1 12 Tf 20 40 Td (Inspection evidence) Tj ET\n";
        String[] objects={"<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                "<< /Length "+content.length()+" >>\nstream\n"+content+"endstream","<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"};
        var text=new StringBuilder("%PDF-1.7\n%"+"p".repeat(Math.max(0,padding))+"\n");List<Integer> offsets=new ArrayList<>();
        for(int i=0;i<objects.length;i++){offsets.add(text.length());text.append(i+1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");}
        int xref=text.length();text.append("xref\n0 6\n0000000000 65535 f \n");for(int offset:offsets)text.append(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offset));
        text.append("trailer\n<< /Root 1 0 R /Size 6 >>\nstartxref\n").append(xref).append("\n%%EOF\n");return text.toString().getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] mp4()throws Exception {byte[] movie=concat(box("mvhd",new byte[100]),box("trak",box("tkhd",new byte[84])));byte[] media=new byte[1536];for(int i=0;i<media.length;i++)media[i]=(byte)i;return concat(box("ftyp","isom0000isommp42".getBytes(StandardCharsets.US_ASCII)),box("moov",movie),box("mdat",media));}
    private static byte[] box(String type,byte[] body)throws Exception {var bytes=new ByteArrayOutputStream();try(var output=new DataOutputStream(bytes)){output.writeInt(body.length+8);output.writeBytes(type);output.write(body);}return bytes.toByteArray();}
    private static byte[] concat(byte[]...parts)throws Exception {var bytes=new ByteArrayOutputStream();for(byte[] part:parts)bytes.write(part);return bytes.toByteArray();}
    private static byte[] slice(byte[] bytes,int part,int size){return Arrays.copyOfRange(bytes,(part-1)*size,Math.min(bytes.length,part*size));}
    private Account admin(){return accounts.get(1);}private Account uploader(){return accounts.get(2);}private String taskPath(){return API+"/tasks/"+taskId;}
    private static String uploads(){return API+"/upload-sessions";}private static String session(String id){return uploads()+"/"+id;}
    private static String env(String key,String fallback){String value=System.getenv(key);if(value==null||value.isBlank()){if(fallback==null)throw new ProbeFailure("missing_required_environment");return fallback;}return value;}
    private static long version(Map<String,Object> row){return number(row.get("lockVersion"));}private static long number(Object value){if(!(value instanceof Number n))throw new ProbeFailure("expected_number");return n.longValue();}
    private static String string(Object value){if(!(value instanceof String text))throw new ProbeFailure("expected_string");return text;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new ProbeFailure("expected_object");return (Map<String,Object>)value;}
    private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new ProbeFailure("expected_array");return list.stream().map(MultipartFlowProbe::object).toList();}
    private static String sha256(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private static final class Account {final String id,username,password;String token;Account(String id,String username,String password){this.id=id;this.username=username;this.password=password;}@Override public String toString(){return "MultipartAccount["+id+"]";}}
    private record Reply(int status,Map<String,Object> body){Map<String,Object> data(){return object(body.get("data"));}}
    private static final class ProbeFailure extends RuntimeException {ProbeFailure(String name){super(name);}}
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
    @TestConfiguration(proxyBeanMethods=false) @Profile("multipart-probe")
    static class FaultInjection {@Bean @Primary FaultStorage multipartFaultStorage(@Qualifier("objectStorage") ObjectStorage delegate){return new FaultStorage(delegate);}}
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
