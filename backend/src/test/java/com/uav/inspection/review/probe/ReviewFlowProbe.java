package com.uav.inspection.review.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.messaging.MessageEvent;
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

/** Explicit real HTTP review/issue probe, with automatic finalization and no Fabric writes. */
public final class ReviewFlowProbe {
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
    private final String runId=UUID.randomUUID().toString(),exchange="inspection.review.probe."+runId;
    private final String finalizeQueue=exchange+".finalize",anchorQueue=exchange+".anchor",deadExchange=exchange+".dead.events",deadQueue=exchange+".dead";
    private final Path reportPath;
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<Map<String,Object>> checks=Collections.synchronizedList(new ArrayList<>()),requests=Collections.synchronizedList(new ArrayList<>());
    private final List<Account> accounts=new ArrayList<>();private final List<String> secrets=new ArrayList<>();
    private final Set<String> uploadIds=new LinkedHashSet<>();private final List<Fixed> fixed=new ArrayList<>();private final AtomicInteger sequence=new AtomicInteger();
    private JdbcTemplate jdbc;private DataSource dataSource;private S3Client s3;private ObjectStorage storage;private RabbitTemplate rabbit;private HttpClient client;
    private String baseUrl,bucket,storageEndpoint,taskId;private Device device;private Scope mainScope,otherScope;
    private ReviewFlowProbe(Path path){reportPath=path.toAbsolutePath().normalize();report.put("formatVersion",1);report.put("runId",runId);report.put("startedAt",Instant.now().toString());report.put("passed",false);report.put("checks",checks);report.put("requests",requests);}
    public static void main(String[] args){
        if(args.length!=1){System.err.println("Usage: ReviewFlowProbe <reportPath>");System.exit(2);return;}
        var probe=new ReviewFlowProbe(Path.of(args[0]));try{probe.execute();probe.writeReport();System.out.println("[PASS] T018 real issue/review probe: "+probe.reportPath);}
        catch(Exception failure){probe.report.put("passed",false);probe.report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof ProbeFailure)probe.report.put("failedCheck",failure.getMessage());try{probe.writeReport();}catch(Exception ignored){}System.err.println("[FAIL] T018 review probe; inspect private safe report");System.exit(1);}
    }
    private void execute()throws Exception {
        report.put("stage","read_only_pending_work_preflight");var source=new DriverManagerDataSource();source.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")
                +"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8");source.setUsername(env("DB_USERNAME","uav_app"));source.setPassword(env("DB_PASSWORD",null));jdbc=new JdbcTemplate(source);dataSource=source;
        require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version='10' AND success=1")==1,"v10_migrated_before_probe");
        require(count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT'")==0&&count("SELECT COUNT(*) FROM upload_session WHERE status='VERIFYING'")==0
                &&count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'")==0&&count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1)")==0,
                "global_no_pending_work_before_automatic_workers");
        byte[] baseline=businessSnapshot();report.put("businessCountsBefore",businessCounts());
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class).profiles("local","database","storage","messaging").web(WebApplicationType.SERVLET).logStartupInfo(false)
                .run("--server.port=0","--inspection.messaging.exchange="+exchange,"--inspection.messaging.finalize-queue="+finalizeQueue,"--inspection.messaging.anchor-queue="+anchorQueue,
                        "--inspection.messaging.dead-letter-exchange="+deadExchange,"--inspection.messaging.dead-letter-queue="+deadQueue,"--inspection.messaging.recovery-enabled=false","--inspection.ledger.recovery-enabled=false")){
            jdbc=context.getBean(JdbcTemplate.class);dataSource=context.getBean(DataSource.class);s3=context.getBean(S3Client.class);storage=context.getBean(ObjectStorage.class);rabbit=context.getBean(RabbitTemplate.class);
            bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");storageEndpoint=context.getEnvironment().getRequiredProperty("inspection.storage.endpoint");baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            require(!context.getEnvironment().acceptsProfiles(org.springframework.core.env.Profiles.of("ledger")),"no_ledger_profile_or_fabric_write");
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
                client=actual;
                try{report.put("stage","isolated_fixture_setup");createFixtures();mainScope=createTask("main",uploader(),true);taskId=mainScope.task;
                    otherScope=createTask("other",accounts.get(5),false);report.put("stage","real_review_and_issue_checks");verifyReviews();verifyFiles();verifyAudit();
                    await(()->ownOutboxPending()==0,"all_own_outbox_events_published_automatically");
                }finally{context.getBean(ThreadPoolTaskScheduler.class).shutdown();context.getBean("finalizationContainer",SimpleMessageListenerContainer.class).stop();report.put("cleanupAttempted",true);
                    drainOwnedAnchors();cleanupFixtures();cleanupTopology();require(MessageDigest.isEqual(baseline,businessSnapshot()),"original_twenty_business_tables_and_binary_checkpoints_unchanged");report.put("originalBusinessDataUnchanged",true);report.put("businessCountsAfter",businessCounts());}
            }
        }
        report.put("stage","complete");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }
    private void createFixtures()throws Exception {
        account(OWNER,"creator",List.of("OWNER_TASK_ADMIN","ORG_ADMIN"));account(OPERATOR,"admin",List.of("OPERATOR_TASK_ADMIN","ORG_ADMIN"));
        account(OPERATOR,"uploader",List.of("OPERATOR_USER"));account(OWNER,"reviewer-a",List.of("OWNER_REVIEWER"));account(OWNER,"reviewer-b",List.of("OWNER_REVIEWER"));
        account(OPERATOR,"operator-outsider",List.of("OPERATOR_USER"));account(OWNER,"reviewer-outsider",List.of("OWNER_REVIEWER"));account(OPERATOR,"org-only",List.of("ORG_ADMIN","OPERATOR_USER"));
        for(Account a:accounts)login(a);
        Map<String,Object> registration=expect("register_fixture_device","POST",API+"/drones",admin(),Map.of("code","T018-"+runId.substring(0,12)),200).data();
        String token=string(registration.get("deviceToken"));secrets.add(token);device=new Device(string(object(registration.get("device")).get("id")),token);
        report.put("fixtureAccountIds",accounts.stream().map(a->a.id).toList());report.put("fixtureDeviceId",device.id);
    }
    private void account(String org,String suffix,List<String> roles){byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);String password=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);secrets.add(password);
        Account a=new Account(UUID.randomUUID().toString(),"t018-"+runId.substring(0,12)+"-"+suffix,password);accounts.add(a);
        require(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",a.id,org,a.username,new BCryptPasswordEncoder(12).encode(password),JSON.writeValueAsString(roles))==1,"isolated_fixture_account_inserted");}
    private void login(Account a)throws Exception{a.token=string(expect("fixture_login","POST",API+"/auth/login",null,Map.of("username",a.username,"password",a.password),200).data().get("accessToken"));secrets.add(a.token);}
    private Scope createTask(String suffix,Account participant,boolean reviewers)throws Exception {
        Map<String,Object> task=expect("create_"+suffix+"_task","POST",API+"/tasks",accounts.getFirst(),Map.of("name","T018-"+suffix+"-"+runId,"scope","isolated review acceptance","requirements",List.of(Map.of("name","inspection-photo","required",true))),200).data();
        String id=string(task.get("id")),path=API+"/tasks/"+id,requirement=string(objects(task.get("requirements")).getFirst().get("id"));
        task=expect("assign_"+suffix+"_task","POST",path+"/assign",accounts.getFirst(),Map.of("operatorOrgId",OPERATOR,"operatorAdminId",admin().id,"expectedLockVersion",version(task)),200).data();
        if(reviewers)task=expect("set_owner_review_members","PUT",path+"/members",accounts.getFirst(),Map.of("userIds",List.of(accounts.getFirst().id,reviewerA().id,reviewerB().id),"expectedLockVersion",version(task)),200).data();
        List<String> operators=reviewers?List.of(admin().id,participant.id,accounts.get(7).id):List.of(admin().id,participant.id);
        task=expect("set_operator_members","PUT",path+"/members",admin(),Map.of("userIds",operators,"expectedLockVersion",version(task)),200).data();
        task=expect("create_subtask","POST",path+"/subtasks",admin(),Map.of("name","review-flight","requirementIds",List.of(requirement),"expectedLockVersion",version(task)),200).data();
        Map<String,Object> sub=objects(task.get("subtasks")).getFirst();String execution=string(expect("create_execution","POST",API+"/subtasks/"+sub.get("id")+"/executions",admin(),Map.of("droneId",device.id,"expectedLockVersion",version(sub)),200).data().get("id"));
        task=expect("refresh_task","GET",path,admin(),null,200).data();expect("start_task","POST",path+"/start",admin(),Map.of("expectedLockVersion",version(task)),200);
        return new Scope(id,requirement,execution);
    }
    private void verifyReviews()throws Exception {
        Fixed first=upload(mainScope,uploader(),0x145aa1);Fixed other=upload(otherScope,accounts.get(5),0x3b8636);
        Map<String,Object> pending=review(first.id,reviewerA());require("PENDING".equals(pending.get("reviewStatus"))&&pending.get("review")==null,"unreviewed_version_has_pending_state_and_null_decision");
        verifyIssuePermissionsAndPagination(first,other);verifyReviewPermissionsAndValidation(first,other);
        Map<String,Object> approved=expect("approve_first_version_without_chain_confirmation","POST",reviewPath(first.id),reviewerA(),decision("APPROVED",null),200).data();
        assertDecision(approved,first.id,"APPROVED",reviewerA().id,null);
        Map<String,Object> approvedMaterial=objects(progress().get("requirements")).getFirst();
        require(first.id.equals(approvedMaterial.get("versionId"))&&"APPROVED".equals(approvedMaterial.get("reviewStatus"))&&"PENDING".equals(approvedMaterial.get("anchorStatus"))&&!Boolean.TRUE.equals(approvedMaterial.get("acceptanceReady")),"approved_but_unanchored_version_is_not_ready_for_acceptance");
        expect("old_decision_cannot_be_overwritten","POST",reviewPath(first.id),reviewerB(),decision("RETURNED","too late"),409);
        require("APPROVED".equals(review(first.id,uploader()).get("reviewStatus")),"operator_member_can_read_owner_decision");
        Fixed second=upload(mainScope,uploader(),0xd34822);
        Map<String,Object> selected=objects(progress().get("requirements")).getFirst();
        require(second.id.equals(selected.get("versionId"))&&"PENDING".equals(selected.get("reviewStatus")),"current_summary_selects_new_pending_version_instead_of_old_approval");
        Map<String,Object> fresh=review(second.id,reviewerA());require("PENDING".equals(fresh.get("reviewStatus"))&&fresh.get("review")==null,"new_fixed_version_starts_pending_and_does_not_inherit_prior_approval");
        List<Reply> race=raceReview(second.id);require(race.stream().filter(r->r.status==200).count()==1&&race.stream().filter(r->r.status==409).count()==1,"concurrent_different_review_decisions_have_exactly_one_winner");
        for(Reply response:race)assertStatus(response,response.status,"review_race");
        Map<String,Object> winner=race.stream().filter(r->r.status==200).findFirst().orElseThrow().data();
        require(Set.of("APPROVED","RETURNED").contains(winner.get("reviewStatus")),"race_winner_has_a_formal_decision");
        require(count("SELECT COUNT(*) FROM review_record WHERE evidence_version_id=?",second.id)==1,"cas_race_has_exactly_one_review_record");
        require(winner.equals(review(second.id,reviewerA())),"later_get_returns_exactly_the_first_committed_review");
        Fixed third=upload(mainScope,uploader(),0x9a48a5);Map<String,Object> returned=expect("return_third_version","POST",reviewPath(third.id),reviewerB(),decision("RETURNED","  图片未覆盖全部巡检范围，请补充证据  "),200).data();
        assertDecision(returned,third.id,"RETURNED",reviewerB().id,"图片未覆盖全部巡检范围，请补充证据");
        require("APPROVED".equals(review(first.id,reviewerB()).get("reviewStatus")),"older_version_keeps_original_approval_after_new_return");
        require(number(issues(first.id,uploader(),1,20).get("total"))==3&&number(issues(second.id,uploader(),1,20).get("total"))==0,"issues_are_fixed_to_their_original_version");
        Map<String,Object> oldIssue=expect("issue_can_still_reference_old_fixed_version","POST",API+"/issues",uploader(),Map.of("versionId",first.id,"description","补充旧版异常定位"),200).data();
        require(first.id.equals(oldIssue.get("versionId"))&&number(issues(first.id,reviewerB(),1,20).get("total"))==4,"old_version_issues_remain_independent_of_newer_version");
        require("RUNNING".equals(task().get("status")),"real_http_task_stays_running_without_future_acceptance_api");
        for(Fixed version:fixed)require(count("SELECT COUNT(*) FROM chain_submission WHERE version_id=? AND status='PENDING' AND confirmed_tx_id IS NULL",version.id)==1,"review_does_not_modify_independent_pending_chain_state");
        require(count("SELECT COUNT(*) FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id IN (?,?)))",mainScope.task,otherScope.task)==0,"no_fabric_transaction_attempts");
        verifyRevokedMembership(first,third);
        report.put("raceWinner",winner.get("reviewStatus"));report.put("approvedVersionId",first.id);report.put("returnedVersionId",third.id);report.put("mainVersionCount",3);report.put("reviewCount",3);report.put("issueCount",4);
        report.put("waitingAcceptanceAndCompletedHttpNotExercised",true);
    }
    private void verifyIssuePermissionsAndPagination(Fixed first,Fixed other)throws Exception {
        for(int index=0;index<3;index++){
            Map<String,Object> issue=expect("create_version_issue","POST",API+"/issues",uploader(),Map.of("versionId",first.id,"description","  巡检异常位置 "+index+"  "),200).data();
            require(issue.keySet().equals(Set.of("id","taskId","versionId","description","createdBy","createdAt"))&&first.id.equals(issue.get("versionId"))
                    &&mainScope.task.equals(issue.get("taskId"))&&uploader().id.equals(issue.get("createdBy"))&&("巡检异常位置 "+index).equals(issue.get("description")),"issue_records_safe_fixed_version_creator_and_trimmed_description");
        }
        List<String> expected=jdbc.queryForList("SELECT id FROM issue WHERE evidence_version_id=? ORDER BY created_at,id",String.class,first.id);
        Map<String,Object> pageOne=issues(first.id,uploader(),1,1),pageTwo=issues(first.id,reviewerA(),2,1),empty=issues(first.id,reviewerA(),4,1);
        require(number(pageOne.get("total"))==3&&number(pageOne.get("page"))==1&&number(pageOne.get("pageSize"))==1
                &&expected.get(0).equals(objects(pageOne.get("items")).getFirst().get("id"))&&expected.get(1).equals(objects(pageTwo.get("items")).getFirst().get("id"))
                &&objects(empty.get("items")).isEmpty(),"issue_pagination_is_version_scoped_and_stable_created_at_id_ascending");
        require("PENDING".equals(review(first.id,reviewerA()).get("reviewStatus")),"creating_issues_does_not_automatically_review_version");
        expect("issue_rejects_empty_description","POST",API+"/issues",uploader(),Map.of("versionId",first.id,"description","  "),400);
        expect("issue_rejects_long_description","POST",API+"/issues",uploader(),Map.of("versionId",first.id,"description","x".repeat(4001)),400);
        expect("issue_rejects_unknown_version","POST",API+"/issues",uploader(),Map.of("versionId",UUID.randomUUID().toString(),"description","unknown"),403);
        expect("issue_rejects_invalid_version_id","POST",API+"/issues",uploader(),Map.of("versionId","bad-id","description","invalid"),400);
        expect("operator_task_admin_cannot_file_issue","POST",API+"/issues",admin(),Map.of("versionId",first.id,"description","wrong role"),403);
        expect("owner_reviewer_cannot_file_operator_issue","POST",API+"/issues",reviewerA(),Map.of("versionId",first.id,"description","wrong organization"),403);
        expect("operator_outsider_cannot_file_issue","POST",API+"/issues",accounts.get(5),Map.of("versionId",first.id,"description","not a main-task member"),403);
        expect("cross_task_version_cannot_be_used_for_issue","POST",API+"/issues",uploader(),Map.of("versionId",other.id,"description","different task"),403);
        expect("cross_task_issue_page_is_forbidden","GET",issuesPath(other.id),uploader(),null,403);
        expect("invalid_issue_page","GET",issuesPath(first.id)+"?page=0&pageSize=1",uploader(),null,400);
        expect("invalid_issue_page_size","GET",issuesPath(first.id)+"?page=1&pageSize=101",uploader(),null,400);
        expect("unknown_issue_page_does_not_reveal_existence","GET",issuesPath(UUID.randomUUID().toString()),uploader(),null,403);
        Reply deviceIssue=request("device_cannot_create_user_issue","POST",API+"/issues",null,device,Map.of("versionId",first.id,"description","device identity"),false);assertStatus(deviceIssue,401,"device_issue");
        Map<String,Object> identity=expect("read_org_member_identity","GET",API+"/users/me",accounts.get(7),null,200).data();
        expect("remove_operator_role_from_existing_member","PUT",API+"/users/"+accounts.get(7).id+"/roles",admin(),Map.of("roles",List.of("ORG_ADMIN"),"expectedLockVersion",version(identity)),200);
        expect("old_token_invalid_after_role_change","GET",issuesPath(first.id),accounts.get(7),null,401);login(accounts.get(7));
        require(count("SELECT COUNT(*) FROM task_member WHERE task_id=? AND user_id=?",mainScope.task,accounts.get(7).id)==1,"org_admin_only_fixture_is_still_a_real_task_member");
        expect("org_admin_only_member_cannot_file_issue","POST",API+"/issues",accounts.get(7),Map.of("versionId",first.id,"description","no operator role"),403);
        expect("org_admin_only_member_cannot_read_issue_history","GET",issuesPath(first.id),accounts.get(7),null,403);
        require(count("SELECT COUNT(*) FROM issue WHERE evidence_version_id=?",first.id)==3,"all_invalid_issue_requests_leave_original_three_records_only");
    }
    private void verifyReviewPermissionsAndValidation(Fixed first,Fixed other)throws Exception {
        expect("return_requires_reason","POST",reviewPath(first.id),reviewerA(),decision("RETURNED",null),400);
        expect("return_rejects_blank_reason","POST",reviewPath(first.id),reviewerA(),decision("RETURNED"," \t "),400);
        expect("review_rejects_reason_over_limit","POST",reviewPath(first.id),reviewerA(),decision("RETURNED","x".repeat(1001)),400);
        expect("review_rejects_unknown_decision","POST",reviewPath(first.id),reviewerA(),decision("ACCEPTED","invalid enum"),400);
        expect("review_requires_pending_expectation","POST",reviewPath(first.id),reviewerA(),Map.of("decision","APPROVED","expectedReviewStatus","APPROVED"),400);
        expect("operator_cannot_review","POST",reviewPath(first.id),uploader(),decision("APPROVED",null),403);
        expect("owner_manager_and_org_admin_do_not_gain_reviewer_role","POST",reviewPath(first.id),accounts.getFirst(),decision("APPROVED",null),403);
        expect("org_admin_only_cannot_review","POST",reviewPath(first.id),accounts.get(7),decision("APPROVED",null),403);
        expect("owner_reviewer_outsider_cannot_review","POST",reviewPath(first.id),accounts.get(6),decision("APPROVED",null),403);
        expect("owner_reviewer_outsider_cannot_read_conclusion","GET",reviewPath(first.id),accounts.get(6),null,403);
        expect("reviewer_cannot_review_other_task","POST",reviewPath(other.id),reviewerA(),decision("APPROVED",null),403);
        expect("reviewer_cannot_read_other_task_conclusion","GET",reviewPath(other.id),reviewerA(),null,403);
        expect("unknown_review_version_forbidden","GET",reviewPath(UUID.randomUUID().toString()),reviewerA(),null,403);
        Reply deviceReview=request("device_cannot_review","POST",reviewPath(first.id),null,device,decision("APPROVED",null),false);assertStatus(deviceReview,401,"device_review");
        Reply deviceRead=request("device_cannot_read_user_review","GET",reviewPath(first.id),null,device,null,false);assertStatus(deviceRead,401,"device_read_review");
        require(count("SELECT COUNT(*) FROM review_record WHERE evidence_version_id=?",first.id)==0,"invalid_reviews_do_not_write_conclusions");
    }
    private void verifyRevokedMembership(Fixed first,Fixed third)throws Exception {
        long issueCount=count("SELECT COUNT(*) FROM issue WHERE task_id=?",mainScope.task),reviewCount=count("SELECT COUNT(*) FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",mainScope.task);
        Map<String,Object> current=task();expect("remove_reviewer_from_task","PUT",taskPath()+"/members",accounts.getFirst(),Map.of("userIds",List.of(accounts.getFirst().id,reviewerB().id),"expectedLockVersion",version(current)),200);
        expect("removed_reviewer_cannot_read_prior_conclusion","GET",reviewPath(first.id),reviewerA(),null,403);
        expect("removed_reviewer_cannot_read_issues","GET",issuesPath(first.id),reviewerA(),null,403);
        expect("removed_reviewer_cannot_write_review","POST",reviewPath(third.id),reviewerA(),decision("APPROVED",null),403);
        current=task();expect("remove_operator_from_task","PUT",taskPath()+"/members",admin(),Map.of("userIds",List.of(admin().id),"expectedLockVersion",version(current)),200);
        expect("removed_operator_cannot_create_issue","POST",API+"/issues",uploader(),Map.of("versionId",first.id,"description","revoked"),403);
        expect("removed_operator_cannot_read_issues","GET",issuesPath(first.id),uploader(),null,403);
        expect("removed_operator_cannot_read_review","GET",reviewPath(first.id),uploader(),null,403);
        require(issueCount==count("SELECT COUNT(*) FROM issue WHERE task_id=?",mainScope.task)&&reviewCount==count("SELECT COUNT(*) FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",mainScope.task),"permission_loss_does_not_write_or_erase_issue_and_review_history");
    }
    private List<Reply> raceReview(String id)throws Exception {
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        var first=parallelReview(ready,start,id,reviewerA(),decision("APPROVED",null));var second=parallelReview(ready,start,id,reviewerB(),decision("RETURNED","并发退回的复核意见"));
        require(ready.await(5,TimeUnit.SECONDS),"both_review_requests_ready");start.countDown();return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
    }
    private CompletableFuture<Reply> parallelReview(CountDownLatch ready,CountDownLatch start,String id,Account reviewer,Map<String,Object> body){return CompletableFuture.supplyAsync(()->{try{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new ProbeFailure("review_race_timeout");return request("concurrent_review","POST",reviewPath(id),reviewer,null,body,false);}catch(Exception failure){throw new ProbeFailure("parallel_review_http_failed");}});}
    private Fixed upload(Scope scope,Account actor,int color)throws Exception {
        byte[] bytes=png(color);Map<String,Object> response=expect("create_fixed_version_upload","POST",API+"/upload-sessions",actor,Map.of("clientRequestId",UUID.randomUUID().toString(),"requirementId",scope.requirement,"executionId",scope.execution,"filename","inspection.png","contentType","image/png","size",bytes.length),200).data();
        String upload=string(response.get("uploadId"));uploadIds.add(upload);assertStatus(request("upload_png_part","PUT",API+"/upload-sessions/"+upload+"/parts/1",actor,null,bytes,true),200,"upload_png_part");
        expect("complete_png_upload","POST",API+"/upload-sessions/"+upload+"/complete",actor,null,202);
        await(()->"COMPLETED".equals(jdbc.queryForObject("SELECT status FROM upload_session WHERE id=?",String.class,upload)),"automatic_worker_really_fixes_png_version");
        String version=string(expect("read_completed_upload","GET",API+"/upload-sessions/"+upload,actor,null,200).data().get("versionId"));
        require(count("SELECT COUNT(*) FROM evidence_version WHERE id=? AND review_status='PENDING'",version)==1,"every_new_fixed_version_starts_pending");
        Fixed result=new Fixed(version,upload,scope.task,bytes);fixed.add(result);return result;
    }
    private Map<String,Object> decision(String value,String reason){Map<String,Object> body=new LinkedHashMap<>();body.put("decision",value);body.put("expectedReviewStatus","PENDING");if(reason!=null)body.put("reason",reason);return body;}
    private Map<String,Object> review(String id,Account actor)throws Exception{return expect("read_review_state","GET",reviewPath(id),actor,null,200).data();}
    private Map<String,Object> issues(String id,Account actor,int page,int size)throws Exception{return expect("read_version_issue_page","GET",issuesPath(id)+"?page="+page+"&pageSize="+size,actor,null,200).data();}
    private void assertDecision(Map<String,Object> state,String version,String expected,String reviewer,String reason){Map<String,Object> review=object(state.get("review"));require(state.keySet().equals(Set.of("versionId","reviewStatus","review"))&&version.equals(state.get("versionId"))&&expected.equals(state.get("reviewStatus"))
            &&expected.equals(review.get("decision"))&&reviewer.equals(review.get("reviewerId"))&&java.util.Objects.equals(reason,review.get("reason"))&&review.get("createdAt")!=null,"review_state_has_exact_fixed_version_formal_decision_and_reviewer");}
    private Map<String,Object> task()throws Exception{return expect("read_main_task","GET",taskPath(),admin(),null,200).data();}
    private Map<String,Object> progress()throws Exception{return expect("read_current_task_progress","GET",taskPath()+"/progress",admin(),null,200).data();}
    private void verifyFiles()throws Exception {
        for(Fixed version:fixed){var row=jdbc.queryForMap("SELECT object_key,size,sha256 FROM evidence_version WHERE id=?",version.id);try(InputStream input=storage.open(string(row.get("object_key")))){byte[] actual=input.readAllBytes();require(java.util.Arrays.equals(actual,version.bytes)&&actual.length==number(row.get("size"))&&sha256(actual).equals(row.get("sha256")),"old_and_new_fixed_objects_keep_original_full_bytes_and_sha256_after_reviews");}}
        require(fixed.stream().map(v->{try{return sha256(v.bytes);}catch(Exception failure){throw new ProbeFailure("digest_unavailable");}}).distinct().count()==4,"all_four_real_versions_have_distinct_file_digests");
        report.put("fixedVersionsVerified",fixed.size());report.put("fileDigests",fixed.stream().map(v->{try{return Map.of("versionId",v.id,"sha256",sha256(v.bytes));}catch(Exception failure){throw new ProbeFailure("digest_unavailable");}}).toList());
    }
    private long ownOutboxPending(){return count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT' AND (biz_id IN (SELECT id FROM upload_session WHERE task_id IN (SELECT id FROM inspection_task WHERE created_by=?)) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id IN (SELECT id FROM inspection_task WHERE created_by=?))))",accounts.getFirst().id,accounts.getFirst().id);}
    private void drainOwnedAnchors(){
        if(accounts.isEmpty())return;
        Set<String> expected=new LinkedHashSet<>(jdbc.queryForList("SELECT event_id FROM outbox_event WHERE event_type='EVIDENCE_ANCHOR_REQUESTED' AND biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id IN (SELECT id FROM inspection_task WHERE created_by=?)))",String.class,accounts.getFirst().id));
        Set<String> seen=new LinkedHashSet<>();rabbit.execute(channel->{while(true){var delivery=channel.basicGet(anchorQueue,false);if(delivery==null)break;MessageEvent event=JSON.readValue(delivery.getBody(),MessageEvent.class);
            require(expected.contains(event.eventId())&&MessageEvent.EVIDENCE_ANCHOR_REQUESTED.equals(event.eventType()),"only_owned_anchor_event_is_acknowledged_without_fabric");require(count("SELECT COUNT(*) FROM outbox_event WHERE event_id=? AND biz_id=? AND status='SENT'",event.eventId(),event.bizId())==1,"anchor_envelope_matches_own_sent_outbox");
            require(Integer.valueOf(2).equals(delivery.getProps().getDeliveryMode()),"own_anchor_event_is_persistent");seen.add(event.eventId());channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);}return null;});
        require(seen.equals(expected),"all_owned_pending_anchor_messages_accounted_for");report.put("anchorMessagesAcknowledgedWithoutLedger",seen.size());
    }
    private Set<String> objectKeys(String upload){Set<String> keys=new LinkedHashSet<>();for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")){String continuation=null;do{var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(continuation).build());page.contents().forEach(o->keys.add(o.key()));continuation=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;}while(continuation!=null);}return keys;}
    private void cleanupFixtures(){
        Set<String> tasks=new LinkedHashSet<>();for(Account a:accounts)tasks.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,a.id));
        for(String task:tasks)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));
        Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        for(String task:tasks){jdbc.update("DELETE FROM issue WHERE task_id=?",task);jdbc.update("DELETE FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",task);jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            List<String> events=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,task,task);
            for(String event:events){jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}
            jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);
            jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);
            jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);
            jdbc.update("DELETE FROM device_event WHERE execution_id IN (SELECT id FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?))",task);
            jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);
            jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);}
        int devices=jdbc.update("DELETE FROM drone WHERE operator_org_id=? AND code=?",OPERATOR,"T018-"+runId.substring(0,12));for(Account a:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",a.id,a.username);
        for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"only_own_upload_objects_removed");report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",devices);report.put("fixtureUploadSessionsDeleted",uploadIds.size());report.put("fixturePrivateObjectsDeleted",keys.size());
    }
    private void cleanupTopology(){rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue)){var state=channel.queueDeclarePassive(queue);require(state.getMessageCount()==0&&state.getConsumerCount()==0,"only_empty_unused_owned_queue_deleted");channel.queueDelete(queue,true,true);}channel.exchangeDelete(exchange,true);channel.exchangeDelete(deadExchange,true);return null;});report.put("ownedRabbitTopologyDeleted",true);}
    private void verifyAudit(){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id","t018-"+runId+"-%");
        require(!rows.isEmpty(),"formal_operations_have_append_only_audit");for(var row:rows){String text=String.valueOf(row);require(secrets.stream().noneMatch(text::contains)&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains(storageEndpoint),"audit_excludes_credentials_and_object_locations");}
        require(count("SELECT COUNT(*) FROM audit_log WHERE trace_id LIKE ? AND action='ISSUE_CREATED' AND resource_type='ISSUE' AND resource_id IN (SELECT id FROM issue WHERE task_id=?)","t018-"+runId+"-%",mainScope.task)==4,"each_successful_issue_has_one_correctly_bound_audit");
        require(count("SELECT COUNT(*) FROM audit_log WHERE trace_id LIKE ? AND action='EVIDENCE_REVIEWED' AND resource_type='EVIDENCE_VERSION' AND resource_id IN (SELECT id FROM evidence_version WHERE task_id=?)","t018-"+runId+"-%",mainScope.task)==3,"review_cas_winners_have_exactly_three_success_audits");report.put("auditRowsRetained",rows.size());
    }
    private Reply expect(String name,String method,String path,Account actor,Object body,int status)throws Exception{Reply reply=request(name,method,path,actor,null,body,false);assertStatus(reply,status,name);return reply;}
    private void assertStatus(Reply reply,int status,String name){require(reply.status==status,name+"_http_"+status);String code=switch(status){case 200,202->"OK";case 400->"INVALID_ARGUMENT";case 401->"UNAUTHORIZED";case 403->"FORBIDDEN";case 409->"VERSION_CONFLICT";default->"UNSUPPORTED";};require(code.equals(reply.body.get("code")),name+"_code");}
    private Reply request(String name,String method,String path,Account actor,Device device,Object body,boolean raw)throws Exception{
        String trace="t018-"+runId+"-"+sequence.incrementAndGet();var builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(25)).header("X-Trace-Id",trace).header("Accept","application/json");
        if(actor!=null&&actor.token!=null)builder.header("Authorization","Bearer "+actor.token);if(device!=null){builder.header("X-Device-Id",device.id).header("X-Device-Token",device.token);}
        byte[] bytes=body==null?null:raw?(byte[])body:JSON.writeValueAsBytes(body);if(bytes!=null)builder.header("Content-Type",raw?"application/octet-stream":"application/json");builder.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));
        var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());requests.add(Map.of("case",name,"status",response.statusCode(),"traceId",trace));require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),name+"_json");
        String text=new String(response.body(),StandardCharsets.UTF_8);boolean secretResponse=path.equals(API+"/drones")&&method.equals("POST")||path.equals(API+"/auth/login");require((secretResponse||secrets.stream().noneMatch(text::contains))&&!text.contains("objectKey")&&!text.contains("object_key")&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("transactionBytes")&&!text.contains("commitBytes")&&!text.contains(storageEndpoint),name+"_safe_response");
        Map<String,Object> envelope=JSON.readValue(response.body(),Map.class);require(envelope.keySet().equals(Set.of("code","message","data","traceId")),name+"_uniform_envelope");require(trace.equals(envelope.get("traceId"))&&trace.equals(response.headers().firstValue("X-Trace-Id").orElse(null)),name+"_trace_propagated");
        if(response.statusCode()==401)require("Bearer".equals(response.headers().firstValue("WWW-Authenticate").orElse(null)),name+"_requires_user_bearer_identity");return new Reply(response.statusCode(),envelope);
    }
    private void writeReport()throws Exception{report.put("caseCount",requests.size());report.put("checkCount",checks.size());byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);String text=new String(bytes,StandardCharsets.UTF_8);if(secrets.stream().anyMatch(text::contains)||text.contains("upload-parts/")||text.contains("evidence-files/"))throw new ProbeFailure("unsafe_report");privateWrite(reportPath,bytes);}
    private static void privateWrite(Path path,byte[] bytes)throws Exception{Files.createDirectories(path.getParent());Path temporary=Files.createTempFile(path.getParent(),".review-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.write(temporary,bytes);Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    private void require(boolean value,String name){checks.add(Map.of("check",name,"passed",value));if(!value)throw new ProbeFailure(name);}
    private Account admin(){return accounts.get(1);}private Account uploader(){return accounts.get(2);}private Account reviewerA(){return accounts.get(3);}private Account reviewerB(){return accounts.get(4);}private String taskPath(){return API+"/tasks/"+taskId;}
    private static String reviewPath(String id){return API+"/evidence-versions/"+id+"/review";}private static String issuesPath(String id){return API+"/evidence-versions/"+id+"/issues";}
    private static String env(String key,String fallback){String value=System.getenv(key);if(value==null||value.isBlank()){if(fallback==null)throw new ProbeFailure("missing_required_environment");return fallback;}return value;}
    private static long version(Map<String,Object> row){return number(row.get("lockVersion"));}private static long number(Object value){if(!(value instanceof Number n))throw new ProbeFailure("expected_number");return n.longValue();}private static String string(Object value){if(!(value instanceof String text))throw new ProbeFailure("expected_string");return text;}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new ProbeFailure("expected_object");return (Map<String,Object>)value;}private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new ProbeFailure("expected_array");return list.stream().map(ReviewFlowProbe::object).toList();}
    private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private static byte[] png(int color)throws Exception{var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);for(int x=0;x<8;x++)for(int y=0;y<8;y++)image.setRGB(x,y,color);var output=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",output))throw new ProbeFailure("png_encoder_unavailable");return output.toByteArray();}
    private static final class Account{final String id,username,password;String token;Account(String id,String username,String password){this.id=id;this.username=username;this.password=password;}@Override public String toString(){return "ReviewAccount["+id+"]";}}
    private record Device(String id,String token){@Override public String toString(){return "ReviewDevice["+id+"]";}}private record Scope(String task,String requirement,String execution){}private record Fixed(String id,String upload,String task,byte[] bytes){}private record Reply(int status,Map<String,Object> body){Map<String,Object> data(){return object(body.get("data"));}}private static final class ProbeFailure extends RuntimeException{ProbeFailure(String name){super(name);}}
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
}
