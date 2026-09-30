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
import com.lrj.commerce.benefit.pointoffer.api.PointOfferApi;
import com.lrj.commerce.benefit.coupon.api.CouponApi;
import com.lrj.commerce.member.points.api.MemberPointsApi;
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

/** 真实MySQL验证积分商品版本锁、命令/身份审计原子性；中央协议桩不替代跨进程验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralPointOfferMySqlTest {
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
    @Autowired MemberPointsApi points;
    @Autowired PointOfferApi offers;
    @Autowired CouponApi coupons;
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
        tenant="offers-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:List.of(POINT_OFFER_READ,POINT_OFFER_DEFINE,POINT_OFFER_STATUS_UPDATE))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'POINT_OFFER','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='POINT_OFFER'",tenant);
        jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,'MERCHANT','merchant','ACTIVE',0)",tenant);
        for (String store : List.of("S1","S2")) jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,?,'MERCHANT','store','ACTIVE',0)",tenant,store);
        coupons.create(admin,id(),new CouponApi.Definition("coupon",1,"S1","points coupon","0.00","5.00",Instant.now().minusSeconds(60),Instant.now().plusSeconds(7200),1,true,10000,"SOURCE_ONLY"));
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
            assertEquals(authTenant,facts.tenantId());assertEquals("point_offer",facts.resourceType());assertNull(facts.storeId());assertNull(facts.departmentId());
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
    private PointOfferApi.Offer offer(String name) {
        return new PointOfferApi.Offer(name,"S1","兑换测试",PointOfferApi.Kind.COUPON,"coupon",1,100,10,2,
                Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),Instant.now().plusSeconds(1800).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }
    private PointOfferApi.View create(String name) { return offers.create(actor(POINT_OFFER_DEFINE),id(),offer(name)); }
    /** 定义不附赠读取，真实资产验证不被中央许可取代，管理与客户目录保持不同可见性。 */
    @Test void definitionIsIndependentAndDirectoryHasStableTenantAndStoreCursor() throws Exception {
        var reader=actor(POINT_OFFER_READ);allowed.remove(POINT_OFFER_READ.code());String key=id();var input=offer("A");
        var first=offers.create(actor(POINT_OFFER_DEFINE),key,input);assertEquals(first,offers.create(actor(POINT_OFFER_DEFINE),key,input));
        forbidden(()->offers.list(reader,"S1","",50));allowed.add(POINT_OFFER_READ.code());create("B");
        assertEquals(List.of(first),offers.list(actor(POINT_OFFER_READ),"S1","",1));assertEquals("B",offers.list(actor(POINT_OFFER_READ),"S1","A",1).getFirst().content().offerId());
        assertTrue(offers.list(actor(POINT_OFFER_READ),"S1","B",1).isEmpty());assertTrue(offers.list(actor(POINT_OFFER_READ),"S2","",50).isEmpty());
        assertEquals(2,count("benefit_point_offer"));assertEquals(2,count("employee_command_identity"));assertEquals(0,count("member_point_ledger"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='point_offer' AND resource_id='A' AND store_id IS NULL",Integer.class,tenant));
        var bad=new PointOfferApi.Offer("C","S2",input.name(),input.kind(),input.assetId(),1,100,10,2,input.validFrom(),input.validTo());
        assertThrows(DomainException.class,()->offers.create(actor(POINT_OFFER_DEFINE),id(),bad));assertEquals(2,count("benefit_point_offer"));
        forbidden(()->offers.list(admin,"S1","",10));
        assertEquals(403,http("GET","/v1/point-offers?storeId=S1",adminToken,false,null).statusCode());
        assertEquals(403,http("GET","/v1/admin/point-offers?storeId=S1",adminToken,false,null).statusCode());
    }
    /** 状态只影响新兑换，版本冲突及稳定身份代际保护旧命令回放。 */
    @Test void statusIsIndependentAndPreservesExpectedVersionAndOriginalKey() {
        create("A");var reader=actor(POINT_OFFER_READ);allowed.remove(POINT_OFFER_READ.code());String key=id();var input=new PointOfferApi.Status(0,false,"暂停兑换");
        var first=offers.status(actor(POINT_OFFER_STATUS_UPDATE),key,"A",input);assertEquals("INACTIVE",first.status());assertEquals(1,first.version());
        assertEquals(first,offers.status(actor(POINT_OFFER_STATUS_UPDATE),key,"A",input));forbidden(()->offers.list(reader,"S1","",10));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->offers.status(actor(POINT_OFFER_STATUS_UPDATE),id(),"A",input)).code());
        assertTrue(offers.list(customer(),"S1","",10).isEmpty());assertEquals(2,count("employee_command_identity"));
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->offers.status(actor(POINT_OFFER_STATUS_UPDATE),key,"A",input)).code());
    }
    /** 两种员工写入的审计失败时，商品、状态和命令回执必须同事务回滚。 */
    @Test void auditFailureRollsBackDefinitionAndStatus() {
        create("A");EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doThrow(new IllegalStateException("injected identity audit failure")).when(target).audit(any(Actor.class),any(EmployeeAccess.ScopePermit.class),anyString(),anyString(),anyString());
        String define=id(),status=id();
        assertThrows(IllegalStateException.class,()->offers.create(actor(POINT_OFFER_DEFINE),define,offer("B")));
        assertThrows(IllegalStateException.class,()->offers.status(actor(POINT_OFFER_STATUS_UPDATE),status,"A",new PointOfferApi.Status(0,false,"rollback")));
        assertEquals(1,count("benefit_point_offer"));assertEquals("ACTIVE",offers.list(actor(POINT_OFFER_READ),"S1","",10).getFirst().status());
        assertEquals(0,offers.list(actor(POINT_OFFER_READ),"S1","",10).getFirst().version());
        for(String key:List.of(define,status)) {assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));}
    }
    /** 客户自助兑换不借员工身份，资产耗尽时扣分/额度/回执整笔回滚。 */
    @Test void customerRedemptionSurvivesEmployeeStopAndAssetFailureDoesNotCharge() {
        create("A");create("B");
        points.publish(admin,id(),new MemberPointsApi.Policy(1,Instant.now().minusSeconds(1),"0.00",30,true,100,10000));
        points.adjust(admin,id(),"M1",new MemberPointsApi.Adjustment(0,300,"customer fixture"));
        allowed.clear();jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=?",tenant);
        assertEquals(2,offers.list(customer(),"S1","",10).size());String key=id();var first=offers.redeem(customer(),key,"A");assertEquals(first,offers.redeem(customer(),key,"A"));
        assertEquals(200,points.current(customer()).available());assertEquals(1,offers.receipts(customer(),"",10).size());
        assertThrows(DomainException.class,()->offers.redeem(customer(),id(),"B"));assertEquals(200,points.current(customer()).available());
        assertEquals(1,count("benefit_point_redemption"));assertEquals(1,count("member_point_exchange"));assertEquals(2,count("member_point_ledger"));assertEquals(2,count("employee_command_identity"));
        assertEquals(0,jdbc.queryForObject("SELECT issued FROM benefit_point_offer WHERE tenant_id=? AND offer_id='B'",Integer.class,tenant));
        forbidden(()->offers.list(admin,"S1","",10));forbidden(()->offers.redeem(admin,id(),"A"));
    }
    /** Owner事实竞态、范围变化、HTTP身份及中央故障都必须拒绝。 */
    @Test void actualOfferOwnerScopeAndHttpBoundariesRemainClosed() throws Exception {
        create("A");jdbc.update("INSERT INTO benefit_point_offer(tenant_id,offer_id,store_id,content_json,status,valid_from,valid_to,quota) VALUES(?,'FOREIGN','S1',?,'ACTIVE',?,?,10)","other-"+id(),JsonCodec.write(offer("FOREIGN")),java.sql.Timestamp.from(Instant.now()),java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->offers.status(actor(POINT_OFFER_STATUS_UPDATE),id(),"FOREIGN",new PointOfferApi.Status(0,false,"foreign"))).code());
        afterResource.set(()->jdbc.update("UPDATE benefit_point_offer SET version=version+1 WHERE tenant_id=? AND offer_id='A'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->offers.status(actor(POINT_OFFER_STATUS_UPDATE),id(),"A",new PointOfferApi.Status(0,false,"race"))).code());
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->offers.list(actor(POINT_OFFER_READ),"S1","",10));
        partial.set(true);forbidden(()->offers.list(actor(POINT_OFFER_READ),"S1","",10));partial.set(false);
        assertEquals(200,http("GET","/v1/admin/point-offers?storeId=S1","valid",true,null).statusCode());assertEquals(401,http("GET","/v1/admin/point-offers?storeId=S1","invalid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/point-offers?storeId=S1","valid",true,null).statusCode());
    }
    /** 撤权和接管停止均早于原回执，不能用历史成功命令继续操作。 */
    @Test void revokedWritesAndStoppedRouteCannotReplayOldReceipts() {
        String defineKey=id(),statusKey=id();var input=offer("A");var define=actor(POINT_OFFER_DEFINE);offers.create(define,defineKey,input);
        allowed.remove(POINT_OFFER_DEFINE.code());forbidden(()->offers.create(define,defineKey,input));allowed.add(POINT_OFFER_DEFINE.code());
        var status=actor(POINT_OFFER_STATUS_UPDATE);var update=new PointOfferApi.Status(0,false,"pause");offers.status(status,statusKey,"A",update);
        allowed.remove(POINT_OFFER_STATUS_UPDATE.code());forbidden(()->offers.status(status,statusKey,"A",update));allowed.add(POINT_OFFER_STATUS_UPDATE.code());
        afterResource.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=?",tenant));forbidden(()->offers.status(status,statusKey,"A",update));
        forbidden(()->offers.create(admin,defineKey,input));assertEquals(2,count("employee_command_identity"));
    }
    @Test void actionHintsRemainIndependentAndNeverAcceptLegacyAdmin() throws Exception {
        var caps = List.of(POINT_OFFER_DEFINE, POINT_OFFER_STATUS_UPDATE);
        var names = List.of("define", "status");
        allowed.remove(POINT_OFFER_READ.code());
        for (int n=0; n<caps.size(); n++) {
            String path = "/v1/operations/point-offers/"+names.get(n)+"-access";
            var response = http("GET",path,"valid",true,null);
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"allowed\":true"));
            assertEquals(403,http("GET",path,adminToken,false,null).statusCode());
            assertEquals(401,http("GET",path,"invalid",true,null).statusCode());
            allowed.remove(caps.get(n).code());assertEquals(403,http("GET",path,"valid",true,null).statusCode());allowed.add(caps.get(n).code());
        }
        partial.set(true);assertEquals(403,http("GET","/v1/operations/point-offers/define-access","valid",true,null).statusCode());partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());assertEquals(403,http("GET","/v1/operations/point-offers/status-access","valid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET","/v1/operations/point-offers/status-access","valid",true,null).statusCode());
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
