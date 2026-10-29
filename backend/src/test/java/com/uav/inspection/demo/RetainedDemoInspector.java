package com.uav.inspection.demo;

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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** SELECT/GET/query-only retained T029 evidence inspection; never starts Spring or changes business rows. */
public final class RetainedDemoInspector {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String DOMAIN="T029-original-twenty-four-row-fields-and-blobs-v1";
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Map<String,String> TABLES=new LinkedHashMap<>();
    private static final Map<String,Integer> MIGRATIONS=Map.ofEntries(
            Map.entry("1",-539134757),Map.entry("2",1185045295),Map.entry("3",-394507728),
            Map.entry("4",-1021150431),Map.entry("5",-507899413),Map.entry("6",186672578),
            Map.entry("7",-763051633),Map.entry("8",1053255897),Map.entry("9",72853277),
            Map.entry("10",-573715695),Map.entry("11",1029133993),Map.entry("12",1723908793),
            Map.entry("13",-1202069766),Map.entry("14",-1562459282));
    static {
        String[][] entries={{"organization","id"},{"app_user","id"},{"inspection_task","id"},
                {"task_member","task_id,user_id"},{"drone","id"},{"inspection_subtask","id"},
                {"material_requirement","id"},{"task_execution","id"},{"upload_session","id"},
                {"upload_part","session_id,part_no"},{"evidence_version","id"},{"outbox_event","event_id"},
                {"consumed_event","consumer_code,event_id"},{"chain_submission","id"},{"chain_attempt","id"},
                {"verification_record","id"},{"device_event","id"},{"execution_adjustment","id"},
                {"issue","id"},{"review_record","id"},{"access_grant","id"},{"message_rejection","id"},
                {"anchor_retry_request","id"},{"storage_cleanup_task","id"}};
        for(var entry:entries) TABLES.put(entry[0],entry[1]);
    }
    private final Map<String,String> environment=System.getenv();
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<String> checks=new ArrayList<>(),secrets=new ArrayList<>();
    private final Path output;
    private Connection connection;

    private RetainedDemoInspector(String phase,Path output)throws Exception {
        this.output=output.toAbsolutePath().normalize();
        privateOutput(this.output);
        report.put("formatVersion",1);report.put("phase",phase);report.put("passed",false);
        report.put("startedAt",Instant.now().toString());report.put("checks",checks);
        environment.forEach((key,value)->{if(value!=null&&!value.isBlank()&&
                (key.contains("PASSWORD")||key.contains("SECRET")||key.equals("S3_ACCESS_KEY")))secrets.add(value);});
        var credentials=object(JSON.readValue(Files.readAllBytes(credentialsPath()),new TypeReference<Map<String,Object>>() {}));
        check(number(credentials,"formatVersion")==1,"existing_credential_format_required");
        for(String group:List.of("passwords","droneTokens"))
            for(Object value:object(credentials.get(group)).values())if(value instanceof String text&&!text.isEmpty())secrets.add(text);
    }
    public static void main(String[] args) {
        if(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
        RetainedDemoInspector inspector=null;
        try {
            require(args.length==2&&"preflight".equals(args[0])||args.length==3&&Set.of("inspect","original-objects").contains(args[0]),"expected_phase_and_paths");
            inspector=new RetainedDemoInspector(args[0],Path.of(args[args.length-1]));
            if("preflight".equals(args[0]))inspector.preflight();
            else if("original-objects".equals(args[0]))inspector.originalObjects(Path.of(args[1]));
            else inspector.inspect(Path.of(args[1]));
            inspector.report.put("passed",true);inspector.report.put("completedAt",Instant.now().toString());inspector.write();
            System.out.println("[PASS] T029 read-only retained evidence inspector "+args[0]);
        }catch(Exception failure) {
            if(inspector!=null){inspector.report.put("passed",false);inspector.report.put("failureType",failure.getClass().getSimpleName());
                if(failure instanceof CheckFailure)inspector.report.put("failedCheck",failure.getMessage());
                try{inspector.write();}catch(Exception ignored){/* Never emit provider diagnostics or credentials. */}}
            System.err.println("[FAIL] T029 retained evidence inspector ("+failure.getClass().getSimpleName()+")");System.exit(1);
        }
    }
    private void preflight()throws Exception {
        try(Connection database=openDatabase()) {
            connection=database;var migrations=validateDatabase();pendingWork();Snapshot snapshot=snapshot();
            report.put("snapshot",snapshot);report.put("snapshotSha256",hash(JSON.writeValueAsBytes(snapshot)));
            report.put("tableCounts",counts(snapshot));report.put("migrationChecksums",migrations);
            report.put("migrationHistory",tableSnapshot("flyway_schema_history","installed_rank"));
            report.put("demoCredentialsSha256",hash(Files.readAllBytes(credentialsPath())));
            report.put("envSha256",hash(Files.readAllBytes(Path.of(".env"))));
            report.put("scope","Twenty-four table original row digests including all columns/BLOBs; audit is append-only and excluded.");
            database.rollback();
        }
    }
    /** Verify every original formal object, including originals outside the retained demonstration tasks. */
    private void originalObjects(Path baselinePath)throws Exception {
        var baseline=object(JSON.readValue(Files.readAllBytes(baselinePath),new TypeReference<Map<String,Object>>() {}));
        check(Boolean.TRUE.equals(baseline.get("passed")),"passing_original_object_baseline_required");
        Snapshot original=JSON.convertValue(baseline.get("snapshot"),Snapshot.class);
        check(hash(JSON.writeValueAsBytes(original)).equals(baseline.get("snapshotSha256")),"original_object_baseline_digest_consistent");
        try(Connection database=openDatabase();S3Client storage=openStorage();
            var owner=GatewayEvidenceLedgerClient.connect(FabricGatewaySettings.fromEnvironment(environment,"ORGA"));
            var operator=GatewayEvidenceLedgerClient.connect(FabricGatewaySettings.fromEnvironment(environment,"ORGB"))) {
            connection=database;validateDatabase();compareOriginal(original,snapshot());
            List<Map<String,Object>> objects=new ArrayList<>();
            for(String id:original.tables().get("evidence_version").rows().keySet()) {
                var row=one("SELECT v.id,v.evidence_id,v.version_no,v.sha256,v.size,v.object_key,c.status,c.confirmed_tx_id FROM evidence_version v LEFT JOIN chain_submission c ON c.version_id=v.id WHERE v.id=?",id);
                long size=number(row,"size");String expected=text(row,"sha256");
                try(var bytes=storage.getObject(request->request.bucket(value("S3_BUCKET","uav-evidence")).key(text(row,"object_key")))) {
                    check(digest(bytes,size).equals(new Digest(size,expected)),"every_original_formal_object_size_and_hash_unchanged");
                }
                Map<String,Object> safe=new LinkedHashMap<>();safe.put("versionId",id);safe.put("size",size);safe.put("sha256",expected);safe.put("actualObjectBytesVerified",true);safe.put("anchorStatus",row.get("status"));
                if("CONFIRMED".equals(row.get("status"))) {
                    String evidence=text(row,"evidence_id");int version=(int)number(row,"version_no");
                    var expectedRecord=new EvidenceLedgerClient.EvidenceRecord(evidence,version,expected,required("FABRIC_ORGB_MSP_ID"),text(row,"confirmed_tx_id"));
                    var a=owner.queryEvidence(evidence,version);var b=operator.queryEvidence(evidence,version);
                    check(a.found()&&b.found()&&expectedRecord.equals(a.record())&&expectedRecord.equals(b.record()),"every_original_confirmed_registration_still_matches_both_institutions");
                    safe.put("originalTransactionId",expectedRecord.registeredTxId());safe.put("bothInstitutionsAgree",true);
                }
                objects.add(safe);
            }
            report.put("originalFormalObjects",objects);report.put("originalFormalObjectCount",objects.size());
            report.put("allOriginalBusinessRowsPreserved",true);report.put("allOriginalFormalObjectBytesVerified",true);
            report.put("scope","SELECT/GET/query-only inspection of every original version in the immutable baseline; no object, registration or business row is written.");
            database.rollback();
        }
    }
    private void inspect(Path inputPath)throws Exception {
        Input input=JSON.readValue(Files.readAllBytes(inputPath),Input.class);validate(input);
        Path baselinePath=Path.of(input.preflightReport()).toAbsolutePath().normalize();
        check(!baselinePath.equals(output),"original_preflight_not_overwritten");
        var baseline=object(JSON.readValue(Files.readAllBytes(baselinePath),new TypeReference<Map<String,Object>>() {}));
        check(Boolean.TRUE.equals(baseline.get("passed"))&&"preflight".equals(baseline.get("phase"))
                &&number(baseline,"formatVersion")==1,"passing_preflight_required");
        Snapshot original=JSON.convertValue(baseline.get("snapshot"),Snapshot.class);
        check(hash(JSON.writeValueAsBytes(original)).equals(baseline.get("snapshotSha256")),"baseline_digest_consistent");
        check(hash(Files.readAllBytes(credentialsPath())).equals(baseline.get("demoCredentialsSha256"))
                &&hash(Files.readAllBytes(Path.of(".env"))).equals(baseline.get("envSha256")),"original_credentials_and_env_unchanged");
        try(Connection database=openDatabase()) {
            connection=database;validateDatabase();Snapshot current=snapshot();compareOriginal(original,current);
            check(JSON.convertValue(baseline.get("migrationHistory"),TableSnapshot.class)
                    .equals(tableSnapshot("flyway_schema_history","installed_rank")),"complete_migration_history_unchanged");
            if(input.recheckPreflightReport()!=null) {
                Path freshPath=Path.of(input.recheckPreflightReport()).toAbsolutePath().normalize();
                check(!freshPath.equals(output),"fresh_recheck_preflight_not_overwritten");
                var fresh=object(JSON.readValue(Files.readAllBytes(freshPath),new TypeReference<Map<String,Object>>() {}));
                check(Boolean.TRUE.equals(fresh.get("passed"))&&"preflight".equals(fresh.get("phase"))
                        &&number(fresh,"formatVersion")==1,"passing_fresh_recheck_preflight_required");
                Snapshot freshSnapshot=JSON.convertValue(fresh.get("snapshot"),Snapshot.class);
                check(hash(JSON.writeValueAsBytes(freshSnapshot)).equals(fresh.get("snapshotSha256")),"fresh_recheck_baseline_digest_consistent");
                check(DOMAIN.equals(freshSnapshot.domain())&&freshSnapshot.tables().keySet().equals(TABLES.keySet())
                        &&freshSnapshot.equals(current),"all_fresh_recheck_business_rows_and_blobs_unchanged");
                check(JSON.convertValue(fresh.get("migrationHistory"),TableSnapshot.class)
                        .equals(tableSnapshot("flyway_schema_history","installed_rank"))
                        &&hash(Files.readAllBytes(credentialsPath())).equals(fresh.get("demoCredentialsSha256"))
                        &&hash(Files.readAllBytes(Path.of(".env"))).equals(fresh.get("envSha256")),
                        "fresh_recheck_migrations_credentials_and_env_unchanged");
                report.put("recheckBusinessRowsUnchanged",true);
                report.put("recheckSnapshotSha256",fresh.get("snapshotSha256"));
            }
            report.put("recheck",input.recheckPreflightReport()!=null);
            check(!original.tables().get("inspection_task").rows().containsKey(input.taskId()),"retained_task_new_to_original_baseline");
            for(String device:input.deviceIds())check(!original.tables().get("drone").rows().containsKey(device),"retained_devices_new_to_original_baseline");
            verifyTask(input);verifyGrantsAndVerifications(input);
            try(S3Client storage=openStorage();var owner=GatewayEvidenceLedgerClient.connect(FabricGatewaySettings.fromEnvironment(environment,"ORGA"));
                var operator=GatewayEvidenceLedgerClient.connect(FabricGatewaySettings.fromEnvironment(environment,"ORGB"))) {
                verifyVersions(input,storage,owner,operator);
            }
            report.put("originalBusinessRowsUnchanged",true);report.put("auditExcludedFromOriginalSnapshot",true);
            report.put("tableCountsBefore",counts(original));report.put("tableCountsAfter",counts(current));
            report.put("originalSnapshotSha256",baseline.get("snapshotSha256"));
            report.put("currentSnapshotSha256",hash(JSON.writeValueAsBytes(current)));
            report.put("scope","SELECT-only retained business inspection, actual private-object GET and both-institution ledger/commit queries; no new verification or business state is written.");
            database.rollback();
        }
    }
    private void verifyTask(Input input)throws Exception {
        var task=one("SELECT id,status,completed_at,lock_version,(completed_at>=created_at AND completed_at<=UTC_TIMESTAMP(3)) AS valid_time FROM inspection_task WHERE id=?",input.taskId());
        check("COMPLETED".equals(text(task,"status"))&&task.get("completed_at")!=null&&truth(task.get("valid_time")),"retained_task_completed_with_valid_time");
        var subtasks=query("SELECT id,current_execution_id FROM inspection_subtask WHERE task_id=? ORDER BY id",input.taskId());
        check(ids(subtasks,"id").equals(Set.copyOf(input.subtaskIds())),"exact_three_retained_subtasks");
        Set<String> current=new LinkedHashSet<>();
        for(var subtask:subtasks) {
            String id=text(subtask,"current_execution_id");current.add(id);
            var execution=one("SELECT id,subtask_id,drone_id,status,progress FROM task_execution WHERE id=?",id);
            check(text(subtask,"id").equals(text(execution,"subtask_id"))&&"RUNNING".equals(text(execution,"status"))
                    &&number(execution,"progress")==100&&input.deviceIds().contains(text(execution,"drone_id")),"current_execution_running_at_one_hundred");
        }
        check(current.equals(Set.copyOf(input.executionIds())),"current_execution_ids_match_manifest");
        var executions=query("SELECT e.id,e.drone_id FROM task_execution e JOIN inspection_subtask s ON s.id=e.subtask_id WHERE s.task_id=?",input.taskId());
        var expectedExecutions=new LinkedHashSet<>(input.executionIds());expectedExecutions.add(input.oldExecutionId());
        check(ids(executions,"id").equals(expectedExecutions)&&ids(executions,"drone_id").equals(Set.copyOf(input.deviceIds())),"four_executions_use_four_retained_devices");
        var old=one("SELECT subtask_id,status,closed_at FROM task_execution WHERE id=?",input.oldExecutionId());
        check("CLOSED".equals(text(old,"status"))&&old.get("closed_at")!=null&&input.subtaskIds().contains(text(old,"subtask_id")),"old_execution_closed_and_preserved");
        var adjustments=query("SELECT id,subtask_id,previous_execution_id,new_execution_id,actor_id FROM execution_adjustment WHERE task_id=?",input.taskId());
        check(adjustments.size()==1&&input.oldExecutionId().equals(text(adjustments.getFirst(),"previous_execution_id"))
                &&current.contains(text(adjustments.getFirst(),"new_execution_id"))
                &&text(old,"subtask_id").equals(text(adjustments.getFirst(),"subtask_id")),"exact_one_retained_execution_adjustment");
        var versions=query("SELECT id FROM evidence_version WHERE task_id=?",input.taskId());
        var uploads=query("SELECT id,status,lease_token,lease_until FROM upload_session WHERE task_id=?",input.taskId());
        check(ids(versions,"id").equals(Set.copyOf(input.versionIds()))&&ids(uploads,"id").equals(Set.copyOf(input.uploadIds())),"exact_four_retained_versions_and_uploads");
        for(var upload:uploads)check("COMPLETED".equals(text(upload,"status"))&&upload.get("lease_token")==null&&upload.get("lease_until")==null,"completed_upload_without_active_lease");
        check(count("SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','SENDING') AND (biz_id IN (SELECT id FROM upload_session WHERE task_id=?) OR biz_id IN (SELECT c.id FROM chain_submission c JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=?))",input.taskId(),input.taskId())==0,"retained_outbox_quiescent");
        check(count("SELECT COUNT(*) FROM review_record r JOIN evidence_version v ON v.id=r.evidence_version_id WHERE v.task_id=?",input.taskId())==4
                &&count("SELECT COUNT(*) FROM review_record r JOIN evidence_version v ON v.id=r.evidence_version_id WHERE v.task_id=? AND r.decision='APPROVED'",input.taskId())==4,"four_approved_review_records");
        check(count("SELECT COUNT(*) FROM material_requirement WHERE task_id=? AND required=1",input.taskId())==3,"three_required_materials");
        check(count("SELECT COUNT(*) FROM material_requirement r JOIN inspection_subtask s ON s.id=r.subtask_id WHERE r.task_id=? AND r.required=1 AND EXISTS (SELECT 1 FROM evidence_version v JOIN chain_submission c ON c.version_id=v.id WHERE v.requirement_id=r.id AND v.execution_id=s.current_execution_id AND v.review_status='APPROVED' AND c.status='CONFIRMED')",input.taskId())==3,"all_current_required_materials_approved_and_confirmed");
        report.put("task",Map.of("id",input.taskId(),"status","COMPLETED","subtaskCount",3,"currentExecutionIds",input.executionIds(),"oldExecutionId",input.oldExecutionId(),"currentProgress",100));
        report.put("executionAdjustment",adjustments.getFirst());
        report.put("deviceEventCount",count("SELECT COUNT(*) FROM device_event d JOIN task_execution e ON e.id=d.execution_id JOIN inspection_subtask s ON s.id=e.subtask_id WHERE s.task_id=?",input.taskId()));
    }
    private void verifyGrantsAndVerifications(Input input)throws Exception {
        List<Map<String,Object>> grants=new ArrayList<>();
        for(String id:List.of(input.revokedGrantId(),input.activeGrantId())) {
            var grant=one("SELECT id,version_id,status,lock_version,CAST(valid_until AS CHAR) AS valid_until,revoked_at,(status='APPROVED' AND valid_until>UTC_TIMESTAMP(3)) AS currently_valid FROM access_grant WHERE id=?",id);
            check(input.versionIds().contains(text(grant,"version_id")),"grant_scoped_to_retained_version");
            check(id.equals(input.revokedGrantId())?"REVOKED".equals(text(grant,"status"))&&grant.get("revoked_at")!=null
                    :"APPROVED".equals(text(grant,"status"))&&grant.get("valid_until")!=null,"revoked_and_stored_approved_grants_preserved");
            grant.remove("revoked_at");grant.put("currently_valid",truth(grant.get("currently_valid")));grants.add(grant);
        }
        var results=query("SELECT r.id,r.version_id,r.user_id,r.source,r.result,r.actual_sha256,r.ledger_sha256,r.actual_size,r.ledger_tx_id FROM verification_record r JOIN evidence_version v ON v.id=r.version_id WHERE v.task_id=? ORDER BY r.verified_at,r.id",input.taskId());
        check(results.size()>=3,"at_least_three_retained_verifications");Set<String> kinds=new LinkedHashSet<>();
        for(var result:results) {
            String kind=text(result,"source")+":"+text(result,"result");
            if(!Set.of("STORED:MATCH","COPY:MATCH","COPY:MISMATCH").contains(kind))continue;
            var expected=input.expectedFiles().get(text(result,"version_id"));
            var submission=one("SELECT confirmed_tx_id FROM chain_submission WHERE version_id=?",text(result,"version_id"));
            check(expected.sha256().equals(text(result,"ledger_sha256"))&&text(submission,"confirmed_tx_id").equals(text(result,"ledger_tx_id"))
                    &&("COPY:MISMATCH".equals(kind)?number(result,"actual_size")>=0:number(result,"actual_size")==expected.size()),
                    "verification_uses_actual_retained_version_hash_size_and_transaction");
            check("COPY:MISMATCH".equals(kind)?!expected.sha256().equals(text(result,"actual_sha256"))
                    :expected.sha256().equals(text(result,"actual_sha256")),"verification_result_matches_actual_hash_comparison");kinds.add(kind);
        }
        check(kinds.containsAll(Set.of("STORED:MATCH","COPY:MATCH","COPY:MISMATCH")),"stored_match_identical_copy_match_and_changed_copy_mismatch_retained");
        report.put("grants",grants);report.put("verifications",results);report.put("verificationCount",results.size());
    }
    private void verifyVersions(Input input,S3Client storage,GatewayEvidenceLedgerClient owner,GatewayEvidenceLedgerClient operator)throws Exception {
        List<Map<String,Object>> safeVersions=new ArrayList<>();Map<String,Map<String,Object>> rows=new LinkedHashMap<>();
        String operatorMsp=required("FABRIC_ORGB_MSP_ID");
        for(String id:input.versionIds()) {
            var version=one("SELECT * FROM evidence_version WHERE id=?",id);rows.put(id,version);var expected=input.expectedFiles().get(id);
            check(input.taskId().equals(text(version,"task_id"))&&input.uploadIds().contains(text(version,"upload_session_id"))
                    &&"APPROVED".equals(text(version,"review_status"))&&expected.sha256().equals(text(version,"sha256"))
                    &&expected.size()==number(version,"size"),"immutable_retained_version_metadata_matches_expected_bytes");
            String uploadId=text(version,"upload_session_id"),key=text(version,"object_key");
            check(key.matches("evidence-files/"+uploadId+"/"+UUID),"final_object_has_server_owned_upload_namespace");
            try(var bytes=storage.getObject(request->request.bucket(value("S3_BUCKET","uav-evidence")).key(key))) {
                check(bytes.response().contentLength()==expected.size(),"actual_final_object_response_size_matches");
                check(digest(bytes,expected.size()).equals(new Digest(expected.size(),expected.sha256())),"actual_final_object_bytes_and_sha256_match");
            }
            var parts=query("SELECT part_no,size,sha256,object_key FROM upload_part WHERE session_id=? ORDER BY part_no",uploadId);
            var upload=one("SELECT expected_size,part_count FROM upload_session WHERE id=?",uploadId);long partBytes=0;
            check(number(upload,"expected_size")==expected.size()&&parts.size()==number(upload,"part_count"),"all_registered_upload_parts_present");
            int next=1;for(var part:parts){check(number(part,"part_no")==next++,"registered_part_numbers_contiguous");long size=number(part,"size");partBytes+=size;
                String partKey=text(part,"object_key");check(partKey.matches("upload-parts/"+uploadId+"/"+number(part,"part_no")+"/"+UUID),"part_object_has_server_owned_upload_namespace");
                try(var bytes=storage.getObject(request->request.bucket(value("S3_BUCKET","uav-evidence")).key(partKey))){check(digest(bytes,size).equals(new Digest(size,text(part,"sha256"))),"actual_part_bytes_match_registered_digest");}}
            check(partBytes==expected.size(),"registered_part_sizes_equal_formal_version_size");
            var submission=one("SELECT * FROM chain_submission WHERE version_id=?",id);
            check("CONFIRMED".equals(text(submission,"status"))&&operatorMsp.equals(text(submission,"confirmed_by"))
                    &&!truth(submission.get("retryable"))&&submission.get("next_action_at")==null&&submission.get("lease_token")==null
                    &&submission.get("lease_until")==null&&submission.get("last_error")==null,"confirmed_anchor_without_pending_recovery");
            var attempts=query("SELECT * FROM chain_attempt WHERE submission_id=?",text(submission,"id"));check(attempts.size()==1,"one_registration_attempt_per_retained_version");
            var attempt=attempts.getFirst();String tx=text(submission,"confirmed_tx_id");byte[] transaction=bytes(attempt,"transaction_bytes"),commit=bytes(attempt,"commit_bytes");
            check(tx.equals(text(attempt,"tx_id"))&&number(attempt,"attempt_no")==1&&"VALID".equals(text(attempt,"phase"))
                    &&number(attempt,"validation_code")==0&&number(attempt,"block_number")>=0,"persisted_original_attempt_is_valid");
            var outcome=operator.queryCommit(new EvidenceLedgerClient.CommitCheckpoint(tx,commit));
            check(outcome.valid()&&outcome.validationCode()==0&&tx.equals(outcome.transactionId())
                    &&outcome.blockNumber()==number(attempt,"block_number"),"original_commit_requeries_actual_valid_block");
            int versionNo=(int)number(version,"version_no");String evidence=text(version,"evidence_id");
            var a=owner.queryEvidence(evidence,versionNo);var b=operator.queryEvidence(evidence,versionNo);
            var record=new EvidenceLedgerClient.EvidenceRecord(evidence,versionNo,expected.sha256(),operatorMsp,tx);
            check(a.found()&&b.found()&&record.equals(a.record())&&record.equals(b.record()),"both_institutions_agree_on_all_five_ledger_fields");
            var prepared=PreparedTransaction.parseFrom(transaction);check(tx.equals(prepared.getTransactionId()),"prepared_checkpoint_keeps_original_transaction_id");
            var payload=Payload.parseFrom(prepared.getEnvelope().getPayload());var actions=Transaction.parseFrom(payload.getData());
            check(actions.getActionsCount()==1,"prepared_checkpoint_has_one_chaincode_action");
            var action=ChaincodeActionPayload.parseFrom(actions.getActions(0).getPayload());
            var proposal=ChaincodeProposalPayload.parseFrom(action.getChaincodeProposalPayload());
            var invocation=ChaincodeInvocationSpec.parseFrom(proposal.getInput());
            check(invocation.getChaincodeSpec().getInput().getArgsList().stream().map(v->v.toStringUtf8()).toList()
                    .equals(List.of(value("FABRIC_CONTRACT","EvidenceContract")+":RegisterEvidence",evidence,Integer.toString(versionNo),expected.sha256())),"prepared_checkpoint_registers_exact_retained_evidence_version_hash");
            Set<String> endorsers=new LinkedHashSet<>();for(var endorsement:action.getAction().getEndorsementsList())endorsers.add(SerializedIdentity.parseFrom(endorsement.getEndorser()).getMspid());
            check(endorsers.containsAll(Set.of(required("FABRIC_ORGA_MSP_ID"),operatorMsp)),"both_institutions_endorsed_original_transaction");
            Map<String,Object> safe=new LinkedHashMap<>();safe.put("id",id);safe.put("uploadId",uploadId);safe.put("executionId",text(version,"execution_id"));
            safe.put("reviewStatus","APPROVED");safe.put("anchorStatus","CONFIRMED");safe.put("size",expected.size());safe.put("sha256",expected.sha256());
            safe.put("objectBytesVerified",true);safe.put("partCount",parts.size());safe.put("ledgerByInstitution",Map.of("OrgA",a.record(),"OrgB",b.record()));
            safe.put("transaction",Map.of("transactionId",tx,"phase","VALID","validationCode",0,"blockNumber",outcome.blockNumber(),
                    "transactionCheckpointSha256",hash(transaction),"commitCheckpointSha256",hash(commit),"endorserMsps",endorsers.stream().sorted().toList()));safeVersions.add(safe);
        }
        var old=rows.get(input.oldVersionId());var fresh=rows.get(input.newVersionId());
        check(text(old,"evidence_id").equals(text(fresh,"evidence_id"))&&text(old,"requirement_id").equals(text(fresh,"requirement_id"))
                &&text(old,"execution_id").equals(text(fresh,"execution_id"))&&input.executionIds().contains(text(fresh,"execution_id"))
                &&number(old,"version_no")<number(fresh,"version_no"),"old_and_new_fixed_versions_preserve_same_material_in_current_execution");
        report.put("versions",safeVersions);report.put("versionCount",safeVersions.size());
    }
    private Connection openDatabase()throws Exception {
        String host=value("DB_HOST","127.0.0.1"),port=value("DB_PORT","3306"),name=value("DB_NAME","uav_inspection");
        check(host.matches("[A-Za-z0-9.-]+")&&port.matches("[0-9]{1,5}")&&name.matches("[A-Za-z0-9_]+"),"safe_database_connection_identifiers");
        Properties settings=new Properties();settings.setProperty("user",value("DB_USERNAME","uav_app"));settings.setProperty("password",required("DB_PASSWORD"));
        settings.setProperty("sslMode",value("DB_SSL_MODE","REQUIRED"));settings.setProperty("connectionTimeZone","+00:00");settings.setProperty("forceConnectionTimeZoneToSession","true");
        settings.setProperty("preserveInstants","true");settings.setProperty("characterEncoding","UTF-8");settings.setProperty("connectTimeout","5000");settings.setProperty("socketTimeout","15000");
        Connection database=DriverManager.getConnection("jdbc:mysql://"+host+":"+port+"/"+name,settings);
        database.setReadOnly(true);database.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);database.setAutoCommit(false);return database;
    }
    private Map<String,Long> validateDatabase()throws Exception {
        check(connection.isReadOnly()&&!connection.getAutoCommit()&&connection.getTransactionIsolation()==Connection.TRANSACTION_REPEATABLE_READ
                &&"MySQL".equals(connection.getMetaData().getDatabaseProductName()),"consistent_read_only_mysql_transaction");
        var session=one("SELECT @@session.time_zone AS zone,@@session.transaction_isolation AS isolation_level");
        check(Set.of("+00:00","UTC").contains(text(session,"zone"))&&"REPEATABLE-READ".equals(text(session,"isolation_level")),"utc_repeatable_read_snapshot");
        var migrations=query("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        check(migrations.size()==14,"fourteen_existing_migrations");Map<String,Long> actual=new LinkedHashMap<>();
        for(var migration:migrations){String version=text(migration,"version");check(MIGRATIONS.containsKey(version)&&truth(migration.get("success"))
                &&MIGRATIONS.get(version).longValue()==number(migration,"checksum"),"all_existing_migration_checksums_match");
            check(actual.put(version,number(migration,"checksum"))==null,"migration_versions_unique");}
        report.put("migrationChecksums",actual);report.put("readOnlyDatabaseTransaction",true);return actual;
    }
    private void pendingWork()throws Exception {
        Map<String,Long> pending=new LinkedHashMap<>();
        pending.put("outbox",count("SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','SENDING') OR lease_until>UTC_TIMESTAMP(3)"));
        pending.put("upload",count("SELECT COUNT(*) FROM upload_session WHERE status IN ('UPLOADING','VERIFYING') OR lease_until>UTC_TIMESTAMP(3)"));
        pending.put("anchor",count("SELECT COUNT(*) FROM chain_submission WHERE status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=TRUE) OR lease_until>UTC_TIMESTAMP(3)"));
        pending.put("consumer",count("SELECT COUNT(*) FROM consumed_event WHERE status<>'DONE'"));
        pending.put("cleanup",count("SELECT COUNT(*) FROM storage_cleanup_task WHERE status IN ('PENDING','SENDING') OR lease_until>UTC_TIMESTAMP(3)"));
        report.put("pendingWork",pending);
        report.put("failedOutboxTableRows",count("SELECT COUNT(*) FROM outbox_event WHERE status='FAILED'"));
        check(pending.values().stream().allMatch(value->value==0),"no_global_unfinished_database_work_before_default_workers");
    }
    private Snapshot snapshot()throws Exception {Map<String,TableSnapshot> tables=new LinkedHashMap<>();for(var table:TABLES.entrySet())tables.put(table.getKey(),tableSnapshot(table.getKey(),table.getValue()));return new Snapshot(DOMAIN,tables);}
    private TableSnapshot tableSnapshot(String table,String primaryKeys)throws Exception {
        try(var statement=select("SELECT * FROM "+table+" ORDER BY "+primaryKeys);ResultSet rows=statement.executeQuery()) {
            var metadata=rows.getMetaData();MessageDigest schema=sha();for(int column=1;column<=metadata.getColumnCount();column++){frame(schema,metadata.getColumnLabel(column));frame(schema,Integer.toString(metadata.getColumnType(column)));}
            String schemaHash=finish(schema);Map<String,String> values=new LinkedHashMap<>();
            while(rows.next()){MessageDigest row=sha();frame(row,DOMAIN);frame(row,table);frame(row,schemaHash);List<String> primary=new ArrayList<>();
                for(String key:primaryKeys.split(","))primary.add(rows.getString(key));
                for(int column=1;column<=metadata.getColumnCount();column++) {
                    if(Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB).contains(metadata.getColumnType(column))) {
                        try(InputStream input=rows.getBinaryStream(column)){if(input==null)frame(row,null);else{Digest content=digest(input,16777215L);frame(row,"binary");frame(row,Long.toString(content.size()));frame(row,content.sha256());}}
                    }else frame(row,rows.getString(column));
                }
                check(values.put(String.join("|",primary),finish(row))==null,"snapshot_primary_keys_unique");
            }
            return new TableSnapshot(schemaHash,values);
        }
    }
    private void compareOriginal(Snapshot before,Snapshot after) {
        check(DOMAIN.equals(before.domain())&&before.tables().keySet().equals(TABLES.keySet()),"complete_twenty_four_table_baseline");
        for(String table:TABLES.keySet()){var old=before.tables().get(table);var current=after.tables().get(table);
            check(old.schemaSha256().equals(current.schemaSha256())&&current.rows().entrySet().containsAll(old.rows().entrySet()),"every_original_row_column_and_blob_preserved_"+table);}
    }
    private S3Client openStorage(){return S3Client.builder().endpointOverride(URI.create(required("S3_ENDPOINT"))).region(Region.of(value("S3_REGION","garage")))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(required("S3_ACCESS_KEY"),required("S3_SECRET_KEY"))))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(Boolean.parseBoolean(value("S3_FORCE_PATH_STYLE","true"))).chunkedEncodingEnabled(false).build())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
            .overrideConfiguration(settings->settings.apiCallTimeout(Duration.ofSeconds(45)).apiCallAttemptTimeout(Duration.ofSeconds(30))).build();}
    private java.sql.PreparedStatement select(String sql,Object...args)throws Exception {
        check(sql.startsWith("SELECT "),"select_only_sql_contract");var statement=connection.prepareStatement(sql);statement.setQueryTimeout(15);
        for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;
    }
    private List<Map<String,Object>> query(String sql,Object...args)throws Exception {List<Map<String,Object>> result=new ArrayList<>();try(var statement=select(sql,args);var rows=statement.executeQuery()){
        while(rows.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=rows.getMetaData().getColumnCount();i++)row.put(rows.getMetaData().getColumnLabel(i),rows.getObject(i));result.add(row);}}return result;}
    private Map<String,Object> one(String sql,Object...args)throws Exception {var rows=query(sql,args);check(rows.size()==1,"one_scoped_business_row_required");return rows.getFirst();}
    private long count(String sql,Object...args)throws Exception {return ((Number)one(sql,args).values().iterator().next()).longValue();}
    private static Set<String> ids(List<Map<String,Object>> rows,String key){Set<String> ids=new LinkedHashSet<>();for(var row:rows)ids.add(text(row,key));return ids;}
    private static Map<String,Long> counts(Snapshot snapshot){Map<String,Long> counts=new LinkedHashMap<>();snapshot.tables().forEach((table,rows)->counts.put(table,(long)rows.rows().size()));return counts;}
    private static Digest digest(InputStream input,long maximum)throws Exception {MessageDigest digest=sha();long size=0;byte[] buffer=new byte[65536];for(int read;(read=input.read(buffer))!=-1;){size+=read;require(size<=maximum,"actual_bytes_exceed_expected_bound");digest.update(buffer,0,read);}return new Digest(size,finish(digest));}
    private void validate(Input input) {
        check(input!=null&&input.preflightReport()!=null&&Path.of(input.preflightReport()).isAbsolute(),"absolute_preflight_report_required");
        check(input.recheckPreflightReport()==null||Path.of(input.recheckPreflightReport()).isAbsolute(),"absolute_optional_recheck_preflight_report");
        for(String id:new String[]{input.taskId(),input.oldExecutionId(),input.oldVersionId(),input.newVersionId(),input.revokedGrantId(),input.activeGrantId()})id(id);
        idList(input.deviceIds(),4);idList(input.subtaskIds(),3);idList(input.executionIds(),3);idList(input.uploadIds(),4);idList(input.versionIds(),4);
        check(!input.executionIds().contains(input.oldExecutionId())&&!input.oldVersionId().equals(input.newVersionId())
                &&input.versionIds().containsAll(List.of(input.oldVersionId(),input.newVersionId()))&&!input.revokedGrantId().equals(input.activeGrantId()),"distinct_old_new_execution_version_and_grants");
        check(input.expectedFiles()!=null&&input.expectedFiles().keySet().equals(Set.copyOf(input.versionIds())),"expected_bytes_metadata_for_all_four_versions");
        for(var file:input.expectedFiles().values())check(file!=null&&file.sha256()!=null&&file.sha256().matches("[0-9a-f]{64}")&&file.size()>0&&file.size()<=209715200L,"valid_expected_file_hash_and_size");
    }
    private void idList(List<String> ids,int size){check(ids!=null&&ids.size()==size&&new LinkedHashSet<>(ids).size()==size,"expected_distinct_resource_list");for(String id:ids)id(id);}
    private void id(String id){check(id!=null&&id.matches(UUID),"valid_resource_uuid");}
    private static void privateOutput(Path path)throws Exception {
        Path local=Path.of(".local").toAbsolutePath().normalize();require(path.startsWith(local)&&Files.isDirectory(path.getParent())
                &&!Files.isSymbolicLink(path)&&!Files.isSymbolicLink(path.getParent()),"private_local_output_required");
        require(Files.getPosixFilePermissions(path.getParent()).stream().noneMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")),"private_output_directory_required");
    }
    private void write()throws Exception {
        String text=JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        check(!text.contains("upload-parts/")&&!text.contains("evidence-files/")&&!text.contains("-----BEGIN")
                &&!text.contains("\"object_key\"")&&!text.contains("\"transaction_bytes\"")&&!text.contains("\"commit_bytes\"")
                &&!text.contains("\"accessToken\"")&&!text.contains("\"deviceToken\"")&&!text.contains("\"password\"")
                &&secrets.stream().noneMatch(secret->!secret.isEmpty()&&text.contains(secret)),"report_contains_only_safe_metadata_and_hashes");
        byte[] content=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        Path temporary=Files.createTempFile(output.getParent(),".retained-inspector-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{Files.write(temporary,content);Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
    private Path credentialsPath(){return Path.of(value("DEMO_CREDENTIALS_PATH",".local/demo-credentials.json")).toAbsolutePath().normalize();}
    private String required(String key){String value=environment.get(key);check(value!=null&&!value.isBlank(),"required_environment_present");return value;}
    private String value(String key,String fallback){String value=environment.get(key);return value==null||value.isBlank()?fallback:value;}
    private static String text(Map<String,Object> row,String key){require(row.get(key) instanceof String,"required_text_metadata");return (String)row.get(key);}
    private static long number(Map<String,Object> row,String key){require(row.get(key) instanceof Number,"required_numeric_metadata");return ((Number)row.get(key)).longValue();}
    private static byte[] bytes(Map<String,Object> row,String key)throws Exception {Object value=row.get(key);if(value instanceof byte[] bytes&&bytes.length>0)return bytes;if(value instanceof java.sql.Blob blob&&blob.length()>0)return blob.getBytes(1,(int)blob.length());throw new CheckFailure("required_binary_checkpoint");}
    private static boolean truth(Object value){return Boolean.TRUE.equals(value)||value instanceof Number number&&number.intValue()==1;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){require(value instanceof Map,"json_object_required");return (Map<String,Object>)value;}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception failure){throw new IllegalStateException("SHA-256 unavailable");}}
    private static String finish(MessageDigest digest){return HexFormat.of().formatHex(digest.digest());}
    private static String hash(byte[] bytes){MessageDigest digest=sha();digest.update(bytes);return finish(digest);}
    private static void frame(MessageDigest digest,String value){byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)digest.update(bytes);}
    private void check(boolean condition,String name){require(condition,name);if(!checks.contains(name))checks.add(name);}
    private static void require(boolean condition,String name){if(!condition)throw new CheckFailure(name);}
    public record Input(String preflightReport,String taskId,List<String> deviceIds,List<String> subtaskIds,
                        String oldExecutionId,List<String> executionIds,List<String> uploadIds,List<String> versionIds,
                        String oldVersionId,String newVersionId,String revokedGrantId,String activeGrantId,
                        Map<String,ExpectedFile> expectedFiles,String recheckPreflightReport) {}
    public record ExpectedFile(String sha256,long size) {}
    public record Snapshot(String domain,Map<String,TableSnapshot> tables) {}
    public record TableSnapshot(String schemaSha256,Map<String,String> rows) {}
    private record Digest(long size,String sha256) {}
    private static final class CheckFailure extends RuntimeException {CheckFailure(String name){super(name);}}
}
