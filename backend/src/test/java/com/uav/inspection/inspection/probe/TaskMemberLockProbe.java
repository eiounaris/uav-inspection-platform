package com.uav.inspection.inspection.probe;

import com.uav.inspection.UavInspectionApplication;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.Role;
import com.uav.inspection.inspection.TaskModels.*;
import com.uav.inspection.inspection.TaskService;
import com.uav.inspection.inspection.persistence.TaskMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Two independently locked tasks exercise the real service and MySQL account/FK lock ordering. */
public final class TaskMemberLockProbe {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String OWNER="00000000-0000-4000-8000-000000000001",OPERATOR="00000000-0000-4000-8000-000000000002";
    private static final List<String> TABLES=List.of("organization","app_user","inspection_task","task_member","drone","inspection_subtask","material_requirement","task_execution","upload_session","upload_part","evidence_version","outbox_event","consumed_event","chain_submission","chain_attempt","verification_record","device_event","execution_adjustment","issue","review_record","access_grant","message_rejection","anchor_retry_request","storage_cleanup_task");
    private final String runId=UUID.randomUUID().toString();
    private final List<String> accounts=List.of(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
    private final List<String> tasks=new ArrayList<>();
    private final Map<String,Object> report=new LinkedHashMap<>();
    private JdbcTemplate jdbc;private DataSource source;

    public static void main(String[] args){
        if(args.length!=1){System.err.println("Usage: TaskMemberLockProbe <private-report-json>");System.exit(2);return;}
        var probe=new TaskMemberLockProbe();
        try{probe.run();probe.write(Path.of(args[0]));System.out.println("[PASS] T026 cross-task member locks");}
        catch(Exception failure){probe.report.put("passed",false);probe.report.put("failureType",failure.getClass().getSimpleName());if(failure instanceof CheckFailure)probe.report.put("failedCheck",failure.getMessage());try{probe.write(Path.of(args[0]));}catch(Exception ignored){}System.err.println("[FAIL] T026 member locks; inspect private safe report");System.exit(1);}
    }

    private void run()throws Exception{
        report.put("formatVersion",1);report.put("passed",false);report.put("runId",runId);report.put("startedAt",Instant.now().toString());
        try(var context=new SpringApplicationBuilder(UavInspectionApplication.class,GateConfiguration.class).profiles("local","database","member-lock-probe").web(WebApplicationType.NONE).logStartupInfo(false).run()){
            jdbc=context.getBean(JdbcTemplate.class);source=context.getBean(DataSource.class);TaskService service=context.getBean(TaskService.class);Control control=context.getBean(Control.class);
            check(count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL")==14,"existing_fourteen_migrations");
            String before=snapshot();report.put("snapshotDomain","T026-member-lock-full-twenty-four-table-v1");report.put("originalTwentyFourTableSha256",before);
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try{
                for(int i=0;i<accounts.size();i++){
                    check(jdbc.update("INSERT INTO app_user(id,org_id,username,password_hash,roles) VALUES(?,?,?,?,CAST(? AS JSON))",accounts.get(i),i==0?OWNER:OPERATOR,"T026-lock-"+runId+"-"+i,"$2a$12$"+".".repeat(53),i==0?"[\"OWNER_TASK_ADMIN\"]":"[\"OPERATOR_TASK_ADMIN\"]")==1,"isolated_account_created");
                }
                CurrentUser owner=principal(0),first=principal(1),second=principal(2);
                List<String> requested=List.of(second.id(),first.id());
                long[] versions=new long[2];
                for(int i=0;i<2;i++){
                    TaskDetail task=service.create(owner,new CreateTaskRequest("T026 member locks "+i,"Isolated cross-task membership regression",List.of()));tasks.add(task.id());
                    task=service.assign(owner,task.id(),new AssignTaskRequest(OPERATOR,i==0?first.id():second.id(),task.lockVersion()));
                    task=service.replaceMembers(i==0?first:second,task.id(),new ReplaceMembersRequest(requested,task.lockVersion()));versions[i]=task.lockVersion();
                }
                int rounds=8;
                List<Map<String,Object>> results=new ArrayList<>();
                for(int round=0;round<rounds;round++){
                    Gate gate=new Gate(Set.copyOf(tasks));control.current=gate;
                    long leftVersion=versions[0],rightVersion=versions[1];
                    Future<TaskDetail> left=workers.submit(()->service.replaceMembers(first,tasks.get(0),new ReplaceMembersRequest(requested,leftVersion)));
                    Future<TaskDetail> right=workers.submit(()->service.replaceMembers(second,tasks.get(1),new ReplaceMembersRequest(requested,rightVersion)));
                    try{
                        check(gate.parents.await(8,TimeUnit.SECONDS),"both_distinct_parent_tasks_locked_before_account_competition");
                        gate.start.countDown();
                        TaskDetail a=left.get(20,TimeUnit.SECONDS),b=right.get(20,TimeUnit.SECONDS);
                        versions[0]=a.lockVersion();versions[1]=b.lockVersion();
                        List<String> expected=requested.stream().sorted().toList();
                        check(gate.orders.size()==2&&gate.orders.values().stream().allMatch(expected::equals),"both_real_workers_lock_the_same_accounts_in_global_order");
                        for(int i=0;i<2;i++){
                            check(count("SELECT lock_version FROM inspection_task WHERE id=?",tasks.get(i))==versions[i],"committed_task_version_matches_service_result");
                            check(count("SELECT COUNT(*) FROM task_member WHERE task_id=?",tasks.get(i))==3,"creator_and_both_operator_members_preserved");
                            check(count("SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND action='TASK_MEMBERS_UPDATED'",tasks.get(i))==round+2,"one_membership_audit_per_committed_change");
                        }
                        results.add(Map.of("round",round+1,"successfulIndependentTasks",2,"deadlocks",0,"globalAccountOrderVerified",true));
                    }finally{gate.start.countDown();control.current=null;}
                }
                report.put("rounds",rounds);report.put("successfulMemberUpdates",rounds*2);report.put("deadlocks",0);report.put("bothParentLocksHeldBeforeEachRace",true);report.put("globalAccountOrderVerified",true);report.put("roundResults",results);report.put("serviceAndDatabaseResultsAgree",true);
                report.put("auditRowsRetained",count("SELECT COUNT(*) FROM audit_log WHERE resource_id IN (?,?)",tasks.get(0),tasks.get(1)));
            }finally{
                if(control.current!=null)control.current.start.countDown();control.current=null;
                workers.shutdownNow();
                if(!workers.awaitTermination(20,TimeUnit.SECONDS)){report.put("cleanupSkippedDueToActiveWorker",true);throw new CheckFailure("active_worker_prevents_fixture_cleanup");}
                for(String task:tasks){jdbc.update("DELETE FROM task_member WHERE task_id=?",task);jdbc.update("DELETE FROM inspection_task WHERE id=?",task);}
                for(String account:accounts)jdbc.update("DELETE FROM app_user WHERE id=?",account);
                report.put("fixtureTasksDeleted",tasks.size());report.put("fixtureAccountsDeleted",accounts.size());
                boolean unchanged=before.equals(snapshot());report.put("originalBusinessDataUnchanged",unchanged);check(unchanged,"original_twenty_four_tables_preserved_after_fixture_cleanup");
            }
        }
        report.put("realLedgerRegistrations",0);report.put("scope","Real MySQL and TaskService concurrency; no HTTP, S3, messaging or Fabric worker is started.");report.put("completedAt",Instant.now().toString());report.put("passed",true);
    }
    private CurrentUser principal(int index){return new CurrentUser(accounts.get(index),index==0?OWNER:OPERATOR,"fixture-principal",List.of(index==0?Role.OWNER_TASK_ADMIN:Role.OPERATOR_TASK_ADMIN),0,0);}
    private long count(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);if(value==null)throw new CheckFailure("missing_sql_result");return value;}
    private static void check(boolean value,String name){if(!value)throw new CheckFailure(name);}
    private String snapshot()throws Exception{
        MessageDigest out=MessageDigest.getInstance("SHA-256");
        try(Connection connection=source.getConnection()){
            for(String table:TABLES){List<String> hashes=new ArrayList<>();
                try(var query=connection.createStatement();ResultSet rows=query.executeQuery("SELECT * FROM "+table)){
                    while(rows.next()){MessageDigest row=MessageDigest.getInstance("SHA-256");for(int column=1;column<=rows.getMetaData().getColumnCount();column++){byte[] bytes=rows.getBytes(column);row.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)row.update(bytes);}hashes.add(HexFormat.of().formatHex(row.digest()));}
                }
                Collections.sort(hashes);out.update(table.getBytes(StandardCharsets.UTF_8));for(String hash:hashes)out.update(hash.getBytes(StandardCharsets.US_ASCII));
            }
        }
        return HexFormat.of().formatHex(out.digest());
    }
    private void write(Path output)throws Exception{
        output=output.toAbsolutePath().normalize();Path temporary=Files.createTempFile(output.getParent(),".member-lock-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{Files.writeString(temporary,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
    private static final class CheckFailure extends RuntimeException{CheckFailure(String name){super(name);}}
    static final class Control{volatile Gate current;}
    static final class Gate{
        final Set<String> tasks;final CountDownLatch parents=new CountDownLatch(2),start=new CountDownLatch(1);final Map<Long,List<String>> orders=new ConcurrentHashMap<>();
        Gate(Set<String> tasks){this.tasks=tasks;}
    }
    @TestConfiguration(proxyBeanMethods=false) @Profile("member-lock-probe")
    static class GateConfiguration{
        @Bean Control memberLockControl(){return new Control();}
        @Bean @Primary TaskMapper orderedMemberProbeMapper(@Qualifier("taskMapper")TaskMapper real,Control control){
            return (TaskMapper)Proxy.newProxyInstance(TaskMapper.class.getClassLoader(),new Class<?>[]{TaskMapper.class},(proxy,method,args)->{
                Gate gate=control.current;
                if(gate!=null&&method.getName().equals("lockCandidate"))gate.orders.computeIfAbsent(Thread.currentThread().threadId(),ignored->new ArrayList<>()).add((String)args[0]);
                Object value;try{value=method.invoke(real,args);}catch(InvocationTargetException wrapped){throw wrapped.getCause();}
                if(gate!=null&&method.getName().equals("lockTask")&&gate.tasks.contains(args[0])){
                    gate.parents.countDown();if(!gate.start.await(10,TimeUnit.SECONDS))throw new CheckFailure("parent_lock_barrier_timeout");
                }
                return value;
            });
        }
    }
}
