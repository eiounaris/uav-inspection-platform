package com.uav.inspection.sharing.probe;

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

/** Explicit real grants/download/verification probe; successful business state is created only through HTTP. */
public final class SharingFlowProbe {
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
        TABLE_ORDER.put("access_grant","id");
    }
    private static final Set<String> GRANT_FIELDS=Set.of("id","versionId","applicantId","clientRequestId","status","reason","requestedValidUntil","approverId","decisionReason","decidedAt","validUntil","revokedBy","revokeReason","revokedAt","lockVersion","createdAt","updatedAt");
    private final String runId=UUID.randomUUID().toString(),exchange="inspection.sharing.probe."+runId;
    private final String finalizeQueue=exchange+".finalize",anchorQueue=exchange+".anchor",deadExchange=exchange+".dead.events",deadQueue=exchange+".dead";
    private final Path reportPath;private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<Map<String,Object>> checks=Collections.synchronizedList(new ArrayList<>()),requests=Collections.synchronizedList(new ArrayList<>());
    private final List<Account> accounts=new ArrayList<>();private final List<String> secrets=new ArrayList<>();private final Set<String> uploadIds=new LinkedHashSet<>();private final List<Fixed> fixed=new ArrayList<>();private final AtomicInteger sequence=new AtomicInteger();private final List<Map<String,Object>> ledgerRecords=new ArrayList<>();
    private JdbcTemplate jdbc;private DataSource dataSource;private S3Client s3;private ObjectStorage storage;private GateStorage storageGate;private GateClients clients;private RabbitTemplate rabbit;private HttpClient client;
    private String baseUrl,bucket,storageEndpoint,taskId,subtaskId,requirementId,evidenceId,executionId,deviceId;
    private SharingFlowProbe(Path path){reportPath=path.toAbsolutePath().normalize();report.put("formatVersion",1);report.put("runId",runId);report.put("startedAt",Instant.now().toString());report.put("passed",false);report.put("checks",checks);report.put("requests",requests);report.put("ledgerRecordsRetained",ledgerRecords);}
    public static void main(String[] args){if(args.length!=1){System.err.println("Usage: SharingFlowProbe <reportPath>");System.exit(2);return;}var p=new SharingFlowProbe(Path.of(args[0]));try{p.execute();p.writeReport();System.out.println("[PASS] T020 real sharing probe: "+p.reportPath);}catch(Exception failure){p.report.put("passed",false);p.report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof ProbeFailure)p.report.put("failedCheck",failure.getMessage());try{p.writeReport();}catch(Exception ignored){}System.err.println("[FAIL] T020 sharing probe; inspect private safe report");System.exit(1);}}
    private void execute()throws Exception{
        report.put("stage","read_only_pending_work_preflight");var source=new DriverManagerDataSource();source.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")+"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8");source.setUsername(env("DB_USERNAME","uav_app"));source.setPassword(env("DB_PASSWORD",null));jdbc=new JdbcTemplate(source);dataSource=source;
        require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version='11' AND success=1")==1,"v11_required_before_sharing_probe");require(count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT'")==0&&count("SELECT COUNT(*) FROM upload_session WHERE status='VERIFYING'")==0&&count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'")==0&&count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1)")==0,"no_global_pending_work_before_automatic_workers");
        byte[] baseline=businessSnapshot();report.put("businessCountsBefore",businessCounts());
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,GateConfiguration.class).profiles("local","database","storage","messaging","ledger","sharing-probe").web(WebApplicationType.SERVLET).logStartupInfo(false)
                .run("--server.port=0","--inspection.messaging.exchange="+exchange,"--inspection.messaging.finalize-queue="+finalizeQueue,"--inspection.messaging.anchor-queue="+anchorQueue,"--inspection.messaging.dead-letter-exchange="+deadExchange,"--inspection.messaging.dead-letter-queue="+deadQueue)){
            jdbc=context.getBean(JdbcTemplate.class);dataSource=context.getBean(DataSource.class);s3=context.getBean(S3Client.class);storage=context.getBean(ObjectStorage.class);storageGate=context.getBean(GateStorage.class);clients=context.getBean(GateClients.class);rabbit=context.getBean(RabbitTemplate.class);bucket=context.getEnvironment().getRequiredProperty("inspection.storage.bucket");storageEndpoint=context.getEnvironment().getRequiredProperty("inspection.storage.endpoint");baseUrl="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            try(HttpClient actual=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){client=actual;try{report.put("stage","formal_fixture_setup");createFixtures();createTask();report.put("stage","grant_scope_download_and_verification");verifySharing();verifyLedgerAndFiles();verifyAudit();await(()->ownOutboxPending()==0,"all_owned_messages_published_automatically");}
                finally{storageGate.release();clients.operator.release();context.getBean(ThreadPoolTaskScheduler.class).shutdown();context.getBean("finalizationContainer",SimpleMessageListenerContainer.class).stop();context.getBean("anchorContainer",SimpleMessageListenerContainer.class).stop();report.put("cleanupAttempted",true);cleanupFixtures();cleanupTopology();require(MessageDigest.isEqual(baseline,businessSnapshot()),"original_twenty_one_tables_all_columns_and_binary_checkpoints_unchanged");report.put("originalBusinessDataUnchanged",true);report.put("businessCountsAfter",businessCounts());}}
        }report.put("stage","complete");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }
    private void createFixtures()throws Exception{
        account(OWNER,"owner",List.of("OWNER_TASK_ADMIN"));account(OPERATOR,"admin",List.of("OPERATOR_TASK_ADMIN"));account(OPERATOR,"uploader",List.of("OPERATOR_USER"));account(OWNER,"reviewer",List.of("OWNER_REVIEWER"));account(OWNER,"approver",List.of("OWNER_DATA_APPROVER"));account(OWNER,"approver-outsider",List.of("OWNER_DATA_APPROVER"));account(OPERATOR,"recipient",List.of("OPERATOR_USER"));account(OPERATOR,"org-admin-recipient",List.of("ORG_ADMIN"));account(OWNER,"owner-recipient",List.of("OWNER_REVIEWER"));
        for(Account a:accounts){a.token=string(expect("fixture_login","POST",API+"/auth/login",null,Map.of("username",a.username,"password",a.password),200).data().get("accessToken"));secrets.add(a.token);}
        var created=expect("register_fixture_device","POST",API+"/drones",admin(),Map.of("code","T020-"+runId.substring(0,12)),200).data();deviceId=string(object(created.get("device")).get("id"));secrets.add(string(created.get("deviceToken")));
    }
    private void account(String org,String suffix,List<String> roles){byte[] entropy=new byte[32];new SecureRandom().nextBytes(entropy);String password=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);secrets.add(password);Account a=new Account(UUID.randomUUID().toString(),"t020-"+runId.substring(0,12)+"-"+suffix,password);accounts.add(a);require(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",a.id,org,a.username,new BCryptPasswordEncoder(12).encode(password),JSON.writeValueAsString(roles))==1,"isolated_fixture_account_inserted");}
    private void createTask()throws Exception{
        Map<String,Object> task=expect("create_running_sharing_task","POST",API+"/tasks",owner(),Map.of("name","T020-"+runId,"scope","isolated exact version sharing","requirements",List.of(Map.of("name","inspection-photo","required",true))),200).data();taskId=string(task.get("id"));Map<String,Object> requirement=objects(task.get("requirements")).getFirst();requirementId=string(requirement.get("id"));evidenceId=string(requirement.get("evidenceId"));
        task=expect("assign_task","POST",taskPath()+"/assign",owner(),Map.of("operatorOrgId",OPERATOR,"operatorAdminId",admin().id,"expectedLockVersion",version(task)),200).data();task=expect("set_owner_members","PUT",taskPath()+"/members",owner(),Map.of("userIds",List.of(owner().id,reviewer().id,approver().id),"expectedLockVersion",version(task)),200).data();task=expect("set_operator_members","PUT",taskPath()+"/members",admin(),Map.of("userIds",List.of(admin().id,uploader().id),"expectedLockVersion",version(task)),200).data();task=expect("create_subtask","POST",taskPath()+"/subtasks",admin(),Map.of("name","sharing-flight","requirementIds",List.of(requirementId),"expectedLockVersion",version(task)),200).data();var sub=objects(task.get("subtasks")).getFirst();subtaskId=string(sub.get("id"));executionId=string(expect("create_execution","POST",API+"/subtasks/"+subtaskId+"/executions",admin(),Map.of("droneId",deviceId,"expectedLockVersion",version(sub)),200).data().get("id"));task=task();expect("start_task","POST",taskPath()+"/start",admin(),Map.of("expectedLockVersion",version(task)),200);
    }
    private void verifySharing()throws Exception{
        clients.operator.arm(evidenceId,1);Fixed first=upload(png(0x145aa1));require(clients.operator.awaitEntered(),"real_endorsement_paused_with_pending_anchor");
        assertNoVersionAccess(recipient(),first.id);Map<String,Object> originalApply=application(first.id,Instant.now().plusSeconds(900),"申请旧版巡检资料用于对比");Map<String,Object> primary=apply(recipient(),originalApply);
        require("PENDING".equals(primary.get("status")),"known_fixed_version_can_be_requested_before_review_or_anchor");Map<String,Object> duplicate=apply(recipient(),originalApply);require(primary.equals(duplicate),"same_application_key_and_content_returns_original_request");
        var conflict=new LinkedHashMap<>(originalApply);conflict.put("reason","different request content");expectCode("same_key_different_application_conflicts","POST",grants(),recipient(),conflict,409,"IDEMPOTENCY_CONFLICT");
        expect("unknown_version_application_forbidden","POST",grants(),recipient(),application(UUID.randomUUID().toString(),Instant.now().plusSeconds(600),"unknown"),403);expect("past_application_deadline_rejected","POST",grants(),recipient(),application(first.id,Instant.now().minusSeconds(1),"past"),400);
        assertGrantMetadata(primary);expect("pending_grant_does_not_allow_access","GET",versionPath(first.id),recipient(),null,403);expect("pending_grant_cannot_be_revoked","POST",grantPath(primary)+"/revoke",approver(),revokeBody(primary,"not yet approved"),409);
        Map<String,Object> approvedBody=approval(primary);expect("unreviewed_unanchored_version_cannot_be_approved","POST",grantPath(primary)+"/decision",approver(),approvedBody,409);
        expect("owner_task_admin_is_not_data_approver","POST",grantPath(primary)+"/decision",owner(),approvedBody,403);expect("operator_cannot_approve","POST",grantPath(primary)+"/decision",admin(),approvedBody,403);expect("data_approver_nonmember_cannot_approve","POST",grantPath(primary)+"/decision",accounts.get(5),approvedBody,403);
        review(first.id);expect("pending_chain_alone_cannot_be_approved","POST",grantPath(primary)+"/decision",approver(),approvedBody,409);clients.operator.release();awaitConfirmed(first.id);
        URI taskUrl=issueUrl(uploader(),first.id,null,300);getSigned(taskUrl,first.bytes,200,"task_member_download_without_a_grant");
        primary=approve(primary);assertPreciseRead(recipient(),first.id);URI issued=issueUrl(recipient(),first.id,primary,300);getSigned(issued,first.bytes,200,"first_shared_download");getUnsigned(issued);
        verifyStoredAndCopies(recipient(),first);
        Fixed newer=upload(png(0xd34822));awaitConfirmed(newer.id);expect("old_version_grant_does_not_extend_to_new_version","GET",versionPath(newer.id),recipient(),null,403);verifyVersionList(recipient(),Set.of(first.id));
        Map<String,Object> newGrant=apply(recipient(),application(newer.id,Instant.now().plusSeconds(900),"申请新版资料"));expect("confirmed_but_unreviewed_version_cannot_be_approved","POST",grantPath(newGrant)+"/decision",approver(),approval(newGrant),409);review(newer.id);newGrant=approve(newGrant);
        verifyVersionList(recipient(),Set.of(first.id,newer.id));
        Map<String,Object> adminGrant=verifyDecisionRaceAndRejected(first);
        assertPreciseRead(accounts.get(7),first.id);expect("org_admin_only_shared_user_has_no_task_access","GET",taskPath(),accounts.get(7),null,403);expect("same_org_shared_user_has_no_task_access","GET",taskPath(),recipient(),null,403);
        verifyRevocationDuringRead(first,primary,newGrant,issued);verifyExpiry(first);verifyDisableDuringRead(first,adminGrant);
        require("RUNNING".equals(task().get("status")),"sharing_does_not_require_task_completion");report.put("grantRowsCreated",count("SELECT COUNT(*) FROM access_grant WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",taskId));report.put("fixedVersions",2);report.put("negativeFixtureDisableOnly",true);
    }
    private void assertNoVersionAccess(Account actor,String id)throws Exception{
        expect("unapproved_version_detail_forbidden","GET",versionPath(id),actor,null,403);expect("unapproved_anchor_forbidden","GET",versionPath(id)+"/anchor",actor,null,403);expect("unapproved_download_forbidden","POST",versionPath(id)+"/download-url",actor,null,403);expect("unapproved_stored_verification_forbidden","POST",verifyPath(id)+"?source=STORED",actor,null,403);expect("unapproved_verification_history_forbidden","GET",verifyPath(id),actor,null,403);
        assertCode(copyRequest(actor,id,Files.readAllBytes(Path.of("examples/files/inspection-sample.png"))),403,"FORBIDDEN","unapproved_copy_verification");verifyVersionList(actor,Set.of());
    }
    private void assertPreciseRead(Account actor,String id)throws Exception{require(id.equals(expect("shared_exact_version_detail","GET",versionPath(id),actor,null,200).data().get("id")),"approved_grant_reads_exact_version");expect("shared_anchor_status","GET",versionPath(id)+"/anchor",actor,null,200);verifyVersionList(actor,Set.of(id));}
    private void verifyVersionList(Account actor,Set<String> expected)throws Exception{
        var page=expect("list_versions_filtered_before_pagination","GET",API+"/evidence-versions?taskId="+taskId+"&requirementId="+requirementId+"&page=1&pageSize=1",actor,null,200).data();require(number(page.get("total"))==expected.size(),"version_list_count_is_prefiltered_by_exact_authority");Set<String> seen=new LinkedHashSet<>();
        for(int p=1;p<=Math.max(1,expected.size()+1);p++){var result=expect("page_exact_shared_versions","GET",API+"/evidence-versions?taskId="+taskId+"&page="+p+"&pageSize=1",actor,null,200).data();for(var row:objects(result.get("items"))){require(expected.contains(row.get("id")),"version_page_never_leaks_an_ungranted_new_version");seen.add(string(row.get("id")));}}
        require(seen.equals(expected),"only_expected_precise_versions_are_listed");
    }
    private Map<String,Object> verifyDecisionRaceAndRejected(Fixed first)throws Exception{
        Account adminRecipient=accounts.get(7);Map<String,Object> requested=apply(adminRecipient,application(first.id,Instant.now().plusSeconds(900),"机构管理员个人申请精确旧版本"));
        var outcomes=race("concurrent_approve_or_reject",grantPath(requested)+"/decision",approver(),approval(requested),Map.of("decision","REJECTED","reason","并发拒绝意见","expectedLockVersion",version(requested)));
        assertOneWinner(outcomes,"decision_cas");Map<String,Object> winner=outcomes.stream().filter(r->r.status==200).findFirst().orElseThrow().data();
        Map<String,Object> active=winner;
        if("REJECTED".equals(winner.get("status"))){expect("rejected_grant_gives_no_version_access","GET",versionPath(first.id),adminRecipient,null,403);active=approve(apply(adminRecipient,application(first.id,Instant.now().plusSeconds(900),"重新申请独立授权")));}
        require("APPROVED".equals(active.get("status")),"org_admin_only_registered_user_can_receive_an_exact_version_grant");
        Map<String,Object> rejected=apply(accounts.get(8),application(first.id,Instant.now().plusSeconds(900),"单独拒绝样例"));
        expect("rejection_requires_reason","POST",grantPath(rejected)+"/decision",approver(),Map.of("decision","REJECTED","reason"," ","expectedLockVersion",version(rejected)),400);
        expect("approval_cannot_extend_requested_deadline","POST",grantPath(rejected)+"/decision",approver(),Map.of("decision","APPROVED","validUntil",Instant.parse(string(rejected.get("requestedValidUntil"))).plusSeconds(1).toString(),"expectedLockVersion",version(rejected)),400);
        Map<String,Object> denied=expect("formally_reject_request","POST",grantPath(rejected)+"/decision",approver(),Map.of("decision","REJECTED","reason","本次申请不符合用途范围","expectedLockVersion",version(rejected)),200).data();require("REJECTED".equals(denied.get("status")),"rejection_is_a_durable_safe_metadata_state");expect("rejected_applicant_cannot_read_file_metadata","GET",versionPath(first.id),accounts.get(8),null,403);
        expect("requester_cannot_revoke_own_approved_grant","POST",grantPath(active)+"/revoke",adminRecipient,revokeBody(active,"wrong role"),403);
        expect("nonmember_data_approver_cannot_list_task_requests","GET",grants()+"?taskId="+taskId,accounts.get(5),null,403);expect("owner_task_admin_cannot_list_approval_queue","GET",grants()+"?taskId="+taskId,owner(),null,403);expect("applicant_cannot_list_whole_task_requests","GET",grants()+"?taskId="+taskId,recipient(),null,403);
        Map<String,Object> mine=expect("applicant_lists_own_metadata_only","GET",grants()+"?mine=true&page=1&pageSize=20",adminRecipient,null,200).data();for(var row:objects(mine.get("items"))){assertGrantMetadata(row);require(adminRecipient.id.equals(row.get("applicantId")),"mine_scope_contains_only_current_applicant");}
        Map<String,Object> taskRequests=expect("task_approver_lists_authorized_queue","GET",grants()+"?taskId="+taskId+"&page=1&pageSize=2",approver(),null,200).data();require(number(taskRequests.get("total"))>=4&&objects(taskRequests.get("items")).size()==2,"task_request_pagination_is_authorized_and_bounded");for(var row:objects(taskRequests.get("items")))assertGrantMetadata(row);
        expect("grant_list_requires_scope","GET",grants(),recipient(),null,400);expect("grant_list_scopes_are_mutually_exclusive","GET",grants()+"?mine=true&taskId="+taskId,recipient(),null,400);return active;
    }
    private void verifyStoredAndCopies(Account actor,Fixed first)throws Exception{
        var stored=expect("shared_stored_actual_verification","POST",verifyPath(first.id)+"?source=STORED",actor,null,200).data();require("MATCH".equals(stored.get("result"))&&sha256(first.bytes).equals(stored.get("actualSha256")),"shared_stored_reads_real_private_bytes_and_ledger");
        Reply same=copyRequest(actor,first.id,first.bytes);assertCode(same,200,"OK","shared_copy_match");require("MATCH".equals(same.data().get("result")),"shared_same_copy_matches_real_ledger");byte[] changed=first.bytes.clone();changed[changed.length-9]^=1;Reply mismatch=copyRequest(actor,first.id,changed);assertCode(mismatch,200,"OK","shared_copy_mismatch");require("MISMATCH".equals(mismatch.data().get("result")),"shared_modified_copy_is_reported_as_mismatch");
        Map<String,Object> history=expect("shared_exact_version_verification_history","GET",verifyPath(first.id)+"?page=1&pageSize=20",actor,null,200).data();require(number(history.get("total"))==3,"shared_version_has_three_actual_verification_results");for(var row:objects(history.get("items")))require(first.id.equals(row.get("versionId")),"verification_history_is_scoped_to_the_granted_version");
    }
    private void verifyRevocationDuringRead(Fixed first,Map<String,Object> primary,Map<String,Object> newGrant,URI issued)throws Exception{
        long before=verificationCount(recipient(),first.id);armStorage(first.id);var inFlight=CompletableFuture.supplyAsync(()->{try{return request("shared_verification_revoke_race","POST",verifyPath(first.id)+"?source=STORED",recipient(),null,false);}catch(Exception failure){throw new ProbeFailure("verification_revoke_http_failed");}});
        require(storageGate.awaitEntered(),"real_stored_input_paused_for_grant_revocation");List<Reply> revokes=race("concurrent_revoke",grantPath(primary)+"/revoke",approver(),revokeBody(primary,"撤销用途授权"),revokeBody(primary,"并发撤销用途授权"));assertOneWinner(revokes,"revoke_cas");storageGate.release();assertCode(inFlight.get(30,TimeUnit.SECONDS),403,"FORBIDDEN","revoked_inflight_verification");
        require(verificationCount(recipient(),first.id)==before,"revoked_inflight_result_is_not_persisted");expect("revocation_prevents_new_url_issuance","POST",versionPath(first.id)+"/download-url",recipient(),null,403);expect("revocation_prevents_old_version_metadata","GET",versionPath(first.id),recipient(),null,403);expect("revocation_prevents_old_history","GET",verifyPath(first.id),recipient(),null,403);
        getSigned(issued,first.bytes,200,"previously_issued_url_keeps_its_original_deadline_after_revocation");verifyVersionList(recipient(),Set.of(string(newGrant.get("versionId"))));expect("independent_new_version_grant_remains_readable","GET",versionPath(string(newGrant.get("versionId"))),recipient(),null,200);
        report.put("revocationDuringIoPreventedPersistence",true);report.put("existingSignedUrlSurvivesGrantRevocationUntilItsOriginalExpiry",true);
    }
    private void verifyExpiry(Fixed first)throws Exception{
        Account actor=accounts.get(8);Map<String,Object> body=application(first.id,Instant.now().plusSeconds(8),"短期共享自然到期样例");Map<String,Object> shortGrant=approve(apply(actor,body));URI url=issueUrl(actor,first.id,shortGrant,8);getSigned(url,first.bytes,200,"short_shared_download_before_expiry");
        await(()->count("SELECT COUNT(*) FROM access_grant WHERE id=? AND valid_until<=CURRENT_TIMESTAMP(3)",shortGrant.get("id"))==1,"grant_naturally_expires_by_real_database_time");
        Map<String,Object> row=findGrant(actor,string(shortGrant.get("id")));require("EXPIRED".equals(row.get("status"))&&"APPROVED".equals(jdbc.queryForObject("SELECT status FROM access_grant WHERE id=?",String.class,shortGrant.get("id"))),"expired_authority_is_derived_from_database_deadline_without_status_rewrite");
        expect("expired_grant_cannot_read_version","GET",versionPath(first.id),actor,null,403);expect("expired_grant_cannot_issue_download","POST",versionPath(first.id)+"/download-url",actor,null,403);expect("expired_approved_grant_cannot_be_revoked","POST",grantPath(shortGrant)+"/revoke",approver(),revokeBody(shortGrant,"too late"),409);verifyVersionList(actor,Set.of());
        Map<String,Object> replay=apply(actor,body);require(shortGrant.get("id").equals(replay.get("id"))&&"EXPIRED".equals(replay.get("status")),"expired_request_replay_keeps_original_id_and_deadline");report.put("expiryUsesDatabaseDeadline",true);
    }
    private void verifyDisableDuringRead(Fixed first,Map<String,Object> activeGrant)throws Exception{
        Account actor=accounts.get(7);require("APPROVED".equals(findGrant(actor,string(activeGrant.get("id"))).get("status")),"disable_negative_fixture_begins_with_real_approved_grant");long before=verificationCount(actor,first.id);armStorage(first.id);
        var inFlight=CompletableFuture.supplyAsync(()->{try{return request("shared_verification_disable_race","POST",verifyPath(first.id)+"?source=STORED",actor,null,false);}catch(Exception failure){throw new ProbeFailure("verification_disable_http_failed");}});require(storageGate.awaitEntered(),"real_stored_input_paused_for_identity_disable");
        // Only this isolated account is disabled as a negative security fixture; no business success is written via SQL.
        require(jdbc.update("UPDATE app_user SET enabled=0 WHERE id=? AND username=?",actor.id,actor.username)==1,"disable_only_own_security_negative_fixture");storageGate.release();assertCode(inFlight.get(30,TimeUnit.SECONDS),401,"UNAUTHORIZED","disabled_inflight_identity");require(verificationCount(actor,first.id)==before,"disabled_identity_result_is_not_saved");expect("disabled_identity_cannot_read_granted_version","GET",versionPath(first.id),actor,null,401);report.put("identityDisableDuringIoPreventedPersistence",true);
    }
    private Map<String,Object> application(String version,Instant requested,String reason){return Map.of("clientRequestId",UUID.randomUUID().toString(),"versionId",version,"reason",reason,"requestedValidUntil",requested.toString());}
    private Map<String,Object> apply(Account actor,Map<String,Object> body)throws Exception{Map<String,Object> result=expect("apply_for_precise_version_metadata_only","POST",grants(),actor,body,200).data();assertGrantMetadata(result);return result;}
    private Map<String,Object> approve(Map<String,Object> grant)throws Exception{return expect("formally_approve_ready_version","POST",grantPath(grant)+"/decision",approver(),approval(grant),200).data();}
    private Map<String,Object> approval(Map<String,Object> grant){return Map.of("decision","APPROVED","validUntil",grant.get("requestedValidUntil"),"expectedLockVersion",version(grant));}
    private Map<String,Object> revokeBody(Map<String,Object> grant,String reason){return Map.of("reason",reason,"expectedLockVersion",version(grant));}
    private Map<String,Object> findGrant(Account actor,String id)throws Exception{return objects(expect("read_mine_request_metadata","GET",grants()+"?mine=true&page=1&pageSize=100",actor,null,200).data().get("items")).stream().filter(g->id.equals(g.get("id"))).findFirst().orElseThrow();}
    private void assertGrantMetadata(Map<String,Object> row){require(row.keySet().equals(GRANT_FIELDS)&&!row.containsKey("taskId")&&!row.containsKey("filename")&&!row.containsKey("sha256"),"grant_metadata_never_contains_file_or_task_metadata");}
    private URI issueUrl(Account actor,String version,Map<String,Object> grant,int maximum)throws Exception{
        Reply result=request("issue_shared_download_url","POST",versionPath(version)+"/download-url",actor,null,false);assertCode(result,200,"OK","issue_shared_download_url");require(result.data().keySet().equals(Set.of("url","expiresAt")),"download_response_contains_only_url_and_expiry");URI url=URI.create(string(result.data().get("url")));Map<String,String> query=new LinkedHashMap<>();for(String field:url.getRawQuery().split("&")){String[] pair=field.split("=",2);query.put(java.net.URLDecoder.decode(pair[0],StandardCharsets.UTF_8),java.net.URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}
        int seconds=Integer.parseInt(query.get("X-Amz-Expires"));Instant expires=Instant.parse(string(result.data().get("expiresAt")));Instant requested=grant==null?null:Instant.parse(string(grant.get("validUntil")));
        require(seconds>=1&&seconds<=maximum&&seconds<=300&&(requested==null||!expires.isAfter(requested)),"actual_sigv4_deadline_is_bounded_by_300_and_grant_remaining_lifetime");report.put(grant==null?"taskDownloadTtlSeconds":maximum==300?"longDownloadTtlSeconds":"shortDownloadTtlSeconds",seconds);return url;
    }
    private void getSigned(URI url,byte[] expected,int status,String name)throws Exception{
        var response=client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());requests.add(Map.of("case",name,"status",response.statusCode()));require(response.statusCode()==status,name+"_http_status");if(status==200)require(java.util.Arrays.equals(expected,response.body())&&sha256(expected).equals(sha256(response.body())),"actual_signed_get_bytes_and_full_sha256_match_private_original");
    }
    private void getUnsigned(URI url)throws Exception{URI unsigned=new URI(url.getScheme(),url.getAuthority(),url.getPath(),null,null);var response=client.send(HttpRequest.newBuilder(unsigned).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.discarding());requests.add(Map.of("case","unsigned_private_object_get","status",response.statusCode()));require(response.statusCode()==403,"same_private_object_rejects_unsigned_get");}
    private Reply copyRequest(Account actor,String version,byte[] bytes)throws Exception{String boundary="probe-"+UUID.randomUUID();var output=new ByteArrayOutputStream();output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"copy.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));output.write(bytes);output.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));return requestContent("shared_copy_verification","POST",verifyPath(version)+"?source=COPY",actor,output.toByteArray(),"multipart/form-data; boundary="+boundary);}
    private long verificationCount(Account actor,String version){return count("SELECT COUNT(*) FROM verification_record WHERE version_id=? AND user_id=?",version,actor.id);}
    private void armStorage(String version){storageGate.arm(jdbc.queryForObject("SELECT object_key FROM evidence_version WHERE id=?",String.class,version));}
    private Fixed upload(byte[] bytes)throws Exception{String upload=string(expect("create_png_upload","POST",API+"/upload-sessions",uploader(),Map.of("clientRequestId",UUID.randomUUID().toString(),"requirementId",requirementId,"executionId",executionId,"filename","inspection.png","contentType","image/png","size",bytes.length),200).data().get("uploadId"));uploadIds.add(upload);assertCode(request("upload_png_part","PUT",API+"/upload-sessions/"+upload+"/parts/1",uploader(),bytes,true),200,"OK","upload_png_part");expect("request_automatic_finalization","POST",API+"/upload-sessions/"+upload+"/complete",uploader(),null,202);await(()->"COMPLETED".equals(jdbc.queryForObject("SELECT status FROM upload_session WHERE id=?",String.class,upload)),"real_worker_fixes_png_version");String id=string(expect("read_fixed_version_id","GET",API+"/upload-sessions/"+upload,uploader(),null,200).data().get("versionId"));Fixed result=new Fixed(id,upload,bytes);fixed.add(result);return result;}
    private void review(String version)throws Exception{expect("formally_review_version","POST",versionPath(version)+"/review",reviewer(),Map.of("decision","APPROVED","expectedReviewStatus","PENDING"),200);}
    private void awaitConfirmed(String id)throws Exception{await(()->"CONFIRMED".equals(jdbc.queryForObject("SELECT status FROM chain_submission WHERE version_id=?",String.class,id)),"actual_fabric_confirmation_observed");}
    private List<Reply> race(String name,String path,Account actor,Object first,Object second)throws Exception{CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);List<CompletableFuture<Reply>> futures=new ArrayList<>();for(Object body:List.of(first,second))futures.add(CompletableFuture.supplyAsync(()->{try{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new ProbeFailure("race_timeout");return request(name,"POST",path,actor,body,false);}catch(Exception failure){throw new ProbeFailure("parallel_http_failure");}}));require(ready.await(5,TimeUnit.SECONDS),"concurrent_requests_ready");start.countDown();return List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));}
    private void assertOneWinner(List<Reply> replies,String name){require(replies.stream().filter(r->r.status==200).count()==1&&replies.stream().filter(r->r.status==409).count()==1,name+"_has_one_success_and_one_conflict");for(Reply reply:replies)assertCode(reply,reply.status,reply.status==200?"OK":"VERSION_CONFLICT",name);}
    private Map<String,Object> task()throws Exception{return expect("read_task","GET",taskPath(),admin(),null,200).data();}
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
        }report.put("confirmedVersions",ledgerRecords.size());require(ledgerRecords.size()==2,"two_real_fabric_registrations_are_retained");
    }
    private static byte[] blob(Object value)throws Exception{if(value instanceof byte[] bytes)return bytes;if(value instanceof java.sql.Blob blob)return blob.getBytes(1,(int)blob.length());throw new ProbeFailure("expected_binary_checkpoint");}
    private long ownOutboxPending(){return count("SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT' AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)))",taskId,taskId);}
    private Set<String> objectKeys(String upload){Set<String> keys=new LinkedHashSet<>();for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")){String continuation=null;do{var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(continuation).build());page.contents().forEach(o->keys.add(o.key()));continuation=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;}while(continuation!=null);}return keys;}
    private void cleanupFixtures(){
        Set<String> tasks=new LinkedHashSet<>();for(Account a:accounts)tasks.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=?",String.class,a.id));for(String task:tasks)uploadIds.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=?",String.class,task));Set<String> keys=new LinkedHashSet<>();for(String upload:uploadIds)keys.addAll(objectKeys(upload));for(String key:keys)storage.delete(key);
        for(String task:tasks){jdbc.update("DELETE FROM access_grant WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM issue WHERE task_id=?",task);jdbc.update("DELETE FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",task);jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            List<String> events=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",String.class,task,task);for(String event:events){jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}
            jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT id FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?))",task);jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);
            jdbc.update("DELETE FROM device_event WHERE execution_id IN (SELECT id FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?))",task);jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);}
        jdbc.update("DELETE FROM drone WHERE operator_org_id=? AND code=?",OPERATOR,"T020-"+runId.substring(0,12));for(Account a:accounts)jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",a.id,a.username);for(String upload:uploadIds)require(objectKeys(upload).isEmpty(),"only_owned_parts_final_and_unreferenced_candidates_removed");
        report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());report.put("fixtureDevicesDeleted",1);report.put("fixtureUploadSessionsDeleted",uploadIds.size());report.put("fixturePrivateObjectsDeleted",keys.size());report.put("fabricRecordsRetainedAppendOnly",true);
    }
    private void cleanupTopology(){rabbit.execute(channel->{for(String queue:List.of(finalizeQueue,anchorQueue,deadQueue)){var state=channel.queueDeclarePassive(queue);require(state.getMessageCount()==0&&state.getConsumerCount()==0,"only_empty_unused_owned_queue_deleted");channel.queueDelete(queue,true,true);}channel.exchangeDelete(exchange,true);channel.exchangeDelete(deadExchange,true);return null;});report.put("ownedRabbitTopologyDeleted",true);}
    private void verifyAudit(){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM audit_log WHERE trace_id LIKE ? ORDER BY created_at,id","t020-"+runId+"-%");require(!rows.isEmpty(),"formal_sharing_flow_retains_append_only_audit");for(var row:rows){String text=String.valueOf(row);require(secrets.stream().noneMatch(text::contains)&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("X-Amz-")&&!text.contains("Signature="),"audit_contains_no_credentials_storage_keys_or_signed_url");}require(count("SELECT COUNT(*) FROM audit_log WHERE trace_id LIKE ? AND action='URL_ISSUED' AND resource_type='EVIDENCE_VERSION'","t020-"+runId+"-%")==3,"three_successful_task_and_shared_url_issuances_are_audited_not_download_success");require(count("SELECT COUNT(*) FROM audit_log WHERE trace_id LIKE ? AND action='ACCESS_GRANT_REVOKED'","t020-"+runId+"-%")==1,"revoke_cas_winner_has_one_success_audit");report.put("auditRowsRetained",rows.size());}
    private Reply expect(String name,String method,String path,Account actor,Object body,int status)throws Exception{return expectCode(name,method,path,actor,body,status,status==200||status==202?"OK":status==400?"INVALID_ARGUMENT":status==401?"UNAUTHORIZED":status==403?"FORBIDDEN":"VERSION_CONFLICT");}
    private Reply expectCode(String name,String method,String path,Account actor,Object body,int status,String code)throws Exception{Reply result=request(name,method,path,actor,body,false);assertCode(result,status,code,name);return result;}
    private void assertCode(Reply reply,int status,String code,String name){require(reply.status==status,name+"_http_"+status);require(code.equals(reply.body.get("code")),name+"_code");}
    private Reply request(String name,String method,String path,Account actor,Object body,boolean raw)throws Exception{return requestContent(name,method,path,actor,body==null?null:raw?(byte[])body:JSON.writeValueAsBytes(body),body==null?null:raw?"application/octet-stream":"application/json");}
    private Reply requestContent(String name,String method,String path,Account actor,byte[] bytes,String contentType)throws Exception{
        String trace="t020-"+runId+"-"+sequence.incrementAndGet();var builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(35)).header("X-Trace-Id",trace).header("Accept","application/json");if(actor!=null&&actor.token!=null)builder.header("Authorization","Bearer "+actor.token);if(contentType!=null)builder.header("Content-Type",contentType);builder.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());requests.add(Map.of("case",name,"status",response.statusCode(),"traceId",trace));require(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),name+"_json");
        boolean credential=path.equals(API+"/auth/login")||path.equals(API+"/drones")&&method.equals("POST"),signed=path.endsWith("/download-url")&&response.statusCode()==200;String text=new String(response.body(),StandardCharsets.UTF_8);if(!signed)require((credential||secrets.stream().noneMatch(text::contains))&&!text.contains("objectKey")&&!text.contains("object_key")&&!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("transactionBytes")&&!text.contains("commitBytes"),name+"_response_excludes_private_provider_data");else require("no-store".equals(response.headers().firstValue("Cache-Control").orElse(null)),"signed_url_response_is_not_cacheable");
        Map<String,Object> envelope=JSON.readValue(response.body(),Map.class);require(envelope.keySet().equals(Set.of("code","message","data","traceId")),name+"_uniform_envelope");require(trace.equals(envelope.get("traceId"))&&trace.equals(response.headers().firstValue("X-Trace-Id").orElse(null)),name+"_trace_propagated");return new Reply(response.statusCode(),envelope);
    }
    private void writeReport()throws Exception{report.put("caseCount",requests.size());report.put("checkCount",checks.size());byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);String text=new String(bytes,StandardCharsets.UTF_8);if(secrets.stream().anyMatch(text::contains)||text.contains("upload-parts/")||text.contains("evidence-files/")||text.contains("X-Amz-")||text.contains("Signature="))throw new ProbeFailure("unsafe_report");Files.createDirectories(reportPath.getParent());Path temporary=Files.createTempFile(reportPath.getParent(),".sharing-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.write(temporary,bytes);Files.move(temporary,reportPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    private void require(boolean value,String name){checks.add(Map.of("check",name,"passed",value));if(!value)throw new ProbeFailure(name);}
    private Account owner(){return accounts.getFirst();}private Account admin(){return accounts.get(1);}private Account uploader(){return accounts.get(2);}private Account reviewer(){return accounts.get(3);}private Account approver(){return accounts.get(4);}private Account recipient(){return accounts.get(6);}private String taskPath(){return API+"/tasks/"+taskId;}private static String versionPath(String id){return API+"/evidence-versions/"+id;}private static String verifyPath(String id){return versionPath(id)+"/verifications";}private static String grants(){return API+"/access-grants";}private static String grantPath(Map<String,Object> row){return grants()+"/"+string(row.get("id"));}
    private static String env(String key,String fallback){String value=System.getenv(key);if(value==null||value.isBlank()){if(fallback==null)throw new ProbeFailure("missing_required_environment");return fallback;}return value;}private static long version(Map<String,Object> row){return number(row.get("lockVersion"));}private static long number(Object value){if(!(value instanceof Number n))throw new ProbeFailure("expected_number");return n.longValue();}private static String string(Object value){if(!(value instanceof String text))throw new ProbeFailure("expected_string");return text;}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new ProbeFailure("expected_object");return (Map<String,Object>)value;}private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new ProbeFailure("expected_array");return list.stream().map(SharingFlowProbe::object).toList();}private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private static byte[] png(int color)throws Exception{var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);for(int x=0;x<8;x++)for(int y=0;y<8;y++)image.setRGB(x,y,color);var output=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",output))throw new ProbeFailure("png_encoder_unavailable");return output.toByteArray();}
    private static final class Account{final String id,username,password;String token;Account(String id,String username,String password){this.id=id;this.username=username;this.password=password;}@Override public String toString(){return "SharingAccount["+id+"]";}}private record Fixed(String id,String upload,byte[] bytes){}private record Reply(int status,Map<String,Object> body){Map<String,Object> data(){return object(body.get("data"));}@Override public String toString(){return "SharingReply["+status+"]";}}private static final class ProbeFailure extends RuntimeException{ProbeFailure(String name){super(name);}}
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
    @TestConfiguration(proxyBeanMethods=false) @Profile("sharing-probe")
    static class GateConfiguration{
        @Bean @Primary GateStorage sharingGateStorage(@Qualifier("objectStorage") ObjectStorage real){return new GateStorage(real);}
        @Bean(destroyMethod="") @Primary GateClients sharingGateClients(@Qualifier("institutionLedgerClients") InstitutionLedgerClients real){return new GateClients(real);}
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
