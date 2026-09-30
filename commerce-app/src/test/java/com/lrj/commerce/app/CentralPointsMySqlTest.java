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
import com.lrj.commerce.member.growth.api.MemberGrowthApi;
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

/** 真实MySQL验证会员版本锁、命令/身份审计原子性；中央协议桩不替代跨进程验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralPointsMySqlTest {
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
        tenant="points-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:List.of(POINTS_READ,POINTS_ADJUST,POINTS_EXPIRE,POINTS_POLICY_READ,POINTS_POLICY_PUBLISH))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'MEMBER_POINTS','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='MEMBER_POINTS'",tenant);
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
            assertEquals(authTenant,facts.tenantId());assertEquals("commerce_member",facts.resourceType());assertNull(facts.storeId());assertNull(facts.departmentId());
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
    private MemberPointsApi.Policy policy(long version) {
        return new MemberPointsApi.Policy(version,Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),"1.00",30,true,100,5000);
    }
    private void publish() { points.publish(actor(POINTS_POLICY_PUBLISH),id(),policy(1)); }
    private Actor customer() { return new Actor(tenant,"customer",Actor.Role.MEMBER); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant); }
    /** 发布只追加实际策略，读取和人工校准不能互相附赠。 */
    @Test void policyPublicationIsIndependentBoundedAndAppendOnly() throws Exception {
        var reader=actor(POINTS_POLICY_READ);allowed.remove(POINTS_POLICY_READ.code());String key=id();var p=policy(1);
        assertEquals(p,points.publish(actor(POINTS_POLICY_PUBLISH),key,p));assertEquals(p,points.publish(actor(POINTS_POLICY_PUBLISH),key,p));
        forbidden(()->points.policies(reader,0,10));assertEquals(403,http("GET","/v1/admin/member-points/policies","valid",true,null).statusCode());
        allowed.add(POINTS_POLICY_READ.code());var later=policy(2);points.publish(actor(POINTS_POLICY_PUBLISH),id(),later);
        assertEquals(List.of(p),points.policies(actor(POINTS_POLICY_READ),0,1));assertEquals(List.of(later),points.policies(actor(POINTS_POLICY_READ),1,1));assertTrue(points.policies(actor(POINTS_POLICY_READ),2,1).isEmpty());
        var bad=new MemberPointsApi.Policy(3,p.effectiveFrom(),"1.001",30,true,100,5000);
        assertEquals(DomainException.Code.INVALID_INPUT,assertThrows(DomainException.class,()->points.publish(actor(POINTS_POLICY_PUBLISH),id(),bad)).code());
        assertEquals(DomainException.Code.INVALID_INPUT,assertThrows(DomainException.class,()->points.policies(actor(POINTS_POLICY_READ),-1,10)).code());
        assertEquals(2,count("member_point_policy"));assertEquals(0,count("member_point_account"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='commerce_member_policy' AND resource_id='points-policy-1' AND store_id IS NULL",Integer.class,tenant));
        forbidden(()->points.policies(admin,0,10));
    }
    /** 调整独立于读取；账户版本、来源唯一性和身份代际共同保护原键重试。 */
    @Test void independentAdjustmentPreservesWalletDebtAndIdentity() throws Exception {
        publish();var reader=actor(POINTS_READ);allowed.remove(POINTS_READ.code());String key=id();var input=new MemberPointsApi.Adjustment(0,150,"manual correction");
        var first=points.adjust(actor(POINTS_ADJUST),key,"M1",input);assertEquals(150,first.available());assertEquals(1,first.version());assertEquals(first,points.adjust(actor(POINTS_ADJUST),key,"M1",input));
        forbidden(()->points.wallet(reader,"M1"));assertEquals(403,http("GET","/v1/admin/member-points/M1","valid",true,null).statusCode());
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->points.adjust(actor(POINTS_ADJUST),id(),"M1",input)).code());
        var deducted=points.adjust(actor(POINTS_ADJUST),id(),"M1",new MemberPointsApi.Adjustment(1,-200,"reconcile shortage"));assertEquals(0,deducted.available());assertEquals(50,deducted.debt());
        assertEquals(2,points.ledger(customer(),"M1",0,10).size());assertEquals(deducted,points.current(customer()));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND capability='commerce.points.adjust'",Integer.class,tenant));
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->points.adjust(actor(POINTS_ADJUST),key,"M1",input)).code());
        forbidden(()->points.wallet(new Actor(tenant,"unbound",Actor.Role.OPERATOR),"M1"));
        jdbc.update("UPDATE member_record SET status='FROZEN',version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant);
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->points.adjust(actor(POINTS_ADJUST),id(),"M1",new MemberPointsApi.Adjustment(2,1,"frozen"))).code());
    }
    /** 钱包只读按时间过滤；到期命令严格最多100批次且无需读取权限。 */
    @Test void expiryIsIndependentBoundedAndDoesNotHideReadSideEffects() {
        publish();allowed.remove(POINTS_READ.code());
        for(int n=0;n<101;n++) jdbc.update("INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,held,expired,expires_at) VALUES(?,?,'M1',1,10,10,0,0,?)",tenant,"expired-"+n,java.sql.Timestamp.from(Instant.now().minusSeconds(10)));
        assertEquals(0,points.current(customer()).available());assertEquals(0,count("member_point_account"));assertEquals(0,count("member_point_ledger"));
        String key=id();var first=points.expire(actor(POINTS_EXPIRE),key,"M1");assertEquals(100,count("member_point_ledger"));assertEquals(first,points.expire(actor(POINTS_EXPIRE),key,"M1"));assertEquals(100,count("member_point_ledger"));
        points.expire(actor(POINTS_EXPIRE),id(),"M1");assertEquals(101,count("member_point_ledger"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND capability='commerce.points.expire'",Integer.class,tenant));
        var ledger=points.ledger(customer(),"M1",0,1);assertEquals(1,ledger.size());assertEquals(1,points.ledger(customer(),"M1",ledger.getFirst().sequenceId(),1).size());
    }
    /** 三个写操作审计失败时策略/账户/批次/账本/回执都不能残留。 */
    @Test void auditFailureRollsBackAllThreeCommandsAndBalances() {
        publish();var initial=points.adjust(actor(POINTS_ADJUST),id(),"M1",new MemberPointsApi.Adjustment(0,20,"fixture"));
        jdbc.update("UPDATE member_point_lot SET expires_at=? WHERE tenant_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),tenant);
        var before=points.current(customer());EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doThrow(new IllegalStateException("injected identity audit failure")).when(target).audit(any(Actor.class),any(EmployeeAccess.ScopePermit.class),anyString(),anyString(),anyString());
        String p=id(),a=id(),e=id();
        assertThrows(IllegalStateException.class,()->points.publish(actor(POINTS_POLICY_PUBLISH),p,policy(2)));
        assertThrows(IllegalStateException.class,()->points.adjust(actor(POINTS_ADJUST),a,"M1",new MemberPointsApi.Adjustment(initial.version(),10,"rollback")));
        assertThrows(IllegalStateException.class,()->points.expire(actor(POINTS_EXPIRE),e,"M1"));
        assertEquals(before,points.current(customer()));assertEquals(1,count("member_point_policy"));assertEquals(1,count("member_point_lot"));assertEquals(1,count("member_point_ledger"));
        assertEquals(20,jdbc.queryForObject("SELECT remaining FROM member_point_lot WHERE tenant_id=?",Long.class,tenant));
        for(String key:List.of(p,a,e)) {assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));}
    }
    /** 后台权威订单事实与客户兑换仍在原事务内，员工停止不取消既有结算职责。 */
    @Test void trustedFactsAndCustomerExchangeSurviveEmployeeStop() {
        publish();allowed.clear();jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=?",tenant);
        var tx=new TransactionTemplate(transactions);var fact=new MemberGrowthApi.OrderFact("order-1","M1","20.00",Instant.now(),true,null,null);
        tx.executeWithoutResult(s->points.observe(tenant,fact));tx.executeWithoutResult(s->points.observe(tenant,fact));assertEquals(20,points.current(customer()).available());
        tx.executeWithoutResult(s->points.exchange(customer(),"exchange-1",5));tx.executeWithoutResult(s->points.exchange(customer(),"exchange-1",5));assertEquals(15,points.current(customer()).available());
        assertEquals(1,count("member_point_exchange"));assertEquals(2,count("member_point_ledger"));assertEquals(1,count("employee_command_identity"));
        forbidden(()->points.wallet(admin,"M1"));
    }
    /** Owner竞争、读取期间撤权、HTTP身份与STOPPED都不能通过旧回执旁路。 */
    @Test void ownerScopeRevocationAndHttpBoundariesRemainClosed() throws Exception {
        publish();assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->points.wallet(actor(POINTS_READ),"FOREIGN")).code());
        afterResource.set(()->jdbc.update("UPDATE member_record SET version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->points.adjust(actor(POINTS_ADJUST),id(),"M1",new MemberPointsApi.Adjustment(0,10,"race"))).code());assertEquals(0,count("member_point_account"));
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->points.wallet(actor(POINTS_READ),"M1"));
        partial.set(true);forbidden(()->points.wallet(actor(POINTS_READ),"M1"));partial.set(false);
        assertEquals(200,http("GET","/v1/admin/member-points/M1","valid",true,null).statusCode());assertEquals(401,http("GET","/v1/admin/member-points/M1","invalid",true,null).statusCode());assertEquals(403,http("GET","/v1/admin/member-points/M1",adminToken,false,null).statusCode());
        String key=id();var input=new MemberPointsApi.Adjustment(0,10,"proof");var write=actor(POINTS_ADJUST);points.adjust(write,key,"M1",input);allowed.remove(POINTS_ADJUST.code());forbidden(()->points.adjust(write,key,"M1",input));
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/member-points/M1","valid",true,null).statusCode());unavailable.set(false);
        var expire=actor(POINTS_EXPIRE);String expiry=id();points.expire(expire,expiry,"M1");afterResource.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=?",tenant));forbidden(()->points.expire(expire,expiry,"M1"));
        assertEquals(10,points.current(customer()).available());
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
