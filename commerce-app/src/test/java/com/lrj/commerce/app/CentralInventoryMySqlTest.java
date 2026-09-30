package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.ExecutionAccessDtos.Reference;
import com.lrj.authz.protocol.ScopeAccessDtos.ResourceDecision;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.inventory.api.InventoryApi;
import com.lrj.commerce.catalog.assortment.api.CatalogApi;
import com.lrj.commerce.kernel.DomainException;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实MySQL与HTTP验证库存范围、路由并发和回执；SDK契约桩不替代另行真实中央授权验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralInventoryMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?")) throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @Autowired CentralEmployeeService service;
    @Autowired InventoryApi inventory;
    @Autowired CatalogApi catalog;
    @Autowired EmployeeAccess access;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @LocalServerPort int port;
    private String tenant,authTenant,principal,member,adminToken;
    private Actor admin;
    private final Map<String,String> references=new ConcurrentHashMap<>();
    private final AtomicLong generation=new AtomicLong(1);
    private final AtomicBoolean denied=new AtomicBoolean(),unavailable=new AtomicBoolean();
    private final AtomicReference<Runnable> afterCheck=new AtomicReference<>();
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        tenant="inventory-"+id();authTenant=id();principal=id();member=id();adminToken=id();generation.set(1);denied.set(false);unavailable.set(false);references.clear();afterCheck.set(null);
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','inventory-test')",authTenant,principal,member,tenant);
        jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,'M','merchant','ACTIVE',0)",tenant);
        for(String store:List.of("S1","S2"))jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,?,'M',?,'ACTIVE',0)",tenant,store,store);
        catalog.create(admin,id(),new CatalogApi.Create("SKU1","S1","sku","12.00"));
        inventory.receive(admin,id(),new InventoryApi.Receipt("S1","SKU1",10));
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'INVENTORY','SHADOW')",tenant,authTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=?",tenant);
        when(client.issueExecution(anyString(),any(),any())).thenAnswer(call->{
            if(!"valid".equals(call.getArgument(0)))throw new CentralAccessException(401);
            CentralAccessDtos.Check check=call.getArgument(1);String ref=id();references.put(ref,check.capability());
            return new Reference("1",check.requestId(),ref,context(),check.capability(),check.resourceType(),((Instant)call.getArgument(2)).toString());
        });
        when(client.checkExecution(anyString(),any(),any())).thenAnswer(call->{
            if(unavailable.get())throw new CentralAccessException(503);
            CentralAccessDtos.Check check=call.getArgument(1);ScopeDtos.Facts facts=call.getArgument(2);
            boolean allow=!denied.get()&&check.capability().equals(references.get(call.<String>getArgument(0)))&&facts.storeId().equals("S1")&&check.tenantId().equals(authTenant);
            Runnable mutation=afterCheck.getAndSet(null);if(mutation!=null)mutation.run();
            return new ResourceDecision("1",check.requestId(),check.capability(),"store",facts.resourceId(),facts.resourceVersion(),allow?"ALLOW":"DENY",id(),context(),Instant.now().plusSeconds(25).toString());
        });
    }
    private GovernanceDtos.AccessContext context() {
        return new GovernanceDtos.AccessContext(principal,member,generation.get(),1,1,authTenant,"commerce","test","inventory-test","HUMAN",id());
    }
    private Actor actor(EmployeeAccess.Capability capability) { return service.authenticate("valid",authTenant,capability); }
    private void forbidden(Runnable action) { assertEquals(DomainException.Code.FORBIDDEN,assertThrows(DomainException.class,action::run).code()); }
    @Test void centralReadUsesOneStoreAndLegacyAdminOrMissingReferenceCannotBypass() throws Exception {
        var read=actor(EmployeeAccess.Capability.INVENTORY_READ);
        assertEquals(10,inventory.list(read,"S1","",10).getFirst().available());
        forbidden(()->inventory.list(read,"S2","",10));forbidden(()->inventory.list(read,"foreign","",10));
        forbidden(()->inventory.list(admin,"S1","",10));
        forbidden(()->inventory.list(new Actor(tenant,"operator",Actor.Role.OPERATOR),"S1","",10));
        forbidden(()->inventory.receive(read,id(),new InventoryApi.Receipt("S1","SKU1",1)));
        assertEquals(200,http("GET","/v1/admin/inventory?storeId=S1","valid",true,null).statusCode());
        assertEquals(403,http("GET","/v1/admin/inventory?storeId=S1",adminToken,false,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/inventory?storeId=S1","invalid",true,null).statusCode());
        // 会员已接入独立能力：库存上下文不得获得会员权限，已认证但授权不足返回403。
        assertEquals(403,http("GET","/v1/admin/members","valid",true,null).statusCode());
        assertEquals(200,http("POST","/v1/admin/inventory/receipts","valid",true,new InventoryApi.Receipt("S1","SKU1",1)).statusCode());
    }
    @Test void actionHintUsesIndependentWriteCheckAndDoesNotConcealOutage() throws Exception {
        when(client.checkResource(anyString(), any(), any())).thenAnswer(call -> {
            CentralAccessDtos.Check check = call.getArgument(1);
            ScopeDtos.Facts facts = call.getArgument(2);
            assertEquals("commerce.inventory.receive", check.capability());
            assertEquals("S1", facts.storeId());
            return new ResourceDecision("1", check.requestId(), check.capability(), "store", facts.resourceId(), facts.resourceVersion(), "ALLOW", id(), context(), Instant.now().plusSeconds(5).toString());
        });
        var allowed = http("GET", "/v1/operations/inventory/actions?storeId=S1", "valid", true, null);
        assertEquals(200, allowed.statusCode()); assertTrue(allowed.body().contains("\"receive\":true"));
        doThrow(new AccessDeniedException("DENY")).when(client).checkResource(anyString(), any(), any());
        var readOnly = http("GET", "/v1/operations/inventory/actions?storeId=S1", "valid", true, null);
        assertEquals(200, readOnly.statusCode()); assertTrue(readOnly.body().contains("\"receive\":false"));
        assertEquals(403, http("GET", "/v1/operations/inventory/actions?storeId=S2", "valid", true, null).statusCode());
        doThrow(new CentralAccessException(503)).when(client).checkResource(anyString(), any(), any());
        assertEquals(503, http("GET", "/v1/operations/inventory/actions?storeId=S1", "valid", true, null).statusCode());
        assertEquals(401, http("GET", "/v1/operations/inventory/actions?storeId=S1", "invalid", true, null).statusCode());
        forbidden(() -> service.actions(admin, adminToken, authTenant, "S1"));
    }
    @Test void receiptIsIdempotentButRebindingCannotReadPreviousIdentityReceipt() {
        var write=actor(EmployeeAccess.Capability.INVENTORY_RECEIVE);String key=id();var receipt=new InventoryApi.Receipt("S1","SKU1",3);
        assertEquals(13,inventory.receive(write,key,receipt).available());
        assertEquals(13,inventory.receive(actor(EmployeeAccess.Capability.INVENTORY_RECEIVE),key,receipt).available());
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->inventory.receive(write,key,new InventoryApi.Receipt("S1","SKU1",4))).code());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND principal_id=? AND membership_id=? AND generation=1",Integer.class,tenant,principal,member));
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->inventory.receive(actor(EmployeeAccess.Capability.INVENTORY_RECEIVE),key,receipt)).code());
        assertEquals(13,jdbc.queryForObject("SELECT available FROM inventory_stock WHERE tenant_id=? AND store_id='S1' AND sku_id='SKU1'",Long.class,tenant));
    }
    @Test void revokeUnavailableChangedStoreAndStoppedRouteDoNotCommit() throws Exception {
        var write=actor(EmployeeAccess.Capability.INVENTORY_RECEIVE);var receipt=new InventoryApi.Receipt("S1","SKU1",5);
        denied.set(true);forbidden(()->inventory.receive(write,id(),receipt));
        assertEquals(403,http("GET","/v1/admin/inventory?storeId=S1","valid",true,null).statusCode());denied.set(false);
        unavailable.set(true);assertEquals(DomainException.Code.UNAVAILABLE,assertThrows(DomainException.class,()->inventory.receive(write,id(),receipt)).code());
        assertEquals(503,http("GET","/v1/admin/inventory?storeId=S1","valid",true,null).statusCode());unavailable.set(false);
        afterCheck.set(()->jdbc.update("UPDATE store_record SET version=version+1 WHERE tenant_id=? AND store_id='S1'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->inventory.receive(write,id(),receipt)).code());
        afterCheck.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=?",tenant));
        forbidden(()->inventory.receive(write,id(),receipt));forbidden(()->inventory.receive(admin,id(),receipt));
        assertEquals(10,jdbc.queryForObject("SELECT available FROM inventory_stock WHERE tenant_id=? AND store_id='S1' AND sku_id='SKU1'",Long.class,tenant));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE employee_authority_route SET state='LEGACY',ever_central=FALSE,version=version+1 WHERE tenant_id=?",tenant));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM employee_authority_route WHERE tenant_id=?",tenant));
    }
    @Test void routeSwitchWaitsForAdmittedTransactionAndOldPermitCannotReplay() throws Exception {
        var permit=access.require(actor(EmployeeAccess.Capability.INVENTORY_RECEIVE),EmployeeAccess.Capability.INVENTORY_RECEIVE,new EmployeeAccess.StoreFact("S1",0));
        var expired=new EmployeeAccess.Permit(permit.capability(),permit.tenant(),permit.fact(),permit.route(),permit.identity(),Instant.now().minusMillis(1));
        forbidden(()->new TransactionTemplate(manager).execute(status->{access.lock(expired);return null;}));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holding=pool.submit(()->new TransactionTemplate(manager).execute(status->{access.lock(permit);locked.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("测试锁等待超时");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return true;}));
            assertTrue(locked.await(2,TimeUnit.SECONDS));
            var switching=pool.submit(()->{attempted.countDown();return jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=?",tenant);});
            try { assertTrue(attempted.await(1,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->switching.get(100,TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            assertTrue(holding.get(3,TimeUnit.SECONDS));assertEquals(1,switching.get(3,TimeUnit.SECONDS));
        }
        forbidden(()->new TransactionTemplate(manager).execute(status->{access.lock(permit);return null;}));
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",authTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
