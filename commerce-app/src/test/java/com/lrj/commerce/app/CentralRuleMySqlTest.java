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
import com.lrj.commerce.campaign.rule.api.RuleNode;
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

/** 真实MySQL验证规则实际版本、发布状态与命令/身份审计原子性；中央协议桩不替代跨进程验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralRuleMySqlTest {
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
        tenant="rules-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:List.of(RULE_READ,RULE_CREATE,RULE_PUBLISH))allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        for(String family:List.of("RULE")) {
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
            assertEquals(authTenant,facts.tenantId());assertEquals("marketing_rule",facts.resourceType());assertNull(facts.storeId());assertNull(facts.departmentId());
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
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant); }
    private RuleNode tree() { return new RuleNode("COMPARE","memberLevel","EQ","TEXT","BASIC",null); }
    private MarketingAssets.Rule input(String id,long version) { return new MarketingAssets.Rule(id,version,"中央规则测试",tree()); }
    private MarketingAssets.RuleView create(String id,long version) { return assets.createRule(actor(RULE_CREATE),id(),input(id,version)); }
    /** 创建不含读取；最新资产分页、字段目录及旧ADMIN均遵循独立边界。 */
    @Test void createAndReadAreIndependentWithLatestVersionCursor() throws Exception {
        var reader=actor(RULE_READ);allowed.remove(RULE_READ.code());var value=input("A",1);String key=id();
        var first=assets.createRule(actor(RULE_CREATE),key,value);assertEquals(first,assets.createRule(actor(RULE_CREATE),key,value));
        forbidden(()->assets.rules(reader,"",10));forbidden(()->assets.ruleFields(reader));
        allowed.add(RULE_READ.code());var a2=create("A",2);var b=create("B",1);
        assertEquals(List.of(a2),assets.rules(actor(RULE_READ),"",1));assertEquals(List.of(b),assets.rules(actor(RULE_READ),"A",1));assertTrue(assets.rules(actor(RULE_READ),"B",1).isEmpty());
        assertEquals(MarketingAssets.TRUSTED_FIELDS,assets.ruleFields(actor(RULE_READ)));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->assets.createRule(actor(RULE_CREATE),id(),value));
        assertEquals(409,http("POST","/v1/admin/rules","valid",true,value).statusCode());
        for(String path:List.of("/v1/admin/rules","/v1/admin/rule-fields"))assertEquals(403,http("GET",path,adminToken,false,null).statusCode());
        assertEquals(3,count("marketing_rule_asset"));assertEquals(3,count("employee_command_identity"));
    }
    /** 真实旧版本仍可发布；发布不含读取或创建，已发布新命令按原业务语义成功。 */
    @Test void publishBindsActualVersionAndRetainsOriginalIdempotency() {
        create("A",1);create("A",2);allowed.remove(RULE_READ.code());allowed.remove(RULE_CREATE.code());String key=id();
        var first=assets.publishRule(actor(RULE_PUBLISH),key,"A",1);assertEquals("PUBLISHED",first.status());assertEquals(1,first.content().version());
        assertEquals(first,assets.publishRule(actor(RULE_PUBLISH),key,"A",1));assertEquals(first,assets.publishRule(actor(RULE_PUBLISH),id(),"A",1));
        verify(client,atLeastOnce()).checkExecution(anyString(),any(),argThat(f->f.resourceId().equals("A")&&f.resourceVersion()==1&&f.resourceType().equals("marketing_rule")));
        assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM marketing_rule_asset WHERE tenant_id=? AND rule_id='A' AND version=2",String.class,tenant));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND capability='commerce.rule.publish' AND resource_id='A' AND resource_type='marketing_rule' AND store_id IS NULL",Integer.class,tenant));
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->assets.publishRule(actor(RULE_PUBLISH),key,"A",2)).code());
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->assets.publishRule(actor(RULE_PUBLISH),key,"A",1)).code());
    }
    /** 审计失败回滚创建/发布状态和原命令，不能只回滚审计表。 */
    @Test void auditFailureRollsBackBothWritesAndReceipts() {
        create("A",1);String createKey=id(),publishKey=id();EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doThrow(new IllegalStateException("injected audit failure")).when(target).audit(any(Actor.class),any(EmployeeAccess.ScopePermit.class),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->assets.createRule(actor(RULE_CREATE),createKey,input("B",1)));
        assertThrows(IllegalStateException.class,()->assets.publishRule(actor(RULE_PUBLISH),publishKey,"A",1));
        assertEquals(1,count("marketing_rule_asset"));assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM marketing_rule_asset WHERE tenant_id=?",String.class,tenant));
        for(String key:List.of(createKey,publishKey)) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key));
        }
    }
    /** 实际资产Owner、范围变化和编码后的64字标识在真实HTTP边界核验。 */
    @Test void ownerScopeAndEncodedVersionRoutesFailClosed() throws Exception {
        String encoded="rule:"+"a".repeat(59);create(encoded,1);
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->assets.publishRule(actor(RULE_PUBLISH),id(),encoded,2)).code());
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->assets.publishRule(actor(RULE_PUBLISH),id(),"foreign",1)).code());
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->assets.rules(actor(RULE_READ),"",10));
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->assets.ruleFields(actor(RULE_READ)));
        partial.set(true);forbidden(()->assets.publishRule(actor(RULE_PUBLISH),id(),encoded,1));partial.set(false);
        assertEquals(200,http("POST","/v1/admin/rules/"+encoded.replace(":","%3A")+"/1/publish","valid",true,Map.of()).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND capability='commerce.rule.publish' AND resource_id=?",Integer.class,tenant,encoded));
        for(String path:List.of("/v1/admin/rules","/v1/admin/rule-fields"))assertEquals(401,http("GET",path,"invalid",true,null).statusCode());
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/rule-fields","valid",true,null).statusCode());
    }
    /** 撤权和路由停止检查先于旧回执，也在事实读取与提交之间再次校验。 */
    @Test void revokeAndStopPrecedeOriginalReceipts() {
        String createKey=id(),publishKey=id();var value=input("A",1);var creator=actor(RULE_CREATE);assets.createRule(creator,createKey,value);
        allowed.remove(RULE_CREATE.code());forbidden(()->assets.createRule(creator,createKey,value));allowed.add(RULE_CREATE.code());
        var publisher=actor(RULE_PUBLISH);assets.publishRule(publisher,publishKey,"A",1);allowed.remove(RULE_PUBLISH.code());forbidden(()->assets.publishRule(publisher,publishKey,"A",1));allowed.add(RULE_PUBLISH.code());
        afterResource.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='RULE'",tenant));
        forbidden(()->assets.publishRule(publisher,publishKey,"A",1));forbidden(()->assets.rules(admin,"",10));assertEquals(2,count("employee_command_identity"));
    }
    /** 可信交易只能引用已发布固定版本；员工撤权或停止不会撤销已发布内容。 */
    @Test void trustedPublishedReferenceSurvivesEmployeeStop() {
        create("A",1);create("A",2);assertThrows(DomainException.class,()->assets.publishedRule(tenant,new MarketingAssets.Ref("A",1)));
        assets.publishRule(actor(RULE_PUBLISH),id(),"A",1);allowed.clear();jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=?",tenant);
        assertEquals(tree(),assets.publishedRule(tenant,new MarketingAssets.Ref("A",1)));
        assertThrows(DomainException.class,()->assets.publishedRule(tenant,new MarketingAssets.Ref("A",2)));
        assertThrows(DomainException.class,()->assets.publishedRule("foreign",new MarketingAssets.Ref("A",1)));
        assertEquals(3,count("employee_command_identity"));
    }
    /** 许可不能代替原可信字段、重复条件、版本和节点资源上限校验。 */
    @Test void originalTreeValidationRemainsAuthoritative() {
        var creator=actor(RULE_CREATE);
        for(var bad:List.of(new RuleNode("COMPARE","injected","EQ","TEXT","x",null),new RuleNode("COMPARE","memberTags","EQ","TEXT","tag",null),new RuleNode("ALL",null,null,null,null,List.of(tree(),tree()))))
            assertThrows(DomainException.class,()->assets.createRule(creator,id(),new MarketingAssets.Rule("invalid",1,"bad",bad)));
        RuleNode deep=tree();for(int i=0;i<8;i++)deep=new RuleNode("NOT",null,null,null,null,List.of(deep));var tooDeep=deep;
        assertEquals(DomainException.Code.LIMIT_EXCEEDED,assertThrows(DomainException.class,()->assets.createRule(creator,id(),new MarketingAssets.Rule("deep",1,"deep",tooDeep))).code());
        assertThrows(DomainException.class,()->assets.createRule(creator,id(),input("A",0)));
        assertEquals(0,count("marketing_rule_asset"));assertEquals(0,count("employee_command_identity"));
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
