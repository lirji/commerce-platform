package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.access.EmployeeAuthority;
import com.lrj.commerce.member.tag.api.MemberTagApi;
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
class CentralTagMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @MockitoSpyBean EmployeeAuthority authority;
    @Autowired CentralEmployeeService service;
    @Autowired MemberTagApi tags;
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
        tenant="tag-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:List.of(MEMBER_TAG_READ,MEMBER_TAG_DEFINE,MEMBER_TAG_ASSIGN))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'MEMBER_TAG','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='MEMBER_TAG'",tenant);
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
    /** 字典定义的资格不能变成读取或会员修改，审计保留真实标签类型。 */
    @Test void definitionDoesNotImplyReadAndAuditDoesNotPretendTagIsMember() throws Exception {
        var reader=actor(MEMBER_TAG_READ);allowed.remove(MEMBER_TAG_READ.code());String key=id();
        var input=new MemberTagApi.Definition("T1","reviewed tag");
        assertEquals(input,tags.create(actor(MEMBER_TAG_DEFINE),key,input));
        assertEquals(input,tags.create(actor(MEMBER_TAG_DEFINE),key,input));
        forbidden(()->tags.definitions(reader,"",10));
        assertEquals(403,http("GET","/v1/admin/member-tags","valid",true,null).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='commerce_member_tag' AND resource_id='T1' AND store_id IS NULL",Integer.class,tenant));
        allowed.add(MEMBER_TAG_READ.code());assertEquals(List.of(input),tags.definitions(actor(MEMBER_TAG_READ),"",10));
        forbidden(()->tags.definitions(admin,"",10));
        forbidden(()->tags.definitions(new Actor(tenant,"customer",Actor.Role.MEMBER),"",10));
        assertEquals(403,http("GET","/v1/admin/member-tags",adminToken,false,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/member-tags","invalid",true,null).statusCode());
        partial.set(true);forbidden(()->tags.definitions(actor(MEMBER_TAG_READ),"",10));partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->tags.definitions(actor(MEMBER_TAG_READ),"",10));
    }

    /** 同会员的关联版本与主体代际分别约束；赋值和撤销使用同一独立能力。 */
    @Test void assignmentWithoutReadPreservesVersionsStatusAndIdempotency() throws Exception {
        tags.create(actor(MEMBER_TAG_DEFINE),id(),new MemberTagApi.Definition("T1","reviewed"));
        allowed.remove(MEMBER_TAG_READ.code());allowed.remove(MEMBER_TAG_DEFINE.code());
        String key=id();var input=new MemberTagApi.Assign("T1",true,0,"grant reason");
        var saved=tags.assign(actor(MEMBER_TAG_ASSIGN),key,"M1",input);assertTrue(saved.active());assertEquals(1,saved.version());
        assertEquals(saved,tags.assign(actor(MEMBER_TAG_ASSIGN),key,"M1",input));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND capability='commerce.member_tag.assign' AND resource_type='commerce_member' AND resource_id='M1'",Integer.class,tenant));
        assertEquals(403,http("GET","/v1/admin/member-tags/M1/assignments","valid",true,null).statusCode());
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->tags.assign(actor(MEMBER_TAG_ASSIGN),id(),"M1",input)).code());
        jdbc.update("UPDATE member_record SET status='FROZEN',version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant);
        var revoked=tags.assign(actor(MEMBER_TAG_ASSIGN),id(),"M1",new MemberTagApi.Assign("T1",false,1,"revoke reason"));assertFalse(revoked.active());assertEquals(2,revoked.version());
        allowed.add(MEMBER_TAG_READ.code());assertEquals(List.of(revoked),tags.assignments(actor(MEMBER_TAG_READ),"M1","",10));
        var restored=tags.assign(actor(MEMBER_TAG_ASSIGN),id(),"M1",new MemberTagApi.Assign("T1",true,2,"restore"));assertEquals(3,restored.version());
        jdbc.update("UPDATE member_record SET status='CLOSED',version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant);
        assertEquals(DomainException.Code.INVALID_INPUT,assertThrows(DomainException.class,()->tags.assign(actor(MEMBER_TAG_ASSIGN),id(),"M1",new MemberTagApi.Assign("T1",false,3,"closed"))).code());
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->tags.assign(actor(MEMBER_TAG_ASSIGN),key,"M1",input)).code());
    }

    /** 实际Owner竞争拒绝旧许可；审计失败回滚字典/关联/命令，使用真实MySQL事务。 */
    @Test void ownerRaceAndAuditFailureRollbackTagEffects() {
        tags.create(actor(MEMBER_TAG_DEFINE),id(),new MemberTagApi.Definition("T1","reviewed"));
        var input=new MemberTagApi.Assign("T1",true,0,"proof");
        afterResource.set(()->jdbc.update("UPDATE member_record SET version=version+1 WHERE tenant_id=? AND member_id='M1'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->tags.assign(actor(MEMBER_TAG_ASSIGN),id(),"M1",input)).code());
        EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doThrow(new IllegalStateException("injected identity audit failure")).when(target).audit(any(Actor.class),any(EmployeeAccess.ScopePermit.class),anyString(),anyString(),anyString());
        String defineKey=id(),assignKey=id();
        assertThrows(IllegalStateException.class,()->tags.create(actor(MEMBER_TAG_DEFINE),defineKey,new MemberTagApi.Definition("ROLLBACK","rollback")));
        assertThrows(IllegalStateException.class,()->tags.assign(actor(MEMBER_TAG_ASSIGN),assignKey,"M1",input));
        assertEquals(1,tags.definitions(actor(MEMBER_TAG_READ),"",10).size());
        assertTrue(tags.assignments(actor(MEMBER_TAG_READ),"M1","",10).isEmpty());
        for(String key:List.of(defineKey,assignKey)) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
        }
    }

    /** 会员行锁保护原64个活跃标签上限；撤销释放名额，历史关联仍保留。 */
    @Test void activeTagLimitPreservesInactiveHistoryAndReleasesCapacity() {
        for(int n=0;n<=64;n++) {
            String tag="T"+n;tags.create(actor(MEMBER_TAG_DEFINE),id(),new MemberTagApi.Definition(tag,tag));
            if(n<64)tags.assign(actor(MEMBER_TAG_ASSIGN),id(),"M1",new MemberTagApi.Assign(tag,true,0,"fill capacity"));
        }
        String rejected=id();
        assertEquals(DomainException.Code.INVALID_INPUT,assertThrows(DomainException.class,()->tags.assign(actor(MEMBER_TAG_ASSIGN),rejected,"M1",new MemberTagApi.Assign("T64",true,0,"full"))).code());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,rejected));
        tags.assign(actor(MEMBER_TAG_ASSIGN),id(),"M1",new MemberTagApi.Assign("T0",false,1,"release capacity"));
        tags.assign(actor(MEMBER_TAG_ASSIGN),id(),"M1",new MemberTagApi.Assign("T64",true,0,"reuse capacity"));
        var assignments=tags.assignments(actor(MEMBER_TAG_READ),"M1","",100);
        assertEquals(65,assignments.size());assertEquals(64,assignments.stream().filter(MemberTagApi.Assignment::active).count());
    }

    /** 撤权、失效依赖和STOPPED不能回退旧ADMIN，真实会员归属在关联查询前检查。 */
    @Test void revocationForeignOwnerAndStoppedRouteStayClosed() throws Exception {
        tags.create(actor(MEMBER_TAG_DEFINE),id(),new MemberTagApi.Definition("T1","reviewed"));
        String key=id();var input=new MemberTagApi.Assign("T1",true,0,"proof");var write=actor(MEMBER_TAG_ASSIGN);
        tags.assign(write,key,"M1",input);allowed.remove(MEMBER_TAG_ASSIGN.code());forbidden(()->tags.assign(write,key,"M1",input));
        assertEquals(1,tags.assignments(actor(MEMBER_TAG_READ),"M1","",10).size());
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->tags.assignments(actor(MEMBER_TAG_READ),"FOREIGN","",10)).code());
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/member-tags/M1/assignments","valid",true,null).statusCode());unavailable.set(false);
        jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='MEMBER_TAG'",tenant);
        forbidden(()->tags.definitions(admin,"",10));
        assertEquals(403,http("GET","/v1/admin/member-tags","valid",true,null).statusCode());
    }

    @Test void actionHintsRemainIndependentAndNeverAcceptLegacyAdmin() throws Exception {
        var caps = List.of(MEMBER_TAG_DEFINE, MEMBER_TAG_ASSIGN);
        var names = List.of("define", "assign");
        allowed.remove(MEMBER_TAG_READ.code());
        for (int n=0; n<caps.size(); n++) {
            String path = "/v1/operations/member-tags/"+names.get(n)+"-access";
            var response = http("GET",path,"valid",true,null);
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"allowed\":true"));
            assertEquals(403,http("GET",path,adminToken,false,null).statusCode());
            assertEquals(401,http("GET",path,"invalid",true,null).statusCode());
            allowed.remove(caps.get(n).code());assertEquals(403,http("GET",path,"valid",true,null).statusCode());allowed.add(caps.get(n).code());
        }
        partial.set(true);assertEquals(403,http("GET","/v1/operations/member-tags/define-access","valid",true,null).statusCode());partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());assertEquals(403,http("GET","/v1/operations/member-tags/assign-access","valid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET","/v1/operations/member-tags/assign-access","valid",true,null).statusCode());
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
