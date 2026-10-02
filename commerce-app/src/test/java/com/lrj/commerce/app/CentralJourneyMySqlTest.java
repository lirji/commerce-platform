package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.benefit.coupon.api.CouponApi;
import com.lrj.commerce.campaign.asset.api.MarketingAssets;
import com.lrj.commerce.journey.delivery.api.CouponDeliveryApi;
import com.lrj.commerce.journey.delivery.application.CouponDeliveryService;
import com.lrj.commerce.journey.delivery.infrastructure.persistence.CouponDeliveryMapper;
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

/** 真实MySQL与HTTP验证方向来源及事务；中央桩只证明适配，原Grant路径另须实际Auth跨进程验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralJourneyMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/commerce_test_20260923(?:_[a-z0-9]+)?\\?.*"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @Autowired CentralEmployeeService service;
    @Autowired com.lrj.commerce.journey.api.JourneyApi journeys;
    @Autowired com.lrj.commerce.journey.infrastructure.persistence.JourneyMapper journeyMapper;
    @Autowired EmployeeAccess access;
    @Autowired JdbcTemplate jdbc;
    @Autowired CouponDeliveryMapper mapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.lrj.commerce.runtime.command.Commands commands;
    @Autowired MarketingAssets audiences;
    @Autowired com.lrj.commerce.member.profile.api.MemberApi members;
    @Autowired com.lrj.commerce.store.management.api.StoreApi stores;
    @Autowired com.lrj.commerce.merchant.api.MerchantApi merchants;
    @MockitoSpyBean CouponApi coupons;
    @LocalServerPort int port;
    private String tenant,authTenant,principal,member,grant,adminToken;
    private Actor admin;
    private final Set<String> allowed=ConcurrentHashMap.newKeySet(),revoked=ConcurrentHashMap.newKeySet();
    private final Map<String,Reference> references=new ConcurrentHashMap<>();
    private final AtomicLong generation=new AtomicLong(1),sourceSeconds=new AtomicLong(2592060);
    private final AtomicBoolean unavailable=new AtomicBoolean(),partial=new AtomicBoolean();
    private final AtomicReference<Runnable> afterScope=new AtomicReference<>(),afterResource=new AtomicReference<>();
    private static final List<EmployeeAccess.Capability> CAPS=Arrays.stream(EmployeeAccess.Capability.values()).filter(c->Set.of("JOURNEY","MARKETING_REPORT").contains(c.family())).toList();
    private record Reference(String capability,long generation,Instant expires) {}
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        reset(client);
        // 测试库保留历史未完成任务；本轮唯一租户先被真实轮转发现，避免其他历史租户先触发中央桩的熔断。
        tenant="000-delivery-"+(Long.MAX_VALUE-System.currentTimeMillis())+"-"+id().substring(0,12);authTenant=id();principal=id();member=id();grant=id();adminToken=id();
        allowed.clear();revoked.clear();references.clear();generation.set(1);sourceSeconds.set(2592060);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:CAPS)allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,? ,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','delivery-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'JOURNEY','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='JOURNEY'",tenant);
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'MARKETING_REPORT','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='MARKETING_REPORT'",tenant);
        merchants.create(admin,"merchant",new com.lrj.commerce.merchant.api.MerchantApi.Create("merchant","测试商家"));
        stores.create(admin,"store",new com.lrj.commerce.store.management.api.StoreApi.Create("store","merchant","测试店铺"));
        members.create(admin,"member",new com.lrj.commerce.member.profile.api.MemberApi.Create("M1","buyer","测试会员","BASIC"));
        coupons.create(admin,"coupon",new CouponApi.Definition("coupon",1,"store","定向券","0.00","5.00",Instant.now().minusSeconds(10),Instant.now().plusSeconds(604800),100,true,10000,"SOURCE_ONLY",366));
        audiences.createAudience(admin,"audience",new MarketingAssets.Audience("audience",1,"固定人群","TEST",Instant.now(),Instant.now().plusSeconds(7200),List.of("M1")));
        when(client.issueExecution(anyString(),any(),any())).thenAnswer(call->{
            if(!"valid".equals(call.getArgument(0)))throw new CentralAccessException(401);
            CentralAccessDtos.Check check=call.getArgument(1);require(check);String ref=id();Instant requested=call.getArgument(2);
            long seconds=Duration.between(Instant.now(),requested).getSeconds();assertTrue(seconds>0&&seconds<=2592060);
            boolean durable=seconds>60;
            if(durable)assertTrue(Set.of(JOURNEY_INSTANCE_CREATE.code()).contains(check.capability()));
            Instant expires=durable&&sourceSeconds.get()<2592060?Instant.now().plusSeconds(sourceSeconds.get()):requested;
            references.put(ref,new Reference(check.capability(),generation.get(),expires));
            return new ExecutionAccessDtos.Reference("1",check.requestId(),ref,context(),check.capability(),check.resourceType(),expires.toString());
        });
        when(client.executionScope(anyString(),any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"远程准入不占用业务事务");
            CentralAccessDtos.Check check=call.getArgument(1);requireReference(call.getArgument(0),check);
            var clause=new ScopeDtos.Clause(partial.get()?ScopeDtos.Kind.SPECIFIED_RESOURCES:ScopeDtos.Kind.TENANT_ALL,partial.get()?List.of("B"):List.of(),false);
            var plan=new ScopeAccessDtos.Plan("1",check.requestId(),check.capability(),check.resourceType(),"ALLOW",id(),context(),"policy",1,"directory",1,1,1,Instant.now().plusSeconds(20).toString(),List.of(new ScopeDtos.Alternative(grant,1,List.of(clause))));
            var mutation=afterScope.getAndSet(null);if(mutation!=null)mutation.run();return plan;
        });
        when(client.checkExecution(anyString(),any(),any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            CentralAccessDtos.Check check=call.getArgument(1);ScopeDtos.Facts facts=call.getArgument(2);requireReference(call.getArgument(0),check);
            assertTrue(CAPS.stream().anyMatch(c->c.code().equals(check.capability())));
            assertEquals(authTenant,facts.tenantId());assertTrue(Set.of("journey","journey_instance","journey_scan").contains(facts.resourceType()));assertEquals(1,facts.resourceVersion());
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
    private GovernanceDtos.AccessContext context(){return new GovernanceDtos.AccessContext(principal,member,generation.get(),1,1,authTenant,"commerce","test","delivery-test","HUMAN",id());}
    private Actor actor(EmployeeAccess.Capability cap){return service.authenticate("valid",authTenant,cap,cap==JOURNEY_INSTANCE_CREATE);}
    private void forbidden(Runnable action){assertEquals(DomainException.Code.FORBIDDEN,assertThrows(DomainException.class,action::run).code());}
    private HttpResponse<String> http(String method,String path,String token,boolean partition,Object body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(partition)request.header("X-Tenant-Id",authTenant);
        if(method.equals("POST"))request.header("Idempotency-Key",id());
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }


    private com.lrj.commerce.journey.api.JourneyApi.Definition definition(String name,com.lrj.commerce.journey.api.JourneyApi.Trigger trigger,boolean coupon) {
        var nodes=new ArrayList<com.lrj.commerce.journey.api.JourneyApi.Node>();
        if(coupon)nodes.add(new com.lrj.commerce.journey.api.JourneyApi.Node("coupon",com.lrj.commerce.journey.api.JourneyApi.Kind.COUPON,null,"notice",null,null,null,null,null,null,new com.lrj.commerce.journey.api.JourneyApi.CouponRef("coupon",1)));
        nodes.add(new com.lrj.commerce.journey.api.JourneyApi.Node("notice",com.lrj.commerce.journey.api.JourneyApi.Kind.NOTIFY,null,"end",null,null,null,null,"触达标题","真实通知"));
        nodes.add(new com.lrj.commerce.journey.api.JourneyApi.Node("end",com.lrj.commerce.journey.api.JourneyApi.Kind.END,null,null,null,null,null,null,null,null));
        return new com.lrj.commerce.journey.api.JourneyApi.Definition(name,1,"store","权限旅程",trigger,Instant.now().minusSeconds(10),Instant.now().plusSeconds(3600),3600,coupon?"coupon":"notice",nodes);
    }
    private void publish(String name,com.lrj.commerce.journey.api.JourneyApi.Trigger trigger,boolean coupon) {
        journeys.create(actor(JOURNEY_CREATE),id(),definition(name,trigger,coupon));
        journeys.change(actor(JOURNEY_SUBMIT),id(),name,1,0,"submit");
        journeys.change(actor(JOURNEY_APPROVE),id(),name,1,1,"approve");
        journeys.change(actor(JOURNEY_PUBLISH),id(),name,1,2,"publish");
    }
    private com.lrj.commerce.journey.api.JourneyApi.Instance enroll(String name,String key) {
        return journeys.enroll(actor(JOURNEY_INSTANCE_CREATE),key,new com.lrj.commerce.journey.api.JourneyApi.Start(name,1,"M1","event"));
    }
    private int count(String table) {
        if(!Set.of("journey_instance","journey_notification","journey_effect","journey_step_execution","employee_command_identity","benefit_coupon").contains(table))throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);
    }
    /** 所有字面资格各自独立；短GET不保存有限来源，也没有命令和业务效果。 */
    @Test void hintsAreIndependentAndDoNotIssueDurableWork() throws Exception {
        var hints=Map.ofEntries(Map.entry(JOURNEY_CREATE,"journeys/create"),Map.entry(JOURNEY_VALIDATE,"journeys/validate"),Map.entry(JOURNEY_PREVIEW,"journeys/preview"),Map.entry(JOURNEY_SUBMIT,"journeys/submit"),Map.entry(JOURNEY_APPROVE,"journeys/approve"),Map.entry(JOURNEY_REJECT,"journeys/reject"),Map.entry(JOURNEY_PUBLISH,"journeys/publish"),Map.entry(JOURNEY_PAUSE,"journeys/pause"),Map.entry(JOURNEY_PUMP,"journeys/pump"),Map.entry(JOURNEY_INSTANCE_CREATE,"journey-instances/create"),Map.entry(JOURNEY_INSTANCE_CONTROL,"journey-instances/control"),Map.entry(JOURNEY_SCAN_RETRY,"journey-scans/retry"),Map.entry(MARKETING_EFFECT_REBUILD,"marketing-effects/rebuild"));
        for(var one:hints.entrySet()) {
            allowed.clear();allowed.add(one.getKey().code());
            for(var other:hints.entrySet())assertEquals(one.getKey()==other.getKey()?200:403,http("GET","/v1/operations/"+other.getValue()+"-access","valid",true,null).statusCode());
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM employee_finite_execution WHERE tenant_id=?",Integer.class,tenant));assertEquals(0,count("employee_command_identity"));
    }
    /** 状态与检查点CAS变化不改变权限的固定内容版本；读取不隐含其他能力。 */
    @Test void immutableContentFactsAndProgressStaySeparate() {
        publish("J",com.lrj.commerce.journey.api.JourneyApi.Trigger.MANUAL,false);
        String key=id();var first=enroll("J",key);var source=journeyMapper.source(tenant,first.instanceId());
        assertEquals(first,enroll("J",key));assertEquals(source,journeyMapper.source(tenant,first.instanceId()));
        assertEquals(1,journeys.pump(actor(JOURNEY_PUMP)));var advanced=journeyMapper.findInstance(tenant,first.instanceId());assertEquals(1,advanced.journeyVersion());assertEquals(1,advanced.version());
        assertEquals(1,journeys.history(actor(JOURNEY_INSTANCE_READ),first.instanceId(),-1,50).definition().version());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM employee_command_identity WHERE tenant_id=? AND (resource_version<>1 OR store_id IS NOT NULL)",Integer.class,tenant));
        verify(client,atLeastOnce()).checkExecution(anyString(),any(),argThat(f->f.resourceVersion()==1));
        allowed.clear();allowed.add(JOURNEY_INSTANCE_READ.code());forbidden(()->journeys.definitions(actor(JOURNEY_INSTANCE_READ),"",50));
    }
    /** 原HUMAN撤权后不得借新pump/newGrant继续；安全取消只停止未执行节点。 */
    @Test void revocationPreservesSubmittedEffectsAndCancelDoesNotRenewSource() {
        publish("J",com.lrj.commerce.journey.api.JourneyApi.Trigger.MANUAL,false);var first=enroll("J",id());
        assertEquals(1,journeys.pump(actor(JOURNEY_PUMP)));assertEquals(1,count("journey_notification"));
        String source=journeyMapper.source(tenant,first.instanceId());var json=JsonCodec.read(source,Map.class);var origin=(Map<?,?>)json.get("actor");revoked.add((String)origin.get("executionId"));
        assertEquals(0,journeys.pump(actor(JOURNEY_PUMP)));assertEquals(1,count("journey_notification"));
        journeys.control(actor(JOURNEY_INSTANCE_CONTROL),id(),first.instanceId(),"cancel");assertEquals(source,journeyMapper.source(tenant,first.instanceId()));assertEquals("CANCELLED",journeyMapper.findInstance(tenant,first.instanceId()).status().name());
        forbidden(()->journeys.control(actor(JOURNEY_INSTANCE_CONTROL),id(),first.instanceId(),"retry"));assertEquals(1,count("journey_notification"));
    }
    /** 节点效果后的真实到期必须使券、执行记录、效果计数与检查点全部回滚。 */
    @Test void expiryAfterCouponRollsBackWholeNodeTransaction() {
        publish("J",com.lrj.commerce.journey.api.JourneyApi.Trigger.MANUAL,true);sourceSeconds.set(2);var first=enroll("J",id());
        var target=org.springframework.test.util.AopTestUtils.<CouponApi>getUltimateTargetObject(coupons);
        doAnswer(call->{var result=call.callRealMethod();Thread.sleep(2200);return result;}).when(target).grantFromJourney(eq(tenant),eq("M1"),anyString(),anyString(),anyString(),anyLong());
        assertEquals(0,journeys.pump(actor(JOURNEY_PUMP)));assertEquals(0,count("benefit_coupon"));assertEquals(0,journeyMapper.findInstance(tenant,first.instanceId()).steps());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM journey_step_execution WHERE tenant_id=? AND status='COMPLETED'",Integer.class,tenant));
    }
    /** 准入后STOPPED阻断当前事务，旧ADMIN不能绕过；报表独立门禁在查询前生效。 */
    @Test void stopAndFailureNeverFallbackToAdmin() throws Exception {
        afterScope.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='JOURNEY'",tenant));
        forbidden(()->journeys.create(actor(JOURNEY_CREATE),id(),definition("J",com.lrj.commerce.journey.api.JourneyApi.Trigger.MANUAL,false)));assertEquals(0,count("employee_command_identity"));
        forbidden(()->journeys.definitions(admin,"",50));assertEquals(403,http("GET","/v1/admin/journeys",adminToken,false,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/marketing-effects?storeId=store&from=2026-09-01T00:00:00Z&to=2026-09-02T00:00:00Z","valid",true,null).statusCode());
    }
    /** 发布政策独立于员工Grant，自动事件保留固定版本，停止权威仍阻断节点。 */
    @Test void actualSystemEventsUsePublishedPolicyWithoutHumanGrantRenewal() {
        var base=definition("AUTO",com.lrj.commerce.journey.api.JourneyApi.Trigger.MEMBER_REGISTERED,false);
        var input=new com.lrj.commerce.journey.api.JourneyApi.Definition(base.journeyId(),1,base.storeId(),base.name(),base.trigger(),base.validFrom(),base.validTo(),base.maxDurationSeconds(),base.entry(),base.nodes(),new com.lrj.commerce.journey.api.JourneyApi.Controls(null,null,1,3600,1,3600));
        journeys.create(actor(JOURNEY_CREATE),id(),input);journeys.change(actor(JOURNEY_SUBMIT),id(),"AUTO",1,0,"submit");journeys.change(actor(JOURNEY_APPROVE),id(),"AUTO",1,1,"approve");
        var publisher=actor(JOURNEY_PUBLISH);journeys.change(publisher,id(),"AUTO",1,2,"publish");revoked.add(publisher.executionId());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var event=new com.lrj.commerce.runtime.api.event.EventHandler.Event(id(),tenant,"member.registered.v1","M1",1,JsonCodec.write(new com.lrj.commerce.member.growth.api.MemberGrowthApi.Registered("M1")),Instant.now().plusMillis(10),0,0);
        transaction.executeWithoutResult(status->((com.lrj.commerce.runtime.api.event.EventHandler)journeys).handle(event));
        var instance=journeyMapper.instances(tenant,null,"",50).getFirst();assertTrue(journeyMapper.source(tenant,instance.instanceId()).contains("SYSTEM"));
        assertEquals(1,journeys.pump(actor(JOURNEY_PUMP)));assertEquals(1,count("journey_notification"));
        var pumpActor=actor(JOURNEY_PUMP);
        jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='JOURNEY'",tenant);
        forbidden(()->journeys.pump(pumpActor));assertEquals(1,count("journey_notification"));
    }
    /** 完整租户报告与执行能力独立；查询返回前代际变化不得返回旧数据。 */
    @Test void reportAndExecutionReadsHaveIndependentScopes() {
        var effects=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(journeys);
        allowed.clear();allowed.add(MARKETING_EFFECT_READ.code());
        assertTrue(journeys.effects(actor(MARKETING_EFFECT_READ),"store",Instant.now().minusSeconds(60),Instant.now(),"",50).isEmpty());
        forbidden(()->journeys.definitions(actor(MARKETING_EFFECT_READ),"",50));
        allowed.clear();allowed.add(MARKETING_EXECUTION_READ.code());
        forbidden(()->journeys.effects(actor(MARKETING_EXECUTION_READ),"store",Instant.now().minusSeconds(60),Instant.now(),"",50));
    }

    /** 生命周期单会员入组和游标同事务，重试使用固定内容1而非进度CAS。 */
    @Test void systemLifecycleScanKeepsPolicyAndCursorWhenRetried() {
        jdbc.update("UPDATE member_record SET created_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 30 DAY) WHERE tenant_id=?",tenant);
        var base=definition("LIFE",com.lrj.commerce.journey.api.JourneyApi.Trigger.DORMANT,false);
        var input=new com.lrj.commerce.journey.api.JourneyApi.Definition(base.journeyId(),1,base.storeId(),base.name(),base.trigger(),base.validFrom(),base.validTo(),base.maxDurationSeconds(),base.entry(),base.nodes(),new com.lrj.commerce.journey.api.JourneyApi.Controls(null,null,1,3600,1,3600),new com.lrj.commerce.journey.api.JourneyApi.Lifecycle(1,60,300,7));
        journeys.create(actor(JOURNEY_CREATE),id(),input);journeys.change(actor(JOURNEY_SUBMIT),id(),"LIFE",1,0,"submit");journeys.change(actor(JOURNEY_APPROVE),id(),"LIFE",1,1,"approve");var publisher=actor(JOURNEY_PUBLISH);journeys.change(publisher,id(),"LIFE",1,2,"publish");revoked.add(publisher.executionId());
        assertTrue(journeys.pump(actor(JOURNEY_PUMP))>0);assertEquals(1,count("journey_instance"));assertEquals(1,count("journey_notification"));
        var scan=journeyMapper.findScan(tenant,"LIFE",1);assertTrue(scan.version()>0);
        jdbc.update("UPDATE journey_lifecycle_scan SET status='ISOLATED',version=version+1 WHERE tenant_id=? AND journey_id='LIFE'",tenant);var isolated=journeyMapper.findScan(tenant,"LIFE",1);
        var after=journeys.retryScan(actor(JOURNEY_SCAN_RETRY),id(),"LIFE",1,new com.lrj.commerce.journey.api.JourneyApi.ScanRetry(isolated.version(),"修复原因"));
        assertEquals(isolated.memberCursor(),after.memberCursor());assertEquals(isolated.version()+1,after.version());assertEquals(1,after.journeyVersion());assertEquals(1,count("journey_instance"));
    }

}
