package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.access.EmployeeAuthority;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.lrj.commerce.benefit.entitlement.api.EntitlementApi;
import com.lrj.commerce.runtime.event.EventDispatcher;
import com.lrj.commerce.kernel.DomainException;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;

/** 真实MySQL验证权益版本事实、补偿台账与命令/身份审计原子性；中央协议桩不替代跨进程验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralEntitlementMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @MockitoSpyBean EmployeeAuthority authority;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CentralEmployeeService service;
    @Autowired EntitlementApi benefits;
    @Autowired EventDispatcher events;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private String tenant,authTenant,principal,member,grant,policy,directory,adminToken;
    private Actor admin;
    private final Set<String> allowed=ConcurrentHashMap.newKeySet();
    private final Map<String,String> references=new ConcurrentHashMap<>();
    private final AtomicLong generation=new AtomicLong(1),epoch=new AtomicLong(1);
    private final AtomicBoolean unavailable=new AtomicBoolean(),partial=new AtomicBoolean();
    private final AtomicReference<Runnable> afterScope=new AtomicReference<>(),afterResource=new AtomicReference<>();
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        tenant="entitlements-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:List.of(ENTITLEMENT_DEFINITION_READ,ENTITLEMENT_DEFINITION_CREATE,ENTITLEMENT_READ,ENTITLEMENT_RESOLVE))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        for(String family:List.of("ENTITLEMENT_DEFINITION","ENTITLEMENT")) {
            jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,?,'SHADOW')",tenant,authTenant,family);
            jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family=?",tenant,family);
        }
        jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,'MERCHANT','merchant','ACTIVE',0)",tenant);
        for (String store : List.of("S1","S2")) jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,?,'MERCHANT','store','ACTIVE',0)",tenant,store);
        when(client.issueExecution(anyString(),any(),any())).thenAnswer(call->{
            if(!"valid".equals(call.getArgument(0)))throw new CentralAccessException(401);
            CentralAccessDtos.Check check=call.getArgument(1);require(check);String ref=id();references.put(ref,check.capability());
            return new ExecutionAccessDtos.Reference("1",check.requestId(),ref,context(),check.capability(),check.resourceType(),((Instant)call.getArgument(2)).toString());
        });
        when(client.executionScope(anyString(),any())).thenAnswer(call->{
            CentralAccessDtos.Check check=call.getArgument(1);require(check);
            if(!check.capability().equals(references.get(call.<String>getArgument(0))))throw new AccessDeniedException("wrong reference");
            var clause=new ScopeDtos.Clause(partial.get()?ScopeDtos.Kind.SPECIFIED_RESOURCES:ScopeDtos.Kind.TENANT_ALL,partial.get()?List.of("M1"):List.of(),false);
            var plan=new ScopeAccessDtos.Plan("1",check.requestId(),check.capability(),check.resourceType(),"ALLOW",id(),context(),policy,epoch.get(),directory,1,1,1,Instant.now().plusSeconds(20).toString(),List.of(new ScopeDtos.Alternative(grant,1,List.of(clause))));
            var mutation=afterScope.getAndSet(null);if(mutation!=null)mutation.run();return plan;
        });
        when(client.checkExecution(anyString(),any(),any())).thenAnswer(call->{
            CentralAccessDtos.Check check=call.getArgument(1);ScopeDtos.Facts facts=call.getArgument(2);require(check);
            if(!check.capability().equals(references.get(call.<String>getArgument(0))))throw new AccessDeniedException("wrong reference");
            assertEquals(authTenant,facts.tenantId());assertEquals("entitlement",facts.resourceType());assertNull(facts.storeId());assertNull(facts.departmentId());
            var result=new ScopeAccessDtos.ResourceDecision("1",check.requestId(),check.capability(),check.resourceType(),facts.resourceId(),facts.resourceVersion(),"ALLOW",id(),context(),Instant.now().plusSeconds(20).toString());
            var mutation=afterResource.getAndSet(null);if(mutation!=null)mutation.run();return result;
        });
    }
    private void require(CentralAccessDtos.Check check) {
        if(unavailable.get())throw new CentralAccessException(503);
        if(!authTenant.equals(check.tenantId())||!allowed.contains(check.capability()))throw new AccessDeniedException("denied");
    }
    private GovernanceDtos.AccessContext context(){return new GovernanceDtos.AccessContext(principal,member,generation.get(),1,1,authTenant,"commerce","test","member-test","HUMAN",id());}
    private Actor actor(EmployeeAccess.Capability capability){return service.authenticate("valid",authTenant,capability);}
    private void forbidden(Runnable action){assertEquals(DomainException.Code.FORBIDDEN,assertThrows(DomainException.class,action::run).code());}
    private Actor customer() { return new Actor(tenant,"customer",Actor.Role.MEMBER); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant); }
    private EntitlementApi.Definition definition(String id,long version,String store) {
        return new EntitlementApi.Definition(id,version,store,"权益测试",5,20,Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),7);
    }
    private EntitlementApi.DefinitionView create(String id) { return benefits.create(actor(ENTITLEMENT_DEFINITION_CREATE),id(),definition(id,1,"S1")); }
    /** 从可信订单域入口运行真实预留/确认/事件/核销/冲正；此处不冒充HTTP整单退款。 */
    private String pending(String benefit) {
        String order=id();var tx=new TransactionTemplate(transactions);
        tx.executeWithoutResult(s->{benefits.reserveOrder(admin,order,"M1","S1",new EntitlementApi.Ref(benefit,1));benefits.confirmOrder(tenant,order);});
        String grant=benefits.orderGrant(tenant,order).grantId();
        for(int n=0;n<8 && benefits.orderGrant(tenant,order).status()!=EntitlementApi.State.AVAILABLE;n++)events.pump(admin);
        assertEquals(EntitlementApi.State.AVAILABLE,benefits.orderGrant(tenant,order).status());
        benefits.consume(customer(),id(),grant,new EntitlementApi.Consume(2));
        tx.executeWithoutResult(s->benefits.reverseOrder(tenant,order));
        assertEquals(EntitlementApi.State.COMPENSATION_REQUIRED,benefits.orderGrant(tenant,order).status());
        return grant;
    }
    private EntitlementApi.Resolution resolution() { return new EntitlementApi.Resolution("WRITTEN_OFF","approved-local-proof"); }
    /** 定义只需创建许可，最新版本目录不回退到旧门店，也不附赠实例读取。 */
    @Test void definitionAndBothReadsRemainIndependentWithStableLatestCursor() throws Exception {
        var reader=actor(ENTITLEMENT_DEFINITION_READ);allowed.remove(ENTITLEMENT_DEFINITION_READ.code());
        var input=definition("A",1,"S1");String key=id();var first=benefits.create(actor(ENTITLEMENT_DEFINITION_CREATE),key,input);
        assertEquals(first,benefits.create(actor(ENTITLEMENT_DEFINITION_CREATE),key,input));forbidden(()->benefits.definitions(reader,"S1","",50));
        allowed.add(ENTITLEMENT_DEFINITION_READ.code());var second=benefits.create(actor(ENTITLEMENT_DEFINITION_CREATE),id(),definition("A",2,"S2"));var b=create("B");
        assertEquals(List.of(b),benefits.definitions(actor(ENTITLEMENT_DEFINITION_READ),"S1","",1));assertEquals(List.of(second),benefits.definitions(actor(ENTITLEMENT_DEFINITION_READ),"S2","",1));
        assertTrue(benefits.definitions(actor(ENTITLEMENT_DEFINITION_READ),"S1","B",1).isEmpty());
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->benefits.create(actor(ENTITLEMENT_DEFINITION_CREATE),id(),input));
        assertEquals(409,http("POST","/v1/admin/entitlement-definitions","valid",true,input).statusCode());
        var instanceReader=actor(ENTITLEMENT_READ);allowed.remove(ENTITLEMENT_READ.code());forbidden(()->benefits.adminList(instanceReader,"",50));
        assertEquals(3,count("employee_command_identity"));assertEquals(3,count("benefit_definition"));
        for(String path:List.of("/v1/admin/entitlement-definitions?storeId=S1","/v1/admin/entitlements"))assertEquals(403,http("GET",path,adminToken,false,null).statusCode());
    }
    /** 处理独立于读取；真实欠项只清理一次，成功后版本变化不破坏原键，代际变化则拒绝。 */
    @Test void resolveBindsActualGrantAndKeepsOriginalReceiptStable() {
        create("A");String grant=pending("A"),key=id();var reader=actor(ENTITLEMENT_READ);allowed.remove(ENTITLEMENT_READ.code());
        var first=benefits.resolve(actor(ENTITLEMENT_RESOLVE),key,grant,resolution());assertEquals(EntitlementApi.State.COMPENSATED,first.status());assertEquals(0,first.debtUnits());assertEquals(0,first.remainingUnits());
        assertEquals(first,benefits.resolve(actor(ENTITLEMENT_RESOLVE),key,grant,resolution()));forbidden(()->benefits.adminList(reader,"",10));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->benefits.resolve(actor(ENTITLEMENT_RESOLVE),id(),grant,resolution())).code());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM benefit_ledger WHERE tenant_id=? AND grant_id=? AND action='WRITTEN_OFF' AND units=2 AND reference='approved-local-proof'",Integer.class,tenant,grant));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='entitlement' AND resource_id=? AND store_id IS NULL",Integer.class,tenant,grant));
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->benefits.resolve(actor(ENTITLEMENT_RESOLVE),key,grant,resolution())).code());
    }
    /** 审计失败必须回滚定义、状态、补偿账本及命令回执，不能只回滚业务表。 */
    @Test void auditFailureRollsBackBothWritesAndCompensationLedger() {
        create("A");String grant=pending("A"),defineKey=id(),resolveKey=id();
        EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doThrow(new IllegalStateException("injected audit failure")).when(target).audit(any(Actor.class),any(EmployeeAccess.ScopePermit.class),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->benefits.create(actor(ENTITLEMENT_DEFINITION_CREATE),defineKey,definition("B",1,"S1")));
        assertThrows(IllegalStateException.class,()->benefits.resolve(actor(ENTITLEMENT_RESOLVE),resolveKey,grant,resolution()));
        assertEquals(1,count("benefit_definition"));assertEquals("COMPENSATION_REQUIRED",jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=? AND grant_id=?",String.class,tenant,grant));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM benefit_ledger WHERE tenant_id=? AND action='WRITTEN_OFF'",Integer.class,tenant));
        for(String key:List.of(defineKey,resolveKey)) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
        }
    }
    /** 事实/授权竞态、部分范围及HTTP身份失败都不能放行；编码冒号仍按真实标识判权。 */
    @Test void ownerScopeHttpAndEncodedIdentifierBoundariesAreEnforced() throws Exception {
        create("A");String grant=pending("A");
        afterResource.set(()->jdbc.update("UPDATE benefit_grant SET version=version+1 WHERE tenant_id=? AND grant_id=?",tenant,grant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->benefits.resolve(actor(ENTITLEMENT_RESOLVE),id(),grant,resolution())).code());
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->benefits.resolve(actor(ENTITLEMENT_RESOLVE),id(),"foreign",resolution())).code());
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->benefits.adminList(actor(ENTITLEMENT_READ),"",10));
        partial.set(true);forbidden(()->benefits.definitions(actor(ENTITLEMENT_DEFINITION_READ),"S1","",10));forbidden(()->benefits.resolve(actor(ENTITLEMENT_RESOLVE),id(),grant,resolution()));partial.set(false);
        for(String path:List.of("/v1/admin/entitlement-definitions?storeId=S1","/v1/admin/entitlements")) {
            assertEquals(200,http("GET",path,"valid",true,null).statusCode());assertEquals(401,http("GET",path,"invalid",true,null).statusCode());
        }
        // 单独的合法标识夹具只证明HTTP路由编码，不作为退款链路证据。
        String encoded="grant:"+"a".repeat(58);
        jdbc.update("INSERT INTO benefit_grant(tenant_id,grant_id,order_id,member_id,benefit_id,benefit_version,name,status,units,remaining_units,debt_units,version,source_type,source_id,expires_at) VALUES(?,?,?,'M1','A',1,'encoded','COMPENSATION_REQUIRED',5,0,2,0,'ORDER',?,?)",tenant,encoded,id(),id(),java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        assertEquals(200,http("POST","/v1/admin/entitlements/"+encoded.replace(":","%3A")+"/resolve","valid",true,new EntitlementApi.Resolution("RECOVERED","actual-recovery-proof")).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_id=? AND resource_type='entitlement'",Integer.class,tenant,encoded));
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/entitlements","valid",true,null).statusCode());
    }
    /** 撤权或族停止先于旧回执；定义与实例两族独立，不污染另一族读取。 */
    @Test void revocationAndRouteChangesPrecedeOldReceipts() {
        var input=definition("A",1,"S1");String key=id();var define=actor(ENTITLEMENT_DEFINITION_CREATE);benefits.create(define,key,input);
        allowed.remove(ENTITLEMENT_DEFINITION_CREATE.code());forbidden(()->benefits.create(define,key,input));allowed.add(ENTITLEMENT_DEFINITION_CREATE.code());
        String grant=pending("A"),resolveKey=id();var resolver=actor(ENTITLEMENT_RESOLVE);benefits.resolve(resolver,resolveKey,grant,resolution());
        allowed.remove(ENTITLEMENT_RESOLVE.code());forbidden(()->benefits.resolve(resolver,resolveKey,grant,resolution()));allowed.add(ENTITLEMENT_RESOLVE.code());
        afterResource.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='ENTITLEMENT'",tenant));forbidden(()->benefits.resolve(resolver,resolveKey,grant,resolution()));
        assertEquals(1,benefits.definitions(actor(ENTITLEMENT_DEFINITION_READ),"S1","",10).size());forbidden(()->benefits.adminList(admin,"",10));assertEquals(2,count("employee_command_identity"));
    }
    /** 两员工族停用后，既有受理事件仍发放；本人消费幂等且不能超扣。 */
    @Test void stoppedEmployeesDoNotStopSystemFulfillmentOrCustomerConsumption() {
        create("A");var tx=new TransactionTemplate(transactions);
        var requested=tx.execute(s->benefits.grantFromPoints(tenant,"M1","S1","points-proof",new EntitlementApi.Ref("A",1)));
        allowed.clear();jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=?",tenant);
        for(int n=0;n<8 && benefits.wallet(customer(),"",10).getFirst().status()!=EntitlementApi.State.AVAILABLE;n++)events.pump(admin);
        assertEquals(EntitlementApi.State.AVAILABLE,benefits.wallet(customer(),"",10).getFirst().status());String key=id();var input=new EntitlementApi.Consume(2);
        var first=benefits.consume(customer(),key,requested.grantId(),input);assertEquals(first,benefits.consume(customer(),key,requested.grantId(),input));assertEquals(3,first.remainingUnits());
        assertThrows(DomainException.class,()->benefits.consume(customer(),id(),requested.grantId(),new EntitlementApi.Consume(4)));
        assertEquals(3,benefits.wallet(customer(),"",10).getFirst().remainingUnits());assertEquals(2,benefits.ledger(customer(),requested.grantId(),"",10).size());
        assertEquals(1,count("employee_command_identity"));assertEquals(1,jdbc.queryForObject("SELECT issued FROM benefit_definition WHERE tenant_id=?",Integer.class,tenant));
        var other=new Actor(tenant,"other-customer",Actor.Role.MEMBER);assertThrows(DomainException.class,()->benefits.consume(other,id(),requested.grantId(),input));
    }
    /** 定义审计使用实际64字标识，中央许可不替代商家状态和现有单位约束。 */
    @Test void definitionKeepsActualOwnerAndOriginalValidation() {
        String identifier="e:"+"x".repeat(62);create(identifier);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_id=? AND resource_type='entitlement_definition' AND store_id IS NULL",Integer.class,tenant,identifier));
        jdbc.update("UPDATE merchant_record SET status='FROZEN' WHERE tenant_id=?",tenant);
        assertThrows(DomainException.class,()->create("blocked"));assertEquals(1,count("benefit_definition"));
        var d=definition("invalid",1,"S1");assertThrows(DomainException.class,()->benefits.create(actor(ENTITLEMENT_DEFINITION_CREATE),id(),new EntitlementApi.Definition(d.benefitId(),1,"S1",d.name(),0,20,d.validFrom(),d.validTo(),7)));
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
