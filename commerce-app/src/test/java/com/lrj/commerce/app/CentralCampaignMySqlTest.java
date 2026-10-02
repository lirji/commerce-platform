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

/** 真实MySQL验证活动独立能力、内容版本与状态锁与命令/身份审计原子性；中央协议桩不替代跨进程验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralCampaignMySqlTest {
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
    @Autowired com.lrj.commerce.campaign.management.api.CampaignApi campaigns;
    @Autowired com.lrj.commerce.campaign.funding.api.CampaignFundingApi funding;
    @Autowired com.lrj.commerce.runtime.access.persistence.EmployeeAuthorityMapper auditMapper;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private String tenant,authTenant,principal,member,grant,policy,directory,adminToken;
    private Actor admin;
    private final Set<String> allowed=ConcurrentHashMap.newKeySet();
    private final Map<String,String> references=new ConcurrentHashMap<>();
    private final AtomicLong generation=new AtomicLong(1),epoch=new AtomicLong(1);
    private final AtomicBoolean unavailable=new AtomicBoolean(),partial=new AtomicBoolean();
    private final AtomicReference<Runnable> afterScope=new AtomicReference<>(),afterResource=new AtomicReference<>();
    private static final List<EmployeeAccess.Capability> CAPABILITIES=List.of(CAMPAIGN_READ,CAMPAIGN_CREATE,CAMPAIGN_PREVIEW,CAMPAIGN_SUBMIT,CAMPAIGN_APPROVE,CAMPAIGN_REJECT,CAMPAIGN_PUBLISH,CAMPAIGN_PAUSE,BUDGET_READ);
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        tenant="campaign-central-"+id();authTenant=id();principal=id();member=id();grant=id();policy=id();directory=id();adminToken=id();
        allowed.clear();references.clear();generation.set(1);epoch.set(1);unavailable.set(false);partial.set(false);afterScope.set(null);afterResource.set(null);
        for(var cap:CAPABILITIES)allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','member-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','old','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'FOREIGN','customer','foreign','BASIC','ACTIVE',0)","other-"+id());
        for(String family:List.of("CAMPAIGN")) {
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
            assertEquals(authTenant,facts.tenantId());assertEquals("campaign",facts.resourceType());assertTrue(facts.resourceVersion()>0);assertNull(facts.storeId());assertNull(facts.departmentId());
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
    private int count(String table) {
        if(!Set.of("marketing_campaign","marketing_budget","marketing_budget_hold","employee_command_identity","platform_command","trade_quote","marketing_execution","benefit_grant","inventory_hold").contains(table))throw new IllegalArgumentException("未登记测试表");
        return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant); }
    private RuleNode tree() { return new RuleNode("COMPARE","memberLevel","EQ","TEXT","BASIC",null); }
    private com.lrj.commerce.campaign.management.api.CampaignApi.Draft input(String name,long version,boolean governed) {
        return new com.lrj.commerce.campaign.management.api.CampaignApi.Draft(name,version,"S1","中央活动测试",Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),"20.00","1.00",tree(),
            governed?new com.lrj.commerce.campaign.management.api.CampaignApi.Policy(null,new MarketingAssets.Ref("R1",1),new com.lrj.commerce.campaign.management.api.CampaignApi.Terms(0,2500,"20.00",null)):null);
    }
    private void rule() {
        assets.createRule(admin,id(),new MarketingAssets.Rule("R1",1,"固定规则",tree()));
        assets.publishRule(admin,id(),"R1",1);
    }
    private com.lrj.commerce.campaign.management.api.CampaignApi.View create(String name,long version,boolean governed) {
        return campaigns.create(actor(CAMPAIGN_CREATE),id(),input(name,version,governed));
    }
    private void stop() { jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='CAMPAIGN'",tenant); }
    private String status(String name,long version) { return jdbc.queryForObject("SELECT status FROM marketing_campaign WHERE tenant_id=? AND campaign_id=? AND version=?",String.class,tenant,name,version); }
    private int receipts(String key) { return jdbc.queryForObject("SELECT count(*) FROM platform_command WHERE tenant_id=? AND command_key=?",Integer.class,tenant,key); }
    /** 九个实际HTTP入口逐一只给自己的权限，其余八个入口均拒绝，不靠页面隐藏证明独立性。 */
    @Test void allNineCapabilitiesRemainIndependentAtHttpBoundary() throws Exception {
        rule();create("A",7,true);
        assertEquals(200,http("POST","/v1/admin/skus",adminToken,false,Map.of("skuId","SKU","storeId","S1","title","商品","unitPrice","25.00")).statusCode());
        for(var cap:CAPABILITIES) {
            allowed.clear();allowed.add(cap.code());
            for(var other:CAPABILITIES) {
                String path=other==BUDGET_READ?"/v1/admin/campaign-budgets":other==CAMPAIGN_READ||other==CAMPAIGN_CREATE?"/v1/admin/campaigns":"/v1/admin/campaigns/A/7/"+other.code().substring(other.code().lastIndexOf('.')+1);
                Object body=other==CAMPAIGN_CREATE?input("NEW-"+cap.name(),1,false):other==CAMPAIGN_PREVIEW?Map.of("memberId","M1","items",List.of(Map.of("skuId","SKU","quantity",1))):Map.of("expectedVersion",0);
                if(other==cap) {
                    String state=cap==CAMPAIGN_APPROVE||cap==CAMPAIGN_REJECT?"IN_REVIEW":cap==CAMPAIGN_PUBLISH?"APPROVED":cap==CAMPAIGN_PAUSE?"PUBLISHED":"DRAFT";
                    jdbc.update("UPDATE marketing_campaign SET status=?,lock_version=0 WHERE tenant_id=? AND campaign_id='A'",state,tenant);
                }
                boolean read=other==CAMPAIGN_READ||other==BUDGET_READ;
                assertEquals(other==cap?200:403,http(read?"GET":"POST",path,"valid",true,read?null:body).statusCode(),cap+" -> "+other);
            }
        }
        assertEquals(7,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='campaign' AND store_id IS NULL AND resource_version>0",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND capability IN ('commerce.campaign.read','commerce.campaign.preview','commerce.budget.read')",Integer.class,tenant));
    }
    /** 最新活动目录和所有版本预算使用各自稳定游标，创建结果与实际内容版本同事务落库。 */
    @Test void createReadAndBudgetCursorsDoNotImplyEachOther() {
        String key=id();var value=input("A",7,false);var first=campaigns.create(actor(CAMPAIGN_CREATE),key,value);
        assertEquals(first,campaigns.create(actor(CAMPAIGN_CREATE),key,value));
        create("A",8,false);create("B",1,false);
        allowed.clear();allowed.add(CAMPAIGN_READ.code());
        assertEquals(8,campaigns.list(actor(CAMPAIGN_READ),"",1).getFirst().content().version());
        assertEquals("B",campaigns.list(actor(CAMPAIGN_READ),"A",1).getFirst().content().campaignId());
        assertTrue(campaigns.list(actor(CAMPAIGN_READ),"B",1).isEmpty());
        assertThrows(AccessDeniedException.class,()->actor(BUDGET_READ));
        allowed.clear();allowed.add(BUDGET_READ.code());
        var one=funding.budgets(actor(BUDGET_READ),"",1);var rest=funding.budgets(actor(BUDGET_READ),one.getFirst().budgetId(),10);
        assertEquals(2,rest.size());assertTrue(funding.budgets(actor(BUDGET_READ),rest.getLast().budgetId(),10).isEmpty());
        assertEquals(Set.of(1L,7L,8L),java.util.stream.Stream.concat(one.stream(),rest.stream()).map(b->b.version()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3,count("marketing_campaign"));assertEquals(3,count("marketing_budget"));assertEquals(3,count("employee_command_identity"));
        assertEquals(7,jdbc.queryForObject("SELECT resource_version FROM employee_command_identity WHERE tenant_id=? AND command_key=?",Long.class,tenant,key));
    }
    /** 不可变v7与递增状态锁明确区分，成功原键可回放，错动作/错预期版本不能新增效果。 */
    @Test void approvalAndPublicationUseContentVersionAndOriginalReceipts() {
        rule();create("A",7,true);String submit=id(),approve=id(),publish=id(),pause=id();
        var submitted=campaigns.review(actor(CAMPAIGN_SUBMIT),submit,"A",7,0,"submit");assertEquals(1,submitted.lockVersion());
        assertEquals(submitted,campaigns.review(actor(CAMPAIGN_SUBMIT),submit,"A",7,0,"submit"));
        var approved=campaigns.review(actor(CAMPAIGN_APPROVE),approve,"A",7,1,"approve");assertEquals(2,approved.lockVersion());
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->campaigns.review(actor(CAMPAIGN_REJECT),id(),"A",7,1,"reject")).code());
        var published=campaigns.publish(actor(CAMPAIGN_PUBLISH),publish,"A",7,2);assertEquals(3,published.lockVersion());
        assertEquals(published,campaigns.publish(actor(CAMPAIGN_PUBLISH),publish,"A",7,2));
        var paused=campaigns.pause(actor(CAMPAIGN_PAUSE),pause,"A",7,3);assertEquals(4,paused.lockVersion());
        assertEquals(paused,campaigns.pause(actor(CAMPAIGN_PAUSE),pause,"A",7,3));
        assertEquals(published,campaigns.publish(actor(CAMPAIGN_PUBLISH),publish,"A",7,2));assertEquals("PAUSED",status("A",7));
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->campaigns.publish(actor(CAMPAIGN_PUBLISH),publish,"A",7,4)).code());
        verify(client,atLeastOnce()).checkExecution(anyString(),any(),argThat(f->f.resourceId().equals("A")&&f.resourceVersion()==7));
        assertEquals(5,count("employee_command_identity"));assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_id='A' AND resource_version=7",Integer.class,tenant));
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->campaigns.pause(actor(CAMPAIGN_PAUSE),pause,"A",7,3)).code());
    }
    /** 真正违反MySQL版本约束，验证创建预算和切换时自动暂停旧版本也整体回滚。 */
    @Test void databaseAuditFailureRollsBackCreationReviewAndVersionSwitch() {
        rule();create("A",7,true);create("A",8,true);
        campaigns.review(actor(CAMPAIGN_SUBMIT),id(),"A",7,0,"submit");campaigns.review(actor(CAMPAIGN_APPROVE),id(),"A",7,1,"approve");campaigns.publish(actor(CAMPAIGN_PUBLISH),id(),"A",7,2);
        campaigns.review(actor(CAMPAIGN_SUBMIT),id(),"A",8,0,"submit");campaigns.review(actor(CAMPAIGN_APPROVE),id(),"A",8,1,"approve");
        int audits=count("employee_command_identity");EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doAnswer(call->{auditMapper.auditVersioned(call.getArgument(0),call.getArgument(1),((EmployeeAccess.ScopePermit)call.getArgument(1)).capability().code(),call.getArgument(2),call.getArgument(3),call.getArgument(4),0);return null;})
            .when(target).auditVersion(any(Actor.class),any(EmployeeAccess.ScopePermit.class),anyString(),anyString(),anyString(),anyLong());
        String createKey=id(),switchKey=id();
        assertThrows(org.springframework.dao.DataAccessException.class,()->campaigns.create(actor(CAMPAIGN_CREATE),createKey,input("B",1,false)));
        assertThrows(org.springframework.dao.DataAccessException.class,()->campaigns.publish(actor(CAMPAIGN_PUBLISH),switchKey,"A",8,2));
        assertEquals("PUBLISHED",status("A",7));assertEquals("APPROVED",status("A",8));assertEquals(2,count("marketing_campaign"));assertEquals(2,count("marketing_budget"));
        assertEquals(0,receipts(createKey));assertEquals(0,receipts(switchKey));assertEquals(audits,count("employee_command_identity"));
        jdbc.update("UPDATE marketing_campaign SET status='IN_REVIEW',lock_version=1 WHERE tenant_id=? AND campaign_id='A' AND version=8",tenant);
        String reviewKey=id();
        assertThrows(org.springframework.dao.DataAccessException.class,()->campaigns.review(actor(CAMPAIGN_REJECT),reviewKey,"A",8,1,"reject"));
        assertEquals("IN_REVIEW",status("A",8));assertEquals(0,receipts(reviewKey));
    }
    /** 发布只允许一个版本；审批拒绝的活动不能发布，旧无policy版本仍保留原兼容。 */
    @Test void uniquePublishedVersionAndReviewStateMachineRemainAuthoritative() {
        rule();create("A",7,false);create("A",8,false);
        campaigns.publish(actor(CAMPAIGN_PUBLISH),id(),"A",7,0);campaigns.publish(actor(CAMPAIGN_PUBLISH),id(),"A",8,0);
        assertEquals("PAUSED",status("A",7));assertEquals("PUBLISHED",status("A",8));
        campaigns.publish(actor(CAMPAIGN_PUBLISH),id(),"A",7,2);assertEquals("PAUSED",status("A",8));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketing_campaign WHERE tenant_id=? AND campaign_id='A' AND status='PUBLISHED'",Integer.class,tenant));
        create("B",1,true);assertThrows(DomainException.class,()->campaigns.publish(actor(CAMPAIGN_PUBLISH),id(),"B",1,0));
        campaigns.review(actor(CAMPAIGN_SUBMIT),id(),"B",1,0,"submit");campaigns.review(actor(CAMPAIGN_REJECT),id(),"B",1,1,"reject");
        assertEquals("REJECTED",status("B",1));assertThrows(DomainException.class,()->campaigns.publish(actor(CAMPAIGN_PUBLISH),id(),"B",1,2));
    }
    /** 真实会员、SKU与规则决定预览结果；无需其他read能力，也不写报价、预算或身份审计。 */
    @Test void previewUsesActualOwnerFactsWithoutParticipationWrites() throws Exception {
        create("A",7,false);assertEquals(200,http("POST","/v1/admin/skus",adminToken,false,Map.of("skuId","SKU","storeId","S1","title","商品","unitPrice","25.00")).statusCode());
        allowed.clear();allowed.add(CAMPAIGN_PREVIEW.code());var reviewer=actor(CAMPAIGN_PREVIEW);
        var preview=new com.lrj.commerce.campaign.management.api.CampaignApi.Preview("M1",null,List.of(new com.lrj.commerce.campaign.management.api.CampaignApi.PreviewItem("SKU",1)),true);
        var result=campaigns.preview(reviewer,"A",7,preview);assertEquals("1.00",result.discount());assertEquals(7,result.selected().version());assertEquals(result,campaigns.preview(reviewer,"A",7,preview));
        for(String table:List.of("marketing_budget_hold","trade_quote","marketing_execution","benefit_grant","inventory_hold"))assertEquals(0,count(table),table);
        assertEquals(java.math.BigDecimal.ZERO.setScale(2),jdbc.queryForObject("SELECT held FROM marketing_budget WHERE tenant_id=?",java.math.BigDecimal.class,tenant));assertEquals(1,count("employee_command_identity"));
        var invalid=new com.lrj.commerce.campaign.management.api.CampaignApi.Preview("FOREIGN",null,preview.items(),false);
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->campaigns.preview(reviewer,"A",7,invalid)).code());
        afterResource.set(()->allowed.clear());forbidden(()->campaigns.preview(reviewer,"A",7,preview));
        assertEquals(1,count("employee_command_identity"));
    }
    /** 撤权、部分范围及停止都检查在旧回执前；停止后的内部预算履约不跟员工入口一起取消。 */
    @Test void revokeStopAndSystemBudgetFulfillmentKeepSeparateBoundaries() {
        String key=id();var value=input("A",7,false);var creator=actor(CAMPAIGN_CREATE);campaigns.create(creator,key,value);
        allowed.remove(CAMPAIGN_CREATE.code());forbidden(()->campaigns.create(creator,key,value));allowed.add(CAMPAIGN_CREATE.code());
        String publishKey=id();var publisher=actor(CAMPAIGN_PUBLISH);campaigns.publish(publisher,publishKey,"A",7,0);
        allowed.remove(CAMPAIGN_PUBLISH.code());forbidden(()->campaigns.publish(publisher,publishKey,"A",7,0));allowed.add(CAMPAIGN_PUBLISH.code());
        partial.set(true);forbidden(()->campaigns.publish(publisher,publishKey,"A",7,0));partial.set(false);
        var customer=new Actor(tenant,"customer",Actor.Role.MEMBER);var terms=funding.commitment(customer,new com.lrj.commerce.marketing.api.DecisionModels.Selection("A",7),"1.00");
        new TransactionTemplate(transactions).execute(s->{funding.reserve(customer,"O1","S1",terms);funding.reserve(customer,"O2","S1",terms);return null;});
        afterResource.set(this::stop);forbidden(()->campaigns.publish(publisher,publishKey,"A",7,0));forbidden(()->campaigns.create(admin,id(),input("B",1,false)));
        allowed.clear();new TransactionTemplate(transactions).execute(s->{funding.confirm(tenant,"O1");funding.confirm(tenant,"O1");funding.release(tenant,"O2");funding.release(tenant,"O2");return null;});
        assertEquals(Set.of("SPENT","RELEASED"),new HashSet<>(jdbc.queryForList("SELECT status FROM marketing_budget_hold WHERE tenant_id=?",String.class,tenant)));
        assertEquals(new java.math.BigDecimal("1.00"),jdbc.queryForObject("SELECT spent FROM marketing_budget WHERE tenant_id=?",java.math.BigDecimal.class,tenant));assertEquals(2,count("employee_command_identity"));
        assertEquals(1,campaigns.candidates(customer,"S1","M1",Instant.now()).offers().size());
    }
    /** 读取返回前上下文变化拒绝；64字冒号标识、实际tenant/version与401/503不绕过过滤器。 */
    @Test void contextTenantAndEncodedRoutesFailClosed() throws Exception {
        String encoded="cam:"+"a".repeat(60);create(encoded,7,false);
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->campaigns.publish(actor(CAMPAIGN_PUBLISH),id(),encoded,8,0)).code());
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->campaigns.publish(actor(CAMPAIGN_PUBLISH),id(),"foreign",7,0)).code());
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->campaigns.list(actor(CAMPAIGN_READ),"",10));
        afterScope.set(()->epoch.incrementAndGet());forbidden(()->funding.budgets(actor(BUDGET_READ),"",10));
        assertEquals(200,http("POST","/v1/admin/campaigns/"+encoded.replace(":","%3A")+"/7/publish","valid",true,Map.of("expectedVersion",0)).statusCode());
        for(String path:List.of("/v1/admin/campaigns","/v1/admin/campaign-budgets")) {
            assertEquals(403,http("GET",path,adminToken,false,null).statusCode());assertEquals(401,http("GET",path,"invalid",true,null).statusCode());
        }
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/campaigns","valid",true,null).statusCode());
    }
    /** 等待真实版本锁以后许可过期，即使原命令已有成功回执也拒绝返回。 */
    @Test void deadlineAfterActualLockPrecedesOriginalReceipt() throws Exception {
        create("A",7,false);String key=id();campaigns.publish(actor(CAMPAIGN_PUBLISH),key,"A",7,0);var publisher=actor(CAMPAIGN_PUBLISH);
        var scope=authority.scope(publisher,CAMPAIGN_PUBLISH);var expired=new EmployeeAccess.ScopePermit(scope.capability(),scope.tenant(),scope.filter(),scope.route(),scope.identity(),scope.fingerprint(),Instant.now().plusMillis(900));
        EmployeeAuthority target=AopTestUtils.getUltimateTargetObject(authority);
        doReturn(expired).when(target).scope(publisher,CAMPAIGN_PUBLISH);
        var ready=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var locker=pool.submit(()->new TransactionTemplate(transactions).execute(s->{jdbc.queryForList("SELECT version FROM marketing_campaign WHERE tenant_id=? AND campaign_id='A' FOR UPDATE",tenant);ready.countDown();try { assertTrue(release.await(4,java.util.concurrent.TimeUnit.SECONDS)); }catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
            assertTrue(ready.await(2,java.util.concurrent.TimeUnit.SECONDS));
            var retry=pool.submit(()->campaigns.publish(publisher,key,"A",7,0));
            Thread.sleep(1200);release.countDown();locker.get(3,java.util.concurrent.TimeUnit.SECONDS);
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->retry.get(3,java.util.concurrent.TimeUnit.SECONDS));assertInstanceOf(DomainException.class,failure.getCause());assertEquals(DomainException.Code.FORBIDDEN,((DomainException)failure.getCause()).code());
        } finally {release.countDown();}
        assertEquals(2,count("employee_command_identity"));assertEquals("PUBLISHED",status("A",7));
    }
    /** 执行引用不能换能力；集合入口不接受伪对象，实际内容版本零值也不能作为初始锁版本混入。 */
    @Test void referencesAndActualOwnerFactsCannotBeSubstituted() {
        var creator=actor(CAMPAIGN_CREATE);
        for(var cap:CAPABILITIES) if(cap!=CAMPAIGN_CREATE) forbidden(()->authority.scope(creator,cap));
        var scope=authority.scope(actor(CAMPAIGN_PUBLISH),CAMPAIGN_PUBLISH);
        for(var fact:List.of(new EmployeeAccess.ResourceFact("campaign","A",0),new EmployeeAccess.ResourceFact("campaign","A",-1),new EmployeeAccess.ResourceFact("marketing_rule","A",7)))
            forbidden(()->authority.resource(actor(CAMPAIGN_PUBLISH),scope,fact));
        var collection=authority.scope(creator,CAMPAIGN_CREATE);
        forbidden(()->authority.resource(creator,collection,new EmployeeAccess.ResourceFact("campaign","A",7)));
        assertEquals(0,count("employee_command_identity"));
    }
    /** 新权限不能跳过可信固定引用或原时效规则；发布时重新核对人群新鲜度。 */
    @Test void fixedAssetsAndPublicationFreshnessRemainAuthoritative() {
        rule();var base=input("A",7,true);
        assets.createRule(admin,id(),new MarketingAssets.Rule("DRAFT-R",1,"未发布",tree()));
        var draftPolicy=new com.lrj.commerce.campaign.management.api.CampaignApi.Policy(null,new MarketingAssets.Ref("DRAFT-R",1),base.policy().terms());
        var invalid=new com.lrj.commerce.campaign.management.api.CampaignApi.Draft(base.campaignId(),base.version(),base.storeId(),base.name(),base.validFrom(),base.validTo(),base.minimumSpend(),base.discountAmount(),base.rule(),draftPolicy);
        assertThrows(DomainException.class,()->campaigns.create(actor(CAMPAIGN_CREATE),id(),invalid));
        assets.createAudience(admin,id(),new MarketingAssets.Audience("AUD",1,"固定人群","fixture",Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),List.of("M1")));
        var policy=new com.lrj.commerce.campaign.management.api.CampaignApi.Policy(new MarketingAssets.Ref("AUD",1),base.policy().rule(),base.policy().terms());
        var valid=new com.lrj.commerce.campaign.management.api.CampaignApi.Draft(base.campaignId(),base.version(),base.storeId(),base.name(),base.validFrom(),base.validTo(),base.minimumSpend(),base.discountAmount(),base.rule(),policy);
        campaigns.create(actor(CAMPAIGN_CREATE),id(),valid);campaigns.review(actor(CAMPAIGN_SUBMIT),id(),"A",7,0,"submit");campaigns.review(actor(CAMPAIGN_APPROVE),id(),"A",7,1,"approve");
        jdbc.update("UPDATE marketing_audience_snapshot SET valid_until=? WHERE tenant_id=? AND audience_id='AUD'",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),tenant);
        String publish=id();assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->campaigns.publish(actor(CAMPAIGN_PUBLISH),publish,"A",7,2)).code());
        assertEquals("APPROVED",status("A",7));assertEquals(0,receipts(publish));assertEquals(3,count("employee_command_identity"));
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
