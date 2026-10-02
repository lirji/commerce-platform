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
class CentralCouponDeliveryMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @Autowired CentralEmployeeService service;
    @Autowired CouponDeliveryApi deliveries;
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
    private final AtomicLong generation=new AtomicLong(1),sourceSeconds=new AtomicLong(604860);
    private final AtomicBoolean unavailable=new AtomicBoolean(),partial=new AtomicBoolean();
    private final AtomicReference<Runnable> afterScope=new AtomicReference<>(),afterResource=new AtomicReference<>();
    private static final List<EmployeeAccess.Capability> CAPS=List.of(COUPON_DELIVERY_READ,COUPON_DELIVERY_CREATE,COUPON_DELIVERY_CONTROL,COUPON_DELIVERY_PUMP);
    private record Reference(String capability,long generation,Instant expires) {}
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        reset(client);
        // 测试库保留历史未完成任务；本轮唯一租户先被真实轮转发现，避免其他历史租户先触发中央桩的熔断。
        tenant="000-delivery-"+(Long.MAX_VALUE-System.currentTimeMillis())+"-"+id().substring(0,12);authTenant=id();principal=id();member=id();grant=id();adminToken=id();
        allowed.clear();revoked.clear();references.clear();generation.set(1);sourceSeconds.set(604860);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:CAPS)allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,? ,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','delivery-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'COUPON_DELIVERY','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='COUPON_DELIVERY'",tenant);
        merchants.create(admin,"merchant",new com.lrj.commerce.merchant.api.MerchantApi.Create("merchant","测试商家"));
        stores.create(admin,"store",new com.lrj.commerce.store.management.api.StoreApi.Create("store","merchant","测试店铺"));
        members.create(admin,"member",new com.lrj.commerce.member.profile.api.MemberApi.Create("M1","buyer","测试会员","BASIC"));
        coupons.create(admin,"coupon",new CouponApi.Definition("coupon",1,"store","定向券","0.00","5.00",Instant.now().minusSeconds(10),Instant.now().plusSeconds(604800),100,true,10000,"SOURCE_ONLY",366));
        audiences.createAudience(admin,"audience",new MarketingAssets.Audience("audience",1,"固定人群","TEST",Instant.now(),Instant.now().plusSeconds(7200),List.of("M1")));
        when(client.issueExecution(anyString(),any(),any())).thenAnswer(call->{
            if(!"valid".equals(call.getArgument(0)))throw new CentralAccessException(401);
            CentralAccessDtos.Check check=call.getArgument(1);require(check);String ref=id();Instant requested=call.getArgument(2);
            long seconds=Duration.between(Instant.now(),requested).getSeconds();assertTrue(seconds>0&&seconds<=604860);
            boolean durable=seconds>60;
            if(durable)assertTrue(Set.of(COUPON_DELIVERY_CREATE.code(),COUPON_DELIVERY_CONTROL.code()).contains(check.capability()));
            Instant expires=durable&&sourceSeconds.get()<604860?Instant.now().plusSeconds(sourceSeconds.get()):requested;
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
            assertTrue(Set.of(COUPON_DELIVERY_READ.code(),COUPON_DELIVERY_CONTROL.code()).contains(check.capability()));
            assertEquals(authTenant,facts.tenantId());assertEquals("coupon_delivery",facts.resourceType());assertEquals(1,facts.resourceVersion());
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
    private Actor actor(EmployeeAccess.Capability cap){return service.authenticate("valid",authTenant,cap,cap==COUPON_DELIVERY_CREATE||cap==COUPON_DELIVERY_CONTROL);}
    private void forbidden(Runnable action){assertEquals(DomainException.Code.FORBIDDEN,assertThrows(DomainException.class,action::run).code());}
    private CouponDeliveryApi.Create input(String batch){return new CouponDeliveryApi.Create(batch,"store","关怀发券","coupon",1,new MarketingAssets.Ref("audience",1),Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),24);}
    private CouponDeliveryApi.View create(String batch){return deliveries.create(actor(COUPON_DELIVERY_CREATE),id(),input(batch));}
    private CouponDeliveryMapper.Row row(String batch){return mapper.find(tenant,batch);}
    private CouponDeliveryApi.View control(String batch,String action){return deliveries.control(actor(COUPON_DELIVERY_CONTROL),id(),batch,new CouponDeliveryApi.Control(row(batch).version(),action,"验证来源"));}
    private void ready(String batch){jdbc.update("UPDATE automation_coupon_batch SET available_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND batch_id=?",tenant,batch);}
    private void expireReference(String ref){references.compute(ref,(k,v)->new Reference(v.capability(),v.generation(),Instant.now().minusSeconds(1)));}
    private String reference(String source){return JsonCodec.read(source,Map.class).get("actor") instanceof Map<?,?> a?(String)a.get("executionId"):null;}
    private int count(String table) {
        if(!Set.of("automation_coupon_batch","automation_coupon_recipient","automation_coupon_frequency","benefit_coupon","employee_command_identity").contains(table))throw new IllegalArgumentException("未登记测试表");
        return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);
    }
    private CouponApi couponTarget(){return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(coupons);}
    private CouponDeliveryService restarted(){return new CouponDeliveryService(mapper,audiences,members,coupons,stores,commands,Clock.systemUTC(),transactions,new com.lrj.commerce.runtime.work.WorkLanes(),access);}
    private HttpResponse<String> http(String method,String path,String token,boolean partition,Object body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(partition)request.header("X-Tenant-Id",authTenant);
        if(method.equals("POST"))request.header("Idempotency-Key",id());
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    /** 写资格不暗含目录读取；仅四个准确方法路径能够使用中央身份。 */
    @Test void independentCapabilitiesAtActualHttpBoundary() throws Exception {
        create("B");deliveries.pump(actor(COUPON_DELIVERY_PUMP));assertEquals("COMPLETED",row("B").status());
        for(var cap:CAPS){
            allowed.clear();allowed.add(cap.code());
            for(var other:CAPS){
                String path=switch(other){case COUPON_DELIVERY_READ->"/v1/admin/coupon-deliveries?storeId=store";case COUPON_DELIVERY_CREATE->"/v1/admin/coupon-deliveries";case COUPON_DELIVERY_CONTROL->"/v1/admin/coupon-deliveries/B/control";default->"/v1/admin/coupon-deliveries/pump";};
                Object body=other==COUPON_DELIVERY_CREATE?input("N-"+cap.name()):other==COUPON_DELIVERY_CONTROL?new CouponDeliveryApi.Control(row("B").version(),"REVOKE","独立补偿"):null;
                assertEquals(cap==other?200:403,http(other==COUPON_DELIVERY_READ?"GET":"POST",path,"valid",true,body).statusCode(),cap+" / "+other);
            }
        }
        assertEquals(403,http("GET","/v1/admin/coupon-deliveries?storeId=store",adminToken,false,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/coupon-deliveries?storeId=store","bad",true,null).statusCode());
        assertEquals(401,http("POST","/v1/admin/coupon-deliveries/B/unknown","valid",true,null).statusCode());
        assertEquals(401,http("POST","/v1/admin/skus","valid",true,Map.of()).statusCode());
    }
    /** 公共CAS从0变化，权限及身份审计始终绑定批次实际内容版本1，不伪造门店或券定义事实。 */
    @Test void immutableContentAndOriginalKeyRemainIndependentFromProgressCas() {
        String key=id();var input=input("B");var first=deliveries.create(actor(COUPON_DELIVERY_CREATE),key,input);
        assertEquals(0,first.version());String original=row("B").issueSourceJson();
        assertEquals(first,deliveries.create(actor(COUPON_DELIVERY_CREATE),key,input));
        assertEquals(original,row("B").issueSourceJson());
        control("B","CANCEL");assertEquals(1,row("B").version());assertEquals(1,row("B").contentVersion());
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->deliveries.control(actor(COUPON_DELIVERY_CONTROL),id(),"B",new CouponDeliveryApi.Control(0,"REVOKE","陈旧CAS"))).code());
        assertEquals(2,count("employee_command_identity"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM employee_command_identity WHERE tenant_id=? AND (resource_type<>'coupon_delivery' OR resource_version<>1 OR store_id IS NOT NULL)",Integer.class,tenant));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE automation_coupon_batch SET content_version=0 WHERE tenant_id=?",tenant));
        assertEquals(1,row("B").contentVersion());
    }
    /** 新推进资格或重新授予不能替换原发放引用，已有第一名券保留，重启仍拒绝后续效果。 */
    @Test void originalRevocationCannotBeReplacedByFreshPumpOrRegrant() {
        members.create(admin,"M2",new com.lrj.commerce.member.profile.api.MemberApi.Create("M2","buyer2","第二会员","BASIC"));
        audiences.createAudience(admin,"audience2",new MarketingAssets.Audience("audience",2,"固定人群","TEST",Instant.now(),Instant.now().plusSeconds(7200),List.of("M1","M2")));
        var input=new CouponDeliveryApi.Create("B","store","关怀发券","coupon",1,new MarketingAssets.Ref("audience",2),Instant.now().plusSeconds(3600),24);
        deliveries.create(actor(COUPON_DELIVERY_CREATE),id(),input);String original=row("B").issueSourceJson();String ref=reference(original);
        doAnswer(c->{var result=c.callRealMethod();revoked.add(ref);return result;}).when(couponTarget()).grantTargeted(eq(tenant),eq("M1"),anyString(),anyString(),anyString(),anyLong());
        assertEquals(1,deliveries.pump(actor(COUPON_DELIVERY_PUMP)));assertEquals(1,row("B").processed());assertEquals(1,count("benefit_coupon"));
        ready("B");assertEquals(0,restarted().pump(actor(COUPON_DELIVERY_PUMP)));assertEquals(1,count("benefit_coupon"));
        String newRef=actor(COUPON_DELIVERY_CREATE).executionId();assertNotEquals(ref,newRef);
        forbidden(()->control("B","CANCEL"));assertEquals(original,row("B").issueSourceJson());assertNull(row("B").revokeSourceJson());
        assertEquals(1,count("automation_coupon_recipient"));assertEquals(1,count("automation_coupon_frequency"));
    }
    /** 首次撤回独立于过期的发放来源，重复撤回/重试只能复核首次撤回原来源。 */
    @Test void independentFirstRevokeDoesNotReplaceIssuanceOrRenewLaterRevoke() {
        create("B");deliveries.pump(actor(COUPON_DELIVERY_PUMP));String issue=row("B").issueSourceJson();expireReference(reference(issue));
        control("B","REVOKE");String revoke=row("B").revokeSourceJson();assertNotNull(revoke);assertEquals(issue,row("B").issueSourceJson());
        doThrow(new DomainException(DomainException.Code.CONFLICT,"注入撤回失败")).when(couponTarget()).revokeTargeted(eq(tenant),anyString(),anyString(),anyString());
        for(int i=0;i<5;i++){ready("B");assertEquals(0,deliveries.pump(actor(COUPON_DELIVERY_PUMP)));}
        assertEquals("ISOLATED",row("B").status());assertEquals("REVOKE",row("B").mode());
        control("B","REVOKE");assertEquals(revoke,row("B").revokeSourceJson());
        for(int i=0;i<5;i++){ready("B");deliveries.pump(actor(COUPON_DELIVERY_PUMP));}
        assertEquals("ISOLATED",row("B").status());
        revoked.add(reference(revoke));forbidden(()->control("B","RETRY"));forbidden(()->control("B","REVOKE"));
        assertEquals(revoke,row("B").revokeSourceJson());assertEquals(issue,row("B").issueSourceJson());assertEquals(1,count("benefit_coupon"));assertEquals(0,row("B").revoked());
    }
    /** 准确短来源在真实时间到期，券效果、频控和收件人检查点在提交栅栏处全部回滚。 */
    @Test void expiryAfterCouponEffectRollsBackRecipientTransaction() {
        sourceSeconds.set(2);create("B");
        doAnswer(c->{var result=c.callRealMethod();Thread.sleep(2200);return result;}).when(couponTarget()).grantTargeted(eq(tenant),anyString(),anyString(),anyString(),anyString(),anyLong());
        assertEquals(0,deliveries.pump(actor(COUPON_DELIVERY_PUMP)));
        assertEquals(0,count("benefit_coupon"));assertEquals(0,count("automation_coupon_frequency"));assertEquals(0,count("automation_coupon_recipient"));assertEquals(0,row("B").processed());
        assertEquals(0,jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=?",Integer.class,tenant));
        assertEquals("FORBIDDEN",row("B").errorCode());assertNotEquals("COMPLETED",row("B").status());
    }
    /** 中央503显式失败，后台保持原来源与已提交结果，不转旧ADMIN或自动换引用。 */
    @Test void centralOutagePreservesSourcesAndManualHttpReturns503() throws Exception {
        create("B");String source=row("B").issueSourceJson();unavailable.set(true);
        assertEquals(503,http("POST","/v1/admin/coupon-deliveries/pump","valid",true,null).statusCode());
        var worker=restarted();
        for(int attempt=0;attempt<50&&row("B").errorCode()==null;attempt++){ready("B");worker.tick();}
        assertEquals(0,row("B").processed());assertEquals(0,count("benefit_coupon"));
        assertEquals(source,row("B").issueSourceJson());assertEquals(0,row("B").attempts());assertEquals("DEPENDENCY_UNAVAILABLE",row("B").errorCode());
        unavailable.set(false);ready("B");assertEquals(1,restarted().pump(actor(COUPON_DELIVERY_PUMP)));assertEquals(1,count("benefit_coupon"));assertEquals(source,row("B").issueSourceJson());
    }
    /** Owner真实父记录与正版本先于分页，租户外对象不进入中央事实；返回前再次复核代际。 */
    @Test void recipientReadUsesActualParentAndChecksIdentityAgain() {
        create("B");deliveries.pump(actor(COUPON_DELIVERY_PUMP));assertEquals(1,deliveries.recipients(actor(COUPON_DELIVERY_READ),"B","",20).size());
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->deliveries.recipients(actor(COUPON_DELIVERY_READ),"foreign","",20)).code());
        afterResource.set(()->generation.incrementAndGet());forbidden(()->deliveries.recipients(actor(COUPON_DELIVERY_READ),"B","",20));
        verify(client,atLeastOnce()).checkExecution(anyString(),any(),argThat(f->f.resourceId().equals("B")&&f.resourceVersion()==1));
    }
    /** 准入后停止路由则创建和同事务审计都不提交，旧ADMIN及退回LEGACY也不能绕过曾接管族。 */
    @Test void routeChangeBeforeCommandAndEverCentralLegacyFailClosed() {
        afterScope.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='COUPON_DELIVERY'",tenant));
        forbidden(()->create("B"));assertEquals(0,count("automation_coupon_batch"));assertEquals(0,count("employee_command_identity"));
        forbidden(()->deliveries.list(admin,"store","",10));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE employee_authority_route SET state='LEGACY',version=version+1 WHERE tenant_id=? AND family='COUPON_DELIVERY'",tenant));
        forbidden(()->deliveries.pump(admin));
    }
    /** 旧空来源在中央不能猜成授权；首次新补偿可独立授权，但原发放来源仍保持未知。 */
    @Test void unknownLegacySourceCannotIssueButCanReceiveExplicitCompensation() {
        create("B");jdbc.update("UPDATE automation_coupon_batch SET issue_source_json=NULL WHERE tenant_id=? AND batch_id='B'",tenant);
        assertEquals(0,deliveries.pump(actor(COUPON_DELIVERY_PUMP)));assertEquals(0,count("benefit_coupon"));
        jdbc.update("UPDATE automation_coupon_batch SET status='CANCELLED' WHERE tenant_id=? AND batch_id='B'",tenant);
        control("B","REVOKE");assertNull(row("B").issueSourceJson());assertNotNull(row("B").revokeSourceJson());
        ready("B");assertEquals(0,restarted().pump(actor(COUPON_DELIVERY_PUMP)));assertEquals("REVOCATION_DONE",row("B").status());assertEquals(0,row("B").revoked());
    }
    /** 两个持久能力上限仅用于POST；短认证不登记来源，创建和pump对象不能借用正版本。 */
    @Test void shortAuthenticationAndObjectTypesDoNotCreateTaskAuthority() {
        var shortActor=service.authenticate("valid",authTenant,COUPON_DELIVERY_CREATE);
        assertTrue(Duration.between(Instant.now(),references.get(shortActor.executionId()).expires()).toSeconds()<=60);
        forbidden(()->access.couponDeliveryExecution(shortActor,COUPON_DELIVERY_CREATE));
        for(var cap:List.of(COUPON_DELIVERY_CREATE,COUPON_DELIVERY_PUMP)){
            var actor=actor(cap);var scope=access.scope(actor,cap);
            forbidden(()->access.resource(actor,scope,new EmployeeAccess.ResourceFact("coupon_delivery","B",1)));
        }
        var actor=actor(COUPON_DELIVERY_CONTROL);var scope=access.scope(actor,COUPON_DELIVERY_CONTROL);
        forbidden(()->access.resource(actor,scope,new EmployeeAccess.ResourceFact("coupon_delivery","B",0)));
        forbidden(()->access.resource(actor,scope,new EmployeeAccess.ResourceFact("coupon_definition","B",1)));
        forbidden(()->access.couponDeliveryExecution(actor,COUPON_DELIVERY_CREATE));
        partial.set(true);forbidden(()->access.scope(actor,COUPON_DELIVERY_CONTROL));
    }

    /** 原控制回执按当时mode复核来源；后续合法撤回不能让旧取消回执绕过已撤销发放引用。 */
    @Test void oldControlReceiptUsesItsOriginalDirectionAfterLaterRevoke() {
        create("B");String cancelKey=id();var cancel=new CouponDeliveryApi.Control(0,"CANCEL","停止发放");
        var stopped=deliveries.control(actor(COUPON_DELIVERY_CONTROL),cancelKey,"B",cancel);
        String issue=row("B").issueSourceJson();String revokeKey=id();var revoke=new CouponDeliveryApi.Control(row("B").version(),"REVOKE","独立撤回");
        var first=deliveries.control(actor(COUPON_DELIVERY_CONTROL),revokeKey,"B",revoke);
        String source=row("B").revokeSourceJson();
        assertEquals(stopped,deliveries.control(actor(COUPON_DELIVERY_CONTROL),cancelKey,"B",cancel));
        assertEquals(first,deliveries.control(actor(COUPON_DELIVERY_CONTROL),revokeKey,"B",revoke));
        expireReference(reference(issue));
        forbidden(()->deliveries.control(actor(COUPON_DELIVERY_CONTROL),cancelKey,"B",cancel));
        assertEquals(first,deliveries.control(actor(COUPON_DELIVERY_CONTROL),revokeKey,"B",revoke));
        expireReference(reference(source));
        forbidden(()->deliveries.control(actor(COUPON_DELIVERY_CONTROL),revokeKey,"B",revoke));
        assertEquals(issue,row("B").issueSourceJson());assertEquals(source,row("B").revokeSourceJson());assertEquals(3,count("employee_command_identity"));
    }

    /** 撤回窗口到期回滚当名撤券和检查点，不把未完成补偿写成REVOCATION_DONE或自动续权。 */
    @Test void compensationExpiryBeforeCommitPreservesIssuedCouponAndFirstSource() {
        create("B");deliveries.pump(actor(COUPON_DELIVERY_PUMP));sourceSeconds.set(2);control("B","REVOKE");
        String source=row("B").revokeSourceJson();
        doAnswer(c->{var result=c.callRealMethod();Thread.sleep(2200);return result;}).when(couponTarget()).revokeTargeted(eq(tenant),anyString(),anyString(),anyString());
        assertEquals(0,deliveries.pump(actor(COUPON_DELIVERY_PUMP)));
        assertEquals(0,row("B").revoked());assertEquals(0,row("B").kept());assertNotEquals("REVOCATION_DONE",row("B").status());
        assertEquals("AVAILABLE",jdbc.queryForObject("SELECT status FROM benefit_coupon WHERE tenant_id=?",String.class,tenant));
        assertEquals("ISSUED",mapper.recipients(tenant,"B","",20).getFirst().status());
        sourceSeconds.set(604860);forbidden(()->control("B","REVOKE"));assertEquals(source,row("B").revokeSourceJson());
    }

    /** 业务deadline独立于七天中央引用；末名效果在业务截止前未提交则回滚，下轮明确过期。 */
    @Test void issuanceBusinessDeadlineDoesNotFollowLongReference() {
        var input=new CouponDeliveryApi.Create("B","store","截止验证","coupon",1,new MarketingAssets.Ref("audience",1),Instant.now().plusSeconds(2),24);
        deliveries.create(actor(COUPON_DELIVERY_CREATE),id(),input);
        doAnswer(c->{var result=c.callRealMethod();Thread.sleep(2200);return result;}).when(couponTarget()).grantTargeted(eq(tenant),anyString(),anyString(),anyString(),anyString(),anyLong());
        assertEquals(0,deliveries.pump(actor(COUPON_DELIVERY_PUMP)));assertEquals(0,count("benefit_coupon"));assertEquals(0,count("automation_coupon_frequency"));assertEquals(0,count("automation_coupon_recipient"));
        ready("B");assertEquals(0,deliveries.pump(actor(COUPON_DELIVERY_PUMP)));assertEquals("EXPIRED",row("B").status());
        assertTrue(references.get(reference(row("B").issueSourceJson())).expires().isAfter(Instant.now().plusSeconds(600000)));
    }

    /** 创建事务末尾也检查新来源期限，已做业务验证不等于允许保存过期的新任务和成功回执。 */
    @Test void expiredNewSourceCannotCommitBatchIdentityOrCommandReceipt() {
        sourceSeconds.set(2);
        doAnswer(c->{c.callRealMethod();Thread.sleep(2200);return null;}).when(couponTarget()).validateExchange(eq(tenant),anyString(),anyString(),anyLong(),any(),any());
        forbidden(()->create("B"));assertEquals(0,count("automation_coupon_batch"));assertEquals(0,count("employee_command_identity"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_command WHERE tenant_id=? AND operation='coupon.delivery.create'",Integer.class,tenant));
    }
}
