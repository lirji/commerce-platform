package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.ordering.order.api.OrderApi;
import com.lrj.commerce.fulfillment.api.FulfillmentApi;
import com.lrj.commerce.aftersales.api.AftersaleApi;
import com.lrj.commerce.payment.charge.api.PaymentApi;
import com.lrj.commerce.payment.refund.api.RefundApi;
import com.lrj.commerce.kernel.DomainException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;

/** 真实 MySQL/HTTP 证明范围、原资金 CAS 与身份审计；SDK 契约桩明确不替代真实 Auth/SpiceDB 演练。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true","commerce.iam.scope.enabled=true"})
class CentralOperationsRuntimeMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/commerce_test_20260923(?:_[a-z0-9]+)?\\?.*"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.address-key",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @Autowired CentralEmployeeService service;
    @Autowired OrderApi orders;
    @Autowired FulfillmentApi fulfillment;
    @Autowired AftersaleApi cases;
    @Autowired PaymentApi payments;
    @Autowired RefundApi refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @LocalServerPort int port;
    private String tenant,authTenant,principal,membership,grant,policy,directory,adminToken,customerToken;
    private Actor admin;
    @Autowired com.lrj.commerce.ops.api.OpsPageApi pages;
    @Autowired com.lrj.commerce.benefit.coupon.api.CouponApi coupons;
    @Autowired com.lrj.commerce.runtime.replay.EventReplay replay;
    @Autowired com.lrj.commerce.runtime.recovery.RuntimeRecovery recovery;
    @Autowired com.lrj.commerce.runtime.event.EventDispatcher events;
    @Autowired EmployeeAccess access;
    private final AtomicReference<Runnable> laterEffect=new AtomicReference<>(),laterCampaign=new AtomicReference<>();
    private final Map<String,Integer> scopeCalls=new ConcurrentHashMap<>();
    private final Set<String> productIds=ConcurrentHashMap.newKeySet();
    private final Map<String,String> references=new ConcurrentHashMap<>();
    private final Set<String> allowed=ConcurrentHashMap.newKeySet();
    private final AtomicLong generation=new AtomicLong(1),sourceSeconds=new AtomicLong(86460);
    private final AtomicBoolean unavailable=new AtomicBoolean();
    private final AtomicReference<Runnable> afterResource=new AtomicReference<>();
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        tenant="000-ops-"+(Long.MAX_VALUE-System.currentTimeMillis())+"-"+id().substring(0,12);authTenant=id();principal=id();membership=id();grant=id();policy=id();directory=id();adminToken=id();customerToken=id();
        generation.set(1);sourceSeconds.set(86460);unavailable.set(false);afterResource.set(null);references.clear();allowed.clear();scopeCalls.clear();productIds.clear();productIds.add("P1");laterEffect.set(null);laterCampaign.set(null);
        for(var cap:EmployeeAccess.Capability.values())allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        credential(adminToken,"admin","ADMIN");credential(customerToken,"customer","MEMBER");credential(id(),"operator","OPERATOR");
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','order-operations-test')",authTenant,principal,membership,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','customer','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,'M','merchant','ACTIVE',0)",tenant);
        for(String store:List.of("S1","S2"))jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,?,'M',?,'ACTIVE',0)",tenant,store,store);
        for(String family:List.of("OPS_PAGE","RUNTIME","EVENT","DASHBOARD","MEMBER_PROFILE","MARKETING_REPORT","COUPON_DEFINITION","CAMPAIGN")) {
            jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,?,'SHADOW')",tenant,authTenant,family);
            jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family=?",tenant,family);
        }
        when(client.requireScope(anyString(),any())).thenAnswer(call->{
            CentralAccessDtos.Check check=call.getArgument(1);require(check);
            var clause=new ScopeDtos.Clause(ScopeDtos.Kind.SPECIFIED_RESOURCES,List.copyOf(productIds),false);
            return new ScopeAccessDtos.Plan("1",check.requestId(),check.capability(),"product","ALLOW",id(),context(),policy,1,directory,1,1,1,Instant.now().plusSeconds(20).toString(),List.of(new ScopeDtos.Alternative(grant,1,List.of(clause))));
        });
        allowed.add("commerce.product.read");
        when(client.issueExecution(anyString(),any(),any())).thenAnswer(call->{
            if(!"valid".equals(call.getArgument(0)))throw new CentralAccessException(401);
            CentralAccessDtos.Check check=call.getArgument(1);require(check);String ref=id();references.put(ref,check.capability());
            Instant requested=call.getArgument(2);Instant expires=check.capability().equals(RUNTIME_REPLAY_CREATE.code())?Instant.now().plusSeconds(sourceSeconds.get()):requested;
            return new ExecutionAccessDtos.Reference("1",check.requestId(),ref,context(),check.capability(),check.resourceType(),expires.toString());
        });
        when(client.executionScope(anyString(),any())).thenAnswer(call->{
            CentralAccessDtos.Check check=call.getArgument(1);require(check);
            if(!check.capability().equals(references.get(call.<String>getArgument(0))))throw new AccessDeniedException("wrong reference");
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"远程鉴权须在业务事务外");
            int calls=scopeCalls.merge(check.capability(),1,Integer::sum);
            if(calls>=2&&check.capability().equals(MARKETING_EFFECT_READ.code())){var change=laterEffect.getAndSet(null);if(change!=null)change.run();}
            if(calls>=2&&check.capability().equals(CAMPAIGN_READ.code())){var change=laterCampaign.getAndSet(null);if(change!=null)change.run();}
            var clause=new ScopeDtos.Clause(ScopeDtos.Kind.TENANT_ALL,List.of(),false);
            return new ScopeAccessDtos.Plan("1",check.requestId(),check.capability(),check.resourceType(),"ALLOW",id(),context(),policy,1,directory,1,1,1,Instant.now().plusSeconds(20).toString(),List.of(new ScopeDtos.Alternative(grant,1,List.of(clause))));
        });
        when(client.checkExecution(anyString(),any(),any())).thenAnswer(call->{
            CentralAccessDtos.Check check=call.getArgument(1);ScopeDtos.Facts fact=call.getArgument(2);require(check);
            if(!check.capability().equals(references.get(call.<String>getArgument(0))))throw new AccessDeniedException("wrong reference or store");
            assertEquals(authTenant,fact.tenantId());assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"对象鉴权须在事务外");
            var result=new ScopeAccessDtos.ResourceDecision("1",check.requestId(),check.capability(),fact.resourceType(),fact.resourceId(),fact.resourceVersion(),"ALLOW",id(),context(),Instant.now().plusSeconds(20).toString());
            var mutation=afterResource.getAndSet(null);if(mutation!=null)mutation.run();return result;
        });
    }
    private void credential(String token,String actor,String role){jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,?,?)",JsonCodec.hash(token),tenant,actor,role,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));}
    private void require(CentralAccessDtos.Check check){if(unavailable.get())throw new CentralAccessException(503);if(!authTenant.equals(check.tenantId())||!allowed.contains(check.capability()))throw new AccessDeniedException("denied");}
    private GovernanceDtos.AccessContext context(){return new GovernanceDtos.AccessContext(principal,membership,generation.get(),1,1,authTenant,"commerce","test","order-operations-test","HUMAN",id());}
    private Actor actor(EmployeeAccess.Capability cap){return service.authenticate("valid",authTenant,cap,cap==RUNTIME_REPLAY_CREATE);}
    private void only(EmployeeAccess.Capability cap){allowed.clear();allowed.add(cap.code());}
    private void forbidden(Runnable run){assertEquals(DomainException.Code.FORBIDDEN,assertThrows(DomainException.class,run::run).code());}
    private void order(String id,String store,String status){jdbc.update("INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version) VALUES(?,?,'M1',?,'M',?,12.00,?,'CHANNEL_REQUIRED',0,?,?,'[]',X'01',1)",tenant,id,store,id,status,java.sql.Timestamp.from(Instant.now().minusSeconds(100)),java.sql.Timestamp.from(Instant.now().minusSeconds(10)));}
    private void fulfillment(String order,boolean blocked){jdbc.update("INSERT INTO fulfillment_record(tenant_id,order_id,status,provider,blocked) VALUES(?,?,'READY','SANDBOX_WMS',?)",tenant,order,blocked);}
    private void aftersale(String id,String order,boolean returns){jdbc.update("INSERT INTO aftersales_case(tenant_id,case_id,order_id,member_id,status,return_required,refund_amount,reason,items_json) VALUES(?,?,?,'M1','REQUESTED',?,0,'test','[]')",tenant,id,order,returns);}
    private int audits(String operation){return jdbc.queryForObject("SELECT COUNT(*) FROM employee_command_identity WHERE tenant_id=? AND operation=?",Integer.class,tenant,operation);}

    private com.lrj.commerce.ops.api.OpsPageApi.Definition definition(String page, List<com.lrj.commerce.ops.api.OpsPageApi.Section> sections) {
        return new com.lrj.commerce.ops.api.OpsPageApi.Definition(page,1,"真实运营页","S1",sections,List.of(new com.lrj.commerce.ops.api.OpsPageApi.Action("coupon","创建券",com.lrj.commerce.ops.api.OpsPageApi.ActionKind.CREATE_COUPON)));
    }
    private com.lrj.commerce.benefit.coupon.api.CouponApi.Definition coupon(String id) {
        return new com.lrj.commerce.benefit.coupon.api.CouponApi.Definition(id,1,"S1","测试券","0.00","1.00",Instant.now().minusSeconds(30),Instant.now().plusSeconds(7200),10,true,0,"PUBLIC",null);
    }
    private void publish(String page) {
        pages.create(actor(OPS_PAGE_CREATE),id(),definition(page,List.of(new com.lrj.commerce.ops.api.OpsPageApi.Section("coupons","优惠券",com.lrj.commerce.ops.api.OpsPageApi.Source.COUPONS))));
        pages.change(actor(OPS_PAGE_SUBMIT),id(),page,1,0,"submit");pages.change(actor(OPS_PAGE_APPROVE),id(),page,1,1,"approve");pages.change(actor(OPS_PAGE_PUBLISH),id(),page,1,2,"publish");
    }
    @Test void pageCapabilitiesDoNotImplyReadsOrChildActionsAndOldAdminIsBlocked() throws Exception {
        only(OPS_PAGE_CREATE);pages.create(actor(OPS_PAGE_CREATE),id(),definition("page",List.of(new com.lrj.commerce.ops.api.OpsPageApi.Section("coupons","优惠券",com.lrj.commerce.ops.api.OpsPageApi.Source.COUPONS))));
        var old=actor(OPS_PAGE_CREATE);forbidden(()->pages.list(old,"",1));
        assertEquals(403,http("GET","/v1/admin/ops-pages",adminToken,false,null,null).statusCode());
        assertEquals(1,audits("ops-page.create"));
        assertEquals(1,jdbc.queryForObject("SELECT resource_version FROM employee_command_identity WHERE tenant_id=? AND operation='ops-page.create'",Integer.class,tenant));
    }
    @Test void pagePreparedChildUsesSeparatePermissionAndAtomicRollbackWhenStoreChanges() {
        publish("page");var page=actor(OPS_PAGE_EXECUTE);var input=new com.lrj.commerce.ops.api.OpsPageApi.ActionInput(null,coupon("prepared"),null);
        var prepared=pages.prepareExecution(page,id(),"page",1,"coupon",input);
        var child=actor(COUPON_DEFINITION_CREATE);
        jdbc.update("UPDATE store_record SET version=version+1 WHERE tenant_id=? AND store_id='S1'",tenant);
        assertThrows(DomainException.class,()->prepared.execute(child));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='prepared'",Integer.class,tenant));assertEquals(0,audits("ops-page.execute"));
        var retry=pages.prepareExecution(actor(OPS_PAGE_EXECUTE),id(),"page",1,"coupon",input);assertEquals("CREATED",retry.execute(actor(COUPON_DEFINITION_CREATE)).status());assertEquals(1,audits("ops-page.execute"));
        var key=id();var duplicate=pages.prepareExecution(actor(OPS_PAGE_EXECUTE),key,"page",1,"coupon",input);
        assertThrows(Exception.class,()->duplicate.execute(actor(COUPON_DEFINITION_CREATE)));assertEquals(1,audits("ops-page.execute"));
    }
    @Test void pageVersionChangeAfterPrepareAndWrongChildReferenceAreRejected() {
        publish("page");var prepared=pages.prepareExecution(actor(OPS_PAGE_EXECUTE),id(),"page",1,"coupon",new com.lrj.commerce.ops.api.OpsPageApi.ActionInput(null,coupon("frozen"),null));
        pages.change(actor(OPS_PAGE_PAUSE),id(),"page",1,3,"pause");assertThrows(DomainException.class,()->prepared.execute(actor(COUPON_DEFINITION_CREATE)));assertEquals(0,audits("ops-page.execute"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='frozen'",Integer.class,tenant));
        publish("other");var other=actor(OPS_PAGE_EXECUTE);var wrong=pages.prepareExecution(other,id(),"other",1,"coupon",new com.lrj.commerce.ops.api.OpsPageApi.ActionInput(null,coupon("wrong"),null));forbidden(()->wrong.execute(other));
    }
    @Test void aggregateRechecksEarlierChildAfterLaterSourceRevokesIt() {
        var input=definition("preview",List.of(new com.lrj.commerce.ops.api.OpsPageApi.Section("coupons","券",com.lrj.commerce.ops.api.OpsPageApi.Source.COUPONS),new com.lrj.commerce.ops.api.OpsPageApi.Section("campaigns","活动",com.lrj.commerce.ops.api.OpsPageApi.Source.CAMPAIGNS)));
        var prepared=pages.preparePreview(actor(OPS_PAGE_PREVIEW),input);var children=Map.of(COUPON_DEFINITION_READ,actor(COUPON_DEFINITION_READ),CAMPAIGN_READ,actor(CAMPAIGN_READ));
        laterCampaign.set(()->allowed.remove(COUPON_DEFINITION_READ.code()));forbidden(()->prepared.render(children));assertEquals(0,audits("ops-page.create"));
    }
    @Test void dashboardProductSqlUsesActualProductAndIndependentSourcesWithFinalGate() throws Exception {
        jdbc.update("INSERT INTO catalog_sku(tenant_id,sku_id,store_id,title,unit_price,revision,status,product_id,specifications_json,specification_key) VALUES(?,'SKU1','S1','allowed',1,1,'ACTIVE','P1',JSON_OBJECT(),'one'),(?,'SKU2','S1','denied',1,1,'ACTIVE','P2',JSON_OBJECT(),'two'),(?,'old','S1','legacy',1,1,'ACTIVE',NULL,NULL,NULL)",tenant,tenant,tenant);
        var result=http("GET","/v1/admin/dashboard?storeId=S1","valid",true,null,null);assertEquals(200,result.statusCode(),result.body());
        assertEquals(1,JsonCodec.read(result.body(),tools.jackson.databind.JsonNode.class).path("catalog").path("total").asInt());
        allowed.remove(MEMBER_READ.code());assertEquals(403,http("GET","/v1/admin/dashboard?storeId=S1","valid",true,null,null).statusCode());allowed.add(MEMBER_READ.code());
        scopeCalls.clear();laterEffect.set(()->allowed.remove("commerce.product.read"));assertEquals(403,http("GET","/v1/admin/dashboard?storeId=S1","valid",true,null,null).statusCode());allowed.add("commerce.product.read");
        scopeCalls.clear();laterEffect.set(()->allowed.remove(MEMBER_READ.code()));assertEquals(403,http("GET","/v1/admin/dashboard?storeId=S1","valid",true,null,null).statusCode());
    }

    private com.lrj.commerce.runtime.replay.EventReplay.Create replayInput(String job,String consumer,String type){return new com.lrj.commerce.runtime.replay.EventReplay.Create(job,consumer,List.of(type),Instant.now().minusSeconds(60),Instant.now(),com.lrj.commerce.runtime.replay.ReplayGate.Mode.UNPROCESSED,10,"实际原任务来源验收");}
    @Test void replayOriginalSourceCannotBeReplacedByFreshCreateOrControlReference() {
        var creator=actor(RUNTIME_REPLAY_CREATE);String key=id();var input=replayInput("source","marketing-effects-v1","order.created.v1");
        var job=replay.create(creator,key,input);String source=jdbc.queryForObject("SELECT source_json FROM employee_runtime_replay_source WHERE tenant_id=? AND job_id='source'",String.class,tenant);
        assertEquals(creator.executionId(),JsonCodec.read(source,tools.jackson.databind.JsonNode.class).path("actor").path("executionId").asString());
        replay.control(actor(RUNTIME_REPLAY_CONTROL),id(),"source",new com.lrj.commerce.runtime.replay.EventReplay.Control("PAUSE",job.version(),"保持原来源"));
        assertEquals(source,jdbc.queryForObject("SELECT source_json FROM employee_runtime_replay_source WHERE tenant_id=? AND job_id='source'",String.class,tenant));
        references.remove(creator.executionId());var current=actor(RUNTIME_REPLAY_CONTROL);forbidden(()->replay.control(current,id(),"source",new com.lrj.commerce.runtime.replay.EventReplay.Control("RESUME",1,"不可续期")));
        var fresh=actor(RUNTIME_REPLAY_CREATE);forbidden(()->replay.create(fresh,key,input));assertEquals(1,audits("runtime.replay.create"));assertEquals(1,audits("runtime.replay.control"));
        assertEquals(source,jdbc.queryForObject("SELECT source_json FROM employee_runtime_replay_source WHERE tenant_id=? AND job_id='source'",String.class,tenant));
    }
    @Test void expiredFiniteReplayHasNoProjectionCursorOrFailureBudgetAndSafetyGateBlocksFunds() throws Exception {
        order("source-order","S1","PAID");String event=id();jdbc.update("INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,created_at) VALUES(?,?,'order.created.v1','source-order',1,'{}','DELIVERED',?)",event,tenant,java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        sourceSeconds.set(2);replay.create(actor(RUNTIME_REPLAY_CREATE),id(),replayInput("expiry","marketing-effects-v1","order.created.v1"));
        Thread.sleep(2300);replay.tick();
        assertEquals("0:0:0",jdbc.queryForObject("SELECT CONCAT(examined,':',executed,':',failed) FROM platform_replay WHERE tenant_id=? AND job_id='expiry'",String.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM marketing_effect_order WHERE tenant_id=? AND order_id='source-order'",Integer.class,tenant));
        var gate=replay.dryRun(actor(RUNTIME_REPLAY_PREVIEW),new com.lrj.commerce.runtime.replay.EventReplay.Scope("order-payment-v1",List.of("payment.paid.v1"),Instant.now().minusSeconds(60),Instant.now(),com.lrj.commerce.runtime.replay.ReplayGate.Mode.UNPROCESSED,10));assertFalse(gate.gate().allowed());
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->replay.create(actor(RUNTIME_REPLAY_CREATE),id(),replayInput("funds","order-payment-v1","payment.paid.v1"))).code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_replay WHERE tenant_id=? AND job_id='funds'",Integer.class,tenant));
    }
    @Test void independentEventRetryIntegerReceiptAndRuntimeRecoveryKeepOriginalFailureAudit() throws Exception {
        String event=id();jdbc.update("INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,attempts,failure_class,last_error) VALUES(?,?,'order.created.v1','original',1,'{}','ISOLATED',8,'BUSINESS_REJECTED','prior-evidence')",event,tenant);
        only(EVENT_RETRY);String key=id();assertEquals(1,events.retry(actor(EVENT_RETRY),key,event));assertEquals(1,events.retry(actor(EVENT_RETRY),key,event));assertEquals(1,audits("event.retry"));
        assertEquals("prior-evidence",jdbc.queryForObject("SELECT last_error FROM platform_event WHERE event_id=?",String.class,event));
        jdbc.update("UPDATE platform_event SET status='ISOLATED',attempts=8 WHERE event_id=?",event);only(RUNTIME_RECOVER);
        var request=new com.lrj.commerce.runtime.recovery.RuntimeRecovery.Request("event",com.lrj.commerce.runtime.api.recovery.RecoverableWork.Action.RETRY,List.of(event),"BUSINESS_REJECTED","原停止事件重新放回");String recoveryKey=id();assertEquals(1,recovery.recover(actor(RUNTIME_RECOVER),recoveryKey,request).applied());assertEquals(1,recovery.recover(actor(RUNTIME_RECOVER),recoveryKey,request).applied());assertEquals(1,audits("runtime.recovery"));
        assertEquals("prior-evidence",jdbc.queryForObject("SELECT last_error FROM platform_event WHERE event_id=?",String.class,event));
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body,String key)throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)r.header("X-Tenant-Id",authTenant);if(key!=null)r.header("Idempotency-Key",key);if(body!=null)r.header("Content-Type","application/json");
        r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(r.build(),HttpResponse.BodyHandlers.ofString());
    }

}
