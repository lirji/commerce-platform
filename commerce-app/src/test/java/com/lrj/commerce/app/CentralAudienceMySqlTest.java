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
import com.lrj.commerce.campaign.asset.api.MarketingAssets;
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

/** 真实MySQL验证人群头/成员/回执/身份原子性及独立权限；中央协议桩不替代跨进程验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralAudienceMySqlTest {
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
    @Autowired MarketingAssets assets;
    @MockitoSpyBean com.lrj.commerce.campaign.asset.infrastructure.persistence.AssetMapper mapper;
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
        tenant="audiences-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:List.of(AUDIENCE_READ,AUDIENCE_CREATE))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        for(String family:List.of("AUDIENCE")) {
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
    }
    private void require(CentralAccessDtos.Check check) {
        if(unavailable.get())throw new CentralAccessException(503);
        if(!authTenant.equals(check.tenantId())||!allowed.contains(check.capability()))throw new AccessDeniedException("denied");
    }
    private GovernanceDtos.AccessContext context(){return new GovernanceDtos.AccessContext(principal,member,generation.get(),1,1,authTenant,"commerce","test","member-test","HUMAN",id());}
    private Actor actor(EmployeeAccess.Capability capability){return service.authenticate("valid",authTenant,capability);}
    private void forbidden(Runnable action){assertEquals(DomainException.Code.FORBIDDEN,assertThrows(DomainException.class,action::run).code());}
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant); }
    private MarketingAssets.Audience input(String resource, long version, List<String> members) {
        return new MarketingAssets.Audience(resource,version,"中央人群测试","isolated-import",Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),members);
    }
    private MarketingAssets.AudienceView create(String resource, long version, List<String> members) {
        return assets.createAudience(actor(AUDIENCE_CREATE),id(),input(resource,version,members));
    }
    /** 导入不附赠读取；分页只返回各最新版本摘要，成员与审计目标必须是真实导入数据。 */
    @Test void independentReadCreateAndLatestSummaryCursor() throws Exception {
        var reader=actor(AUDIENCE_READ);allowed.remove(AUDIENCE_READ.code());
        String key=id();var value=input("A",1,List.of("M1","imported-not-a-member"));
        var first=assets.createAudience(actor(AUDIENCE_CREATE),key,value);
        assertEquals(first,assets.createAudience(actor(AUDIENCE_CREATE),key,value));
        forbidden(()->assets.audiences(reader,"",10));
        assertEquals(1,count("marketing_audience_snapshot"));assertEquals(2,count("marketing_audience_member"));
        assertEquals(1,count("member_record"));
        var a2=create("A",2,List.of());var b=create("B",1,List.of("M1"));allowed.add(AUDIENCE_READ.code());
        assertEquals(List.of(a2),assets.audiences(actor(AUDIENCE_READ),"",1));
        assertEquals(List.of(b),assets.audiences(actor(AUDIENCE_READ),"A",1));
        assertTrue(assets.audiences(actor(AUDIENCE_READ),"B",1).isEmpty());
        assertEquals(0,a2.memberCount());assertEquals(3,count("employee_command_identity"));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND capability='commerce.audience.create' AND resource_type='audience' AND resource_id IN ('A','B') AND store_id IS NULL",Integer.class,tenant));
        var response=http("GET","/v1/admin/audiences","valid",true,null);
        assertEquals(200,response.statusCode());assertFalse(response.body().contains("memberIds"));assertFalse(response.body().contains("imported-not-a-member"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->assets.createAudience(actor(AUDIENCE_CREATE),id(),value));
        assertEquals(409,http("POST","/v1/admin/audiences","valid",true,value).statusCode());
        allowed.remove(AUDIENCE_CREATE.code());forbidden(()->assets.createAudience(actor(AUDIENCE_READ),id(),input("C",1,List.of())));
        for(String method:List.of("GET","POST"))assertEquals(403,http(method,"/v1/admin/audiences",adminToken,false,method.equals("POST")?value:null).statusCode());
        verify(client,never()).checkExecution(anyString(),any(),any());
    }
    /** 成员插入或审计失败均回滚已写快照头，不遗留成功回执和半个人群。 */
    @Test void membersAndIdentityAuditFailuresRollBackAllRows() {
        String memberKey=id(),auditKey=id();
        doThrow(new IllegalStateException("injected member insert failure")).when(mapper).members(anyString(),any());
        assertThrows(IllegalStateException.class,()->assets.createAudience(actor(AUDIENCE_CREATE),memberKey,input("member-failure",1,List.of("M1"))));
        reset(mapper);
        EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doThrow(new IllegalStateException("injected identity audit failure")).when(target).audit(any(Actor.class),any(EmployeeAccess.ScopePermit.class),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->assets.createAudience(actor(AUDIENCE_CREATE),auditKey,input("audit-failure",1,List.of("M1"))));
        for(String table:List.of("marketing_audience_snapshot","marketing_audience_member","platform_command","employee_command_identity"))assertEquals(0,count(table));
    }
    /** 原命令先核对权限和路由，再读取旧回执；主体代际变化不能借原键重放。 */
    @Test void originalKeyRequiresCurrentAuthorityAndStableIdentity() {
        String key=id();var value=input("A",1,List.of("M1"));var creator=actor(AUDIENCE_CREATE);
        var first=assets.createAudience(creator,key,value);
        assertEquals(first,assets.createAudience(actor(AUDIENCE_CREATE),key,value));
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->assets.createAudience(creator,key,input("B",1,List.of()))).code());
        allowed.remove(AUDIENCE_CREATE.code());forbidden(()->assets.createAudience(creator,key,value));allowed.add(AUDIENCE_CREATE.code());
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->assets.createAudience(actor(AUDIENCE_CREATE),key,value)).code());
        afterScope.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='AUDIENCE'",tenant));
        forbidden(()->assets.createAudience(actor(AUDIENCE_CREATE),id(),input("C",1,List.of())));
        forbidden(()->assets.createAudience(admin,key,value));forbidden(()->assets.audiences(admin,"",10));
        assertEquals(1,count("marketing_audience_snapshot"));assertEquals(1,count("employee_command_identity"));
    }
    /** 完整租户范围和返回前上下文复核是独立门禁；截止后的许可不能进入旧回执事务。 */
    @Test void partialScopeContextChangeAndExpiredPermitFailClosed() throws Exception {
        create("A",1,List.of("M1"));
        partial.set(true);forbidden(()->assets.audiences(actor(AUDIENCE_READ),"",10));
        forbidden(()->assets.createAudience(actor(AUDIENCE_CREATE),id(),input("B",1,List.of())));partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->assets.audiences(actor(AUDIENCE_READ),"",10));
        var valid=authority.scope(actor(AUDIENCE_CREATE),AUDIENCE_CREATE);
        var expired=new EmployeeAccess.ScopePermit(valid.capability(),valid.tenant(),valid.filter(),valid.route(),valid.identity(),valid.fingerprint(),Instant.now().minusSeconds(1));
        var tx=new TransactionTemplate(transactions);forbidden(()->tx.executeWithoutResult(status->authority.lock(expired)));
        assertEquals(401,http("GET","/v1/admin/audiences","invalid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/audiences","valid",true,null).statusCode());
        assertEquals(503,http("POST","/v1/admin/audiences","valid",true,input("B",1,List.of())).statusCode());
        assertEquals(1,count("marketing_audience_snapshot"));assertEquals(1,count("employee_command_identity"));
    }
    /** 员工撤权不撤销固定版本；受信任引用仍正确区分命中、未命中、过期和缺失。 */
    @Test void trustedFixedVersionsSurviveEmployeeStopWithoutLeakingOtherTenants() {
        var a=create("A",1,List.of("M1","M2"));var a2=create("A",2,List.of());
        var ref=new MarketingAssets.Ref("A",1);var ref2=new MarketingAssets.Ref("A",2);
        allowed.clear();jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='AUDIENCE'",tenant);
        assertEquals(List.of("M1"),assets.members(tenant,ref,"",1));assertEquals(List.of("M2"),assets.members(tenant,ref,"M1",1));assertTrue(assets.members(tenant,ref,"M2",1).isEmpty());
        assertEquals(List.of("HIT","MISS"),assets.sources(tenant,"M1",List.of(ref,ref,ref2),a.watermark().plusSeconds(1)).stream().map(MarketingAssets.Source::match).toList());
        assertEquals("UNKNOWN",assets.sources(tenant,"M1",List.of(ref),a.validUntil()).getFirst().match());
        assertEquals("UNKNOWN",assets.sources(tenant,"M1",List.of(ref),a.watermark().minusSeconds(1)).getFirst().match());
        assets.requireFresh(tenant,ref,a.watermark());assertThrows(DomainException.class,()->assets.requireFresh(tenant,ref,a.validUntil()));
        assertThrows(DomainException.class,()->assets.sources(tenant,"M1",List.of(new MarketingAssets.Ref("missing",1)),Instant.now()));
        assertThrows(DomainException.class,()->assets.sources(tenant,"M1",Collections.nCopies(101,ref),Instant.now()));
        assertThrows(DomainException.class,()->assets.members("foreign",ref,"",10));
        assertEquals(2,count("marketing_audience_snapshot"));assertEquals(2,count("employee_command_identity"));
    }
    /** 原时间语义、500上限、重复和保留前缀继续校验；过期和空快照不被擅自禁止。 */
    @Test void originalWindowIdentifierAndMemberBoundsRemainAuthoritative() {
        var creator=actor(AUDIENCE_CREATE);Instant now=Instant.now();
        var expired=new MarketingAssets.Audience("expired",1,"past","import",now.minusSeconds(7200),now.minusSeconds(3600),List.of());
        assertEquals(0,assets.createAudience(creator,id(),expired).memberCount());
        assertEquals("UNKNOWN",assets.sources(tenant,"M1",List.of(new MarketingAssets.Ref("expired",1)),now).getFirst().match());
        List<MarketingAssets.Audience> invalid=List.of(input("dyn-reserved",1,List.of()),input("A",0,List.of()),input("x".repeat(65),1,List.of()),input("duplicate",1,List.of("M1","M1")),input("null",1,null),input("too-many",1,java.util.stream.IntStream.range(0,501).mapToObj(i->"M"+i).toList()),input("bad-member",1,List.of("has space")),new MarketingAssets.Audience("future",1,"n","s",now.plusSeconds(60),now.plusSeconds(120),List.of()),new MarketingAssets.Audience("wide",1,"n","s",now.minusSeconds(1),now.plusSeconds(86400),List.of()),new MarketingAssets.Audience("same-time",1,"n","s",now,now,List.of()));
        for(var bad:invalid)assertThrows(DomainException.class,()->assets.createAudience(creator,id(),bad));
        var members=java.util.stream.IntStream.range(0,500).mapToObj(i->"M"+i).toList();
        assertEquals(500,assets.createAudience(creator,id(),input("audience:"+"a".repeat(55),1,members)).memberCount());
        assertEquals(500,count("marketing_audience_member"));assertEquals(2,count("marketing_audience_snapshot"));assertEquals(2,count("employee_command_identity"));
    }
    /** 未接管旧ADMIN仍保持原键摘要；独立人群接管不扩张到规则或会员能力族。 */
    @Test void legacyInputAndIndependentFamilyRemainCompatible() {
        // 用独立未接管租户验证旧模式；数据库禁止删除已接管路由，不能抹掉中央拒绝历史。
        tenant="audience-legacy-"+id();authTenant=id();principal=id();member=id();
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        var legacy=new Actor(tenant,"operator",Actor.Role.ADMIN);
        var value=input("legacy",1,List.of("M1"));String key=id();
        var first=assets.createAudience(legacy,key,value);assertEquals(first,assets.createAudience(legacy,key,value));
        assertEquals(List.of(first),assets.audiences(legacy,"",10));assertEquals(0,count("employee_command_identity"));
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'AUDIENCE','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='AUDIENCE'",tenant);
        forbidden(()->assets.createAudience(legacy,key,value));
        var employee=actor(AUDIENCE_CREATE);assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->assets.createAudience(employee,key,value)).code());
        assertEquals(1,count("marketing_audience_snapshot"));assertEquals(1,count("marketing_audience_member"));
        assertThrows(DomainException.class,()->assets.rules(employee,"",10));
    }
    /** 创建提示仅复核当前独立权限，不返回执行引用；读取、撤权与依赖失败分别处理。 */
    @Test void createHintIsIndependentAndFailsClosed() throws Exception {
        String path="/v1/operations/audiences/create-access";
        allowed.remove(AUDIENCE_READ.code());
        var response=http("GET",path,"valid",true,null);
        assertEquals(200,response.statusCode());assertEquals("{\"allowed\":true}",response.body());
        assertEquals(403,http("GET","/v1/admin/audiences","valid",true,null).statusCode());
        allowed.remove(AUDIENCE_CREATE.code());allowed.add(AUDIENCE_READ.code());
        assertEquals(403,http("GET",path,"valid",true,null).statusCode());
        assertEquals(200,http("GET","/v1/admin/audiences","valid",true,null).statusCode());
        assertEquals(401,http("GET",path,"invalid",true,null).statusCode());
        allowed.add(AUDIENCE_CREATE.code());partial.set(true);
        assertEquals(403,http("GET",path,"valid",true,null).statusCode());partial.set(false);
        afterScope.set(()->epoch.incrementAndGet());
        assertEquals(403,http("GET",path,"valid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET",path,"valid",true,null).statusCode());unavailable.set(false);
        jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='AUDIENCE'",tenant);
        assertEquals(403,http("GET",path,"valid",true,null).statusCode());
        assertEquals(0,count("marketing_audience_snapshot"));assertEquals(0,count("employee_command_identity"));
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
