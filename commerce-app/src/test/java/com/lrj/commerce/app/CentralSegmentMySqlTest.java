package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.campaign.segment.api.SegmentApi;
import com.lrj.commerce.campaign.segment.application.SegmentService;
import com.lrj.commerce.campaign.rule.api.RuleNode;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;

/** 真实MySQL验证原来源、固定政策与事务；中央协议桩仅验证适配，实际Auth原路径仍须跨进程验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralSegmentMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @Autowired CentralEmployeeService service;
    @Autowired SegmentApi segments;
    @Autowired EmployeeAccess access;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.lrj.commerce.campaign.segment.infrastructure.persistence.SegmentMapper mapper;
    @MockitoSpyBean com.lrj.commerce.member.growth.api.MemberGrowthApi growth;
    @Autowired com.lrj.commerce.marketing.api.RuleDecisionPort rules;
    @Autowired com.lrj.commerce.runtime.command.Commands commands;
    @Autowired com.lrj.commerce.runtime.event.Outbox outbox;
    @LocalServerPort int port;
    private String tenant,authTenant,principal,member,grant,adminToken;
    private Actor admin;
    private final Set<String> allowed=ConcurrentHashMap.newKeySet(),revoked=ConcurrentHashMap.newKeySet();
    private final Map<String,Reference> references=new ConcurrentHashMap<>();
    private final AtomicLong generation=new AtomicLong(1),refreshSeconds=new AtomicLong(86460);
    private final AtomicBoolean unavailable=new AtomicBoolean(),partial=new AtomicBoolean();
    private final AtomicReference<Runnable> afterScope=new AtomicReference<>(),afterResource=new AtomicReference<>();
    private static final List<EmployeeAccess.Capability> CAPS=List.of(SEGMENT_READ,SEGMENT_CREATE,SEGMENT_SCHEDULE,SEGMENT_REFRESH,SEGMENT_CONTROL,SEGMENT_PUMP);
    private record Reference(String capability,long generation,Instant expires) {}
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        tenant="segment-"+id();authTenant=id();principal=id();member=id();grant=id();adminToken=id();
        allowed.clear();revoked.clear();references.clear();generation.set(1);refreshSeconds.set(86460);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:CAPS)allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','segment-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'SEGMENT','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='SEGMENT'",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','test','BASIC','ACTIVE',0)",tenant);
        when(client.issueExecution(anyString(),any(),any())).thenAnswer(call->{
            if(!"valid".equals(call.getArgument(0)))throw new CentralAccessException(401);
            CentralAccessDtos.Check check=call.getArgument(1);require(check);String ref=id();Instant requested=call.getArgument(2);
            long seconds=Duration.between(Instant.now(),requested).getSeconds();assertTrue(seconds>0);
            if(check.capability().equals(SEGMENT_REFRESH.code()))assertTrue(seconds>=86458&&seconds<=86460);else assertTrue(seconds<=60);
            Instant expires=check.capability().equals(SEGMENT_REFRESH.code())&&refreshSeconds.get()<86460?Instant.now().plusSeconds(refreshSeconds.get()):requested;
            references.put(ref,new Reference(check.capability(),generation.get(),expires));
            return new ExecutionAccessDtos.Reference("1",check.requestId(),ref,context(),check.capability(),check.resourceType(),expires.toString());
        });
        when(client.executionScope(anyString(),any())).thenAnswer(call->{
            CentralAccessDtos.Check check=call.getArgument(1);requireReference(call.getArgument(0),check);
            var clause=new ScopeDtos.Clause(partial.get()?ScopeDtos.Kind.SPECIFIED_RESOURCES:ScopeDtos.Kind.TENANT_ALL,partial.get()?List.of("S"):List.of(),false);
            var plan=new ScopeAccessDtos.Plan("1",check.requestId(),check.capability(),check.resourceType(),"ALLOW",id(),context(),"policy",1,"directory",1,1,1,Instant.now().plusSeconds(20).toString(),List.of(new ScopeDtos.Alternative(grant,1,List.of(clause))));
            var mutation=afterScope.getAndSet(null);if(mutation!=null)mutation.run();return plan;
        });
        when(client.checkExecution(anyString(),any(),any())).thenAnswer(call->{
            CentralAccessDtos.Check check=call.getArgument(1);ScopeDtos.Facts facts=call.getArgument(2);requireReference(call.getArgument(0),check);
            assertEquals(authTenant,facts.tenantId());assertEquals("segment",facts.resourceType());assertTrue(facts.resourceVersion()>0);
            assertNull(facts.storeId());assertNull(facts.departmentId());assertNull(facts.ownerPrincipalId());
            var decision=new ScopeAccessDtos.ResourceDecision("1",check.requestId(),check.capability(),check.resourceType(),facts.resourceId(),facts.resourceVersion(),"ALLOW",id(),context(),Instant.now().plusSeconds(20).toString());
            var mutation=afterResource.getAndSet(null);if(mutation!=null)mutation.run();return decision;
        });
    }
    private void require(CentralAccessDtos.Check check) {
        if(unavailable.get())throw new CentralAccessException(503);
        if(!authTenant.equals(check.tenantId())||!allowed.contains(check.capability()))throw new AccessDeniedException("denied");
    }
    private void requireReference(String ref,CentralAccessDtos.Check check) {
        require(check);var original=references.get(ref);
        if(original==null||!check.capability().equals(original.capability())||revoked.contains(ref)||original.generation()!=generation.get()||!original.expires().isAfter(Instant.now()))throw new AccessDeniedException("original denied");
    }
    private GovernanceDtos.AccessContext context(){return new GovernanceDtos.AccessContext(principal,member,generation.get(),1,1,authTenant,"commerce","test","segment-test","HUMAN",id());}
    private Actor actor(EmployeeAccess.Capability cap){return service.authenticate("valid",authTenant,cap);}
    private void forbidden(Runnable action){assertEquals(DomainException.Code.FORBIDDEN,assertThrows(DomainException.class,action::run).code());}
    private SegmentApi.Definition input(String name,long version){return new SegmentApi.Definition(name,version,"中央动态人群",new RuleNode("COMPARE","memberLevel","EQ","TEXT","BASIC",null),3600,60,1000);}
    private SegmentApi.View create(String name,long version){return segments.create(actor(SEGMENT_CREATE),id(),input(name,version));}
    private void stop(){jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='SEGMENT'",tenant);}
    private int count(String table) {
        if(!Set.of("marketing_segment","marketing_segment_definition","marketing_segment_run","marketing_audience_snapshot","marketing_audience_member","employee_command_identity","platform_command","platform_event").contains(table))throw new IllegalArgumentException("未登记测试表");
        return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);
    }
    private String source(SegmentApi.Run run){return mapper.source(tenant,run.runId());}
    private SegmentApi.Run actual(SegmentApi.Run run){return mapper.runFind(tenant,run.runId());}
    private void ready(SegmentApi.Run run){jdbc.update("UPDATE marketing_segment_run SET available_at=UTC_TIMESTAMP(3),entry_available_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND run_id=?",tenant,run.runId());}
    private SegmentService restarted(){return new SegmentService(mapper,growth,rules,commands,Clock.systemUTC(),transactions,outbox,new com.lrj.commerce.runtime.work.WorkLanes(),access);}

    /** 六独立HTTP入口只用各自资格，旧ADMIN、无header及未知动作不能绕过已接管路由。 */
    @Test void sixCapabilitiesAreIndependentAtRealHttpBoundary() throws Exception {
        create("S",7);
        var run=segments.refresh(actor(SEGMENT_REFRESH),id(),"S");
        for(var cap:CAPS) {
            allowed.clear();allowed.add(cap.code());
            for(var other:CAPS) {
                String path=switch(other){case SEGMENT_READ,SEGMENT_CREATE->"/v1/admin/segments";case SEGMENT_SCHEDULE->"/v1/admin/segments/S/schedule";case SEGMENT_REFRESH->"/v1/admin/segments/S/refresh";case SEGMENT_PUMP->"/v1/admin/segments/pump";default->"/v1/admin/segment-runs/"+run.runId()+"/cancel";};
                Object body=other==SEGMENT_CREATE?input("N-"+cap.name(),1):other==SEGMENT_SCHEDULE?new SegmentApi.Schedule(0,true):null;
                var reply=http(other==SEGMENT_READ?"GET":"POST",path,"valid",true,body);
                if(cap!=other)assertEquals(403,reply.statusCode(),cap+" must not grant "+other);
                else if(cap==SEGMENT_CONTROL)assertEquals(403,reply.statusCode(),"current command cannot replace the original refresh source");
                else assertEquals(200,reply.statusCode(),cap+" own entry");
            }
        }
        assertEquals(403,http("GET","/v1/admin/segments",adminToken,false,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/segments","bad",true,null).statusCode());
        assertEquals(401,http("POST","/v1/admin/segment-runs/"+run.runId()+"/unknown","valid",true,null).statusCode());
        assertEquals(401,http("POST","/v1/admin/skus","valid",true,Map.of()).statusCode());
    }
    /** 五个提示逐项判权：只返回资格，任何组合都不隐含读取或其他操作，也不写命令和审计。 */
    @Test void actionHintsAreIndependentReadOnlyAndFailClosedAtHttp() throws Exception {
        var hints=List.of(SEGMENT_CREATE,SEGMENT_SCHEDULE,SEGMENT_REFRESH,SEGMENT_CONTROL,SEGMENT_PUMP);
        for(var granted:CAPS) {
            allowed.clear();allowed.add(granted.code());
            for(var hint:hints) {
                String path="/v1/operations/segments/"+hint.name().substring("SEGMENT_".length()).toLowerCase(Locale.ROOT)+"-access";
                var reply=http("GET",path,"valid",true,null);
                assertEquals(granted==hint?200:403,reply.statusCode(),granted+" / "+hint);
                if(granted==hint) assertEquals(Map.of("allowed",true),JsonCodec.read(reply.body(),Map.class));
            }
        }
        allowed.clear();for(var cap:CAPS)allowed.add(cap.code());
        for(var hint:hints) {
            String path="/v1/operations/segments/"+hint.name().substring("SEGMENT_".length()).toLowerCase(Locale.ROOT)+"-access";
            assertEquals(401,http("GET",path,"bad",true,null).statusCode());
            assertEquals(401,http("GET",path,"valid",false,null).statusCode());
            assertEquals(401,http("POST",path,"valid",true,null).statusCode());
            assertEquals(403,http("GET",path,adminToken,false,null).statusCode());
            unavailable.set(true);assertEquals(503,http("GET",path,"valid",true,null).statusCode());unavailable.set(false);
        }
        assertEquals(401,http("GET","/v1/operations/segments/read-access","valid",true,null).statusCode());
        assertEquals(0,count("platform_command"));assertEquals(0,count("employee_command_identity"));
        assertEquals(0,count("marketing_segment_run"));assertEquals(0,count("platform_event"));
    }

    /** 提示期间发生授权范围变化须拒绝，固定壳页不能变成任意路径或匿名业务入口。 */
    @Test void hintScopeChangesAndFixedShellDoNotExposeData() throws Exception {
        afterScope.set(()->generation.incrementAndGet());
        assertEquals(403,http("GET","/v1/operations/segments/schedule-access","valid",true,null).statusCode());
        generation.set(1);partial.set(true);
        assertEquals(403,http("GET","/v1/operations/segments/create-access","valid",true,null).statusCode());
        partial.set(false);
        assertEquals(200,http("GET","/operations/segments",null,false,null).statusCode());
        assertNotEquals(200,http("GET","/operations/segments/unknown",null,false,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/segments",null,false,null).statusCode());
        assertEquals(0,count("platform_command"));assertEquals(0,count("employee_command_identity"));
    }

    /** 定义版本、锁版本及原键分别保持；换nonce不换主体，实际版本审计不能冒用任务或快照ID。 */
    @Test void definitionsAndScheduleUsePositiveOwnerVersionAndStableReceipt() {
        String key=id();var def=input("S",7);var first=segments.create(actor(SEGMENT_CREATE),key,def);
        assertEquals(first,segments.create(actor(SEGMENT_CREATE),key,def));
        var enable=segments.schedule(actor(SEGMENT_SCHEDULE),"enable","S",new SegmentApi.Schedule(0,true));
        assertTrue(enable.enabled());assertEquals(1,enable.lockVersion());
        assertEquals(enable,segments.schedule(actor(SEGMENT_SCHEDULE),"enable","S",new SegmentApi.Schedule(0,true)));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->segments.schedule(actor(SEGMENT_SCHEDULE),id(),"S",new SegmentApi.Schedule(0,false))).code());
        create("S",8);assertFalse(segments.definitions(actor(SEGMENT_READ),"",10).getFirst().enabled());
        assertEquals(3,count("employee_command_identity"));
        assertEquals(Set.of(7L,8L),new HashSet<>(jdbc.queryForList("SELECT resource_version FROM employee_command_identity WHERE tenant_id=?",Long.class,tenant)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND (resource_type<>'segment' OR store_id IS NOT NULL)",Integer.class,tenant));
        verify(client,atLeastOnce()).checkExecution(anyString(),any(),argThat(f->f.resourceId().equals("S")&&f.resourceVersion()==7));
    }
    /** 并发刷新全部返回原任务，来源只写一次，更新定义仍以原任务固定版本审计。 */
    @Test void concurrentRefreshNeverReplacesOriginalCreatorOrDefinition() throws Exception {
        create("S",7);var originalActor=actor(SEGMENT_REFRESH);String key=id();var run=segments.refresh(originalActor,key,"S");String original=source(run);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<SegmentApi.Run>>();
            for(int i=0;i<4;i++)futures.add(executor.submit(()->segments.refresh(actor(SEGMENT_REFRESH),id(),"S")));
            for(var future:futures)assertEquals(run.runId(),future.get(15,TimeUnit.SECONDS).runId());
        }
        create("S",8);assertEquals(run.runId(),segments.refresh(actor(SEGMENT_REFRESH),id(),"S").runId());
        assertEquals(original,source(run));assertEquals(1,count("marketing_segment_run"));
        assertEquals(7,actual(run).definitionVersion());assertEquals(3600,Duration.between(run.startedAt(),run.validUntil()).getSeconds());
        var meta=access.segmentExecution(originalActor);assertEquals(references.get(originalActor.executionId()).expires(),meta.expiresAt());
        assertEquals(meta.identity().generation(),1);assertEquals("test",meta.environment());
        assertEquals(6,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND capability='commerce.segment.refresh' AND resource_version=7",Integer.class,tenant));
    }
    /** 撤权后新增同能力Grant/控制/推进均不能复活原任务，包括已有成功回执。 */
    @Test void revokedOriginalIsDeniedBeforeFreshRefreshOrControlReceipts() {
        create("S",7);String key=id();var original=actor(SEGMENT_REFRESH);var run=segments.refresh(original,key,"S");String source=source(run);
        revoked.add(original.executionId());
        forbidden(()->segments.refresh(actor(SEGMENT_REFRESH),key,"S"));
        forbidden(()->segments.refresh(actor(SEGMENT_REFRESH),id(),"S"));
        forbidden(()->segments.control(actor(SEGMENT_CONTROL),id(),run.runId(),"cancel"));
        assertEquals(0,restarted().pump(actor(SEGMENT_PUMP)));
        assertEquals(0,actual(run).processed());assertEquals(0,count("marketing_audience_snapshot"));assertEquals(source,source(run));
        assertEquals(2,count("employee_command_identity"));
    }
    /** 真正过期的原引用不得用新认证替换，业务TTL仍独立保持。 */
    @Test void exactOriginalExpiryStopsRestartedOwnerWithoutChangingFreshness() throws Exception {
        create("S",7);refreshSeconds.set(1);var original=actor(SEGMENT_REFRESH);var run=segments.refresh(original,id(),"S");
        assertTrue(access.segmentExecution(original).expiresAt().isBefore(run.validUntil()));
        Thread.sleep(1200);refreshSeconds.set(86460);
        forbidden(()->segments.refresh(actor(SEGMENT_REFRESH),id(),"S"));
        assertEquals(0,restarted().pump(actor(SEGMENT_PUMP)));assertEquals(0,actual(run).processed());assertEquals(0,count("marketing_audience_snapshot"));
    }
    /** 一百名会员批次和公告分别复核来源，拒绝不会撤回已公布快照或已提交Outbox。 */
    @Test void originalSourceIsRecheckedAtNextBatchAndAnnouncements() {
        for(int i=0;i<104;i++)jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,?,?,'test','BASIC','ACTIVE',0)",tenant,String.format("B%03d",i),"customer-"+i);
        create("S",7);var original=actor(SEGMENT_REFRESH);var run=segments.refresh(original,id(),"S");
        assertEquals(1,restarted().pump(actor(SEGMENT_PUMP)));assertEquals(100,actual(run).processed());assertEquals(0,count("marketing_audience_snapshot"));
        revoked.add(original.executionId());ready(run);assertEquals(0,restarted().pump(actor(SEGMENT_PUMP)));assertEquals(100,actual(run).processed());
        assertEquals(100,count("marketing_audience_member"));assertEquals(0,count("marketing_audience_snapshot"));
        revoked.clear();ready(run);assertEquals(1,restarted().pump(actor(SEGMENT_PUMP)));assertEquals("COMPLETED",actual(run).status());
        assertEquals(105,actual(run).matched());assertEquals(1,count("marketing_audience_snapshot"));assertFalse(actual(run).entriesAnnounced());
        int announcements=jdbc.queryForObject("SELECT count(*) FROM platform_event WHERE tenant_id=? AND event_type='segment.member.entered.v1'",Integer.class,tenant);
        assertEquals(100,announcements);revoked.add(original.executionId());ready(run);restarted().pump(actor(SEGMENT_PUMP));
        assertEquals(announcements,jdbc.queryForObject("SELECT count(*) FROM platform_event WHERE tenant_id=? AND event_type='segment.member.entered.v1'",Integer.class,tenant));
        assertEquals(1,count("marketing_audience_snapshot"));assertFalse(actual(run).entriesAnnounced());
    }
    /** 周期政策独立于批准人和人工推进的Grant，新版本/停调度只停未来启动，原任务固定旧定义。 */
    @Test void approvedPeriodicPolicyIsIndependentAndPinnedAcrossNewDefinitions() {
        for(int i=0;i<104;i++)jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,?,?,'test','BASIC','ACTIVE',0)",tenant,String.format("B%03d",i),"customer-"+i);
        create("S",7);segments.schedule(actor(SEGMENT_SCHEDULE),id(),"S",new SegmentApi.Schedule(0,true));
        allowed.clear();allowed.add(SEGMENT_PUMP.code());assertEquals(1,restarted().pump(actor(SEGMENT_PUMP)));
        var run=jdbc.queryForObject("SELECT run_id FROM marketing_segment_run WHERE tenant_id=?",String.class,tenant);var current=mapper.runFind(tenant,run);String source=source(current);
        assertTrue(source.contains("SYSTEM"));assertFalse(source.contains("executionId"));assertEquals(100,current.processed());
        allowed.add(SEGMENT_SCHEDULE.code());segments.schedule(actor(SEGMENT_SCHEDULE),id(),"S",new SegmentApi.Schedule(1,false));allowed.remove(SEGMENT_SCHEDULE.code());
        allowed.add(SEGMENT_CREATE.code());create("S",8);allowed.remove(SEGMENT_CREATE.code());
        ready(current);assertEquals(1,restarted().pump(actor(SEGMENT_PUMP)));assertEquals("COMPLETED",actual(current).status());assertEquals(7,actual(current).definitionVersion());assertEquals(source,source(current));
        ready(current);restarted().pump(actor(SEGMENT_PUMP));assertTrue(actual(current).entriesAnnounced());assertEquals(1,count("marketing_segment_run"));
        var pumpActor=actor(SEGMENT_PUMP);var policyRoute=access.central(authTenant,SEGMENT_SCHEDULE);
        stop();forbidden(()->restarted().pump(pumpActor));forbidden(()->access.segmentPolicy(tenant,policyRoute));assertEquals(1,count("marketing_segment_run"));
    }
    /** 未知旧来源在CENTRAL下拒绝，而原未接管租户仍保留兼容；不能推断空政策已被批准。 */
    @Test void missingProvenanceCannotBecomeApprovedCentralPolicy() {
        create("S",7);var run=segments.refresh(actor(SEGMENT_REFRESH),id(),"S");
        jdbc.update("UPDATE marketing_segment_run SET execution_source_json=NULL WHERE tenant_id=? AND run_id=?",tenant,run.runId());
        assertEquals(0,restarted().pump(actor(SEGMENT_PUMP)));assertEquals(0,actual(run).processed());
        forbidden(()->segments.control(actor(SEGMENT_CONTROL),id(),run.runId(),"cancel"));
        jdbc.update("UPDATE marketing_segment_run SET status='CANCELLED' WHERE tenant_id=? AND run_id=?",tenant,run.runId());
        jdbc.update("UPDATE marketing_segment SET enabled=TRUE,schedule_policy_json=NULL,next_due=UTC_TIMESTAMP(3) WHERE tenant_id=?",tenant);
        restarted().pump(actor(SEGMENT_PUMP));assertEquals(1,count("marketing_segment_run"));
        assertEquals(0,count("marketing_audience_snapshot"));
    }
    /** 当前身份代际、范围、权威切换和503都不能留下旧准入结果。 */
    @Test void identityScopeRouteAndUnavailableRemainFailClosed() throws Exception {
        create("S",7);partial.set(true);forbidden(()->segments.definitions(actor(SEGMENT_READ),"",10));partial.set(false);
        var original=actor(SEGMENT_REFRESH);var run=segments.refresh(original,id(),"S");
        generation.set(2);jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);
        assertEquals(0,restarted().pump(actor(SEGMENT_PUMP)));assertEquals(0,actual(run).processed());
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/segments","valid",true,null).statusCode());
        unavailable.set(false);afterScope.set(this::stop);forbidden(()->segments.create(actor(SEGMENT_CREATE),id(),input("NO",1)));assertEquals(1,count("marketing_segment"));
        assertEquals(403,http("GET","/v1/admin/segments",adminToken,false,null).statusCode());
    }
    /** 真实SQL约束故障使任务、序号、回执及身份审计全部回滚，移除本测试约束后同键可正常执行。 */
    @Test void actualAuditFailureRollsBackAllOwnerEffects() {
        create("S",7);String key=id(),constraint="ck_segment_test_"+id().replace("-","").substring(0,16);
        jdbc.execute("ALTER TABLE employee_command_identity ADD CONSTRAINT "+constraint+" CHECK(tenant_id<>'"+tenant+"' OR capability<>'commerce.segment.refresh')");
        try {
            var failure=assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,()->segments.refresh(actor(SEGMENT_REFRESH),key,"S"));
            assertEquals(3819,failure.getSQLException().getErrorCode(),"actual MySQL CHECK rejection");
            assertEquals(0,count("marketing_segment_run"));assertEquals(1,count("platform_command"));assertEquals(1,count("employee_command_identity"));
            assertEquals(0,jdbc.queryForObject("SELECT snapshot_sequence FROM marketing_segment WHERE tenant_id=?",Integer.class,tenant));
        } finally {jdbc.execute("ALTER TABLE employee_command_identity DROP CHECK "+constraint);}
        var run=segments.refresh(actor(SEGMENT_REFRESH),key,"S");assertEquals(1,run.snapshotVersion());assertEquals(1,count("marketing_segment_run"));
    }
    /** 五秒准入过期后批次、快照和Outbox一起回滚，原任务TTL不被五秒窗口缩短。 */
    @Test void expiredCommitWindowRollsBackRealBatchAndSnapshot() {
        create("S",7);var run=segments.refresh(actor(SEGMENT_REFRESH),id(),"S");
        doAnswer(call->{var result=call.callRealMethod();Thread.sleep(5200);return result;}).when(growth).scan(eq(tenant),anyString(),eq(100),any());
        assertEquals(0,restarted().pump(actor(SEGMENT_PUMP)));assertEquals(0,actual(run).processed());assertEquals(0,count("marketing_audience_member"));assertEquals(0,count("marketing_audience_snapshot"));
        assertEquals(3600,Duration.between(run.startedAt(),run.validUntil()).getSeconds());
    }
    /** 控制取消/恢复和公告恢复使用原父定义，不能把任务ID或快照版本当成权限事实。 */
    @Test void controlAndAnnouncementRecoveryUseOriginalParentAndAudit() {
        create("S",7);var original=actor(SEGMENT_REFRESH);var run=segments.refresh(original,id(),"S");String source=source(run);
        jdbc.update("UPDATE marketing_segment_run SET status='ISOLATED' WHERE tenant_id=? AND run_id=?",tenant,run.runId());
        String key=id();var retried=segments.control(actor(SEGMENT_CONTROL),key,run.runId(),"retry");assertEquals("RUNNING",retried.status());
        assertEquals(retried,segments.control(actor(SEGMENT_CONTROL),key,run.runId(),"retry"));
        assertEquals(1,restarted().pump(actor(SEGMENT_PUMP)));assertEquals("COMPLETED",actual(run).status());
        jdbc.update("UPDATE marketing_segment_run SET entries_announced=FALSE,entry_attempts=5 WHERE tenant_id=? AND run_id=?",tenant,run.runId());
        segments.control(actor(SEGMENT_CONTROL),id(),run.runId(),"retry-announcement");assertEquals(0,actual(run).entryAttempts());assertEquals(source,source(run));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND capability='commerce.segment.control' AND resource_id='S' AND resource_version=7",Integer.class,tenant));
        forbidden(()->access.resource(actor(SEGMENT_CONTROL),access.scope(actor(SEGMENT_CONTROL),SEGMENT_CONTROL),new EmployeeAccess.ResourceFact("segment",run.runId(),0)));
    }
    /** 判权后真实定义发生并发变化时，事务锁必须拒绝旧版本，且不能创建任务或回执。 */
    @Test void ownerDefinitionDriftAndInvalidFactsCannotCommit() {
        create("S",7);var original=actor(SEGMENT_REFRESH);String key=id();
        afterResource.set(()->{
            jdbc.update("INSERT INTO marketing_segment_definition(tenant_id,segment_id,version,definition_json) VALUES(?,'S',8,?)",tenant,JsonCodec.write(input("S",8)));
            jdbc.update("UPDATE marketing_segment SET current_version=8,enabled=FALSE,schedule_policy_json=NULL,lock_version=lock_version+1 WHERE tenant_id=? AND segment_id='S'",tenant);
        });
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->segments.refresh(original,key,"S")).code());
        assertEquals(0,count("marketing_segment_run"));assertEquals(1,count("platform_command"));assertEquals(1,count("employee_command_identity"));
        var reader=actor(SEGMENT_READ);var scope=access.scope(reader,SEGMENT_READ);
        forbidden(()->access.resource(reader,scope,new EmployeeAccess.ResourceFact("segment","S",0)));
        forbidden(()->access.resource(reader,scope,new EmployeeAccess.ResourceFact("segment","S",-1)));
        forbidden(()->access.resource(reader,scope,new EmployeeAccess.ResourceFact("campaign","S",8)));
        var creator=actor(SEGMENT_CREATE);var createScope=access.scope(creator,SEGMENT_CREATE);
        forbidden(()->access.resource(creator,createScope,new EmployeeAccess.ResourceFact("segment","S",8)));
        afterScope.set(()->partial.set(true));forbidden(()->segments.definitions(reader,"",10));
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(20)).header("Idempotency-Key",id());
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
