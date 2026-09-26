package com.lrj.commerce.app;

import com.lrj.commerce.runtime.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 后台任务无需管理员触发即可释放过期订单，单行故障不能阻塞同一任务的其他数据。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerResilienceTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?")) throw new IllegalStateException("必须显式指定本项目隔离测试库");
        registry.add("spring.datasource.url",()->url);
        registry.add("commerce.sandbox-enabled",()->true);
        registry.add("commerce.workers-enabled",()->false);
        registry.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));
        registry.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.lrj.commerce.ordering.api.OrderApi orders;
    @Autowired com.lrj.commerce.payment.api.PaymentApi payments;
    @Autowired com.lrj.commerce.member.api.MemberCycleApi cycles;
    @Autowired EventDispatcher events;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private String tenant,admin,member,poisonTenant;
    record Reply(int status,JsonNode body) { }

    @BeforeEach void identities() {tenant="t-"+UUID.randomUUID();admin=token("admin","ADMIN");member=token("buyer","MEMBER");}
    @AfterEach void removePoison() {
        if(poisonTenant==null)return;
        jdbc.update("DELETE FROM member_cycle_policy WHERE tenant_id=?",poisonTenant);jdbc.update("DELETE FROM member_record WHERE tenant_id=?",poisonTenant);
    }
    private String token(String actor,String role) {
        String token=UUID.randomUUID()+"-"+UUID.randomUUID();
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,?,?)",JsonCodec.hash(token),tenant,actor,role,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        return token;
    }
    private Reply call(String method,String path,String token,String key,Object body) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)req.header("Authorization","Bearer "+token);
        if(key!=null)req.header("Idempotency-Key",key);
        if(body!=null)req.header("Content-Type","application/json");
        req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
        var reply=http.send(req.build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(reply.statusCode(),json.readTree(reply.body()));
    }
    private JsonNode post(String path,String token,String key,Object body) throws Exception {
        var r=call("POST",path,token,key,body);assertEquals(200,r.status(),r.body().toString());return r.body();
    }
    private JsonNode pendingOrder() throws Exception {
        post("/v1/admin/members",admin,"member",Map.of("memberId","m1","actorId","buyer","displayName","测试会员","memberLevel","VIP"));
        post("/v1/admin/merchants",admin,"merchant",Map.of("merchantId","merchant1","name","测试商家"));
        post("/v1/admin/stores",admin,"store",Map.of("storeId","store1","merchantId","merchant1","name","测试店铺"));
        post("/v1/admin/skus",admin,"sku",Map.of("skuId","sku1","storeId","store1","title","测试商品","unitPrice","25.00"));
        post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",2));
        var quote=post("/v1/quotes",member,"q",Map.of("storeId","store1","items",List.of(Map.of("skuId","sku1","quantity",1))));
        return post("/v1/orders",member,"o",Map.of("quoteId",quote.path("quoteId").asString(),"address",Map.of("recipient","收货测试","phone","13800000000","detail","隔离测试地址123")));
    }
    private String status(JsonNode order) {return jdbc.queryForObject("SELECT status FROM order_record WHERE tenant_id=? AND order_id=?",String.class,tenant,order.path("orderId").asString());}
    private long stock(String column) {return jdbc.queryForObject("SELECT "+column+" FROM inventory_stock WHERE tenant_id=? AND sku_id='sku1'",Long.class,tenant);}
    private void expireNow() {jdbc.update("UPDATE order_record SET expires_at=? WHERE tenant_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),tenant);}
    /** 共享测试库可能有历史租户的过期订单，按租户轮转直到本租户被处理。 */
    private void tickOrdersUntil(JsonNode order,String expected) {
        for(int i=0;i<2000&&!status(order).equals(expected);i++)orders.tick();
        assertEquals(expected,status(order));
    }

    @Test void backgroundExpiryReleasesAbandonedOrderWithoutAdmin() throws Exception {
        var order=pendingOrder();assertEquals(1,stock("held"));
        orders.tick();assertEquals("PENDING_PAYMENT",status(order));
        expireNow();tickOrdersUntil(order,"CANCELLED");
        assertEquals(0,stock("held"));assertEquals(2,stock("available"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='order.cancelled.v1'",Integer.class,tenant));
        // 重复轮询不再改变已取消订单。
        orders.tick();assertEquals(2,stock("available"));
        assertEquals(409,call("POST","/v1/orders/"+order.path("orderId").asString()+"/payments",member,"late",null).status());
    }
    @Test void expiredInFlightPaymentIsClosedByBackgroundChecksAfterAutoChecksWereExhausted() throws Exception {
        var order=pendingOrder();String id=order.path("orderId").asString();
        var payment=post("/v1/orders/"+id+"/payments",member,"pay",null);
        post("/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",admin,"open",Map.of("status","OPEN"));
        // 模拟下单后超过自动核对窗口：五次重查已用完。
        jdbc.update("UPDATE payment_attempt SET check_attempts=5 WHERE tenant_id=?",tenant);
        expireNow();tickOrdersUntil(order,"CLOSING");
        assertEquals(1,stock("held"),"支付未知时到期不能释放库存");
        post("/v1/admin/events/pump",admin,null,null);
        assertEquals(0,jdbc.queryForObject("SELECT check_attempts FROM payment_attempt WHERE tenant_id=?",Integer.class,tenant));
        for(int i=0;i<200&&!"CLOSED".equals(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE tenant_id=?",String.class,tenant));i++)payments.tick();
        post("/v1/admin/events/pump",admin,null,null);
        assertEquals("CANCELLED",status(order));assertEquals(0,stock("held"));assertEquals(2,stock("available"));
    }
    @Test void poisonCycleRowDoesNotStopOtherMembersInSameTick() {
        poisonTenant="t-poison-"+UUID.randomUUID();
        // 周期天数为0的历史坏数据会在每次考核时抛出算术异常，且排序最靠前。
        jdbc.update("INSERT INTO member_cycle_policy(tenant_id,version,effective_from,policy_json) VALUES(?,1,'2000-01-01 00:00:00.000',?)",poisonTenant,"{\"version\":1,\"effectiveFrom\":\"2000-01-01T00:00:00Z\",\"periodDays\":0,\"levels\":[{\"code\":\"L1\",\"minimumGrowth\":0}]}");
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,?,?,?,?)",poisonTenant,"poison","poison","坏数据会员","L1");
        jdbc.update("INSERT INTO member_cycle_policy(tenant_id,version,effective_from,policy_json) VALUES(?,1,'2000-01-01 00:00:00.001',?)",tenant,"{\"version\":1,\"effectiveFrom\":\"2000-01-01T00:00:00.001Z\",\"periodDays\":30,\"levels\":[{\"code\":\"L1\",\"minimumGrowth\":0}]}");
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,?,?,?,?)",tenant,"healthy","healthy","正常会员","L1");
        for(int i=0;i<50&&assessed()==0;i++)assertDoesNotThrow(cycles::tick);
        assertEquals(1,assessed());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM member_cycle_account WHERE tenant_id=?",Integer.class,poisonTenant));
    }
    private int assessed() {return jdbc.queryForObject("SELECT COUNT(*) FROM member_cycle_account WHERE tenant_id=? AND member_id='healthy'",Integer.class,tenant);}
}
