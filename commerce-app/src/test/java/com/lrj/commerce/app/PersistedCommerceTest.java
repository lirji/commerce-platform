package com.lrj.commerce.app;

import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.Actor;
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
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL与HTTP验证，不用Mock证明事务或身份隔离。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersistedCommerceTest {
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
    @Autowired Commands commands;
    @Autowired com.lrj.commerce.payment.api.PaymentApi payments;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private String tenant,admin,member,other;
    record Reply(int status,JsonNode body) { }

    @BeforeEach void identities() {
        tenant="t-"+UUID.randomUUID();admin=token(tenant,"admin","ADMIN");member=token(tenant,"buyer","MEMBER");other=token("other-"+UUID.randomUUID(),"buyer","MEMBER");
    }
    private String token(String tenant,String actor,String role) {
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
    private void seed() throws Exception {
        post("/v1/admin/members",admin,"member",Map.of("memberId","m1","actorId","buyer","displayName","测试会员","memberLevel","VIP"));
        post("/v1/admin/merchants",admin,"merchant",Map.of("merchantId","merchant1","name","测试商家"));
        post("/v1/admin/stores",admin,"store",Map.of("storeId","store1","merchantId","merchant1","name","测试店铺"));
        post("/v1/admin/skus",admin,"sku",Map.of("skuId","sku1","storeId","store1","title","测试商品","unitPrice","25.00"));
    }
    private Map<String,Object> draft(String id,long version,String discount) {
        return Map.of("campaignId",id,"version",version,"storeId","store1","name","会员活动","validFrom",Instant.now().minusSeconds(60).toString(),"validTo",Instant.now().plusSeconds(3600).toString(),"minimumSpend","20.00","discountAmount",discount,"rule",Map.of("kind","COMPARE","field","memberLevel","operator","EQ","valueType","TEXT","value","VIP"));
    }
    private Object basket(int quantity) {return Map.of("storeId","store1","items",List.of(Map.of("skuId","sku1","quantity",quantity)));}
    @Test void authenticationAndPermissionsFailClosed() throws Exception {
        assertEquals(401,call("GET","/v1/me",null,null,null).status());
        assertEquals(401,call("GET","/v1/me","invalid",null,null).status());
        assertEquals(403,call("GET","/v1/admin/members",member,null,null).status());
        jdbc.update("UPDATE platform_credential SET active=FALSE WHERE token_hash=?",JsonCodec.hash(member));
        assertEquals(401,call("GET","/v1/me",member,null,null).status());
    }
    @Test void publishesAndPersistsAuthoritativeQuoteWithReplay() throws Exception {
        seed();post("/v1/admin/campaigns",admin,"campaign",draft("c1",1,"3.00"));
        post("/v1/admin/campaigns/c1/1/publish",admin,"publish",Map.of("expectedVersion",0));
        var q=post("/v1/quotes",member,"q1",basket(2));
        assertEquals("50.00",q.path("gross").asString());assertEquals("47.00",q.path("payable").asString());
        assertEquals("c1",q.path("campaign").path("campaignId").asString());
        assertEquals(q,post("/v1/quotes",member,"q1",basket(2)));
        assertEquals(q,call("GET","/v1/quotes/"+q.path("quoteId").asString(),member,null,null).body());
        assertEquals(409,call("POST","/v1/quotes",member,"q1",basket(3)).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trade_quote WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void tenantAndMemberOwnershipAreEnforcedInSql() throws Exception {
        seed();var q=post("/v1/quotes",member,"q1",basket(1));
        assertEquals(404,call("GET","/v1/quotes/"+q.path("quoteId").asString(),other,null,null).status());
        String second=token(tenant,"other-buyer","MEMBER");
        post("/v1/admin/members",admin,"member2",Map.of("memberId","m2","actorId","other-buyer","displayName","第二会员","memberLevel","VIP"));
        assertEquals(404,call("GET","/v1/quotes/"+q.path("quoteId").asString(),second,null,null).status());
        assertEquals(404,call("GET","/v1/catalog?storeId=store1",other,null,null).status());
    }
    @Test void simultaneousDuplicateRequestsHaveOneEffect() throws Exception {
        seed();var pool=Executors.newFixedThreadPool(6);
        try {
            List<Callable<Reply>> tasks=new ArrayList<>();
            for(int i=0;i<6;i++) tasks.add(()->call("POST","/v1/quotes",member,"parallel",basket(1)));
            var results=pool.invokeAll(tasks);Set<String> ids=new HashSet<>();
            for(var future:results) {var result=future.get();assertEquals(200,result.status(),result.body().toString());ids.add(result.body().path("quoteId").asString());}
            assertEquals(1,ids.size());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trade_quote WHERE tenant_id=?",Integer.class,tenant));
        } finally {pool.shutdownNow();}
    }
    @Test void failureRollsBackEffectsCommandAndAuditTogether() {
        var actor=new Actor(tenant,"admin",Actor.Role.ADMIN);
        assertThrows(IllegalStateException.class,()->commands.run(actor,"test.rollback","rollback",Map.of("value",1),String.class,()->{
            jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name) VALUES(?,?,?)",tenant,"rollback","不应提交");
            throw new IllegalStateException("故障注入");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM merchant_record WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_command WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_audit WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void snapshotSurvivesCampaignChangeAndNewVersionIsAtomic() throws Exception {
        seed();post("/v1/admin/campaigns",admin,"c1",draft("c1",1,"3.00"));
        post("/v1/admin/campaigns/c1/1/publish",admin,"p1",Map.of("expectedVersion",0));
        var old=post("/v1/quotes",member,"q1",basket(1));
        post("/v1/admin/campaigns",admin,"c2",draft("c1",2,"5.00"));
        post("/v1/admin/campaigns/c1/2/publish",admin,"p2",Map.of("expectedVersion",0));
        assertEquals("20.00",post("/v1/quotes",member,"q2",basket(1)).path("payable").asString());
        assertEquals(old,call("GET","/v1/quotes/"+old.path("quoteId").asString(),member,null,null).body());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign WHERE tenant_id=? AND status='PUBLISHED'",Integer.class,tenant));
        assertEquals(409,call("POST","/v1/admin/campaigns/c1/2/pause",admin,"bad-version",Map.of("expectedVersion",0)).status());
    }
    @Test void rejectsPriceInjectionInvalidBodiesAndMissingKeys() throws Exception {
        seed();assertEquals(400,call("POST","/v1/quotes",member,null,basket(1)).status());
        assertEquals(400,call("POST","/v1/quotes",member,"price",Map.of("storeId","store1","items",List.of(Map.of("skuId","sku1","quantity",1,"unitPrice","0.01")))).status());
        assertEquals(413,call("POST","/v1/quotes",member,"huge",Map.of("padding","x".repeat(66000))).status());
        assertEquals(400,call("GET","/v1/catalog?storeId=store1&limit=101",member,null,null).status());
    }
    private void stock(String sku,int quantity) throws Exception {
        post("/v1/admin/inventory/receipts",admin,UUID.randomUUID().toString(),Map.of("storeId","store1","skuId",sku,"quantity",quantity));
    }
    private Object orderInput(JsonNode quote) {
        return Map.of("quoteId",quote.path("quoteId").asString(),"address",Map.of("recipient","收货测试","phone","13800000000","detail","隔离测试地址123"));
    }
    private long stockValue(String column) {
        // 列名仅来自本测试常量，业务Mapper不允许动态客户端列名。
        return jdbc.queryForObject("SELECT "+column+" FROM inventory_stock WHERE tenant_id=? AND sku_id='sku1'",Long.class,tenant);
    }
    @Test void orderReservesOnceAndCancellationReleasesOnce() throws Exception {
        seed();stock("sku1",3);var q=post("/v1/quotes",member,"quote",basket(2));
        var order=post("/v1/orders",member,"order",orderInput(q));String id=order.path("orderId").asString();
        assertEquals("PENDING_PAYMENT",order.path("status").asString());
        assertEquals(order,post("/v1/orders",member,"order",orderInput(q)));
        assertEquals(1,stockValue("available"));assertEquals(2,stockValue("held"));
        assertEquals(409,call("POST","/v1/orders",member,"different-key",orderInput(q)).status());
        byte[] encrypted=jdbc.queryForObject("SELECT address_cipher FROM order_record WHERE tenant_id=? AND order_id=?",byte[].class,tenant,id);
        assertFalse(new String(encrypted,java.nio.charset.StandardCharsets.UTF_8).contains("隔离测试地址"));
        assertFalse(order.has("address"));
        assertEquals("CANCELLED",post("/v1/orders/"+id+"/cancel",member,"cancel",null).path("status").asString());
        post("/v1/orders/"+id+"/cancel",member,"cancel-again",null);
        assertEquals(3,stockValue("available"));assertEquals(0,stockValue("held"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(404,call("GET","/v1/orders/"+id,other,null,null).status());
        assertEquals(404,call("POST","/v1/orders/"+id+"/cancel",other,"foreign-cancel",null).status());
    }
    @Test void simultaneousOrdersCannotOversellOrConsumeOneQuoteTwice() throws Exception {
        seed();stock("sku1",1);var q1=post("/v1/quotes",member,"q1",basket(1));var q2=post("/v1/quotes",member,"q2",basket(1));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var latch=new CountDownLatch(1);
            var a=pool.submit(()->{latch.await();return call("POST","/v1/orders",member,"o1",orderInput(q1));});
            var b=pool.submit(()->{latch.await();return call("POST","/v1/orders",member,"o2",orderInput(q2));});latch.countDown();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(),b.get()).map(Reply::status).sorted().toList());
        }
        assertEquals(0,stockValue("available"));assertEquals(1,stockValue("held"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_record WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trade_quote WHERE tenant_id=? AND consumed_order_id IS NOT NULL",Integer.class,tenant));
    }
    @Test void parallelDuplicateOrdersHaveSingleEffect() throws Exception {
        seed();stock("sku1",10);var q=post("/v1/quotes",member,"q",basket(2));
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Callable<Reply>> tasks=new ArrayList<>();for(int i=0;i<4;i++)tasks.add(()->call("POST","/v1/orders",member,"o",orderInput(q)));
            Set<String> ids=new HashSet<>();for(var f:pool.invokeAll(tasks)){var r=f.get();assertEquals(200,r.status(),r.body().toString());ids.add(r.body().path("orderId").asString());}assertEquals(1,ids.size());
        }
        assertEquals(8,stockValue("available"));assertEquals(2,stockValue("held"));
    }
    @Test void failedSecondSkuRollsBackQuoteInventoryAndEventsAndCanRetry() throws Exception {
        seed();stock("sku1",2);
        post("/v1/admin/skus",admin,"sku2",Map.of("skuId","sku2","storeId","store1","title","第二商品","unitPrice","10.00"));
        var q=post("/v1/quotes",member,"q",Map.of("storeId","store1","items",List.of(Map.of("skuId","sku1","quantity",1),Map.of("skuId","sku2","quantity",1))));
        assertEquals(409,call("POST","/v1/orders",member,"o",orderInput(q)).status());
        assertEquals(2,stockValue("available"));assertEquals(0,stockValue("held"));
        for(String table:List.of("order_record","inventory_hold","platform_event")) assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trade_quote WHERE tenant_id=? AND consumed_order_id IS NOT NULL",Integer.class,tenant));
        stock("sku2",1);assertEquals("PENDING_PAYMENT",post("/v1/orders",member,"o",orderInput(q)).path("status").asString());
    }
    @Test void expiredQuoteCannotBecomeOrder() throws Exception {
        seed();stock("sku1",1);var q=post("/v1/quotes",member,"q",basket(1));
        jdbc.update("UPDATE trade_quote SET expires_at=? WHERE tenant_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(10)),tenant);
        assertEquals(409,call("POST","/v1/orders",member,"o",orderInput(q)).status());assertEquals(1,stockValue("available"));
    }
    @Test void freeOrderConfirmsStockWithoutPretendingChannelPayment() throws Exception {
        seed();stock("sku1",1);post("/v1/admin/campaigns",admin,"campaign",draft("free",1,"100.00"));
        post("/v1/admin/campaigns/free/1/publish",admin,"publish",Map.of("expectedVersion",0));
        var q=post("/v1/quotes",member,"q",basket(1));var order=post("/v1/orders",member,"o",orderInput(q));
        assertEquals("PAID",order.path("status").asString());assertEquals("NO_PAYMENT_REQUIRED",order.path("paymentKind").asString());
        assertEquals(0,stockValue("held"));assertEquals(1,stockValue("sold"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='order.paid.v1'",Integer.class,tenant));
        assertEquals(409,call("POST","/v1/orders/"+order.path("orderId").asString()+"/cancel",member,"cancel",null).status());
    }
    private JsonNode pendingOrder() throws Exception {
        seed();stock("sku1",2);return post("/v1/orders",member,"o",orderInput(post("/v1/quotes",member,"q",basket(1))));
    }
    private JsonNode startPayment(JsonNode order) throws Exception {return post("/v1/orders/"+order.path("orderId").asString()+"/payments",member,"pay",null);}
    private void sandbox(JsonNode payment,String status) throws Exception {post("/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",admin,"fact-"+status,Map.of("status",status));}
    private JsonNode reconcile(JsonNode order) throws Exception {return post("/v1/orders/"+order.path("orderId").asString()+"/payment/reconcile",member,null,null);}
    private JsonNode readOrder(JsonNode order) throws Exception {return call("GET","/v1/orders/"+order.path("orderId").asString(),member,null,null).body();}
    private void pump() throws Exception {post("/v1/admin/events/pump",admin,null,null);}
    @Test void unknownPaymentCancellationKeepsStockUntilClosedProof() throws Exception {
        var order=pendingOrder();var payment=startPayment(order);assertEquals("UNKNOWN",payment.path("status").asString());
        assertEquals(payment,startPayment(order));
        post("/v1/orders/"+order.path("orderId").asString()+"/cancel",member,"cancel",null);
        assertEquals("UNKNOWN",reconcile(order).path("status").asString());pump();
        assertEquals("CLOSING",readOrder(order).path("status").asString());assertEquals(1,stockValue("held"));
        sandbox(payment,"OPEN");assertEquals("CLOSED",reconcile(order).path("status").asString());pump();
        assertEquals("CANCELLED",readOrder(order).path("status").asString());assertEquals(2,stockValue("available"));assertEquals(0,stockValue("held"));
        assertEquals(409,call("POST","/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",admin,"late-paid",Map.of("status","PAID")).status());
    }
    @Test void paidFactWinsCancellationAndDuplicateDeliveryDoesNotDoubleConfirm() throws Exception {
        var order=pendingOrder();var payment=startPayment(order);sandbox(payment,"PAID");
        post("/v1/orders/"+order.path("orderId").asString()+"/cancel",member,"cancel",null);
        assertEquals("PAID",reconcile(order).path("status").asString());pump();
        assertNotNull(jdbc.queryForObject("SELECT evidence_json FROM payment_attempt WHERE tenant_id=?",String.class,tenant));
        var paid=readOrder(order);assertEquals("PAID",paid.path("status").asString());assertEquals(1,stockValue("sold"));
        // 模拟ACK丢失导致同事件再次可见，Inbox必须阻止重复业务副作用。
        jdbc.update("UPDATE platform_event SET status='PENDING',available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='payment.paid.v1'",tenant);
        pump();assertEquals(paid,readOrder(order));assertEquals(1,stockValue("sold"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(409,call("POST","/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",admin,"reverse",Map.of("status","OPEN")).status());
    }
    @Test void concurrentCloseAndChannelSuccessChooseOneDurableFact() throws Exception {
        var order=pendingOrder();var payment=startPayment(order);sandbox(payment,"OPEN");
        post("/v1/orders/"+order.path("orderId").asString()+"/cancel",member,"cancel",null);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var latch=new CountDownLatch(1);
            var close=pool.submit(()->{latch.await();return reconcile(order);});
            var paid=pool.submit(()->{latch.await();return call("POST","/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",admin,"race-paid",Map.of("status","PAID"));});
            latch.countDown();var result=close.get();assertTrue(Set.of("PAID","CLOSED").contains(result.path("status").asString()));assertTrue(Set.of(200,409).contains(paid.get().status()));
        }
        reconcile(order);pump();String state=readOrder(order).path("status").asString();assertTrue(Set.of("PAID","CANCELLED").contains(state));
        assertEquals(0,stockValue("held"));assertEquals(state.equals("PAID")?1:0,stockValue("sold"));
    }
    @Test void mismatchedChannelAmountCannotGeneratePaidEvent() throws Exception {
        var order=pendingOrder();var payment=startPayment(order);sandbox(payment,"PAID");
        jdbc.update("UPDATE payment_sandbox_ledger SET amount=amount+1 WHERE tenant_id=?",tenant);
        assertEquals(409,call("POST","/v1/orders/"+order.path("orderId").asString()+"/payment/reconcile",member,null,null).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='payment.paid.v1'",Integer.class,tenant));
        assertEquals(1,stockValue("held"));
    }
    @Test void failedConsumerRollsBackInboxThenIsolatesAndAuditedRetryRecovers() throws Exception {
        var order=pendingOrder();var payment=startPayment(order);sandbox(payment,"PAID");reconcile(order);
        String event=jdbc.queryForObject("SELECT event_id FROM platform_event WHERE tenant_id=? AND event_type='payment.paid.v1'",String.class,tenant);
        String original=jdbc.queryForObject("SELECT payload_json FROM platform_event WHERE event_id=?",String.class,event);
        jdbc.update("UPDATE platform_event SET payload_json='{}' WHERE event_id=?",event);
        for(int i=0;i<5;i++){pump();jdbc.update("UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(3) WHERE event_id=?",event);}
        assertEquals("ISOLATED",jdbc.queryForObject("SELECT status FROM platform_event WHERE event_id=?",String.class,event));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox WHERE tenant_id=?",Integer.class,tenant));assertEquals(1,stockValue("held"));
        assertEquals(403,call("POST","/v1/admin/events/"+event+"/retry",member,"retry",null).status());
        jdbc.update("UPDATE platform_event SET payload_json=? WHERE event_id=?",original,event);
        post("/v1/admin/events/"+event+"/retry",admin,"retry",null);pump();
        assertEquals("PAID",readOrder(order).path("status").asString());assertEquals(1,stockValue("sold"));
    }
    @Test void expiredPaymentInProgressCannotReleaseUnknownFunds() throws Exception {
        var order=pendingOrder();startPayment(order);
        jdbc.update("UPDATE order_record SET expires_at=? WHERE tenant_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),tenant);
        assertEquals(1,post("/v1/admin/orders/expire",admin,"expire",null).asInt());
        assertEquals("CLOSING",readOrder(order).path("status").asString());assertEquals(1,stockValue("held"));
        assertEquals(0,post("/v1/admin/orders/expire",admin,"expire-again",null).asInt());
    }
    @Test void paymentAndSandboxPermissionsAreTenantScoped() throws Exception {
        var order=pendingOrder();var payment=startPayment(order);String id=order.path("orderId").asString();
        assertEquals(404,call("GET","/v1/orders/"+id+"/payment",other,null,null).status());
        assertEquals(404,call("POST","/v1/orders/"+id+"/payment/reconcile",other,null,null).status());
        assertEquals(403,call("POST","/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",member,"fact",Map.of("status","PAID")).status());
    }
    @Test void backgroundReconciliationRecoversAfterChannelSuccessWithoutClientReturn() throws Exception {
        var order=pendingOrder();var payment=startPayment(order);sandbox(payment,"PAID");
        // 后台有界轮转，模拟浏览器关闭后没有调用reconcile；不改动其他租户数据。
        for(int i=0;i<100;i++) {
            payments.tick();
            String status=jdbc.queryForObject("SELECT status FROM payment_attempt WHERE tenant_id=?",String.class,tenant);
            if(status.equals("PAID"))break;
        }
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM payment_attempt WHERE tenant_id=?",String.class,tenant));
        pump();assertEquals("PAID",readOrder(order).path("status").asString());
    }
    @Test void expiryWithoutPaymentCanReleaseAndRejectLaterPayment() throws Exception {
        var order=pendingOrder();jdbc.update("UPDATE order_record SET expires_at=? WHERE tenant_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),tenant);
        post("/v1/admin/orders/expire",admin,"expire",null);
        assertEquals("CANCELLED",readOrder(order).path("status").asString());assertEquals(2,stockValue("available"));
        assertEquals(409,call("POST","/v1/orders/"+order.path("orderId").asString()+"/payments",member,"late",null).status());
    }
    @Test void everyBusinessTableAndColumnHasComments() {
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' AND TABLE_COMMENT=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' AND COLUMN_COMMENT=''",Integer.class));
    }
}
