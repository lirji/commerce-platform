package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.event.Outbox;
import com.lrj.commerce.member.growth.api.MemberGrowthApi;
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
class CentralGrowthMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @MockitoSpyBean Outbox outbox;
    @Autowired CentralEmployeeService service;
    @Autowired MemberGrowthApi growth;
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
        tenant="growth-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:List.of(GROWTH_READ,GROWTH_ADJUST,GROWTH_RECALCULATE,GROWTH_POLICY_READ,GROWTH_POLICY_PUBLISH))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'MEMBER_GROWTH','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='MEMBER_GROWTH'",tenant);
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
    private MemberGrowthApi.Policy policy() {
        return new MemberGrowthApi.Policy(1,Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),"1.00",List.of(new MemberGrowthApi.Level("BASIC",0),new MemberGrowthApi.Level("SILVER",100)));
    }
    @Test void policyPublicationDoesNotImplyReadingAndCustomerIdentityRemainsLocal() throws Exception {
        var reader=actor(GROWTH_POLICY_READ);allowed.remove(GROWTH_POLICY_READ.code());String key=id();var p=policy();
        assertEquals(p,growth.publish(actor(GROWTH_POLICY_PUBLISH),key,p));
        assertEquals(p,growth.publish(actor(GROWTH_POLICY_PUBLISH),key,p));
        forbidden(()->growth.policies(reader,0,10));assertEquals(403,http("GET","/v1/admin/member-growth/policies","valid",true,null).statusCode());allowed.add(GROWTH_POLICY_READ.code());
        assertEquals(1,growth.policies(actor(GROWTH_POLICY_READ),0,10).size());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='commerce_member_policy' AND resource_id='growth-policy-1' AND store_id IS NULL",Integer.class,tenant));
        forbidden(()->growth.policies(admin,0,10));forbidden(()->growth.wallet(admin,"M1"));
        assertEquals(0,growth.current(new Actor(tenant,"customer",Actor.Role.MEMBER)).growth());
        assertEquals(0,growth.wallet(new Actor(tenant,"customer",Actor.Role.MEMBER),"M1").growth());
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->growth.wallet(actor(GROWTH_READ),"FOREIGN")).code());
        assertEquals(200,http("GET","/v1/admin/member-growth/policies","valid",true,null).statusCode());
        assertEquals(403,http("GET","/v1/admin/member-growth/policies",adminToken,false,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/member-growth/M1","invalid",true,null).statusCode());
        partial.set(true);forbidden(()->growth.wallet(actor(GROWTH_READ),"M1"));partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->growth.policies(actor(GROWTH_POLICY_READ),0,10));
    }
    @Test void independentAdjustmentAndRecalculationUseStableIdentityAndExactlyOnceAudit() throws Exception {
        growth.publish(actor(GROWTH_POLICY_PUBLISH),id(),policy());allowed.remove(GROWTH_READ.code());
        String key=id();var input=new MemberGrowthApi.Adjustment(0,150,"manual proof");
        var changed=growth.adjust(actor(GROWTH_ADJUST),key,"M1",input);assertEquals(150,changed.growth());assertEquals("SILVER",changed.memberLevel());assertEquals(1,changed.version());
        assertEquals(changed,growth.adjust(actor(GROWTH_ADJUST),key,"M1",input));
        assertEquals(403,http("GET","/v1/admin/member-growth/M1","valid",true,null).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM member_growth_ledger WHERE tenant_id=? AND member_id='M1'",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND capability='commerce.growth.adjust'",Integer.class,tenant));
        var recalculator=actor(GROWTH_RECALCULATE);allowed.remove(GROWTH_RECALCULATE.code());forbidden(()->growth.recalculate(recalculator,id(),"M1"));allowed.add(GROWTH_RECALCULATE.code());
        String recalc=id();var recalculated=growth.recalculate(actor(GROWTH_RECALCULATE),recalc,"M1");assertEquals(2,recalculated.version());assertEquals(recalculated,growth.recalculate(actor(GROWTH_RECALCULATE),recalc,"M1"));
        var customer=new Actor(tenant,"customer",Actor.Role.MEMBER);assertEquals(150,growth.current(customer).growth());assertEquals(1,growth.ledger(customer,"M1",0,10).size());
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->growth.adjust(actor(GROWTH_ADJUST),key,"M1",input)).code());
    }
    @Test void ownerRaceAndOutboxFailureLeaveNoGrowthOrAuditSideEffects() {
        growth.publish(actor(GROWTH_POLICY_PUBLISH),id(),policy());
        afterResource.set(()->jdbc.update("UPDATE member_record SET version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->growth.adjust(actor(GROWTH_ADJUST),id(),"M1",new MemberGrowthApi.Adjustment(0,150,"race"))).code());
        Outbox target=AopTestUtils.getUltimateTargetObject(outbox);
        doThrow(new IllegalStateException("injected level event failure")).when(target).append(eq(tenant),eq("member.level.changed.v1"),eq("M1"),anyLong(),any());
        String key=id();assertThrows(IllegalStateException.class,()->growth.adjust(actor(GROWTH_ADJUST),key,"M1",new MemberGrowthApi.Adjustment(0,150,"rollback")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM member_growth_account WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM member_growth_ledger WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
        assertEquals("BASIC",jdbc.queryForObject("SELECT member_level FROM member_record WHERE tenant_id=? AND member_id='M1'",String.class,tenant));
    }
    @Test void revocationAndStoppedRouteDenyOldReceiptsWhileOutageIs503() throws Exception {
        String key=id();var input=new MemberGrowthApi.Adjustment(0,20,"proof");var write=actor(GROWTH_ADJUST);
        growth.adjust(write,key,"M1",input);allowed.remove(GROWTH_ADJUST.code());forbidden(()->growth.adjust(write,key,"M1",input));
        assertEquals(20,growth.wallet(actor(GROWTH_READ),"M1").growth());
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/member-growth/M1","valid",true,null).statusCode());unavailable.set(false);
        afterResource.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='MEMBER_GROWTH'",tenant));
        forbidden(()->growth.recalculate(actor(GROWTH_RECALCULATE),id(),"M1"));forbidden(()->growth.wallet(admin,"M1"));
        assertEquals(20,growth.current(new Actor(tenant,"customer",Actor.Role.MEMBER)).growth());
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
