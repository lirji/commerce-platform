package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.event.Outbox;
import com.lrj.commerce.member.profile.api.MemberApi;
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
class CentralMemberMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @MockitoSpyBean Outbox outbox;
    @Autowired CentralEmployeeService service;
    @Autowired MemberApi members;
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
        tenant="member-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:List.of(MEMBER_READ,MEMBER_CREATE,MEMBER_PROFILE_UPDATE,MEMBER_STATUS_UPDATE))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'MEMBER_PROFILE','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='MEMBER_PROFILE'",tenant);
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
    @Test void tenantReadHistoryAndLegacyAggregateAreBoundWithoutChangingCustomerIdentity() throws Exception {
        assertEquals(List.of("M1"),members.list(actor(MEMBER_READ),"",1).stream().map(MemberApi.View::memberId).toList());
        assertEquals(1,members.stats(actor(MEMBER_READ)).total());assertTrue(members.history(actor(MEMBER_READ),"M1",0,10).isEmpty());
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->members.history(actor(MEMBER_READ),"FOREIGN",0,10)).code());
        forbidden(()->members.list(admin,"",10));forbidden(()->members.stats(admin));
        assertEquals("M1",members.current(new Actor(tenant,"customer",Actor.Role.MEMBER)).memberId());
        assertEquals("M1",members.requireActive(actor(MEMBER_READ),"M1").memberId());
        assertEquals(403,http("GET","/v1/admin/members",adminToken,false,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/members","invalid",true,null).statusCode());
        partial.set(true);forbidden(()->members.list(actor(MEMBER_READ),"",10));partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->members.list(actor(MEMBER_READ),"",10));
    }
    @Test void independentCreateAndChangesAuditOnceAndPreserveTerminalState() throws Exception {
        allowed.remove(MEMBER_READ.code());
        assertEquals(200,http("POST","/v1/admin/members","valid",true,new MemberApi.Create("NEW","new-customer","New","BASIC")).statusCode());
        assertEquals(403,http("GET","/v1/admin/members","valid",true,null).statusCode());
        String key=id();var input=new MemberApi.Change(0,"updated","reason");
        assertEquals(1,members.change(actor(MEMBER_PROFILE_UPDATE),key,"M1","PROFILE",input).version());
        assertEquals(1,members.change(actor(MEMBER_PROFILE_UPDATE),key,"M1","PROFILE",input).version());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='commerce_member' AND resource_id='M1' AND capability='commerce.member.profile.update' AND store_id IS NULL",Integer.class,tenant));
        allowed.remove(MEMBER_STATUS_UPDATE.code());
        assertEquals(403,http("POST","/v1/admin/members/M1/status","valid",true,new MemberApi.Change(1,"CLOSED","close")).statusCode());
        allowed.add(MEMBER_STATUS_UPDATE.code());
        assertEquals("CLOSED",members.change(actor(MEMBER_STATUS_UPDATE),id(),"M1","STATUS",new MemberApi.Change(1,"CLOSED","close")).status());
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->members.change(actor(MEMBER_STATUS_UPDATE),id(),"M1","STATUS",new MemberApi.Change(2,"ACTIVE","reopen"))).code());
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->members.change(actor(MEMBER_PROFILE_UPDATE),key,"M1","PROFILE",input)).code());
    }
    @Test void ownerVersionRecheckAndOutboxFailureRollbackMemberCommandAndAudit() {
        afterResource.set(()->jdbc.update("UPDATE member_record SET version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->members.change(actor(MEMBER_PROFILE_UPDATE),id(),"M1","PROFILE",new MemberApi.Change(0,"race","race"))).code());
        assertEquals("old",jdbc.queryForObject("SELECT display_name FROM member_record WHERE tenant_id=? AND member_id='M1'",String.class,tenant));
        // 绕过事务代理设置故障；真正业务调用仍经过MANDATORY事务代理。
        Outbox target = AopTestUtils.getUltimateTargetObject(outbox);
        String key=id();doThrow(new IllegalStateException("injected outbox failure")).when(target).append(eq(tenant),eq("member.registered.v1"),eq("ROLLBACK"),anyLong(),any());
        assertThrows(IllegalStateException.class,()->members.create(actor(MEMBER_CREATE),key,new MemberApi.Create("ROLLBACK","rollback-customer","Rollback","BASIC")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM member_record WHERE tenant_id=? AND member_id='ROLLBACK'",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
    }
    @Test void revocationOutageAndStoppedRouteCannotReplayOrFallback() throws Exception {
        String key=id();var input=new MemberApi.Create("NEW","new-customer","New","BASIC");var write=actor(MEMBER_CREATE);
        members.create(write,key,input);allowed.remove(MEMBER_CREATE.code());forbidden(()->members.create(write,key,input));
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/members","valid",true,null).statusCode());unavailable.set(false);
        afterResource.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='MEMBER_PROFILE'",tenant));
        forbidden(()->members.change(actor(MEMBER_PROFILE_UPDATE),id(),"M1","PROFILE",new MemberApi.Change(0,"no","stopped")));
        forbidden(()->members.list(admin,"",10));
        assertEquals("old",jdbc.queryForObject("SELECT display_name FROM member_record WHERE tenant_id=? AND member_id='M1'",String.class,tenant));
    }
    @Test void actionHintsRemainIndependentAndNeverAcceptLegacyAdmin() throws Exception {
        var caps = List.of(MEMBER_CREATE, MEMBER_PROFILE_UPDATE, MEMBER_STATUS_UPDATE);
        var names = List.of("create", "profile", "status");
        allowed.remove(MEMBER_READ.code());
        for (int n=0; n<caps.size(); n++) {
            String path = "/v1/operations/members/"+names.get(n)+"-access";
            var response = http("GET",path,"valid",true,null);
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"allowed\":true"));
            assertEquals(403,http("GET",path,adminToken,false,null).statusCode());
            assertEquals(401,http("GET",path,"invalid",true,null).statusCode());
            allowed.remove(caps.get(n).code());assertEquals(403,http("GET",path,"valid",true,null).statusCode());allowed.add(caps.get(n).code());
        }
        partial.set(true);assertEquals(403,http("GET","/v1/operations/members/create-access","valid",true,null).statusCode());partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());assertEquals(403,http("GET","/v1/operations/members/profile-access","valid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET","/v1/operations/members/status-access","valid",true,null).statusCode());
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
