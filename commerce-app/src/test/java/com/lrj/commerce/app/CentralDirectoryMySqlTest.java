package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.merchant.api.MerchantApi;
import com.lrj.commerce.store.management.api.StoreApi;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实MySQL验证目录SQL/创建事务；SDK协议桩不替代后续真实中央联调。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralDirectoryMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @Autowired CentralEmployeeService service;
    @Autowired MerchantApi merchants;
    @Autowired StoreApi stores;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private String tenant,centralTenant,principal,member,adminToken,grant,policy,directory;
    private Actor admin;
    private final AtomicLong generation=new AtomicLong(1),epoch=new AtomicLong(1);
    private final AtomicBoolean createAll=new AtomicBoolean(),denied=new AtomicBoolean(),unavailable=new AtomicBoolean();
    private final Map<String,String> references=new ConcurrentHashMap<>();
    private final AtomicReference<Runnable> afterCheck=new AtomicReference<>();
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        tenant="directory-"+id();centralTenant=id();principal=id();member=id();adminToken=id();grant=id();policy=id();directory=id();
        generation.set(1);epoch.set(1);createAll.set(false);denied.set(false);unavailable.set(false);references.clear();afterCheck.set(null);
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'operator','OPERATOR',?)",JsonCodec.hash(id()),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','directory-test')",centralTenant,principal,member,tenant);
        for(int i=0;i<3;i++) {
            jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,?,?,'ACTIVE',0)",tenant,"M"+i,"merchant"+i);
            jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,?,?,?,'ACTIVE',0)",tenant,"S"+i,"M"+i,"store"+i);
        }
        jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,'DIRECTORY','SHADOW')",tenant,centralTenant);
        jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family='DIRECTORY'",tenant);
        when(client.issueExecution(anyString(),any(),any())).thenAnswer(call->{
            if(!"valid".equals(call.getArgument(0)))throw new CentralAccessException(401);
            CentralAccessDtos.Check check=call.getArgument(1);String ref=id();references.put(ref,check.capability());
            return new ExecutionAccessDtos.Reference("1",check.requestId(),ref,context(),check.capability(),check.resourceType(),((Instant)call.getArgument(2)).toString());
        });
        when(client.executionScope(anyString(),any())).thenAnswer(call->{
            if(unavailable.get())throw new CentralAccessException(503);
            CentralAccessDtos.Check check=call.getArgument(1);
            if(!check.capability().equals(references.get(call.<String>getArgument(0))))throw new AccessDeniedException("wrong reference capability");
            boolean full=createAll.get()&&check.capability().endsWith(".create");
            var kind=full?ScopeDtos.Kind.TENANT_ALL:(check.resourceType().equals("merchant")?ScopeDtos.Kind.SPECIFIED_RESOURCES:ScopeDtos.Kind.SPECIFIED_STORES);
            var clause=new ScopeDtos.Clause(kind,full?List.of():List.of(check.resourceType().equals("merchant")?"M1":"S1"),false);
            var paths=denied.get()?List.<ScopeDtos.Alternative>of():List.of(new ScopeDtos.Alternative(grant,1,List.of(clause)));
            var plan=new ScopeAccessDtos.Plan("1",check.requestId(),check.capability(),check.resourceType(),paths.isEmpty()?"DENY":"ALLOW",id(),context(),policy,epoch.get(),directory,1,1,1,Instant.now().plusSeconds(20).toString(),paths);
            var mutation=afterCheck.getAndSet(null);if(mutation!=null)mutation.run();
            return plan;
        });
    }
    private GovernanceDtos.AccessContext context(){return new GovernanceDtos.AccessContext(principal,member,generation.get(),1,1,centralTenant,"commerce","test","directory-test","HUMAN",id());}
    private Actor actor(EmployeeAccess.Capability capability){return service.authenticate("valid",centralTenant,capability);}
    private void forbidden(Runnable call){assertEquals(DomainException.Code.FORBIDDEN,assertThrows(DomainException.class,call::run).code());}
    @Test void rangeIsFilteredBeforeLimitAndReadDoesNotImplyCreate() throws Exception {
        var merchant=actor(EmployeeAccess.Capability.MERCHANT_READ);var store=actor(EmployeeAccess.Capability.STORE_DIRECTORY_READ);
        assertEquals(List.of("M1"),merchants.list(merchant,"",1).stream().map(MerchantApi.View::merchantId).toList());
        assertEquals(List.of("S1"),stores.list(store,"",1).stream().map(StoreApi.View::storeId).toList());
        assertTrue(merchants.list(merchant,"M1",1).isEmpty());assertTrue(stores.list(store,"S1",1).isEmpty());
        forbidden(()->merchants.create(merchant,id(),new MerchantApi.Create("N","new")));
        forbidden(()->merchants.create(actor(EmployeeAccess.Capability.MERCHANT_CREATE),id(),new MerchantApi.Create("N","new")));
        forbidden(()->stores.create(actor(EmployeeAccess.Capability.STORE_CREATE),id(),new StoreApi.Create("N","M1","new")));
        forbidden(()->merchants.list(admin,"",10));forbidden(()->stores.list(admin,"",10));
        assertEquals(200,http("GET","/v1/admin/merchants?limit=1","valid",true,null).statusCode());
        assertEquals(403,http("GET","/v1/admin/stores",adminToken,false,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/merchants","invalid",true,null).statusCode());
        assertEquals(403,http("POST","/v1/admin/merchants","valid",true,new MerchantApi.Create("N","new")).statusCode());
    }
    @Test void wholeTenantCreateIsAtomicIdempotentAndAuditsRealResourceType() throws Exception {
        createAll.set(true);var write=actor(EmployeeAccess.Capability.MERCHANT_CREATE);String key=id();var input=new MerchantApi.Create("N","new");
        assertEquals("N",merchants.create(write,key,input).merchantId());
        assertEquals("N",merchants.create(actor(EmployeeAccess.Capability.MERCHANT_CREATE),key,input).merchantId());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='merchant' AND resource_id='N' AND store_id IS NULL",Integer.class,tenant));
        assertEquals(200,http("POST","/v1/admin/stores","valid",true,new StoreApi.Create("NEW","N","new store")).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM employee_command_identity WHERE tenant_id=? AND resource_type='store' AND resource_id='NEW' AND store_id='NEW'",Integer.class,tenant));
        assertEquals(DomainException.Code.NOT_FOUND,assertThrows(DomainException.class,()->stores.create(actor(EmployeeAccess.Capability.STORE_CREATE),id(),new StoreApi.Create("BAD","FOREIGN","bad"))).code());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM store_record WHERE tenant_id=? AND store_id='BAD'",Integer.class,tenant));
        jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",centralTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->merchants.create(actor(EmployeeAccess.Capability.MERCHANT_CREATE),key,input)).code());
    }
    @Test void changedReadFenceRevocationOutageAndStoppedRouteReject() throws Exception {
        var read=actor(EmployeeAccess.Capability.MERCHANT_READ);
        afterCheck.set(()->epoch.incrementAndGet());forbidden(()->merchants.list(read,"",10));
        createAll.set(true);var write=actor(EmployeeAccess.Capability.MERCHANT_CREATE);String key=id();var input=new MerchantApi.Create("N","new");
        merchants.create(write,key,input);denied.set(true);forbidden(()->merchants.create(write,key,input));denied.set(false);
        unavailable.set(true);assertEquals(503,http("GET","/v1/admin/stores","valid",true,null).statusCode());unavailable.set(false);
        afterCheck.set(()->jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='DIRECTORY'",tenant));
        forbidden(()->merchants.create(write,id(),new MerchantApi.Create("NO","no")));
        forbidden(()->stores.list(admin,"",10));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merchant_record WHERE tenant_id=? AND merchant_id='NO'",Integer.class,tenant));
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)request.header("X-Tenant-Id",centralTenant);
        if(body!=null)request.header("Content-Type","application/json").header("Idempotency-Key",id());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
