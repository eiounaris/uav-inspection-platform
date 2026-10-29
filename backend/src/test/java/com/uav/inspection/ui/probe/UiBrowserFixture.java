package com.uav.inspection.ui.probe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.FabricGatewaySettings;
import com.uav.inspection.blockchain.GatewayEvidenceLedgerClient;
import com.uav.inspection.identity.Role;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Explicit browser-test support: seed isolated identities, inspect real results, then clean own fixtures. */
public final class UiBrowserFixture {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String OWNER="00000000-0000-4000-8000-000000000001",OPERATOR="00000000-0000-4000-8000-000000000002";
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Map<String,String> TABLES=new LinkedHashMap<>();
    static{String[][] entries={{"organization","id"},{"app_user","id"},{"inspection_task","id"},{"task_member","task_id,user_id"},{"drone","id"},{"inspection_subtask","id"},{"material_requirement","id"},{"task_execution","id"},{"upload_session","id"},{"upload_part","session_id,part_no"},{"evidence_version","id"},{"outbox_event","event_id"},{"consumed_event","consumer_code,event_id"},{"chain_submission","id"},{"chain_attempt","id"},{"verification_record","id"},{"device_event","id"},{"execution_adjustment","id"},{"issue","id"},{"review_record","id"},{"access_grant","id"},{"message_rejection","id"},{"anchor_retry_request","id"},{"storage_cleanup_task","id"}};for(var value:entries)TABLES.put(value[0],value[1]);}
    private final Path checkpointPath;
    private final JdbcTemplate jdbc;
    private final DataSource source;
    private Map<String,Object> checkpoint;

    private UiBrowserFixture(Path path)throws Exception{
        checkpointPath=path.toAbsolutePath().normalize();
        Path local=Path.of(".local").toAbsolutePath().normalize();
        require(checkpointPath.startsWith(local)&&Files.isDirectory(checkpointPath.getParent())&&!Files.isSymbolicLink(checkpointPath),"private_checkpoint_required");
        require((Files.getPosixFilePermissions(checkpointPath.getParent()).stream().noneMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_"))),"private_checkpoint_directory_required");
        var database=new DriverManagerDataSource();database.setUrl("jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/"+env("DB_NAME","uav_inspection")+"?sslMode="+env("DB_SSL_MODE","REQUIRED")+"&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8");database.setUsername(env("DB_USERNAME","uav_app"));database.setPassword(env("DB_PASSWORD",null));source=database;jdbc=new JdbcTemplate(database);jdbc.setQueryTimeout(10);
    }
    public static void main(String[] args){
        if(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
        try{
            require(args.length==2&&Set.of("create","inspect","cleanup").contains(args[0]),"expected_phase_and_checkpoint");
            var helper=new UiBrowserFixture(Path.of(args[1]));
            Map<String,Object> result=switch(args[0]){case "create"->helper.create();case "inspect"->helper.inspect();case "cleanup"->helper.cleanup();default->throw new CheckFailure("invalid_phase");};
            System.out.println(JSON.writeValueAsString(result));
        }catch(Exception failure){try{System.out.println(JSON.writeValueAsString(Map.of("passed",false,"failureType",failure.getClass().getSimpleName(),"failedCheck",failure instanceof CheckFailure?failure.getMessage():"safe_fixture_operation_failed")));}catch(Exception ignored){System.err.println("Fixture failed");}System.exit(1);}
    }
    private Map<String,Object> create()throws Exception{
        require(!Files.exists(checkpointPath),"checkpoint_already_exists");
        require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL")==14,"fourteen_existing_migrations_required");
        require(count("SELECT COUNT(*) FROM upload_session WHERE status='VERIFYING'")==0&&count("SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','SENDING')")==0&&count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=1 AND next_action_at IS NOT NULL)")==0,"no_pending_business_before_browser_fixture");
        byte[] input=System.in.readNBytes(65537);require(input.length<=65536,"fixture_input_too_large");
        Map<String,Object> payload=object(JSON.readValue(input,Map.class));List<Map<String,Object>> accounts=objects(payload.get("accounts"));require(accounts.size()>=3&&accounts.size()<=20,"fixture_account_count");
        Set<String> ids=new HashSet<>(),usernames=new HashSet<>();List<Map<String,Object>> safeAccounts=new ArrayList<>();
        for(var account:accounts){
            String id=string(account.get("id")),username=string(account.get("username")),password=string(account.get("password")),org=string(account.get("orgId"));
            require(id.matches(UUID)&&ids.add(id)&&username.matches("[A-Za-z0-9_-]{1,64}")&&usernames.add(username)&&Set.of(OWNER,OPERATOR).contains(org),"invalid_fixture_identity");
            require(password.getBytes(StandardCharsets.UTF_8).length>=24&&password.getBytes(StandardCharsets.UTF_8).length<=72,"invalid_fixture_password_size");
            List<String> roles=strings(account.get("roles"));require(!roles.isEmpty()&&roles.size()<=Role.values().length&&new HashSet<>(roles).size()==roles.size(),"invalid_fixture_roles");
            for(String role:roles)require(Role.valueOf(role).isAllowedFor(org),"invalid_fixture_role_org");
            require(count("SELECT COUNT(*) FROM app_user WHERE id=? OR username=?",id,username)==0,"fixture_identity_must_be_new");
            safeAccounts.add(Map.of("id",id,"username",username,"orgId",org,"roles",roles));
        }
        checkpoint=new LinkedHashMap<>();checkpoint.put("formatVersion",1);checkpoint.put("createdAt",Instant.now().toString());checkpoint.put("snapshotDomain","T028-original-twenty-four-fields-and-blobs-v1");checkpoint.put("snapshotSha256",snapshot());checkpoint.put("tableCountsBefore",counts());checkpoint.put("accounts",safeAccounts);checkpoint.put("demoCredentialsSha256",fileHash(Path.of(env("DEMO_CREDENTIALS_PATH",".local/demo-credentials.json"))));checkpoint.put("envSha256",fileHash(Path.of(".env")));writeCheckpoint();
        try(Connection connection=source.getConnection()){
            connection.setAutoCommit(false);
            try{
                var encoder=new BCryptPasswordEncoder(12);
                try(var insert=connection.prepareStatement("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))")){
                    for(var account:accounts){insert.setString(1,string(account.get("id")));insert.setString(2,string(account.get("orgId")));insert.setString(3,string(account.get("username")));insert.setString(4,encoder.encode(string(account.get("password"))));insert.setString(5,JSON.writeValueAsString(account.get("roles")));require(insert.executeUpdate()==1,"fixture_identity_inserted_once");}
                }
                connection.commit();
            }catch(Exception failure){connection.rollback();throw failure;}
        }
        checkpoint.put("accountsCreated",true);writeCheckpoint();
        return Map.of("passed",true,"phase","create","accountCount",safeAccounts.size(),"accounts",safeAccounts,"originalBusinessSnapshotSha256",checkpoint.get("snapshotSha256"),"migrations",14,"tableCountsBefore",checkpoint.get("tableCountsBefore"));
    }
    private void readCheckpoint()throws Exception{
        require(Files.isRegularFile(checkpointPath,LinkOption.NOFOLLOW_LINKS),"fixture_checkpoint_missing");checkpoint=object(JSON.readValue(Files.readAllBytes(checkpointPath),Map.class));require(number(checkpoint.get("formatVersion"))==1,"fixture_checkpoint_version");
        for(var account:objects(checkpoint.get("accounts")))require(string(account.get("id")).matches(UUID),"invalid_checkpoint_account");
        require(fileHash(Path.of(env("DEMO_CREDENTIALS_PATH",".local/demo-credentials.json"))).equals(checkpoint.get("demoCredentialsSha256"))&&fileHash(Path.of(".env")).equals(checkpoint.get("envSha256")),"original_credentials_and_env_unchanged");
    }
    private List<String> accountIds(){return objects(checkpoint.get("accounts")).stream().map(value->string(value.get("id"))).toList();}
    private List<String> taskIds(){List<String> result=new ArrayList<>();for(String id:accountIds())result.addAll(jdbc.queryForList("SELECT id FROM inspection_task WHERE created_by=? ORDER BY id",String.class,id));return result;}
    private List<String> uploadIds(List<String> tasks){List<String> result=new ArrayList<>();for(String task:tasks)result.addAll(jdbc.queryForList("SELECT id FROM upload_session WHERE task_id=? ORDER BY id",String.class,task));return result;}
    private List<String> deviceIds(){Set<String> result=new LinkedHashSet<>();for(String id:accountIds())result.addAll(jdbc.queryForList("SELECT resource_id FROM audit_log WHERE actor_id=? AND action='DEVICE_CREATED' AND resource_type='DRONE' ORDER BY created_at,id",String.class,id));return List.copyOf(result);}
    private Map<String,Object> inspect()throws Exception{
        readCheckpoint();List<String> tasks=taskIds(),uploads=uploadIds(tasks);Map<String,Object> result=new LinkedHashMap<>();result.put("phase","inspect");result.put("tasks",new ArrayList<Map<String,Object>>());result.put("uploads",new ArrayList<Map<String,Object>>());result.put("parts",new ArrayList<Map<String,Object>>());result.put("versions",new ArrayList<Map<String,Object>>());result.put("grants",new ArrayList<Map<String,Object>>());result.put("verifications",new ArrayList<Map<String,Object>>());result.put("reviews",new ArrayList<Map<String,Object>>());result.put("devices",deviceIds());
        for(String task:tasks){
            var row=jdbc.queryForMap("SELECT id,status,lock_version,CAST(completed_at AS CHAR) AS completed_at FROM inspection_task WHERE id=?",task);objects(result.get("tasks")).add(row);
            objects(result.get("uploads")).addAll(jdbc.queryForList("SELECT id,task_id,requirement_id,execution_id,actor_id,client_request_id,expected_size,part_size,part_count,status FROM upload_session WHERE task_id=? ORDER BY created_at,id",task));
            objects(result.get("parts")).addAll(jdbc.queryForList("SELECT p.session_id,p.part_no,p.size,p.sha256,CAST(p.created_at AS CHAR) AS created_at FROM upload_part p JOIN upload_session u ON u.id=p.session_id WHERE u.task_id=? ORDER BY p.session_id,p.part_no",task));
            objects(result.get("grants")).addAll(jdbc.queryForList("SELECT g.id,g.version_id,g.applicant_id,g.status,g.lock_version,CAST(g.valid_until AS CHAR) AS valid_until FROM access_grant g JOIN evidence_version v ON v.id=g.version_id WHERE v.task_id=? ORDER BY g.created_at,g.id",task));
            objects(result.get("verifications")).addAll(jdbc.queryForList("SELECT r.id,r.version_id,r.user_id,r.source,r.result,r.actual_sha256,r.ledger_sha256,r.actual_size FROM verification_record r JOIN evidence_version v ON v.id=r.version_id WHERE v.task_id=? ORDER BY r.verified_at,r.id",task));
            objects(result.get("reviews")).addAll(jdbc.queryForList("SELECT r.id,r.evidence_version_id,r.decision,r.reviewer_id FROM review_record r JOIN evidence_version v ON v.id=r.evidence_version_id WHERE v.task_id=? ORDER BY r.created_at,r.id",task));
        }
        List<Map<String,Object>> versions=new ArrayList<>();for(String task:tasks)versions.addAll(jdbc.queryForList("SELECT v.id,v.task_id,v.evidence_id,v.version_no,v.sha256,v.size,v.review_status,v.upload_session_id,c.id AS submission_id,c.status AS anchor_status,c.confirmed_tx_id FROM evidence_version v LEFT JOIN chain_submission c ON c.version_id=v.id WHERE v.task_id=? ORDER BY v.version_no,v.id",task));
        try(S3Client s3=s3()){
            GatewayEvidenceLedgerClient owner=null,operator=null;
            try{
                for(var value:versions){
                    String version=string(value.get("id")),key=jdbc.queryForObject("SELECT object_key FROM evidence_version WHERE id=?",String.class,version);
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;
                    try(InputStream input=s3.getObject(builder->builder.bucket(bucket()).key(key))){byte[] buffer=new byte[65536];for(int read;(read=input.read(buffer))!=-1;){digest.update(buffer,0,read);bytes+=read;}}
                    String hash=HexFormat.of().formatHex(digest.digest());require(bytes==number(value.get("size"))&&hash.equals(value.get("sha256")),"actual_version_bytes_match_database");value.put("objectBytesVerified",true);
                    List<Map<String,Object>> attempts=jdbc.queryForList("SELECT tx_id,phase,validation_code,block_number,transaction_bytes,commit_bytes FROM chain_attempt WHERE submission_id=? ORDER BY attempt_no",value.get("submission_id"));
                    value.put("attemptCount",attempts.size());
                    if("CONFIRMED".equals(value.get("anchor_status"))){
                        if(owner==null){owner=GatewayEvidenceLedgerClient.connect(FabricGatewaySettings.fromEnvironment(System.getenv(),"ORGA"));operator=GatewayEvidenceLedgerClient.connect(FabricGatewaySettings.fromEnvironment(System.getenv(),"ORGB"));}
                        String tx=string(value.get("confirmed_tx_id"));var a=owner.queryEvidence(string(value.get("evidence_id")),(int)number(value.get("version_no")));var b=operator.queryEvidence(string(value.get("evidence_id")),(int)number(value.get("version_no")));
                        require(a.found()&&a.equals(b)&&value.get("evidence_id").equals(a.record().evidenceId())&&number(value.get("version_no"))==a.record().version()&&tx.equals(a.record().registeredTxId())&&hash.equals(a.record().fileHash())&&a.record().submitterMsp().equals(env("FABRIC_ORGB_MSP_ID","Org2MSP")),"both_institutions_match_actual_version");value.put("bothInstitutionsAgree",true);
                        value.put("ledgerRecord",Map.of("evidenceId",a.record().evidenceId(),"version",a.record().version(),"fileHash",a.record().fileHash(),"submitterMsp",a.record().submitterMsp(),"registeredTxId",a.record().registeredTxId()));
                        var attempt=attempts.stream().filter(row->tx.equals(row.get("tx_id"))).findFirst().orElseThrow(()->new CheckFailure("confirmed_transaction_attempt_missing"));
                        value.put("attemptPhase",attempt.get("phase"));value.put("transactionCheckpointSha256",hash(blob(attempt.get("transaction_bytes"))));value.put("commitCheckpointPresent",attempt.get("commit_bytes")!=null);
                        if(attempt.get("commit_bytes")!=null){var outcome=operator.queryCommit(new EvidenceLedgerClient.CommitCheckpoint(tx,blob(attempt.get("commit_bytes"))));require(outcome.valid()&&outcome.validationCode()==0&&tx.equals(outcome.transactionId()),"original_commit_requeries_valid");value.put("validationCode",outcome.validationCode());value.put("blockNumber",outcome.blockNumber());value.put("commitCheckpointSha256",hash(blob(attempt.get("commit_bytes"))));}
                    }
                    objects(result.get("versions")).add(value);
                }
            }finally{if(owner!=null)owner.close();if(operator!=null)operator.close();}
        }
        long pendingUploads=0,pendingAnchors=0,pendingMessages=0;
        for(String task:tasks){
            pendingUploads+=count("SELECT COUNT(*) FROM upload_session WHERE task_id=? AND (status='VERIFYING' OR lease_until>UTC_TIMESTAMP(3))",task);
            pendingAnchors+=count("SELECT COUNT(*) FROM chain_submission c JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=? AND (c.status IN ('PENDING','SUBMITTED','UNKNOWN') OR (c.status='FAILED' AND c.retryable=1 AND c.next_action_at IS NOT NULL) OR c.lease_until>UTC_TIMESTAMP(3))",task);
            pendingMessages+=count("SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','SENDING') AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT c.id FROM chain_submission c JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=?))",task,task);
        }
        result.put("pendingWork",Map.of("uploads",pendingUploads,"anchors",pendingAnchors,"messages",pendingMessages));
        result.put("taskCount",tasks.size());result.put("uploadCount",uploads.size());result.put("versionCount",versions.size());result.put("currentTableCounts",counts());result.put("passed",true);result.put("scope","SELECT-only fixture inspection, actual private-object GET and two-institution ledger queries; no success state is written.");return result;
    }
    private Map<String,Object> cleanup()throws Exception{
        readCheckpoint();require(!Boolean.TRUE.equals(checkpoint.get("cleaned")),"fixture_already_cleaned");requireNoBackend();List<String> tasks=taskIds(),uploads=uploadIds(tasks),devices=deviceIds();
        for(String task:tasks){
            require(count("SELECT COUNT(*) FROM upload_session WHERE task_id=? AND (status='VERIFYING' OR lease_until>UTC_TIMESTAMP(3))",task)==0,"upload_work_not_quiescent");
            require(count("SELECT COUNT(*) FROM chain_submission c JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=? AND (c.status IN ('PENDING','SUBMITTED','UNKNOWN') OR (c.status='FAILED' AND c.retryable=1 AND c.next_action_at IS NOT NULL) OR c.lease_until>UTC_TIMESTAMP(3))",task)==0,"anchor_work_not_quiescent");
            require(count("SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','SENDING') AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT c.id FROM chain_submission c JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=?))",task,task)==0,"message_work_not_quiescent");
        }
        Set<String> keys=new LinkedHashSet<>();try(S3Client s3=s3()){
            for(String upload:uploads)for(String prefix:List.of("upload-parts/"+upload+"/","evidence-files/"+upload+"/")){String continuation=null;do{var page=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket()).prefix(prefix).continuationToken(continuation).build());for(var value:page.contents()){require(value.key().startsWith(prefix),"cleanup_inventory_scope");keys.add(value.key());}continuation=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;}while(continuation!=null);}
            for(String key:keys)s3.deleteObject(builder->builder.bucket(bucket()).key(key));
        }
        for(String id:accountIds()){jdbc.update("DELETE FROM access_grant WHERE applicant_id=?",id);jdbc.update("DELETE FROM verification_record WHERE user_id=?",id);}
        for(String upload:uploads)jdbc.update("DELETE FROM storage_cleanup_task WHERE upload_id=?",upload);
        for(String task:tasks){
            jdbc.update("DELETE FROM anchor_retry_request WHERE submission_id IN (SELECT c.id FROM chain_submission c JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=?)",task);jdbc.update("DELETE FROM access_grant WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM review_record WHERE evidence_version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM issue WHERE task_id=?",task);jdbc.update("DELETE FROM execution_adjustment WHERE task_id=?",task);jdbc.update("DELETE FROM verification_record WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);
            List<String> events=jdbc.queryForList("SELECT event_id FROM outbox_event WHERE biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT c.id FROM chain_submission c JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=?)",String.class,task,task);for(String event:events){jdbc.update("DELETE FROM consumed_event WHERE event_id=?",event);jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);}
            jdbc.update("DELETE FROM chain_attempt WHERE submission_id IN (SELECT c.id FROM chain_submission c JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=?)",task);jdbc.update("DELETE FROM chain_submission WHERE version_id IN (SELECT id FROM evidence_version WHERE task_id=?)",task);jdbc.update("DELETE FROM evidence_version WHERE task_id=?",task);jdbc.update("DELETE FROM upload_part WHERE session_id IN (SELECT id FROM upload_session WHERE task_id=?)",task);jdbc.update("DELETE FROM upload_session WHERE task_id=?",task);jdbc.update("DELETE FROM device_event WHERE execution_id IN (SELECT e.id FROM task_execution e JOIN inspection_subtask s ON s.id=e.subtask_id WHERE s.task_id=?)",task);jdbc.update("UPDATE inspection_subtask SET current_execution_id=NULL WHERE task_id=?",task);jdbc.update("DELETE FROM task_execution WHERE subtask_id IN (SELECT id FROM inspection_subtask WHERE task_id=?)",task);jdbc.update("DELETE FROM material_requirement WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_subtask WHERE task_id=?",task);jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);
        }
        for(String device:devices)jdbc.update("DELETE FROM drone WHERE id=?",device);
        long audits=0;for(String id:accountIds()){audits+=count("SELECT COUNT(*) FROM audit_log WHERE actor_id=?",id);jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",id,objects(checkpoint.get("accounts")).stream().filter(a->id.equals(a.get("id"))).findFirst().orElseThrow().get("username"));}
        boolean unchanged=snapshot().equals(checkpoint.get("snapshotSha256"));require(unchanged,"original_twenty_four_table_fields_and_blobs_preserved");checkpoint.put("cleaned",true);checkpoint.put("cleanedAt",Instant.now().toString());writeCheckpoint();
        return Map.of("passed",true,"phase","cleanup","originalBusinessDataUnchanged",true,"originalSnapshotSha256",checkpoint.get("snapshotSha256"),"fixtureTasksDeleted",tasks.size(),"fixtureAccountsDeleted",accountIds().size(),"fixtureDevicesDeleted",devices.size(),"fixtureUploadSessionsDeleted",uploads.size(),"fixturePrivateObjectsDeleted",keys.size(),"fixtureActorAuditRowsRetained",audits);
    }
    private static void requireNoBackend(){
        long self=ProcessHandle.current().pid();boolean running=ProcessHandle.allProcesses().filter(p->p.pid()!=self).anyMatch(p->Arrays.stream(p.info().arguments().orElse(new String[0])).anyMatch(a->a.contains("inspection-backend")||a.startsWith("com.uav.inspection.")));require(!running,"stop_project_backend_and_business_probes_before_cleanup");
    }
    private static S3Client s3(){return S3Client.builder().endpointOverride(URI.create(env("S3_ENDPOINT",null))).region(Region.of(env("S3_REGION","garage"))).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(env("S3_ACCESS_KEY",null),env("S3_SECRET_KEY",null)))).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(Boolean.parseBoolean(env("S3_FORCE_PATH_STYLE","true"))).chunkedEncodingEnabled(false).build()).requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED).httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30))).overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(45)).apiCallAttemptTimeout(Duration.ofSeconds(30))).build();}
    private static String bucket(){return env("S3_BUCKET","uav-evidence");}
    private Map<String,Long> counts(){Map<String,Long> result=new LinkedHashMap<>();TABLES.keySet().forEach(table->result.put(table,count("SELECT COUNT(*) FROM "+table)));return result;}
    private long count(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);require(value!=null,"missing_sql_count");return value;}
    private String snapshot()throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(Connection connection=source.getConnection()){for(var entry:TABLES.entrySet()){frame(digest,entry.getKey());try(var statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT * FROM "+entry.getKey()+" ORDER BY "+entry.getValue())){while(rows.next()){digest.update((byte)1);for(int column=1;column<=rows.getMetaData().getColumnCount();column++){int type=rows.getMetaData().getColumnType(column);if(Set.of(java.sql.Types.BINARY,java.sql.Types.VARBINARY,java.sql.Types.LONGVARBINARY,java.sql.Types.BLOB).contains(type)){byte[] bytes=rows.getBytes(column);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}else frame(digest,rows.getString(column));}}digest.update((byte)0);}}}return HexFormat.of().formatHex(digest.digest());}
    private void writeCheckpoint()throws Exception{String value=JSON.writerWithDefaultPrettyPrinter().writeValueAsString(checkpoint);require(!value.contains("password")&&!value.contains("accessToken")&&!value.contains("deviceToken")&&!value.contains("upload-parts/")&&!value.contains("evidence-files/"),"unsafe_fixture_checkpoint");Path temporary=Files.createTempFile(checkpointPath.getParent(),".ui-fixture-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.writeString(temporary,value);Files.move(temporary,checkpointPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    private static String fileHash(Path path)throws Exception{return hash(Files.readAllBytes(path));}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private static byte[] blob(Object value)throws Exception{if(value instanceof byte[] bytes)return bytes;if(value instanceof java.sql.Blob blob)return blob.getBytes(1,(int)blob.length());throw new CheckFailure("missing_checkpoint_bytes");}
    private static String env(String name,String fallback){String value=System.getenv(name);if(value==null||value.isBlank()){require(fallback!=null,"missing_fixture_environment");return fallback;}return value;}
    private static String string(Object value){require(value instanceof String,"expected_string");return (String)value;}
    private static long number(Object value){require(value instanceof Number,"expected_number");return ((Number)value).longValue();}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){require(value instanceof Map,"expected_object");return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> objects(Object value){require(value instanceof List,"expected_object_list");return (List<Map<String,Object>>)value;}
    @SuppressWarnings("unchecked") private static List<String> strings(Object value){require(value instanceof List,"expected_string_list");return (List<String>)value;}
    private static void require(boolean value,String name){if(!value)throw new CheckFailure(name);}
    private static final class CheckFailure extends RuntimeException{CheckFailure(String name){super(name);}}
}
