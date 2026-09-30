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
import com.lrj.commerce.benefit.coupon.api.CouponApi;
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

/** 真实MySQL验证券定义不可变版本、命令/身份审计原子性；中央协议桩不替代跨进程验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralCouponDefinitionMySqlTest {
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
    @Autowired CouponApi coupons;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private String tenant,authTenant,principal,member,grant,policy,directory,adminToken;
    private Actor admin;
    private final Set<String> allowed=ConcurrentHashMap.newKeySet();
    private final Map<String,String> references=new ConcurrentHashMap<>();
    private final AtomicLong generation=new AtomicLong(1),epoch=new AtomicLong(1);
    private final AtomicBoolean unavailable=new AtomicBoolean(),partial=new AtomicBoolean();
    private final AtomicReference<Runnable> afterScope=new AtomicReference<>();
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        tenant="coupon-definitions-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);
        for(var cap:List.of(COUPON_DEFINITION_READ,COUPON_DEFINITION_CREATE))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'COUPON_DEFINITION','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='COUPON_DEFINITION'",tenant);
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
        when(client.checkExecution(anyString(),any(),any())).thenThrow(new AssertionError("券定义只使用集合许可"));
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
    private CouponApi.Definition definition(String name,long version,String mode) {
        return new CouponApi.Definition(name,version,"S1","券定义测试","0.00","5.00",
                Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),1,true,null,mode,null);
    }
    private CouponApi.DefinitionView create(String name,long version,String mode) {
        return coupons.create(actor(COUPON_DEFINITION_CREATE),id(),definition(name,version,mode));
    }
    /** 创建独立于目录读取；最新版本不回退泄露旧公开版本，游标与实际审计保持稳定。 */
    @Test void independentCreationPreservesLatestVersionCursorAndActualAudit() throws Exception {
        var reader=actor(COUPON_DEFINITION_READ);allowed.remove(COUPON_DEFINITION_READ.code());String key=id();var input=definition("A",1,null);
        var first=coupons.create(actor(COUPON_DEFINITION_CREATE),key,input);
        assertEquals(first,coupons.create(actor(COUPON_DEFINITION_CREATE),key,input));
        assertEquals("PUBLIC",first.content().issuanceMode());assertEquals(0,first.content().platformFundingBps());assertNull(first.content().validityDays());
        forbidden(()->coupons.definitions(reader,"S1","",50));allowed.add(COUPON_DEFINITION_READ.code());
        var latest=create("A",2,"SOURCE_ONLY");var second=create("B",1,"PUBLIC");
        assertEquals(List.of(latest),coupons.definitions(actor(COUPON_DEFINITION_READ),"S1","",1));
        assertEquals(List.of(second),coupons.definitions(actor(COUPON_DEFINITION_READ),"S1","A",1));
        assertTrue(coupons.definitions(actor(COUPON_DEFINITION_READ),"S1","B",1).isEmpty());assertTrue(coupons.definitions(actor(COUPON_DEFINITION_READ),"S2","",10).isEmpty());
        assertEquals(List.of(second),coupons.definitions(customer(),"S1","",50));
        assertEquals(3,count("benefit_coupon_definition"));assertEquals(3,count("employee_command_identity"));assertEquals(0,count("benefit_coupon"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='coupon_definition' AND resource_id='A' AND store_id IS NULL",Integer.class,tenant));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->coupons.create(actor(COUPON_DEFINITION_CREATE),id(),input));assertEquals(3,count("employee_command_identity"));
        forbidden(()->coupons.definitions(admin,"S1","",10));
        assertEquals(403,http("GET","/v1/coupon-definitions?storeId=S1",adminToken,false,null).statusCode());
        assertEquals(403,http("GET","/v1/admin/coupon-definitions?storeId=S1",adminToken,false,null).statusCode());
    }
    /** 审计失败不能留下定义或成功命令；最长合法编号直接作为审计目标而不拼接版本。 */
    @Test void auditFailureRollsBackDefinitionAndCommand() {
        var first=create("A".repeat(64),1,"PUBLIC");assertEquals(64,first.content().definitionId().length());
        EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doThrow(new IllegalStateException("injected identity audit failure")).when(target).audit(any(Actor.class),any(EmployeeAccess.ScopePermit.class),anyString(),anyString(),anyString());
        String key=id();assertThrows(IllegalStateException.class,()->coupons.create(actor(COUPON_DEFINITION_CREATE),key,definition("B",1,"PUBLIC")));
        assertEquals(1,count("benefit_coupon_definition"));assertEquals(1,count("employee_command_identity"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
    }
    /** 撤权、代际与路由停止检查早于旧成功回执，重试不能恢复历史权利。 */
    @Test void revokedGenerationAndStoppedRouteCannotReplayReceipts() {
        var creator=actor(COUPON_DEFINITION_CREATE);String key=id();var input=definition("A",1,"PUBLIC");coupons.create(creator,key,input);
        allowed.remove(COUPON_DEFINITION_CREATE.code());forbidden(()->coupons.create(creator,key,input));allowed.add(COUPON_DEFINITION_CREATE.code());
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->coupons.create(actor(COUPON_DEFINITION_CREATE),key,input)).code());
        afterScope.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='COUPON_DEFINITION'",tenant));
        forbidden(()->coupons.create(actor(COUPON_DEFINITION_CREATE),id(),definition("B",1,"PUBLIC")));
        forbidden(()->coupons.create(admin,key,input));assertEquals(1,count("benefit_coupon_definition"));assertEquals(1,count("employee_command_identity"));
    }
    /** 客户自助及可信来源发放不借员工权限；停止员工管理仍保持来源去重与真实配额。 */
    @Test void customerClaimAndTrustedIssuanceSurviveEmployeeStop() {
        create("A",1,"PUBLIC");create("B",1,"SOURCE_ONLY");allowed.clear();unavailable.set(true);
        jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='COUPON_DEFINITION'",tenant);
        assertEquals(1,coupons.definitions(customer(),"S1","",50).size());String key=id();var claimed=coupons.claim(customer(),key,"A",1);
        assertEquals(claimed,coupons.claim(customer(),key,"A",1));assertEquals(claimed,coupons.claim(customer(),id(),"A",1));
        assertThrows(DomainException.class,()->coupons.claim(customer(),id(),"B",1));
        var tx=new TransactionTemplate(transactions);
        var controlled=tx.execute(s->coupons.grantFromPoints(tenant,"M1","S1","source-1","B",1));
        assertEquals(controlled,tx.execute(s->coupons.grantFromPoints(tenant,"M1","S1","source-1","B",1)));
        assertThrows(DomainException.class,()->tx.execute(s->coupons.grantFromPoints(tenant,"M1","S1","source-2","B",1)));
        assertEquals(2,coupons.wallet(customer(),"",50).size());assertEquals(2,count("benefit_coupon"));assertEquals(2,count("employee_command_identity"));
        assertEquals(2,jdbc.queryForObject("SELECT SUM(issued) FROM benefit_coupon_definition WHERE tenant_id=?",Integer.class,tenant));
    }
    /** 真实租户门店/商家必须存在并有效；范围改变、局部范围、HTTP坏身份和停机全部拒绝。 */
    @Test void actualOwnerScopeAndHttpBoundariesRemainClosed() throws Exception {
        var input=definition("A",1,"PUBLIC");var wrong=new CouponApi.Definition("A",1,"FOREIGN",input.name(),"0.00","5.00",input.validFrom(),input.validTo(),1,true,null,"PUBLIC",null);
        String other="other-"+id();jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,'MERCHANT','foreign','ACTIVE',0)",other);
        jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,'FOREIGN','MERCHANT','foreign','ACTIVE',0)",other);
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->coupons.create(actor(COUPON_DEFINITION_CREATE),id(),wrong)).code());
        jdbc.update("UPDATE merchant_record SET status='FROZEN' WHERE tenant_id=?",tenant);
        assertThrows(DomainException.class,()->coupons.create(actor(COUPON_DEFINITION_CREATE),id(),input));jdbc.update("UPDATE merchant_record SET status='ACTIVE' WHERE tenant_id=?",tenant);
        create("A",1,"PUBLIC");afterScope.set(()->epoch.incrementAndGet());forbidden(()->coupons.definitions(actor(COUPON_DEFINITION_READ),"S1","",10));
        partial.set(true);forbidden(()->coupons.definitions(actor(COUPON_DEFINITION_READ),"S1","",10));partial.set(false);
        assertEquals(200,http("GET","/v1/admin/coupon-definitions?storeId=S1","valid",true,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/coupon-definitions?storeId=S1","invalid",true,null).statusCode());
        allowed.remove(COUPON_DEFINITION_READ.code());assertEquals(200,http("POST","/v1/admin/coupon-definitions","valid",true,definition("B",1,"SOURCE_ONLY")).statusCode());
        assertEquals(403,http("GET","/v1/admin/coupon-definitions?storeId=S1","valid",true,null).statusCode());allowed.add(COUPON_DEFINITION_READ.code());
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/coupon-definitions?storeId=S1","valid",true,null).statusCode());assertEquals(503,http("POST","/v1/admin/coupon-definitions","valid",true,definition("C",1,"PUBLIC")).statusCode());
        assertEquals(2,count("benefit_coupon_definition"));assertEquals(2,count("employee_command_identity"));
    }
    /** 原业务金额精度和相对有效期约束不能因中央许可被放宽。 */
    @Test void invalidValuesCannotCreateDefinitionsOrAudits() {
        var d=definition("A",1,"PUBLIC");
        for(String money:List.of("0.001","-1.00","1000000000000.00")) {
            var bad=new CouponApi.Definition("A",1,"S1",d.name(),"0.00",money,d.validFrom(),d.validTo(),1,true,null,"PUBLIC",null);
            assertThrows(DomainException.class,()->coupons.create(actor(COUPON_DEFINITION_CREATE),id(),bad));
        }
        var bad=new CouponApi.Definition("A",1,"S1",d.name(),"0.00","5.00",d.validFrom(),d.validTo(),1,false,10001,"PUBLIC",367);
        assertThrows(DomainException.class,()->coupons.create(actor(COUPON_DEFINITION_CREATE),id(),bad));
        assertEquals(0,count("benefit_coupon_definition"));assertEquals(0,count("employee_command_identity"));
    }
    /** 提示只接受当前中央创建资格，读权限和旧ADMIN不能补齐它。 */
    @Test void createHintIsIndependentAndFailsClosed() throws Exception {
        String path="/v1/operations/coupon-definitions/create-access";
        allowed.remove(COUPON_DEFINITION_READ.code());
        var response=http("GET",path,"valid",true,null);assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"allowed\":true"));
        assertEquals(403,http("GET",path,adminToken,false,null).statusCode());assertEquals(401,http("GET",path,"invalid",true,null).statusCode());
        allowed.remove(COUPON_DEFINITION_CREATE.code());assertEquals(403,http("GET",path,"valid",true,null).statusCode());allowed.add(COUPON_DEFINITION_CREATE.code());
        partial.set(true);assertEquals(403,http("GET",path,"valid",true,null).statusCode());partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());assertEquals(403,http("GET",path,"valid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET",path,"valid",true,null).statusCode());
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
