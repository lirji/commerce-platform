package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.access.EmployeeAuthority;
import com.lrj.commerce.member.cycle.api.MemberCycleApi;
import com.lrj.commerce.benefit.memberbenefit.api.MemberBenefitApi;
import com.lrj.commerce.benefit.memberbenefit.application.MemberBenefitService;
import com.lrj.commerce.benefit.entitlement.api.EntitlementApi;
import com.lrj.commerce.runtime.api.event.EventHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class CentralCycleMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @MockitoSpyBean EmployeeAuthority authority;
    @Autowired CentralEmployeeService service;
    @Autowired MemberCycleApi cycles;
    @Autowired MemberBenefitApi benefits;
    @Autowired MemberBenefitService handler;
    @Autowired EntitlementApi entitlements;
    @Autowired PlatformTransactionManager transactions;
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
        tenant="cycle-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:List.of(MEMBER_CYCLE_POLICY_READ,MEMBER_CYCLE_POLICY_PUBLISH,MEMBER_CYCLE_READ,MEMBER_CYCLE_EVALUATE,CYCLE_BENEFIT_READ,CYCLE_BENEFIT_DEFINE,CYCLE_BENEFIT_GRANT))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        for(String family:List.of("MEMBER_CYCLE","CYCLE_BENEFIT")) {
            jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,?,'SHADOW')",tenant,authTenant,family);
            jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family=?",tenant,family);
        }
        jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,'MERCHANT','merchant','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,'S1','MERCHANT','store','ACTIVE',0)",tenant);
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
    /** 发布只需政策写能力，历史游标与参数约束不变，真实审计指向不可变版本。 */
    @Test void policyPublishHasIndependentScopeAndRealVersionAudit() {
        var oldRead=actor(MEMBER_CYCLE_POLICY_READ);
        allowed.remove(MEMBER_CYCLE_POLICY_READ.code());allowed.remove(MEMBER_CYCLE_READ.code());
        String key=id();var input=policy(1);var result=cycles.publish(actor(MEMBER_CYCLE_POLICY_PUBLISH),key,input);
        assertEquals(result,cycles.publish(actor(MEMBER_CYCLE_POLICY_PUBLISH),key,input));
        forbidden(()->cycles.policies(oldRead,0,10));
        assertEquals(1,count("employee_command_identity","resource_type='commerce_member_policy' AND resource_id='cycle-policy-1'"));
        allowed.add(MEMBER_CYCLE_POLICY_READ.code());
        assertEquals(List.of(result),cycles.policies(actor(MEMBER_CYCLE_POLICY_READ),0,1));
        assertTrue(cycles.policies(actor(MEMBER_CYCLE_POLICY_READ),1,1).isEmpty());
        assertEquals(DomainException.Code.INVALID_INPUT,assertThrows(DomainException.class,()->cycles.publish(actor(MEMBER_CYCLE_POLICY_PUBLISH),id(),new MemberCycleApi.Policy(2,input.effectiveFrom(),0,input.levels()))).code());
        assertEquals(DomainException.Code.INVALID_INPUT,assertThrows(DomainException.class,()->cycles.publish(actor(MEMBER_CYCLE_POLICY_PUBLISH),id(),new MemberCycleApi.Policy(2,input.effectiveFrom(),1,List.of(new MemberCycleApi.Level("GOLD",1))))).code());
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->cycles.policyForOperation(tenant,1));
    }

    /** 考核独立于周期读取，Owner为真实会员，客户本人读取不受员工接管替代。 */
    @Test void evaluateWithoutReadKeepsActualOwnerAndCustomerCycle() {
        var oldRead=actor(MEMBER_CYCLE_READ);var oldGrant=actor(CYCLE_BENEFIT_GRANT);
        cycles.publish(actor(MEMBER_CYCLE_POLICY_PUBLISH),id(),policy(1));
        allowed.remove(MEMBER_CYCLE_READ.code());allowed.remove(CYCLE_BENEFIT_GRANT.code());
        String key=id();var view=cycles.evaluate(actor(MEMBER_CYCLE_EVALUATE),key,"M1");assertTrue(view.enabled());
        assertEquals(view,cycles.evaluate(actor(MEMBER_CYCLE_EVALUATE),key,"M1"));
        assertEquals(view,cycles.current(new Actor(tenant,"customer",Actor.Role.MEMBER)));
        forbidden(()->cycles.read(oldRead,"M1"));
        forbidden(()->benefits.grant(oldGrant,id(),"M1"));
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->cycles.evaluate(actor(MEMBER_CYCLE_EVALUATE),id(),"FOREIGN")).code());
        assertEquals(1,count("employee_command_identity","capability='commerce.member_cycle.evaluate' AND resource_id='M1'"));
        afterResource.set(()->jdbc.update("UPDATE member_record SET version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->cycles.evaluate(actor(MEMBER_CYCLE_EVALUATE),id(),"M1")).code());
    }

    /** 礼包定义和补发不能要求附赠周期读权限；权益真实受理且来源去重。 */
    @Test void benefitDefineAndGrantDoNotBorrowCyclePermissions() {
        var oldPolicyRead=actor(MEMBER_CYCLE_POLICY_READ);
        var policy=policy(1);cycles.publish(actor(MEMBER_CYCLE_POLICY_PUBLISH),id(),policy);definition(policy.effectiveFrom());
        for(var cap:List.of(MEMBER_CYCLE_POLICY_READ,MEMBER_CYCLE_READ,MEMBER_CYCLE_EVALUATE,CYCLE_BENEFIT_READ))allowed.remove(cap.code());
        var bundle=bundle("B1","BASIC",policy.effectiveFrom());String defineKey=id();
        assertEquals(bundle,benefits.publish(actor(CYCLE_BENEFIT_DEFINE),defineKey,bundle));
        assertEquals(bundle,benefits.publish(actor(CYCLE_BENEFIT_DEFINE),defineKey,bundle));
        String key=id();var receipt=benefits.grant(actor(CYCLE_BENEFIT_GRANT),key,"M1");
        assertEquals(1,receipt.grants().size());assertEquals(EntitlementApi.State.REQUESTED,receipt.grants().getFirst().status());
        assertEquals(receipt,benefits.grant(actor(CYCLE_BENEFIT_GRANT),key,"M1"));
        assertEquals(receipt.grants().getFirst().grantId(),benefits.grant(actor(CYCLE_BENEFIT_GRANT),id(),"M1").grants().getFirst().grantId());
        assertEquals(1,count("benefit_grant","source_type='LEVEL'"));
        assertEquals(1,count("employee_command_identity","resource_type='commerce_cycle_benefit' AND resource_id='B1'"));
        allowed.add(CYCLE_BENEFIT_READ.code());assertEquals(List.of(bundle),benefits.list(actor(CYCLE_BENEFIT_READ),1));
        forbidden(()->cycles.policy(oldPolicyRead,1));
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->benefits.grant(actor(CYCLE_BENEFIT_GRANT),key,"M1")).code());
    }

    /** 身份审计失败必须连同策略、礼包、考核和权益配额一起回滚。 */
    @Test void auditFailureRollsBackAllFourCommandsAndOutbox() {
        var policy=policy(1);cycles.publish(actor(MEMBER_CYCLE_POLICY_PUBLISH),id(),policy);definition(policy.effectiveFrom());
        benefits.publish(actor(CYCLE_BENEFIT_DEFINE),id(),bundle("B1","BASIC",policy.effectiveFrom()));
        int beforeEvents=count("platform_event","1=1");
        EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doThrow(new IllegalStateException("injected identity audit failure")).when(target).audit(any(Actor.class),any(EmployeeAccess.ScopePermit.class),anyString(),anyString(),anyString());
        String p=id(),b=id(),e=id(),g=id();
        assertThrows(IllegalStateException.class,()->cycles.publish(actor(MEMBER_CYCLE_POLICY_PUBLISH),p,policy(2)));
        assertThrows(IllegalStateException.class,()->benefits.publish(actor(CYCLE_BENEFIT_DEFINE),b,bundle("B2","GOLD",policy.effectiveFrom())));
        assertThrows(IllegalStateException.class,()->cycles.evaluate(actor(MEMBER_CYCLE_EVALUATE),e,"M1"));
        assertThrows(IllegalStateException.class,()->benefits.grant(actor(CYCLE_BENEFIT_GRANT),g,"M1"));
        for(String key:List.of(p,b,e,g)) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
        }
        assertEquals(1,cycles.policies(actor(MEMBER_CYCLE_POLICY_READ),0,50).size());
        assertEquals(1,benefits.list(actor(CYCLE_BENEFIT_READ),1).size());
        assertFalse(cycles.current(new Actor(tenant,"customer",Actor.Role.MEMBER)).enabled());
        assertEquals(0,count("benefit_grant","1=1"));assertEquals(beforeEvents,count("platform_event","1=1"));
        assertEquals(0,jdbc.queryForObject("SELECT issued+reserved FROM benefit_definition WHERE tenant_id=? AND benefit_id='tea'",Integer.class,tenant));
    }

    /** 已承诺系统事件在员工撤权/停止后仍履约，迟到信号不复活旧周期。 */
    @Test void trustedCycleEventSurvivesEmployeeRevocationAndDeduplicates() {
        var policy=policy(1);cycles.publish(actor(MEMBER_CYCLE_POLICY_PUBLISH),id(),policy);definition(policy.effectiveFrom());
        benefits.publish(actor(CYCLE_BENEFIT_DEFINE),id(),bundle("B1","BASIC",policy.effectiveFrom()));
        var view=cycles.evaluate(actor(MEMBER_CYCLE_EVALUATE),id(),"M1");
        var event=event(new MemberCycleApi.Assessed("M1",1,view.cycleStart(),view.cycleEnd(),view.memberLevel(),0,0));
        allowed.clear();jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=?",tenant);
        var tx=new TransactionTemplate(transactions);tx.executeWithoutResult(status->handler.handle(event));
        tx.executeWithoutResult(status->handler.handle(event));assertEquals(1,count("benefit_grant","source_type='LEVEL'"));
        var late=event(new MemberCycleApi.Assessed("M1",999,view.cycleStart(),view.cycleEnd(),view.memberLevel(),0,0));
        tx.executeWithoutResult(status->handler.handle(late));assertEquals(1,count("benefit_grant","1=1"));
        assertEquals(0,count("employee_command_identity","capability='commerce.cycle_benefit.grant'"));
        jdbc.update("UPDATE member_record SET status='FROZEN',version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant);
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->tx.executeWithoutResult(status->handler.handle(event))).code());
    }

    /** 读取后范围/Owner变化拒绝，两个独立族不得旁路，错误令牌和真实业务依赖均失败关闭。 */
    @Test void scopeOwnerRevocationAndHttpBoundariesStayIndependent() throws Exception {
        cycles.publish(actor(MEMBER_CYCLE_POLICY_PUBLISH),id(),policy(1));
        assertEquals(200,http("GET","/v1/admin/member-cycles/policies?after=0&limit=1","valid",true,null).statusCode());
        assertEquals(200,http("GET","/v1/admin/member-cycle-benefits?policyVersion=1","valid",true,null).statusCode());
        assertEquals(403,http("GET","/v1/admin/member-cycles/M1",adminToken,false,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/member-cycles/M1","invalid",true,null).statusCode());
        partial.set(true);forbidden(()->cycles.policies(actor(MEMBER_CYCLE_POLICY_READ),0,10));forbidden(()->benefits.list(actor(CYCLE_BENEFIT_READ),1));partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->cycles.policies(actor(MEMBER_CYCLE_POLICY_READ),0,10));
        afterResource.set(()->jdbc.update("UPDATE member_record SET version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->cycles.read(actor(MEMBER_CYCLE_READ),"M1")).code());
        String key=id();var evaluate=actor(MEMBER_CYCLE_EVALUATE);cycles.evaluate(evaluate,key,"M1");allowed.remove(MEMBER_CYCLE_EVALUATE.code());
        forbidden(()->cycles.evaluate(evaluate,key,"M1"));assertTrue(cycles.read(actor(MEMBER_CYCLE_READ),"M1").enabled());
        jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='MEMBER_CYCLE'",tenant);
        forbidden(()->cycles.read(admin,"M1"));assertEquals(403,http("GET","/v1/admin/member-cycles/M1","valid",true,null).statusCode());
        assertEquals(200,http("GET","/v1/admin/member-cycle-benefits?policyVersion=1","valid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/member-cycle-benefits?policyVersion=1","valid",true,null).statusCode());
    }

    /** 四提示相互独立；提示不是可复用许可，不能接受旧ADMIN或不完整租户范围。 */
    @Test void actionHintsStayIndependentAndFailClosed() throws Exception {
        var caps=List.of(MEMBER_CYCLE_POLICY_PUBLISH,MEMBER_CYCLE_EVALUATE,CYCLE_BENEFIT_DEFINE,CYCLE_BENEFIT_GRANT);
        var paths=List.of("member-cycles/publish-access","member-cycles/evaluate-access","member-cycle-benefits/define-access","member-cycle-benefits/grant-access");
        for(var read:List.of(MEMBER_CYCLE_POLICY_READ,MEMBER_CYCLE_READ,CYCLE_BENEFIT_READ))allowed.remove(read.code());
        for(int n=0;n<caps.size();n++) {
            String path="/v1/operations/"+paths.get(n);var result=http("GET",path,"valid",true,null);
            assertEquals(200,result.statusCode());assertTrue(result.body().contains("\"allowed\":true"));
            assertEquals(403,http("GET",path,adminToken,false,null).statusCode());assertEquals(401,http("GET",path,"invalid",true,null).statusCode());
            allowed.remove(caps.get(n).code());assertEquals(403,http("GET",path,"valid",true,null).statusCode());allowed.add(caps.get(n).code());
        }
        partial.set(true);assertEquals(403,http("GET","/v1/operations/member-cycle-benefits/define-access","valid",true,null).statusCode());partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());assertEquals(403,http("GET","/v1/operations/member-cycles/publish-access","valid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET","/v1/operations/member-cycles/evaluate-access","valid",true,null).statusCode());
    }

    private MemberCycleApi.Policy policy(long version) {return new MemberCycleApi.Policy(version,Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),7,List.of(new MemberCycleApi.Level("BASIC",0),new MemberCycleApi.Level("GOLD",100)));}
    private void definition(Instant from) {entitlements.create(admin,id(),new EntitlementApi.Definition("tea",1,"S1","周期茶",2,10,from.minusSeconds(10),from.plusSeconds(864000),7));}
    private MemberBenefitApi.Bundle bundle(String id,String level,Instant from) {return new MemberBenefitApi.Bundle(id,1,level,"S1",from.plusSeconds(604800),List.of(new EntitlementApi.Ref("tea",1)));}
    private EventHandler.Event event(MemberCycleApi.Assessed payload){return new EventHandler.Event(id(),tenant,"member.cycle.assessed.v1","M1",1,JsonCodec.write(payload),Instant.now(),0,0);}
    /** 仅测试可信表名/谓词，产品SQL仍由Owner Mapper负责。 */
    private int count(String table,String predicate){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND "+predicate,Integer.class,tenant);}
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
