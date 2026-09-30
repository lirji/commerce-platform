package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.access.EmployeeAuthority;
import com.lrj.commerce.member.behavior.api.MemberBehaviorApi;
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
class CentralBehaviorMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @MockitoSpyBean EmployeeAuthority authority;
    @Autowired CentralEmployeeService service;
    @Autowired MemberBehaviorApi behavior;
    @Autowired com.lrj.commerce.app.application.member.MemberBehaviorRebuildService rebuild;
    @Autowired com.lrj.commerce.ordering.order.api.OrderApi orders;
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
        tenant="behavior-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:List.of(MEMBER_BEHAVIOR_READ,MEMBER_BEHAVIOR_UPDATE,MEMBER_BEHAVIOR_REBUILD))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'MEMBER_BEHAVIOR','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='MEMBER_BEHAVIOR'",tenant);
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
    /** 偏好修改不附赠读取；客户本人仍沿原身份，不受员工能力路由替代。 */
    @Test void updateWithoutReadPreservesCustomerProfileAndIndependentVersions() throws Exception {
        allowed.remove(MEMBER_BEHAVIOR_READ.code());allowed.remove(MEMBER_BEHAVIOR_REBUILD.code());
        String key=id();var input=new MemberBehaviorApi.ProfileChange(0,"02-29",false,"reviewed preference");
        var changed=behavior.profile(actor(MEMBER_BEHAVIOR_UPDATE),key,"M1",input);
        assertEquals(1,changed.version());assertFalse(changed.journeyEnabled());assertEquals("02-29",changed.birthday());
        assertEquals(changed,behavior.profile(actor(MEMBER_BEHAVIOR_UPDATE),key,"M1",input));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='commerce_member' AND resource_id='M1'",Integer.class,tenant));
        assertEquals(403,http("GET","/v1/admin/member-behavior/M1","valid",true,null).statusCode());
        assertEquals(403,http("POST","/v1/admin/member-behavior/rebuild","valid",true,new com.lrj.commerce.app.application.member.MemberBehaviorRebuildService.Rebuild("",1)).statusCode());
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->behavior.profile(actor(MEMBER_BEHAVIOR_UPDATE),id(),"M1",input)).code());
        var customer=new Actor(tenant,"customer",Actor.Role.MEMBER);
        assertEquals(changed,behavior.detail(customer,"M1").profile());
        var own=behavior.profile(customer,id(),"M1",new MemberBehaviorApi.ProfileChange(1,null,true,"own preference"));assertEquals(2,own.version());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=?",Integer.class,tenant));
        forbidden(()->behavior.detail(new Actor(tenant,"other",Actor.Role.MEMBER),"M1"));
        assertEquals(DomainException.Code.INVALID_INPUT,assertThrows(DomainException.class,()->behavior.profile(actor(MEMBER_BEHAVIOR_UPDATE),id(),"M1",new MemberBehaviorApi.ProfileChange(2,"02-30",true,"invalid birthday"))).code());
        jdbc.update("UPDATE member_record SET status='FROZEN',version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant);
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->behavior.profile(actor(MEMBER_BEHAVIOR_UPDATE),id(),"M1",new MemberBehaviorApi.ProfileChange(2,null,true,"frozen"))).code());
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->behavior.profile(actor(MEMBER_BEHAVIOR_UPDATE),key,"M1",input)).code());
    }

    /** 读取由真实Member Owner及查询后范围核验保护，缺失不冒充跨租户实体。 */
    @Test void actualOwnerAndReadRecheckProtectDetailAndEvents() throws Exception {
        assertEquals("M1",behavior.detail(actor(MEMBER_BEHAVIOR_READ),"M1").member().memberId());
        assertTrue(behavior.events(actor(MEMBER_BEHAVIOR_READ),"M1",0,10).isEmpty());
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->behavior.detail(actor(MEMBER_BEHAVIOR_READ),"FOREIGN")).code());
        forbidden(()->behavior.detail(admin,"M1"));
        assertEquals(403,http("GET","/v1/admin/member-behavior/M1",adminToken,false,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/member-behavior/M1","invalid",true,null).statusCode());
        partial.set(true);forbidden(()->behavior.detail(actor(MEMBER_BEHAVIOR_READ),"M1"));partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->behavior.events(actor(MEMBER_BEHAVIOR_READ),"M1",0,10));
        afterResource.set(()->jdbc.update("UPDATE member_record SET version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->behavior.detail(actor(MEMBER_BEHAVIOR_READ),"M1")).code());
        assertEquals(DomainException.Code.INVALID_INPUT,assertThrows(DomainException.class,()->behavior.events(actor(MEMBER_BEHAVIOR_READ),"M1",-1,10)).code());
    }

    /** 批次权限不返回订单资料，真实SQL有界分页；空批次与重试都有唯一命令归属。 */
    @Test void rebuildUsesMinimalTenantSourcesAndAuditsRealBatch() throws Exception {
        source(tenant,"A1",true);source(tenant,"A2",false);source("foreign-"+id(),"FOREIGN",false);
        allowed.remove(MEMBER_BEHAVIOR_READ.code());allowed.remove(MEMBER_BEHAVIOR_UPDATE.code());
        var actor=actor(MEMBER_BEHAVIOR_REBUILD);String key=id();
        var input=new com.lrj.commerce.app.application.member.MemberBehaviorRebuildService.Rebuild("",1);
        var first=rebuild.rebuild(actor,key,input);assertEquals("A1",first.next());assertEquals(1,first.scanned());assertFalse(first.done());
        assertEquals(first,rebuild.rebuild(actor(MEMBER_BEHAVIOR_REBUILD),key,input));
        assertEquals("12.00",jdbc.queryForObject("SELECT CAST(net_spend AS CHAR) FROM member_behavior_order WHERE tenant_id=? AND order_id='A1'",String.class,tenant));
        var second=rebuild.rebuild(actor(MEMBER_BEHAVIOR_REBUILD),id(),new com.lrj.commerce.app.application.member.MemberBehaviorRebuildService.Rebuild("A1",1));assertEquals("A2",second.next());
        var empty=rebuild.rebuild(actor(MEMBER_BEHAVIOR_REBUILD),id(),new com.lrj.commerce.app.application.member.MemberBehaviorRebuildService.Rebuild("A2",1));assertEquals("A2",empty.next());assertTrue(empty.done());assertEquals(0,empty.scanned());
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='commerce_member_behavior_batch' AND resource_id=command_key AND store_id IS NULL",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM member_behavior_order WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(403,http("GET","/v1/admin/member-behavior/M1","valid",true,null).statusCode());
        assertEquals(DomainException.Code.INVALID_INPUT,assertThrows(DomainException.class,()->rebuild.rebuild(actor(MEMBER_BEHAVIOR_REBUILD),id(),new com.lrj.commerce.app.application.member.MemberBehaviorRebuildService.Rebuild("",51))).code());
        assertEquals(DomainException.Code.INVALID_INPUT,assertThrows(DomainException.class,()->orders.behaviorSources(tenant,"",51)).code());
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->rebuild.rebuild(actor(MEMBER_BEHAVIOR_REBUILD),key,input)).code());
    }

    /** 审计注入失败后真实MySQL回滚偏好、投影及命令；Owner并发也必须拒绝。 */
    @Test void ownerRaceAndAuditFailureRollbackProfileAndRebuild() {
        var input=new MemberBehaviorApi.ProfileChange(0,null,false,"proof");
        afterResource.set(()->jdbc.update("UPDATE member_record SET version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->behavior.profile(actor(MEMBER_BEHAVIOR_UPDATE),id(),"M1",input)).code());
        source(tenant,"A1",true);
        EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doThrow(new IllegalStateException("injected identity audit failure")).when(target).audit(any(Actor.class),any(EmployeeAccess.ScopePermit.class),anyString(),anyString(),anyString());
        String profileKey=id(),batchKey=id();
        assertThrows(IllegalStateException.class,()->behavior.profile(actor(MEMBER_BEHAVIOR_UPDATE),profileKey,"M1",input));
        assertThrows(IllegalStateException.class,()->rebuild.rebuild(actor(MEMBER_BEHAVIOR_REBUILD),batchKey,new com.lrj.commerce.app.application.member.MemberBehaviorRebuildService.Rebuild("",10)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM member_behavior_profile WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM member_behavior_order WHERE tenant_id=?",Integer.class,tenant));
        for(String key:List.of(profileKey,batchKey)) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
        }
    }

    /** 撤权发生后旧回执也拒绝，独立读取仍可用；停用与中央故障不能回退旧ADMIN。 */
    @Test void revocationStopsOldCommandsAndOutageFailsClosed() throws Exception {
        String key=id(),batchKey=id();var input=new MemberBehaviorApi.ProfileChange(0,null,false,"reviewed");
        var update=actor(MEMBER_BEHAVIOR_UPDATE);var batchActor=actor(MEMBER_BEHAVIOR_REBUILD);
        behavior.profile(update,key,"M1",input);
        var batch=new com.lrj.commerce.app.application.member.MemberBehaviorRebuildService.Rebuild("",10);rebuild.rebuild(batchActor,batchKey,batch);
        allowed.remove(MEMBER_BEHAVIOR_UPDATE.code());allowed.remove(MEMBER_BEHAVIOR_REBUILD.code());
        forbidden(()->behavior.profile(update,key,"M1",input));forbidden(()->rebuild.rebuild(batchActor,batchKey,batch));
        assertEquals(1,behavior.detail(actor(MEMBER_BEHAVIOR_READ),"M1").profile().version());
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/member-behavior/M1","valid",true,null).statusCode());unavailable.set(false);
        jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='MEMBER_BEHAVIOR'",tenant);
        forbidden(()->behavior.detail(admin,"M1"));assertEquals(403,http("GET","/v1/admin/member-behavior/M1","valid",true,null).statusCode());
    }

    /** 测试数据写入真实数据库，只有含成长来源的订单才被会员Owner投影。 */
    private void source(String sourceTenant,String order,boolean growth) {
        jdbc.update("INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version) VALUES(?,?,'M1','S1','merchant',?,20,'COMPLETED','CHANNEL_REQUIRED',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'[]',X'00',1)",sourceTenant,order,id());
        if(growth)jdbc.update("INSERT INTO member_growth_order(tenant_id,order_id,member_id,paid,completed,policy_version,growth_rate,net_spend) VALUES(?,?,'M1',20,TRUE,0,0,12)",sourceTenant,order);
    }

    @Test void actionHintsRemainIndependentAndNeverAcceptLegacyAdmin() throws Exception {
        var caps = List.of(MEMBER_BEHAVIOR_UPDATE, MEMBER_BEHAVIOR_REBUILD);
        var names = List.of("update", "rebuild");
        allowed.remove(MEMBER_BEHAVIOR_READ.code());
        for (int n=0; n<caps.size(); n++) {
            String path = "/v1/operations/member-behavior/"+names.get(n)+"-access";
            var response = http("GET",path,"valid",true,null);
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"allowed\":true"));
            assertEquals(403,http("GET",path,adminToken,false,null).statusCode());
            assertEquals(401,http("GET",path,"invalid",true,null).statusCode());
            allowed.remove(caps.get(n).code());assertEquals(403,http("GET",path,"valid",true,null).statusCode());allowed.add(caps.get(n).code());
        }
        partial.set(true);assertEquals(403,http("GET","/v1/operations/member-behavior/update-access","valid",true,null).statusCode());partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());assertEquals(403,http("GET","/v1/operations/member-behavior/rebuild-access","valid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET","/v1/operations/member-behavior/rebuild-access","valid",true,null).statusCode());
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
