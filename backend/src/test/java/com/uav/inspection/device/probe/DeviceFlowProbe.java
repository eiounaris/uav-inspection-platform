package com.uav.inspection.device.probe;

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

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.Socket;
import java.util.function.BooleanSupplier;
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
import java.util.stream.Collectors;

/** Explicit real HTTP/MySQL/Redis/Garage probe. Never runs as an ordinary unit test. */
public final class DeviceFlowProbe {
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
        TABLE_ORDER.put("verification_record", "id");
        TABLE_ORDER.put("device_event", "id");
    }
    private final Path reportPath;
    private final String runId = UUID.randomUUID().toString();
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
    private final List<Device> devices=new ArrayList<>();
    private final String redisPrefix="inspection:probe:"+runId+":";
    private Redis redis;
    private String otherTaskId;
    private String otherExecutionId;
    private S3Client s3;
    private HttpClient client;
    private String baseUrl;
    private String storageEndpoint;
    private String bucket;
    private String taskId;
    private Scope firstScope;
    private Scope secondScope;

    private DeviceFlowProbe(Path reportPath) {
        this.reportPath = reportPath.toAbsolutePath().normalize();
        report.put("formatVersion",1);
        report.put("runId",runId);
        report.put("startedAt",Instant.now().toString());
        report.put("passed",false);
        report.put("checks",checks);
        report.put("requests",requests);
    }
    public static void main(String[] args) {
        if(args.length!=1) { System.err.println("Usage: DeviceFlowProbe <reportPath>"); System.exit(2); return; }
        var probe=new DeviceFlowProbe(Path.of(args[0]));
        try {
            probe.execute(); probe.writeReport();
            System.out.println("[PASS] T015 real HTTP/MySQL/Redis/Garage pipeline probe: "+probe.reportPath);
        } catch(Exception failure) {
            probe.report.put("passed",false);
            probe.report.put("failureType",failure.getClass().getSimpleName());
            if(failure instanceof ProbeFailure) probe.report.put("failedCheck",failure.getMessage());
            try { probe.writeReport(); } catch(Exception ignored) { System.err.println("Could not write safe pipeline report"); }
            System.err.println("[FAIL] T015 pipeline probe ("+failure.getClass().getSimpleName()+"); inspect safe report metadata.");
            System.exit(1);
        }
    }
    private void execute() throws Exception {
        report.put("stage","application_startup");
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class)
                .web(WebApplicationType.SERVLET).profiles("local","database","storage").logStartupInfo(false)
                .run("--server.port=0","--logging.level.com.uav.inspection=INFO",
                        "--inspection.device.online-threshold-seconds=2",
                        "--inspection.device.presence-cache-ttl-seconds=10",
                        "--inspection.device.presence-key-prefix="+redisPrefix)) {
            jdbc=context.getBean(JdbcTemplate.class); dataSource=context.getBean(DataSource.class);
            storage=context.getBean(ObjectStorage.class);s3=context.getBean(S3Client.class);
            redis=new Redis(context.getEnvironment().getProperty("spring.data.redis.host","127.0.0.1"),
                    context.getEnvironment().getProperty("spring.data.redis.port",Integer.class,6379),
                    context.getEnvironment().getRequiredProperty("spring.data.redis.password"));
            storageEndpoint=context.getEnvironment().getRequiredProperty("inspection.storage.endpoint");
            bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");
            baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            try(Connection connection=dataSource.getConnection()) {
                require("MySQL".equals(connection.getMetaData().getDatabaseProductName()),"real_mysql_required");
            }
            byte[] baseline=businessSnapshot(); report.put("businessCountsBefore",businessCounts());
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                client=actual;
                try {
                    report.put("stage","isolated_fixture_setup"); createFixtures(); createRunningTask();
                    report.put("stage","real_pipeline_checks"); verifyDevices(); verifyAuditSafety();
                } finally {
                    report.put("cleanupAttempted",true); cleanupFixtures();
                    require(MessageDigest.isEqual(baseline,businessSnapshot()),"original_seventeen_business_tables_unchanged");
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
        account(OPERATOR, "admin-only", List.of("ORG_ADMIN"));
        for (Account account : accounts) {
            account.token = string(expect("fixture_login", "POST", API + "/auth/login", null,
                    Map.of("username", account.username, "password", account.password), 200).data().get("accessToken"));
            secrets.add(account.token);
        }
        devices.add(createDevice("first"));devices.add(createDevice("second"));
        report.put("fixtureAccountIds",accounts.stream().map(account->account.id).toList());
        report.put("fixtureDeviceIds",devices.stream().map(device->device.id).toList());
    }

    private void account(String org, String suffix, List<String> roles) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        secrets.add(password);
        var account = new Account(UUID.randomUUID().toString(),
                "t015-" + runId.substring(0, 12) + "-" + suffix, password);
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
                Map.of("name", "T015-" + runId, "scope", "isolated real upload verification", "requirements", List.of(
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
                    admin.token, Map.of("droneId", devices.get(index).id, "expectedLockVersion", version(subtask)), 200).data();
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
        createOtherRunningTask();
    }

    private Device createDevice(String suffix)throws Exception {
        String code="T015-"+runId.substring(0,12)+"-"+suffix;
        Reply reply=expect("register_device_"+suffix,"POST",API+"/drones",accounts.get(1).token,Map.of("code",code),200);
        require(reply.data().keySet().equals(Set.of("device","deviceToken")),"registration_returns_one_time_device_token_and_safe_device_view");
        String token=string(reply.data().get("deviceToken"));secrets.add(token);secrets.add(sha256(token.getBytes(StandardCharsets.UTF_8)));
        Map<String,Object> view=object(reply.data().get("device"));String id=string(view.get("id"));
        require(token.matches("[0-9a-f]{64}")&&view.keySet().equals(deviceFields())&&OPERATOR.equals(view.get("operatorOrgId")),
                "registered_device_has_random_token_and_fixed_operator_scope");
        require(sha256(token.getBytes(StandardCharsets.UTF_8)).equals(jdbc.queryForObject("SELECT token_hash FROM drone WHERE id=?",String.class,id)),
                "mysql_persists_only_sha256_of_new_device_token");
        return new Device(id,code,token);
    }
    private void createOtherRunningTask()throws Exception {
        Account creator=accounts.get(0),admin=accounts.get(1);
        var task=expect("create_other_task","POST",API+"/tasks",creator.token,Map.of("name","T015-other-"+runId,"scope","isolated forbidden execution fixture",
                "requirements",List.of(Map.of("name","other-photo","required",true))),200).data();
        otherTaskId=string(task.get("id"));String path=API+"/tasks/"+otherTaskId;
        String requirement=string(objects(task.get("requirements")).getFirst().get("id"));
        task=expect("assign_other_task","POST",path+"/assign",creator.token,Map.of("operatorOrgId",OPERATOR,"operatorAdminId",admin.id,"expectedLockVersion",version(task)),200).data();
        task=expect("other_task_subtask","POST",path+"/subtasks",admin.token,Map.of("name","other-flight","requirementIds",List.of(requirement),"expectedLockVersion",version(task)),200).data();
        var subtask=objects(task.get("subtasks")).getFirst();
        otherExecutionId=string(expect("other_task_execution","POST",API+"/subtasks/"+subtask.get("id")+"/executions",admin.token,
                Map.of("droneId",devices.get(1).id,"expectedLockVersion",version(subtask)),200).data().get("id"));
        task=expect("refresh_other_task","GET",path,admin.token,null,200).data();
        expect("start_other_task","POST",path+"/start",admin.token,Map.of("expectedLockVersion",version(task)),200);
    }
    private void verifyDevices()throws Exception {
        Device first=devices.get(0),second=devices.get(1);Account admin=accounts.get(1);
        expect("anonymous_device_registration","POST",API+"/drones",null,Map.of("code","forbidden"),401);
        expect("owner_cannot_register_operator_device","POST",API+"/drones",accounts.get(0).token,Map.of("code","forbidden"),403);
        expect("operator_user_cannot_register_device","POST",API+"/drones",accounts.get(2).token,Map.of("code","forbidden"),403);
        expect("org_admin_only_cannot_register_device","POST",API+"/drones",accounts.get(5).token,Map.of("code","forbidden"),403);
        expect("invalid_device_code","POST",API+"/drones",admin.token,Map.of("code","../bad"),400);
        Reply duplicateCode=request("same_device_code_conflict","POST",API+"/drones",admin.token,Map.of("code",first.code),false);
        require(duplicateCode.status()==409,"device_code_is_unique_under_concurrent_safe_management");
        var page=expect("list_operator_devices","GET",API+"/drones?page=1&pageSize=100",admin.token,null,200).data();
        require(number(page.get("total"))>=devices.size()&&objects(page.get("items")).stream().allMatch(row->row.keySet().equals(deviceFields())),
                "device_list_exposes_only_safe_operator_metadata");
        Map<String,Object> initial=deviceView(first);require(initial.get("lastSeenAt")==null&&Boolean.FALSE.equals(initial.get("online")),"new_device_is_offline_until_first_authenticated_report");
        String boot=UUID.randomUUID().toString();Map<String,Object> heartbeat=event(boot,0,UUID.randomUUID().toString(),firstScope.executionId(),"HEARTBEAT",null);
        deviceExpect("unknown_device_identity",new Device(UUID.randomUUID().toString(),"unknown",first.token),"POST",API+"/device/reports",heartbeat,null,401,"UNAUTHORIZED");
        deviceExpect("wrong_device_token",new Device(first.id,first.code,"0".repeat(64)),"POST",API+"/device/reports",heartbeat,null,401,"UNAUTHORIZED");
        expect("user_jwt_cannot_report_as_device","POST",API+"/device/reports",accounts.get(2).token,heartbeat,401);
        deviceExpect("device_token_is_not_user_jwt",first,"GET",API+"/users/me",null,null,401,"UNAUTHORIZED");
        deviceExpect("device_token_cannot_access_review",first,"POST",API+"/evidence-versions/"+UUID.randomUUID()+"/review",Map.of("decision","APPROVED"),null,401,"UNAUTHORIZED");
        deviceExpect("device_token_cannot_access_sharing",first,"POST",API+"/access-grants",Map.of("versionId",UUID.randomUUID().toString()),null,401,"UNAUTHORIZED");
        deviceExpect("other_device_execution_forbidden",second,"POST",API+"/device/reports",heartbeat,null,403,"FORBIDDEN");
        deviceExpect("other_subtask_execution_forbidden",first,"POST",API+"/device/reports",event(boot,1,UUID.randomUUID().toString(),secondScope.executionId(),"HEARTBEAT",null),null,403,"FORBIDDEN");
        deviceExpect("other_task_execution_forbidden",first,"POST",API+"/device/reports",event(boot,1,UUID.randomUUID().toString(),otherExecutionId,"HEARTBEAT",null),null,403,"FORBIDDEN");
        deviceExpect("progress_is_required",first,"POST",API+"/device/reports",event(boot,1,UUID.randomUUID().toString(),firstScope.executionId(),"PROGRESS",null),null,400,"INVALID_ARGUMENT");
        deviceExpect("heartbeat_cannot_set_progress",first,"POST",API+"/device/reports",event(boot,1,UUID.randomUUID().toString(),firstScope.executionId(),"HEARTBEAT",4),null,400,"INVALID_ARGUMENT");
        deviceExpect("progress_is_bounded",first,"POST",API+"/device/reports",event(boot,1,UUID.randomUUID().toString(),firstScope.executionId(),"PROGRESS",101),null,400,"INVALID_ARGUMENT");
        deviceExpect("negative_sequence_rejected",first,"POST",API+"/device/reports",event(boot,-1,UUID.randomUUID().toString(),firstScope.executionId(),"HEARTBEAT",null),null,400,"INVALID_ARGUMENT");
        deviceExpect("unsafe_json_sequence_rejected",first,"POST",API+"/device/reports",event(boot,9007199254740992L,UUID.randomUUID().toString(),firstScope.executionId(),"HEARTBEAT",null),null,400,"INVALID_ARGUMENT");
        Map<String,Object> fractionalSequence=new LinkedHashMap<>(event(boot,1,UUID.randomUUID().toString(),firstScope.executionId(),"HEARTBEAT",null));fractionalSequence.put("seq",1.5);
        deviceExpect("fractional_sequence_rejected",first,"POST",API+"/device/reports",fractionalSequence,null,400,"INVALID_ARGUMENT");
        Map<String,Object> stringSequence=new LinkedHashMap<>(event(boot,1,UUID.randomUUID().toString(),firstScope.executionId(),"HEARTBEAT",null));stringSequence.put("seq","1");
        deviceExpect("string_sequence_rejected",first,"POST",API+"/device/reports",stringSequence,null,400,"INVALID_ARGUMENT");
        Map<String,Object> fractionalProgress=new LinkedHashMap<>(event(boot,1,UUID.randomUUID().toString(),firstScope.executionId(),"PROGRESS",1));fractionalProgress.put("progress",1.5);
        deviceExpect("fractional_progress_rejected",first,"POST",API+"/device/reports",fractionalProgress,null,400,"INVALID_ARGUMENT");
        require(eventCount(first)==0&&count("SELECT COUNT(*) FROM drone WHERE id=? AND last_seen_at IS NULL",first.id)==1,
                "rejected_reports_do_not_update_device_presence_or_progress");

        Instant before=Instant.now();Map<String,Object> firstReport=deviceExpect("first_authenticated_heartbeat",first,"POST",API+"/device/reports",heartbeat,null,200,"OK").data();
        Instant received=Instant.parse(string(firstReport.get("receivedAt")));
        require(!received.isBefore(before.minusSeconds(1))&&!received.isAfter(Instant.now().plusSeconds(1)),"presence_timestamp_is_server_utc_reception_time");
        require(Boolean.FALSE.equals(firstReport.get("duplicate"))&&number(firstReport.get("executionProgress"))==0,"first_heartbeat_is_applied_once");
        Map<String,Object> visible=deviceView(first);require(Boolean.TRUE.equals(visible.get("online"))&&string(visible.get("lastSeenAt")).equals(firstReport.get("receivedAt")),"authenticated_report_marks_real_device_online");
        String key=redisPrefix+first.id;await(()->{try{return redis.get(key)!=null;}catch(Exception failure){return false;}},"real_redis_contains_committed_presence_epoch");
        String cached=redis.get(key);require(Long.parseLong(cached)==received.toEpochMilli()&&redis.pttl(key)>0&&redis.pttl(key)<=10000,"real_redis_presence_value_and_ttl_match_mysql_fact");
        long ttlBefore=redis.pttl(key);Thread.sleep(60);
        Map<String,Object> duplicate=deviceExpect("heartbeat_replay",first,"POST",API+"/device/reports",heartbeat,null,200,"OK").data();
        require(Boolean.TRUE.equals(duplicate.get("duplicate"))&&sameResult(firstReport,duplicate)&&eventCount(first)==1
                &&seenMillis(first)==received.toEpochMilli()&&redis.get(key).equals(cached)&&redis.pttl(key)<=ttlBefore,
                "duplicate_returns_original_snapshot_without_refreshing_db_or_redis_ttl");
        Map<String,Object> forty=event(boot,1,UUID.randomUUID().toString(),firstScope.executionId(),"PROGRESS",40);
        Map<String,Object> applied=deviceExpect("progress_forty",first,"POST",API+"/device/reports",forty,null,200,"OK").data();
        require(number(applied.get("executionProgress"))==40,"authenticated_progress_updates_assigned_execution");
        Map<String,Object> lower=deviceExpect("lower_progress_is_monotonic",first,"POST",API+"/device/reports",event(boot,2,UUID.randomUUID().toString(),firstScope.executionId(),"PROGRESS",20),null,200,"OK").data();
        require(number(lower.get("progress"))==20&&number(lower.get("executionProgress"))==40,"late_lower_progress_does_not_roll_back_execution");
        Map<String,Object> changed=new LinkedHashMap<>(forty);changed.put("progress",41);
        long seenBefore=seenMillis(first),countBefore=eventCount(first);
        deviceExpect("same_event_content_conflict",first,"POST",API+"/device/reports",changed,null,409,"IDEMPOTENCY_CONFLICT");
        changed=new LinkedHashMap<>(forty);changed.put("eventId",UUID.randomUUID().toString());
        deviceExpect("same_boot_sequence_different_event_conflict",first,"POST",API+"/device/reports",changed,null,409,"IDEMPOTENCY_CONFLICT");
        require(seenMillis(first)==seenBefore&&eventCount(first)==countBefore,"conflicting_event_has_no_presence_or_event_side_effect");
        Map<String,Object> concurrent=event(boot,3,UUID.randomUUID().toString(),firstScope.executionId(),"PROGRESS",100);
        List<Reply> raced=raceDevice(first,concurrent);
        require(raced.stream().allMatch(reply->reply.status()==200)&&raced.stream().filter(reply->Boolean.TRUE.equals(reply.data().get("duplicate"))).count()==1,
                "concurrent_report_retries_have_one_apply_and_one_duplicate");
        require(eventCount(first)==countBefore+1&&jdbc.queryForObject("SELECT progress FROM task_execution WHERE id=?",Integer.class,firstScope.executionId())==100,
                "concurrent_retry_persists_one_event_and_monotonic_progress");
        require("RUNNING".equals(jdbc.queryForObject("SELECT status FROM inspection_task WHERE id=?",String.class,taskId))
                &&"RUNNING".equals(jdbc.queryForObject("SELECT status FROM task_execution WHERE id=?",String.class,firstScope.executionId())),"device_progress_100_never_completes_task_or_execution");
        Map<String,Object> old=deviceExpect("old_progress_replay_keeps_original_snapshot",first,"POST",API+"/device/reports",forty,null,200,"OK").data();
        require(Boolean.TRUE.equals(old.get("duplicate"))&&number(old.get("executionProgress"))==40,"old_duplicate_preserves_historical_applied_progress");
        deviceExpect("new_boot_resets_sequence",first,"POST",API+"/device/reports",event(UUID.randomUUID().toString(),0,UUID.randomUUID().toString(),firstScope.executionId(),"PROGRESS",50),null,200,"OK");
        verifyManagementCas(first,heartbeat);
        verifyRedisFallback(first);
        // A healthy new event establishes the configured two-second threshold independently of prior I/O time.
        deviceExpect("fresh_heartbeat_for_offline_threshold",first,"POST",API+"/device/reports",event(UUID.randomUUID().toString(),0,UUID.randomUUID().toString(),firstScope.executionId(),"HEARTBEAT",null),null,200,"OK");
        require(Boolean.TRUE.equals(deviceView(first).get("online")),"fresh_device_is_online_under_configured_threshold");
        Thread.sleep(2200);require(Boolean.FALSE.equals(deviceView(first).get("online")),"online_status_becomes_offline_from_server_time_without_status_write");
        verifyDeviceUpload(first,second);
        report.put("deviceEventRows",eventCount(first));report.put("concurrentReportStatuses",raced.stream().map(Reply::status).toList());
        report.put("redisValueAndTtlVerified",true);report.put("redisUnavailableFallsBackToMysql",true);report.put("deviceUploadUsesActualPrivateStorage",true);
    }
    private void verifyManagementCas(Device device,Map<String,Object> replay)throws Exception {
        Map<String,Object> current=deviceView(device);long before=version(current);
        var disabled=expect("disable_device_with_cas","PATCH",API+"/drones/"+device.id+"/enabled",accounts.get(1).token,Map.of("enabled",false,"expectedLockVersion",before),200).data();
        require(Boolean.FALSE.equals(disabled.get("enabled"))&&Boolean.FALSE.equals(disabled.get("online"))&&version(disabled)==before+1,"manager_can_disable_device_and_increment_cas_version");
        expect("stale_device_management_version","PATCH",API+"/drones/"+device.id+"/enabled",accounts.get(1).token,Map.of("enabled",true,"expectedLockVersion",before),409);
        deviceExpect("disabled_device_token_rejected_even_for_duplicate",device,"POST",API+"/device/reports",replay,null,401,"UNAUTHORIZED");
        expect("reenable_device_with_current_version","PATCH",API+"/drones/"+device.id+"/enabled",accounts.get(1).token,Map.of("enabled",true,"expectedLockVersion",version(disabled)),200);
    }
    private void verifyRedisFallback(Device device)throws Exception {
        String originalUrl=baseUrl;
        try(var unavailable=new SpringApplicationBuilder(UavInspectionApplication.class).web(WebApplicationType.SERVLET)
                .profiles("local","database","storage").logStartupInfo(false).run("--server.port=0","--spring.data.redis.port=1",
                        "--inspection.device.online-threshold-seconds=2","--inspection.device.presence-key-prefix="+redisPrefix,
                        "--logging.level.com.uav.inspection=INFO")) {
            baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)unavailable).getWebServer().getPort();
            var result=deviceExpect("report_survives_actual_redis_connection_failure",device,"POST",API+"/device/reports",
                    event(UUID.randomUUID().toString(),0,UUID.randomUUID().toString(),firstScope.executionId(),"HEARTBEAT",null),null,200,"OK").data();
            var visible=deviceView(device);
            require(result.get("receivedAt").equals(visible.get("lastSeenAt"))&&seenMillis(device)==Instant.parse(string(result.get("receivedAt"))).toEpochMilli(),
                    "unreachable_redis_reads_fresh_committed_presence_from_mysql");
        }finally{baseUrl=originalUrl;}
        redis.command("SET",redisPrefix+device.id,Long.toString(System.currentTimeMillis()+86400000),"PX","10000");
        var visible=deviceView(device);
        require(Instant.parse(string(visible.get("lastSeenAt"))).toEpochMilli()==seenMillis(device),"future_cache_value_cannot_forge_presence_fact");
        redis.command("DEL",redisPrefix+device.id);
    }
    private void verifyDeviceUpload(Device owner,Device other)throws Exception {
        byte[] png=Files.readAllBytes(Path.of("examples/files/inspection-sample.png"));
        var body=createBody(UUID.randomUUID().toString(),firstScope,"device-photo.png","image/png",png.length);
        var created=deviceExpect("device_creates_upload_session",owner,"POST",API+"/device/upload-sessions",body,null,200,"OK").data();
        String upload=string(created.get("uploadId"));uploadIds.add(upload);
        require("DEVICE".equals(jdbc.queryForObject("SELECT actor_type FROM upload_session WHERE id=?",String.class,upload))
                &&owner.id.equals(jdbc.queryForObject("SELECT actor_id FROM upload_session WHERE id=?",String.class,upload)),"device_upload_provenance_is_derived_from_authenticated_headers");
        var repeated=deviceExpect("same_device_session_is_idempotent",owner,"POST",API+"/device/upload-sessions",body,null,200,"OK").data();
        require(upload.equals(repeated.get("uploadId")),"device_request_key_returns_original_session");
        deviceExpect("other_device_cannot_read_upload",other,"GET",API+"/device/upload-sessions/"+upload,null,null,403,"FORBIDDEN");
        expect("user_jwt_cannot_read_device_route","GET",API+"/device/upload-sessions/"+upload,accounts.get(2).token,null,401);
        expect("user_cannot_own_device_session","GET",API+"/upload-sessions/"+upload,accounts.get(2).token,null,403);
        deviceExpect("device_cannot_upload_for_other_execution",owner,"POST",API+"/device/upload-sessions",
                createBody(UUID.randomUUID().toString(),secondScope,"forbidden.png","image/png",png.length),null,403,"FORBIDDEN");
        var part=deviceExpect("device_uploads_actual_raw_png",owner,"PUT",API+"/device/upload-sessions/"+upload+"/parts/1",null,png,200,"OK").data();
        require(sha256(png).equals(part.get("sha256"))&&number(part.get("size"))==png.length,"device_part_digest_is_computed_from_real_bytes");
        var progress=deviceExpect("device_reads_persistent_upload_progress",owner,"GET",API+"/device/upload-sessions/"+upload,null,null,200,"OK").data();
        require("UPLOADING".equals(progress.get("processingStatus"))&&number(progress.get("uploadedSize"))==png.length&&progress.get("versionId")==null,
                "device_upload_without_messaging_creates_no_version_or_chain_side_effect");
        String key=jdbc.queryForObject("SELECT object_key FROM upload_part WHERE session_id=? AND part_no=1",String.class,upload);
        try(InputStream input=storage.open(key)){require(MessageDigest.isEqual(png,input.readAllBytes()),"device_upload_private_object_matches_database_and_http_digest");}
        require(count("SELECT COUNT(*) FROM evidence_version WHERE task_id=?",taskId)==0,"device_probe_never_creates_a_formal_version_or_ledger_record");
    }
    private Map<String,Object> deviceView(Device device)throws Exception {
        Map<String,Object> view=expect("read_safe_device_state","GET",API+"/drones/"+device.id,accounts.get(1).token,null,200).data();
        require(view.keySet().equals(deviceFields()),"management_reads_never_return_device_token_or_hash");return view;
    }
    private Reply deviceExpect(String name,Device device,String method,String path,Object body,byte[] raw,int status,String code)throws Exception {
        Reply reply=deviceRequest(name,device,method,path,body,raw);assertStatus(reply,name,status,code);return reply;
    }
    private Reply deviceRequest(String name,Device device,String method,String path,Object body,byte[] raw)throws Exception {
        return requestHeaders(name,method,path,Map.of("X-Device-Id",device.id,"X-Device-Token",device.token),
                raw!=null?raw:body==null?null:JSON.writeValueAsBytes(body),raw!=null?"application/octet-stream":body==null?null:"application/json");
    }
    private List<Reply> raceDevice(Device device,Map<String,Object> body)throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        java.util.function.Supplier<Reply> call=()->{try{ready.countDown();start.await(10,TimeUnit.SECONDS);return deviceRequest("concurrent_device_report",device,"POST",API+"/device/reports",body,null);}catch(Exception failure){throw new ProbeFailure("parallel_device_request_failed");}};
        var first=CompletableFuture.supplyAsync(call);var second=CompletableFuture.supplyAsync(call);
        require(ready.await(10,TimeUnit.SECONDS),"concurrent_device_requests_ready");start.countDown();
        CompletableFuture.allOf(first,second).get(40,TimeUnit.SECONDS);return List.of(first.get(),second.get());
    }
    private long eventCount(Device device){return count("SELECT COUNT(*) FROM device_event WHERE drone_id=?",device.id);}
    private long seenMillis(Device device){return jdbc.queryForObject("SELECT CAST(UNIX_TIMESTAMP(last_seen_at)*1000 AS SIGNED) FROM drone WHERE id=?",Long.class,device.id);}
    private static boolean sameResult(Map<String,Object> first,Map<String,Object> second){var a=new LinkedHashMap<>(first);var b=new LinkedHashMap<>(second);a.remove("duplicate");b.remove("duplicate");return a.equals(b);}
    private static Set<String> deviceFields(){return Set.of("id","operatorOrgId","code","enabled","lastSeenAt","online","lockVersion","createdAt","updatedAt");}
    private static Map<String,Object> event(String boot,long seq,String event,String execution,String type,Integer progress){var body=new LinkedHashMap<String,Object>();body.put("bootId",boot);body.put("seq",seq);body.put("eventId",event);body.put("executionId",execution);body.put("type",type);if(progress!=null)body.put("progress",progress);return body;}
    private static final class Device {final String id,code,token;Device(String id,String code,String token){this.id=id;this.code=code;this.token=token;}@Override public String toString(){return "ProbeDevice["+id+"]";}}

    private long count(String sql,Object...args){Long count=jdbc.queryForObject(sql,Long.class,args);if(count==null)throw new ProbeFailure("missing_count");return count;}

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
        Set<String> tasks=new LinkedHashSet<>();
        for(Account account:accounts)tasks.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,account.id));
        for(String task:tasks)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));
        Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        for(Device device:devices)jdbc.update("DELETE FROM device_event WHERE drone_id=?",device.id);
        for(String task:tasks) {
            jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);
            jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);
            jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);
            jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);
            jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);
            jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);
            jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);
            jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);
            jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);
        }
        for(Device device:devices)jdbc.update("DELETE FROM drone WHERE id=? AND code=?",device.id,device.code);
        for(Account account:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",account.id,account.username);
        try {
            List<String> redisKeys=redis.keys(redisPrefix+"*");for(String key:redisKeys){require(key.startsWith(redisPrefix),"redis_cleanup_key_is_owned_namespace");redis.command("DEL",key);}
            require(redis.keys(redisPrefix+"*").isEmpty(),"only_owned_redis_presence_namespace_is_cleaned");report.put("fixtureRedisKeysDeleted",redisKeys.size());
        }catch(Exception failure){throw new ProbeFailure("fixture_redis_cleanup_failed");}
        for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"only_owned_device_upload_objects_are_cleaned");
        report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",devices.size());
        report.put("fixtureUploadSessionsDeleted",uploadIds.size());report.put("fixturePrivateObjectsDeleted",keys.size());
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

    private Reply request(String name,String method,String path,String token,Object body,boolean raw)throws Exception {
        return requestContent(name,method,path,token,body==null?null:raw?(byte[])body:JSON.writeValueAsBytes(body),body==null?null:raw?"application/octet-stream":"application/json");
    }
    private Reply requestContent(String name,String method,String path,String token,byte[] bytes,String contentType)throws Exception {
        return requestHeaders(name,method,path,token==null?Map.of():Map.of("Authorization","Bearer "+token),bytes,contentType);
    }
    private Reply requestHeaders(String name,String method,String path,Map<String,String> headers,byte[] bytes,String contentType)throws Exception {
        String trace="t015-"+runId+"-"+sequence.incrementAndGet();
        var builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(30)).header("X-Trace-Id",trace).header("Accept","application/json");
        headers.forEach(builder::header);if(contentType!=null)builder.header("Content-Type",contentType);
        builder.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));
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
        if(response.statusCode()==401)require((path.startsWith(API+"/device/")?"Device":"Bearer").equals(response.headers().firstValue("WWW-Authenticate").orElse(null)),name+"_correct_identity_challenge");
        return new Reply(response.statusCode(), envelope, trace);
    }

    private void noUploadAudit(Reply reply) {
        List<String> actions = jdbc.queryForList("SELECT action FROM audit_log WHERE trace_id=?", String.class, reply.trace());
        require(actions.stream().noneMatch(UPLOAD_ACTIONS::contains), "rejected_or_idempotent_operation_has_no_extra_upload_audit");
    }

    private void verifyAuditSafety() {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id", "t015-"+runId+"-%");
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

    private byte[] businessSnapshot()throws Exception{return snapshot(TABLE_ORDER);}
    private byte[] snapshot(Map<String,String> tables)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(Connection connection=dataSource.getConnection()) {
            for(var table:new java.util.TreeMap<>(tables).entrySet()) {
                try(var statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT * FROM "+table.getKey()+" ORDER BY "+table.getValue())) {
                    frame(digest,table.getKey());
                    while(rows.next()) {
                        digest.update((byte)1);
                        for(int column=1;column<=rows.getMetaData().getColumnCount();column++) {
                            int type=rows.getMetaData().getColumnType(column);
                            if(type==java.sql.Types.BINARY||type==java.sql.Types.VARBINARY||type==java.sql.Types.LONGVARBINARY||type==java.sql.Types.BLOB) {
                                byte[] bytes=rows.getBytes(column);
                                digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());
                                if(bytes!=null)digest.update(bytes);
                            }else frame(digest,rows.getString(column));
                        }
                    }
                    digest.update((byte)0);
                }
            }
        }
        return digest.digest();
    }

    private void writeReport() throws Exception {
        report.put("caseCount", requests.size());
        report.put("checkCount", checks.size());
        Files.createDirectories(reportPath.getParent());
        Path temporary = Files.createTempFile(reportPath.getParent(), ".device-flow-probe-", ".tmp",
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

    private static Map<String, Object> createBody(String request, Scope scope, String filename, String type, long size) {
        return Map.of("clientRequestId", request, "requirementId", scope.requirementId(), "executionId", scope.executionId(),
                "filename", filename, "contentType", type, "size", size);
    }

    private String taskPath() { return API + "/tasks/" + taskId; }
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
        return list.stream().map(DeviceFlowProbe::object).toList();
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

    /** Minimal real RESP client for observing this probe's private cache namespace, never for business decisions. */
    private static final class Redis {
        private final String host,password;private final int port;
        Redis(String host,int port,String password){this.host=host;this.port=port;this.password=password;}
        synchronized Object command(String...arguments)throws Exception {
            try(var socket=new Socket()) {
                socket.connect(new java.net.InetSocketAddress(host,port),2000);socket.setSoTimeout(2000);
                var input=new BufferedInputStream(socket.getInputStream());var output=new BufferedOutputStream(socket.getOutputStream());
                write(output,"AUTH",password);if(!"OK".equals(read(input)))throw new ProbeFailure("redis_authentication_failed");
                write(output,arguments);return read(input);
            }
        }
        String get(String key)throws Exception {Object value=command("GET",key);return value==null?null:(String)value;}
        long pttl(String key)throws Exception{return (Long)command("PTTL",key);}
        @SuppressWarnings("unchecked") List<String> keys(String pattern)throws Exception {
            List<String> result=new ArrayList<>();String cursor="0";
            do {List<Object> page=(List<Object>)command("SCAN",cursor,"MATCH",pattern,"COUNT","100");cursor=(String)page.get(0);for(Object key:(List<Object>)page.get(1))result.add((String)key);}while(!"0".equals(cursor));return result;
        }
        private static void write(java.io.OutputStream output,String...arguments)throws Exception {
            output.write(("*"+arguments.length+"\r\n").getBytes(StandardCharsets.US_ASCII));
            for(String argument:arguments){byte[] bytes=argument.getBytes(StandardCharsets.UTF_8);output.write(("$"+bytes.length+"\r\n").getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.write("\r\n".getBytes(StandardCharsets.US_ASCII));}output.flush();
        }
        private static Object read(java.io.InputStream input)throws Exception {
            int kind=input.read();String line=line(input);
            return switch(kind){
                case '+'->line;case ':'->Long.parseLong(line);case '-'->throw new ProbeFailure("redis_command_failed");
                case '$'->{int length=Integer.parseInt(line);if(length==-1)yield null;byte[] bytes=input.readNBytes(length);if(bytes.length!=length||input.read()!=13||input.read()!=10)throw new ProbeFailure("redis_reply_truncated");yield new String(bytes,StandardCharsets.UTF_8);}
                case '*'->{int count=Integer.parseInt(line);List<Object> values=new ArrayList<>();for(int i=0;i<count;i++)values.add(read(input));yield values;}
                default->throw new ProbeFailure("invalid_redis_reply");};
        }
        private static String line(java.io.InputStream input)throws Exception {var bytes=new ByteArrayOutputStream();while(true){int value=input.read();if(value<0)throw new ProbeFailure("redis_reply_closed");if(value==13){if(input.read()!=10)throw new ProbeFailure("invalid_redis_line");return bytes.toString(StandardCharsets.UTF_8);}bytes.write(value);if(bytes.size()>65536)throw new ProbeFailure("redis_line_too_large");}}
    }

}
