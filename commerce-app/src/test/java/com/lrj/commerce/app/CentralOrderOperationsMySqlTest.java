package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.ordering.order.api.OrderApi;
import com.lrj.commerce.fulfillment.api.FulfillmentApi;
import com.lrj.commerce.aftersales.api.AftersaleApi;
import com.lrj.commerce.payment.charge.api.PaymentApi;
import com.lrj.commerce.payment.refund.api.RefundApi;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;

/** 真实 MySQL/HTTP 证明范围、原资金 CAS 与身份审计；SDK 契约桩明确不替代真实 Auth/SpiceDB 演练。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"commerce.iam.store-read.enabled=true","commerce.iam.employee.enabled=true"})
class CentralOrderOperationsMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/commerce_test_20260923(?:_[a-z0-9]+)?\\?.*"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.address-key",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @Autowired CentralEmployeeService service;
    @Autowired OrderApi orders;
    @Autowired FulfillmentApi fulfillment;
    @Autowired AftersaleApi cases;
    @Autowired PaymentApi payments;
    @Autowired RefundApi refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @LocalServerPort int port;
    private String tenant,authTenant,principal,membership,grant,policy,directory,adminToken,customerToken;
    private Actor admin;
    private final Map<String,String> references=new ConcurrentHashMap<>();
    private final Set<String> allowed=ConcurrentHashMap.newKeySet();
    private final AtomicLong generation=new AtomicLong(1);
    private final AtomicBoolean unavailable=new AtomicBoolean();
    private final AtomicReference<Runnable> afterResource=new AtomicReference<>();
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed() {
        tenant="order-ops-"+id();authTenant=id();principal=id();membership=id();grant=id();policy=id();directory=id();adminToken=id();customerToken=id();
        generation.set(1);unavailable.set(false);afterResource.set(null);references.clear();allowed.clear();
        for(var cap:EmployeeAccess.Capability.values())allowed.add(cap.code());
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        credential(adminToken,"admin","ADMIN");credential(customerToken,"customer","MEMBER");credential(id(),"operator","OPERATOR");
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','order-operations-test')",authTenant,principal,membership,tenant);
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status,version) VALUES(?,'M1','customer','customer','BASIC','ACTIVE',0)",tenant);
        jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,'M','merchant','ACTIVE',0)",tenant);
        for(String store:List.of("S1","S2"))jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,?,'M',?,'ACTIVE',0)",tenant,store,store);
        for(String family:List.of("ORDER","PAYMENT","FULFILLMENT","AFTERSALE","REFUND")) {
            jdbc.update("INSERT INTO employee_authority_route(tenant_id,auth_tenant_id,family,state) VALUES(?,?,?,'SHADOW')",tenant,authTenant,family);
            jdbc.update("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=? AND family=?",tenant,family);
        }
        when(client.issueExecution(anyString(),any(),any())).thenAnswer(call->{
            if(!"valid".equals(call.getArgument(0)))throw new CentralAccessException(401);
            CentralAccessDtos.Check check=call.getArgument(1);require(check);String ref=id();references.put(ref,check.capability());
            return new ExecutionAccessDtos.Reference("1",check.requestId(),ref,context(),check.capability(),check.resourceType(),((Instant)call.getArgument(2)).toString());
        });
        when(client.executionScope(anyString(),any())).thenAnswer(call->{
            CentralAccessDtos.Check check=call.getArgument(1);require(check);
            if(!check.capability().equals(references.get(call.<String>getArgument(0))))throw new AccessDeniedException("wrong reference");
            var clause=new ScopeDtos.Clause(ScopeDtos.Kind.SPECIFIED_STORES,List.of("S1"),false);
            return new ScopeAccessDtos.Plan("1",check.requestId(),check.capability(),"store","ALLOW",id(),context(),policy,1,directory,1,1,1,Instant.now().plusSeconds(20).toString(),List.of(new ScopeDtos.Alternative(grant,1,List.of(clause))));
        });
        when(client.checkExecution(anyString(),any(),any())).thenAnswer(call->{
            CentralAccessDtos.Check check=call.getArgument(1);ScopeDtos.Facts fact=call.getArgument(2);require(check);
            if(!check.capability().equals(references.get(call.<String>getArgument(0)))||!"S1".equals(fact.storeId()))throw new AccessDeniedException("wrong reference or store");
            assertEquals(authTenant,fact.tenantId());assertEquals("store",fact.resourceType());assertEquals(fact.storeId(),fact.resourceId());
            var result=new ScopeAccessDtos.ResourceDecision("1",check.requestId(),check.capability(),"store",fact.resourceId(),fact.resourceVersion(),"ALLOW",id(),context(),Instant.now().plusSeconds(20).toString());
            var mutation=afterResource.getAndSet(null);if(mutation!=null)mutation.run();return result;
        });
    }
    private void credential(String token,String actor,String role){jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,?,?)",JsonCodec.hash(token),tenant,actor,role,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));}
    private void require(CentralAccessDtos.Check check){if(unavailable.get())throw new CentralAccessException(503);if(!authTenant.equals(check.tenantId())||!allowed.contains(check.capability()))throw new AccessDeniedException("denied");}
    private GovernanceDtos.AccessContext context(){return new GovernanceDtos.AccessContext(principal,membership,generation.get(),1,1,authTenant,"commerce","test","order-operations-test","HUMAN",id());}
    private Actor actor(EmployeeAccess.Capability cap){return service.authenticate("valid",authTenant,cap);}
    private void only(EmployeeAccess.Capability cap){allowed.clear();allowed.add(cap.code());}
    private void forbidden(Runnable run){assertEquals(DomainException.Code.FORBIDDEN,assertThrows(DomainException.class,run::run).code());}
    private void order(String id,String store,String status){jdbc.update("INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version) VALUES(?,?,'M1',?,'M',?,12.00,?,'CHANNEL_REQUIRED',0,?,?,'[]',X'01',1)",tenant,id,store,id,status,java.sql.Timestamp.from(Instant.now().minusSeconds(100)),java.sql.Timestamp.from(Instant.now().minusSeconds(10)));}
    private void fulfillment(String order,boolean blocked){jdbc.update("INSERT INTO fulfillment_record(tenant_id,order_id,status,provider,blocked) VALUES(?,?,'READY','SANDBOX_WMS',?)",tenant,order,blocked);}
    private void aftersale(String id,String order,boolean returns){jdbc.update("INSERT INTO aftersales_case(tenant_id,case_id,order_id,member_id,status,return_required,refund_amount,reason,items_json) VALUES(?,?,?,'M1','REQUESTED',?,0,'test','[]')",tenant,id,order,returns);}
    private int audits(String operation){return jdbc.queryForObject("SELECT COUNT(*) FROM employee_command_identity WHERE tenant_id=? AND operation=?",Integer.class,tenant,operation);}
    @Test void sqlFiltersBeforeLimitAndMemberOldEntryIsIndependent() throws Exception {
        order("0-denied","S2","PAID");order("1-allowed","S1","PAID");fulfillment("0-denied",true);fulfillment("1-allowed",true);
        aftersale("0-case","0-denied",true);aftersale("1-case","1-allowed",true);
        jdbc.update("INSERT INTO payment_refund(tenant_id,refund_id,case_id,order_id,amount,currency,provider,status) VALUES(?,'0-refund','old0','0-denied',0,'CNY','NO_PAYMENT_REQUIRED','SUCCEEDED'),(?,'1-refund','old1','1-allowed',0,'CNY','NO_PAYMENT_REQUIRED','SUCCEEDED')",tenant,tenant);
        assertEquals("1-allowed",orders.adminList(actor(ORDER_READ),"",1).getFirst().orderId());
        assertEquals("1-allowed",fulfillment.list(actor(FULFILLMENT_READ),"",1).getFirst().orderId());
        assertEquals("1-case",cases.adminList(actor(AFTERSALE_READ),"",1).getFirst().caseId());
        assertEquals("1-refund",refunds.list(actor(REFUND_READ),"",1).getFirst().refundId());
        forbidden(()->orders.adminRead(actor(ORDER_READ),"0-denied"));forbidden(()->orders.adminList(admin,"",1));
        assertEquals(200,http("GET","/v1/orders/1-allowed",customerToken,false,null,null).statusCode());
        assertEquals(403,http("GET","/v1/admin/orders",adminToken,false,null,null).statusCode());
        assertEquals(401,http("GET","/v1/admin/orders","invalid",true,null,null).statusCode());
    }
    @Test void fulfillmentIndependentCapabilitiesPreserveOriginalKeyAndOrderLifecycle() {
        order("ship","S1","PAID");only(FULFILLMENT_SHIP);String key=id();var input=new FulfillmentApi.Ship("TRACK-1");
        assertEquals("SHIPPED",fulfillment.ship(actor(FULFILLMENT_SHIP),key,"ship",input).status());
        assertEquals("SHIPPED",fulfillment.ship(actor(FULFILLMENT_SHIP),key,"ship",input).status());assertEquals(1,audits("fulfillment.ship"));
        assertEquals("FULFILLING",orders.internalRead(tenant,"ship").status());
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->fulfillment.ship(actor(FULFILLMENT_SHIP),key,"ship",new FulfillmentApi.Ship("TRACK-2"))).code());
        only(FULFILLMENT_DELIVER);assertEquals("DELIVERED",fulfillment.deliver(actor(FULFILLMENT_DELIVER),id(),"ship").status());
        assertEquals("COMPLETED",orders.internalRead(tenant,"ship").status());assertEquals(1,audits("fulfillment.deliver"));
    }
    @Test void aftersaleApprovalAndReturnRequireOwnCapabilitiesWithoutRead() {
        order("return","S1","PAID");fulfillment("return",true);aftersale("case","return",true);
        only(AFTERSALE_APPROVE);assertEquals("WAIT_RETURN",cases.approve(actor(AFTERSALE_APPROVE),id(),"case").status());
        only(AFTERSALE_RECEIVE_RETURN);assertEquals("REFUNDING",cases.receiveReturn(actor(AFTERSALE_RECEIVE_RETURN),id(),"case").status());
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM payment_refund WHERE tenant_id=? AND case_id='case'",String.class,tenant));
        assertEquals(1,audits("aftersale.approve"));assertEquals(1,audits("aftersale.receive"));
    }
    @Test void rejectionDoesNotCreateRefundAndExactStoreAuditIsImmutable() {
        order("reject","S1","PAID");fulfillment("reject",true);aftersale("case","reject",false);only(AFTERSALE_REJECT);
        assertEquals("REJECTED",cases.reject(actor(AFTERSALE_REJECT),id(),"case").status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund WHERE tenant_id=?",Integer.class,tenant));
        var audit=jdbc.queryForMap("SELECT principal_id,membership_id,generation,capability,resource_type,resource_id,store_id FROM employee_command_identity WHERE tenant_id=?",tenant);
        assertEquals(principal,audit.get("principal_id"));assertEquals(membership,audit.get("membership_id"));assertEquals(1L,((Number)audit.get("generation")).longValue());
        assertEquals(AFTERSALE_REJECT.code(),audit.get("capability"));assertEquals("store",audit.get("resource_type"));assertEquals("S1",audit.get("resource_id"));assertEquals("S1",audit.get("store_id"));
    }
    @Test void paymentAndRefundReconcileDoNotImplicitlyGrantOrderReadOrDuplicateFunds() {
        order("money","S1","PAYMENT_IN_PROGRESS");jdbc.update("INSERT INTO payment_attempt(tenant_id,payment_id,order_id,amount,currency,provider,status) VALUES(?,'PAY','money',12,'CNY','SANDBOX','UNKNOWN')",tenant);
        jdbc.update("INSERT INTO payment_sandbox_ledger(tenant_id,payment_id,order_id,amount,currency,status,transaction_id) VALUES(?,'PAY','money',12,'CNY','PAID','TX')",tenant);
        only(PAYMENT_READ);assertEquals(PaymentApi.Status.UNKNOWN,payments.adminRead(actor(PAYMENT_READ),"money").status());
        only(PAYMENT_RECONCILE);assertEquals(PaymentApi.Status.PAID,payments.adminReconcile(actor(PAYMENT_RECONCILE),"money").status());
        assertEquals(PaymentApi.Status.PAID,payments.adminReconcile(actor(PAYMENT_RECONCILE),"money").status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='payment.paid.v1'",Integer.class,tenant));
        jdbc.update("INSERT INTO payment_refund(tenant_id,refund_id,case_id,order_id,amount,currency,provider,status) VALUES(?,'REF','CASE','money',6,'CNY','SANDBOX','UNKNOWN')",tenant);
        jdbc.update("INSERT INTO payment_refund_sandbox(tenant_id,refund_id,order_id,amount,currency,status,transaction_id) VALUES(?,'REF','money',6,'CNY','SUCCEEDED','RTX')",tenant);
        only(REFUND_RECONCILE);assertEquals("SUCCEEDED",refunds.reconcile(actor(REFUND_RECONCILE),"REF").status());assertEquals("SUCCEEDED",refunds.reconcile(actor(REFUND_RECONCILE),"REF").status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='refund.succeeded.v1'",Integer.class,tenant));
    }
    @Test void expiryOnlyTouchesAuthorizedOrdersAndReplaysOriginalBatchFacts() {
        order("0-other","S2","PAYMENT_IN_PROGRESS");order("1-due","S1","PAYMENT_IN_PROGRESS");only(ORDER_EXPIRE);String key=id();
        assertEquals(1,orders.expire(actor(ORDER_EXPIRE),key));assertEquals("CLOSING",orders.internalRead(tenant,"1-due").status());assertEquals("PAYMENT_IN_PROGRESS",orders.internalRead(tenant,"0-other").status());
        assertEquals(1,orders.expire(actor(ORDER_EXPIRE),key));assertEquals(1,audits("order.expire"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM employee_order_expiry_fact WHERE tenant_id=? AND order_id='1-due' AND store_id='S1' AND store_version=0",Integer.class,tenant));
        var original=actor(ORDER_EXPIRE);allowed.clear();forbidden(()->orders.expire(original,key));
    }
    @Test void revocationOutageStoreMutationAndStoppedRouteHaveNoCommit() throws Exception {
        order("guard","S1","PAID");var op=actor(FULFILLMENT_SHIP);only(FULFILLMENT_SHIP);var input=new FulfillmentApi.Ship("TRACK");
        allowed.clear();forbidden(()->fulfillment.ship(op,id(),"guard",input));only(FULFILLMENT_SHIP);
        unavailable.set(true);assertEquals(DomainException.Code.UNAVAILABLE,assertThrows(DomainException.class,()->fulfillment.ship(op,id(),"guard",input)).code());
        assertEquals(503,http("POST","/v1/admin/fulfillments/guard/ship","valid",true,input,id()).statusCode());unavailable.set(false);
        afterResource.set(()->jdbc.update("UPDATE store_record SET version=version+1 WHERE tenant_id=? AND store_id='S1'",tenant));
        assertEquals(DomainException.Code.CONFLICT,assertThrows(DomainException.class,()->fulfillment.ship(op,id(),"guard",input)).code());
        jdbc.update("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=? AND family='FULFILLMENT'",tenant);
        forbidden(()->fulfillment.ship(op,id(),"guard",input));assertEquals(0,audits("fulfillment.ship"));assertEquals("PAID",orders.internalRead(tenant,"guard").status());
    }
    @Test void concurrentSameKeyCommitsOneShipmentAndBindingGenerationCannotReadIt() throws Exception {
        order("race","S1","PAID");only(FULFILLMENT_SHIP);String key=id();var input=new FulfillmentApi.Ship("TRACK");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->fulfillment.ship(actor(FULFILLMENT_SHIP),key,"race",input));var b=pool.submit(()->fulfillment.ship(actor(FULFILLMENT_SHIP),key,"race",input));
            assertEquals(a.get(8,TimeUnit.SECONDS),b.get(8,TimeUnit.SECONDS));
        }
        assertEquals(1,audits("fulfillment.ship"));jdbc.update("UPDATE central_store_identity_binding SET generation=2 WHERE auth_tenant_id=?",authTenant);generation.set(2);
        assertEquals(DomainException.Code.IDEMPOTENCY_CONFLICT,assertThrows(DomainException.class,()->fulfillment.ship(actor(FULFILLMENT_SHIP),key,"race",input)).code());
    }
    private HttpResponse<String> http(String method,String path,String token,boolean central,Object body,String key)throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token);
        if(central)r.header("X-Tenant-Id",authTenant);if(key!=null)r.header("Idempotency-Key",key);if(body!=null)r.header("Content-Type","application/json");
        r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        return HttpClient.newHttpClient().send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
}
