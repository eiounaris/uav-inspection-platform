package com.uav.inspection.performance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.FabricGatewaySettings;
import com.uav.inspection.blockchain.GatewayEvidenceLedgerClient;
import org.hyperledger.fabric.protos.common.Payload;
import org.hyperledger.fabric.protos.gateway.PreparedTransaction;
import org.hyperledger.fabric.protos.msp.SerializedIdentity;
import org.hyperledger.fabric.protos.peer.ChaincodeActionPayload;
import org.hyperledger.fabric.protos.peer.ChaincodeInvocationSpec;
import org.hyperledger.fabric.protos.peer.ChaincodeProposalPayload;
import org.hyperledger.fabric.protos.peer.Transaction;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

/**
 * T031 SELECT/GET/query-only measurement inspection. Does not start Spring or write business state.
 * CLI: inspect &lt;absolute-private-input-json&gt; &lt;absolute-private-output-json&gt;.
 * Preflight/original protection use RetainedDemoInspector; account seed and safe cleanup use
 * UiBrowserFixture create/cleanup, preserving that fixture's complete original-row comparison.
 */
public final class PerformanceInspector {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final Map<String,String> environment=System.getenv();
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<String> checks=new ArrayList<>(),secrets=new ArrayList<>();
    private final Path output;
    private Connection connection;

    private PerformanceInspector(Path output)throws Exception {
        this.output=output.toAbsolutePath().normalize();privatePath(this.output,false);
        report.put("formatVersion",1);report.put("phase","inspect");report.put("passed",false);
        report.put("startedAt",Instant.now().toString());report.put("checks",checks);
        environment.forEach((key,value)->{if(value!=null&&!value.isBlank()&&
                (key.contains("PASSWORD")||key.contains("SECRET")||key.equals("S3_ACCESS_KEY")))secrets.add(value);});
    }

    public static void main(String[] args) {
        if(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
        PerformanceInspector inspector=null;
        try {
            require(args.length==3&&"inspect".equals(args[0]),"expected_inspect_input_output");
            inspector=new PerformanceInspector(Path.of(args[2]));inspector.inspect(Path.of(args[1]));
            inspector.report.put("passed",true);inspector.report.put("completedAt",Instant.now().toString());inspector.write();
            System.out.println("[PASS] T031 read-only performance evidence inspection");
        }catch(Exception failure) {
            if(inspector!=null){inspector.report.put("passed",false);inspector.report.put("failureType",failure.getClass().getSimpleName());
                if(failure instanceof CheckFailure)inspector.report.put("failedCheck",failure.getMessage());
                try{inspector.write();}catch(Exception ignored){/* Provider diagnostics must not disclose secrets. */}}
            System.err.println("[FAIL] T031 performance inspector ("+failure.getClass().getSimpleName()+")");System.exit(1);
        }
    }

    private void inspect(Path inputPath)throws Exception {
        privatePath(inputPath,true);Input input=JSON.readValue(Files.readAllBytes(inputPath),Input.class);validate(input);
        Path checkpointPath=Path.of(input.checkpointPath());privatePath(checkpointPath,true);
        var checkpoint=object(JSON.readValue(Files.readAllBytes(checkpointPath),Map.class));
        check(number(checkpoint,"formatVersion")==1&&Boolean.TRUE.equals(checkpoint.get("accountsCreated"))
                &&!Boolean.TRUE.equals(checkpoint.get("cleaned")),"live_new_account_fixture_checkpoint");
        check(hash(Files.readAllBytes(Path.of(value("DEMO_CREDENTIALS_PATH",".local/demo-credentials.json"))))
                .equals(checkpoint.get("demoCredentialsSha256"))&&hash(Files.readAllBytes(Path.of(".env")))
                .equals(checkpoint.get("envSha256")),"original_credentials_and_env_unchanged");
        Set<String> accounts=new LinkedHashSet<>();
        for(Object item:list(checkpoint.get("accounts"))){String id=text(object(item),"id");id(id);check(accounts.add(id),"fixture_account_ids_unique");}
        check(accounts.size()>=3&&accounts.size()<=20,"bounded_fixture_account_scope");
        report.put("clockDomain","MySQL UTC DATETIME(3); precision 1 ms");
        report.put("timingDefinitions",Map.of(
                "uploadCreatedToLastPartPersistedMs","DB upload_session.created_at to last upload_part.created_at; includes client pacing and transfer, not pure network duration.",
                "finalizationIntentToVersionCreatedMs","DB FILE_FINALIZE_REQUESTED.created_at to evidence_version.created_at; includes dispatch, queueing, object processing and persistence.",
                "preparedCheckpointToValidPersistenceMs","DB chain_attempt.created_at to updated_at of its sole VALID attempt; includes submit, status query and local persistence, not pure Fabric consensus or commit RPC latency.",
                "anchorCreatedToConfirmedPersistenceMs","DB chain_submission.created_at to updated_at of CONFIRMED state; includes queueing, endorsement, submission, status query and persistence.",
                "uploadCreatedToConfirmedPersistenceMs","DB upload_session.created_at to chain_submission.updated_at; complete database-side lifecycle for this upload.",
                "limitations","No separate submitted_at/prepared_at/confirmed_at columns exist. Raw created_at/updated_at are persistence checkpoints. Never subtract client monotonic values from these UTC values. HTTP polling observes an upper bound on confirmation visibility, not an isolated commit duration. Outbox sent_at can follow consumer completion."));
        List<Map<String,Object>> versions=new ArrayList<>();
        try(Connection database=openDatabase();S3Client storage=openStorage();
            GatewayEvidenceLedgerClient owner=GatewayEvidenceLedgerClient.connect(FabricGatewaySettings.fromEnvironment(environment,"ORGA"));
            GatewayEvidenceLedgerClient operator=GatewayEvidenceLedgerClient.connect(FabricGatewaySettings.fromEnvironment(environment,"ORGB"))) {
            connection=database;var session=one("SELECT @@session.time_zone AS zone,@@session.transaction_isolation AS isolation_level");
            check(database.isReadOnly()&&!database.getAutoCommit()&&Set.of("+00:00","UTC").contains(text(session,"zone"))
                    &&"REPEATABLE-READ".equals(text(session,"isolation_level")),"select_only_utc_repeatable_read_snapshot");
            for(String versionId:input.versionIds())versions.add(inspectVersion(versionId,input.expectedFiles().get(versionId),accounts,storage,owner,operator));
            database.rollback();
        }
        report.put("versions",versions);report.put("versionCount",versions.size());
        report.put("scope","Requested fixed versions belonging to tasks created by this new-account fixture; S3 GET, both ordinary institution ledger queries and original commit-status query only. Complete original twenty-four-table protection and cleanup are performed by the retained/fixture inspectors.");
    }

    private Map<String,Object> inspectVersion(String id,ExpectedFile expected,Set<String> accounts,S3Client storage,
                                              GatewayEvidenceLedgerClient owner,GatewayEvidenceLedgerClient operator)throws Exception {
        var version=one("SELECT v.id,v.task_id,v.evidence_id,v.version_no,v.sha256,v.size,v.object_key,v.upload_session_id,v.created_at,t.created_by FROM evidence_version v JOIN inspection_task t ON t.id=v.task_id WHERE v.id=?",id);
        check(accounts.contains(text(version,"created_by")),"requested_version_belongs_to_fixture_task");
        check(expected.size()==number(version,"size")&&expected.sha256().equals(text(version,"sha256")),"database_matches_expected_file_metadata");
        String uploadId=text(version,"upload_session_id");
        var upload=one("SELECT id,task_id,actor_type,actor_id,status,expected_size,part_count,lease_token,lease_until,created_at,updated_at FROM upload_session WHERE id=?",uploadId);
        check("COMPLETED".equals(text(upload,"status"))&&upload.get("lease_token")==null&&upload.get("lease_until")==null
                &&text(version,"task_id").equals(text(upload,"task_id"))&&expected.size()==number(upload,"expected_size"),"scoped_upload_completed_without_lease");
        if("USER".equals(text(upload,"actor_type")))check(accounts.contains(text(upload,"actor_id")),"upload_actor_belongs_to_fixture");
        else {check("DEVICE".equals(text(upload,"actor_type")),"known_upload_actor_type");var creators=query("SELECT actor_id FROM audit_log WHERE action='DEVICE_CREATED' AND resource_type='DRONE' AND resource_id=?",text(upload,"actor_id"));
            check(!creators.isEmpty()&&creators.stream().allMatch(row->accounts.contains(text(row,"actor_id"))),"upload_device_created_by_fixture");}
        var parts=query("SELECT part_no,size,sha256,created_at FROM upload_part WHERE session_id=? ORDER BY part_no",uploadId);
        check(parts.size()==number(upload,"part_count")&&!parts.isEmpty(),"all_expected_upload_parts_persisted");
        long partBytes=0;Instant lastPart=instant(upload,"created_at");List<Map<String,Object>> safeParts=new ArrayList<>();
        for(int i=0;i<parts.size();i++){var part=parts.get(i);check(number(part,"part_no")==i+1,"upload_part_numbers_contiguous");partBytes+=number(part,"size");
            if(instant(part,"created_at").isAfter(lastPart))lastPart=instant(part,"created_at");
            safeParts.add(Map.of("partNo",number(part,"part_no"),"size",number(part,"size"),"sha256",text(part,"sha256"),"createdAtUtc",instant(part,"created_at").toString()));}
        check(partBytes==expected.size(),"part_sizes_sum_to_expected_file_size");
        Digest actual;try(InputStream input=storage.getObject(builder->builder.bucket(value("S3_BUCKET","uav-evidence")).key(text(version,"object_key")))){actual=digest(input,expected.size());}
        check(actual.size()==expected.size()&&actual.sha256().equals(expected.sha256()),"actual_formal_object_bytes_and_sha256_match");
        var submission=one("SELECT id,status,confirmed_tx_id,confirmed_by,retry_count,lease_token,lease_until,created_at,updated_at FROM chain_submission WHERE version_id=?",id);
        check("CONFIRMED".equals(text(submission,"status"))&&submission.get("lease_token")==null&&submission.get("lease_until")==null,"anchor_confirmed_without_lease");
        String submissionId=text(submission,"id"),tx=text(submission,"confirmed_tx_id");
        var attempt=one("SELECT attempt_no,tx_id,phase,validation_code,block_number,transaction_bytes,commit_bytes,created_at,updated_at FROM chain_attempt WHERE submission_id=?",submissionId);
        check(number(attempt,"attempt_no")==1&&"VALID".equals(text(attempt,"phase"))&&tx.equals(text(attempt,"tx_id"))
                &&number(attempt,"validation_code")==0,"single_original_valid_anchor_attempt");
        var a=owner.queryEvidence(text(version,"evidence_id"),(int)number(version,"version_no"));
        var b=operator.queryEvidence(text(version,"evidence_id"),(int)number(version,"version_no"));
        var expectedRecord=new EvidenceLedgerClient.EvidenceRecord(text(version,"evidence_id"),(int)number(version,"version_no"),expected.sha256(),required("FABRIC_ORGB_MSP_ID"),tx);
        check(a.found()&&b.found()&&expectedRecord.equals(a.record())&&expectedRecord.equals(b.record()),"both_institutions_match_all_five_ledger_fields");
        byte[] transaction=bytes(attempt,"transaction_bytes"),commit=bytes(attempt,"commit_bytes");
        var prepared=PreparedTransaction.parseFrom(transaction);check(tx.equals(prepared.getTransactionId()),"prepared_checkpoint_retains_original_transaction_id");
        var payload=Payload.parseFrom(prepared.getEnvelope().getPayload());var actions=Transaction.parseFrom(payload.getData());
        check(actions.getActionsCount()==1,"original_prepared_transaction_has_one_action");
        var action=ChaincodeActionPayload.parseFrom(actions.getActions(0).getPayload());
        var proposal=ChaincodeProposalPayload.parseFrom(action.getChaincodeProposalPayload());
        var invocation=ChaincodeInvocationSpec.parseFrom(proposal.getInput());
        check(invocation.getChaincodeSpec().getInput().getArgsList().stream().map(value->value.toStringUtf8()).toList().equals(
                List.of(value("FABRIC_CONTRACT","EvidenceContract")+":RegisterEvidence",expectedRecord.evidenceId(),Integer.toString(expectedRecord.version()),expected.sha256())),"original_transaction_registers_exact_evidence_version_hash");
        Set<String> endorsers=new LinkedHashSet<>();for(var endorsement:action.getAction().getEndorsementsList())endorsers.add(SerializedIdentity.parseFrom(endorsement.getEndorser()).getMspid());
        check(endorsers.containsAll(Set.of(required("FABRIC_ORGA_MSP_ID"),required("FABRIC_ORGB_MSP_ID"))),"both_institutions_endorsed_original_transaction");
        var outcome=operator.queryCommit(new EvidenceLedgerClient.CommitCheckpoint(tx,commit));
        check(outcome.valid()&&outcome.validationCode()==0&&tx.equals(outcome.transactionId())&&outcome.blockNumber()==number(attempt,"block_number"),"original_commit_requeries_valid_at_persisted_block");
        var finalizeEvent=event("FILE_FINALIZE_REQUESTED",uploadId,"FILE_FINALIZE");
        var anchorEvent=event("EVIDENCE_ANCHOR_REQUESTED",submissionId,"EVIDENCE_ANCHOR");
        Map<String,Object> timings=new LinkedHashMap<>();
        timings.put("uploadCreatedAtUtc",instant(upload,"created_at").toString());timings.put("uploadCompletedStateUpdatedAtUtc",instant(upload,"updated_at").toString());
        timings.put("lastPartCreatedAtUtc",lastPart.toString());timings.put("versionCreatedAtUtc",instant(version,"created_at").toString());
        timings.put("anchorCreatedAtUtc",instant(submission,"created_at").toString());timings.put("anchorConfirmedStateUpdatedAtUtc",instant(submission,"updated_at").toString());
        timings.put("attemptPreparedCheckpointCreatedAtUtc",instant(attempt,"created_at").toString());timings.put("attemptValidStateUpdatedAtUtc",instant(attempt,"updated_at").toString());
        timings.put("finalizationEvent",safeEvent(finalizeEvent));timings.put("anchorEvent",safeEvent(anchorEvent));
        timings.put("uploadCreatedToLastPartPersistedMs",elapsed(instant(upload,"created_at"),lastPart));
        timings.put("finalizationIntentToVersionCreatedMs",elapsed(instant(finalizeEvent,"created_at"),instant(version,"created_at")));
        timings.put("preparedCheckpointToValidPersistenceMs",elapsed(instant(attempt,"created_at"),instant(attempt,"updated_at")));
        timings.put("anchorCreatedToConfirmedPersistenceMs",elapsed(instant(submission,"created_at"),instant(submission,"updated_at")));
        timings.put("uploadCreatedToConfirmedPersistenceMs",elapsed(instant(upload,"created_at"),instant(submission,"updated_at")));
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("taskId",text(version,"task_id"));result.put("uploadId",uploadId);
        result.put("size",expected.size());result.put("sha256",expected.sha256());result.put("objectBytesVerified",true);result.put("uploadStatus","COMPLETED");result.put("anchorStatus","CONFIRMED");
        result.put("partCount",parts.size());result.put("parts",safeParts);result.put("attemptCount",1);result.put("anchorRetryCount",number(submission,"retry_count"));
        result.put("timings",timings);result.put("ledgerByInstitution",Map.of("OrgA",a.record(),"OrgB",b.record()));
        result.put("transaction",Map.of("transactionId",tx,"phase","VALID","validationCode",0,"blockNumber",outcome.blockNumber(),
                "transactionCheckpointSha256",hash(transaction),"commitCheckpointSha256",hash(commit),"endorserMsps",endorsers.stream().sorted().toList()));
        return result;
    }

    private Map<String,Object> event(String type,String businessId,String consumer)throws Exception {
        var event=one("SELECT event_id,status,attempt_count,created_at,sent_at,updated_at,lease_token,lease_until FROM outbox_event WHERE event_type=? AND biz_id=?",type,businessId);
        check("SENT".equals(text(event,"status"))&&event.get("sent_at")!=null&&event.get("lease_token")==null&&event.get("lease_until")==null,"business_outbox_sent_without_lease");
        var consumed=one("SELECT status,updated_at FROM consumed_event WHERE consumer_code=? AND event_id=?",consumer,text(event,"event_id"));
        check("DONE".equals(text(consumed,"status")),"business_event_consumption_completed");event.put("consumer_done_at",instant(consumed,"updated_at"));return event;
    }
    private Map<String,Object> safeEvent(Map<String,Object> event){return Map.of("createdAtUtc",instant(event,"created_at").toString(),"sentAtUtc",instant(event,"sent_at").toString(),
            "updatedAtUtc",instant(event,"updated_at").toString(),"consumerDoneStateUpdatedAtUtc",instant(event,"consumer_done_at").toString(),"status","SENT","publishAttemptCount",number(event,"attempt_count"));}

    private Connection openDatabase()throws Exception {
        String host=value("DB_HOST","127.0.0.1"),port=value("DB_PORT","3306"),name=value("DB_NAME","uav_inspection");
        check(host.matches("[A-Za-z0-9.-]+")&&port.matches("[0-9]{1,5}")&&name.matches("[A-Za-z0-9_]+"),"safe_database_connection_identifiers");
        Properties settings=new Properties();settings.setProperty("user",value("DB_USERNAME","uav_app"));settings.setProperty("password",required("DB_PASSWORD"));
        settings.setProperty("sslMode",value("DB_SSL_MODE","REQUIRED"));settings.setProperty("connectionTimeZone","+00:00");settings.setProperty("forceConnectionTimeZoneToSession","true");
        settings.setProperty("preserveInstants","true");settings.setProperty("characterEncoding","UTF-8");settings.setProperty("connectTimeout","5000");settings.setProperty("socketTimeout","15000");
        Connection database=DriverManager.getConnection("jdbc:mysql://"+host+":"+port+"/"+name,settings);database.setReadOnly(true);
        database.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);database.setAutoCommit(false);return database;
    }
    private S3Client openStorage(){return S3Client.builder().endpointOverride(URI.create(required("S3_ENDPOINT"))).region(Region.of(value("S3_REGION","garage")))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(required("S3_ACCESS_KEY"),required("S3_SECRET_KEY"))))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(Boolean.parseBoolean(value("S3_FORCE_PATH_STYLE","true"))).chunkedEncodingEnabled(false).build())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
            .overrideConfiguration(settings->settings.apiCallTimeout(Duration.ofSeconds(45)).apiCallAttemptTimeout(Duration.ofSeconds(30))).build();}
    private List<Map<String,Object>> query(String sql,Object...args)throws Exception {
        check(sql.startsWith("SELECT ")&&!sql.contains(";"),"select_only_sql_contract");List<Map<String,Object>> result=new ArrayList<>();
        try(var statement=connection.prepareStatement(sql)){statement.setQueryTimeout(15);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
            try(var rows=statement.executeQuery()){while(rows.next()){Map<String,Object> row=new LinkedHashMap<>();var metadata=rows.getMetaData();
                for(int i=1;i<=metadata.getColumnCount();i++){Object value;if(metadata.getColumnType(i)==Types.TIMESTAMP||metadata.getColumnType(i)==Types.TIMESTAMP_WITH_TIMEZONE){
                        var timestamp=rows.getTimestamp(i,Calendar.getInstance(TimeZone.getTimeZone("UTC")));value=timestamp==null?null:timestamp.toInstant();
                    }else value=rows.getObject(i);row.put(metadata.getColumnLabel(i),value);}result.add(row);}}}return result;
    }
    private Map<String,Object> one(String sql,Object...args)throws Exception {var rows=query(sql,args);check(rows.size()==1,"exactly_one_scoped_business_row_required");return rows.getFirst();}
    private void validate(Input input){check(input!=null&&input.checkpointPath()!=null&&Path.of(input.checkpointPath()).isAbsolute(),"absolute_checkpoint_path_required");
        check(input.versionIds()!=null&&!input.versionIds().isEmpty()&&input.versionIds().size()<=200&&new LinkedHashSet<>(input.versionIds()).size()==input.versionIds().size(),"bounded_unique_version_list");
        for(String id:input.versionIds())id(id);check(input.expectedFiles()!=null&&input.expectedFiles().keySet().equals(Set.copyOf(input.versionIds())),"expected_metadata_for_every_requested_version");
        for(var file:input.expectedFiles().values())check(file!=null&&file.sha256()!=null&&file.sha256().matches("[0-9a-f]{64}")&&file.size()>0&&file.size()<=209715200L,"valid_bounded_expected_file_metadata");}
    private static void privatePath(Path path,boolean existing)throws Exception {require(path.isAbsolute(),"absolute_private_path_required");path=path.normalize();Path local=Path.of(".local").toAbsolutePath().normalize();
        require(path.startsWith(local)&&Files.isDirectory(path.getParent())&&!Files.isSymbolicLink(path),"private_local_path_required");
        for(Path ancestor=path.getParent();ancestor!=null&&!ancestor.equals(local);ancestor=ancestor.getParent())require(!Files.isSymbolicLink(ancestor),"private_path_has_no_symlink_ancestor");
        require(Files.getPosixFilePermissions(path.getParent()).stream().noneMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")),"private_parent_directory_required");
        if(existing){require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"private_input_file_required");require(Files.getPosixFilePermissions(path).stream().noneMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")),"private_input_permissions_required");}}
    private void write()throws Exception {String safe=JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        require(!safe.contains("upload-parts/")&&!safe.contains("evidence-files/")&&!safe.contains("-----BEGIN")&&!safe.contains("\"object_key\"")
                &&!safe.contains("\"transaction_bytes\"")&&!safe.contains("\"commit_bytes\"")&&!safe.contains("\"accessToken\"")&&!safe.contains("\"deviceToken\"")&&!safe.contains("\"password\"")
                &&secrets.stream().noneMatch(secret->safe.contains(secret)),"safe_report_metadata_only");
        Path temporary=Files.createTempFile(output.getParent(),".performance-inspector-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{Files.writeString(temporary,safe);Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    private static long elapsed(Instant from,Instant to){long millis=Duration.between(from,to).toMillis();require(millis>=0,"database_checkpoint_interval_nonnegative");return millis;}
    private static Instant instant(Map<String,Object> row,String key){require(row.get(key) instanceof Instant,"utc_timestamp_metadata_required");return (Instant)row.get(key);}
    private static String text(Map<String,Object> row,String key){require(row.get(key) instanceof String,"text_metadata_required");return (String)row.get(key);}
    private static long number(Map<String,Object> row,String key){require(row.get(key) instanceof Number,"numeric_metadata_required");return ((Number)row.get(key)).longValue();}
    private static byte[] bytes(Map<String,Object> row,String key)throws Exception {Object value=row.get(key);if(value instanceof byte[] bytes&&bytes.length>0)return bytes;
        if(value instanceof java.sql.Blob blob&&blob.length()>0&&blob.length()<=16777215L)return blob.getBytes(1,(int)blob.length());throw new CheckFailure("original_binary_checkpoint_required");}
    private static Digest digest(InputStream input,long maximum)throws Exception {MessageDigest digest=sha();long size=0;byte[] buffer=new byte[65536];
        for(int read;(read=input.read(buffer))!=-1;){size+=read;require(size<=maximum,"formal_object_exceeds_expected_size");digest.update(buffer,0,read);}return new Digest(size,HexFormat.of().formatHex(digest.digest()));}
    private static String hash(byte[] bytes){return HexFormat.of().formatHex(sha().digest(bytes));}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception failure){throw new IllegalStateException("SHA-256 unavailable");}}
    private String required(String key){String value=environment.get(key);check(value!=null&&!value.isBlank(),"required_environment_present");return value;}
    private String value(String key,String fallback){String value=environment.get(key);return value==null||value.isBlank()?fallback:value;}
    private void check(boolean condition,String name){require(condition,name);if(!checks.contains(name))checks.add(name);}
    private static void id(String id){require(id!=null&&id.matches(UUID),"valid_scoped_resource_uuid");}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){require(value instanceof Map,"json_object_required");return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value){require(value instanceof List,"json_array_required");return (List<Object>)value;}
    private static void require(boolean condition,String name){if(!condition)throw new CheckFailure(name);}
    public record ExpectedFile(long size,String sha256){}
    public record Input(String checkpointPath,List<String> versionIds,Map<String,ExpectedFile> expectedFiles){}
    private record Digest(long size,String sha256){}
    private static final class CheckFailure extends IllegalStateException {private CheckFailure(String name){super(name);}}
}
