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
    @Autowired com.lrj.commerce.payment.api.RefundApi refunds;
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
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type LIKE 'order.%'",Integer.class,tenant));
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
        for(String table:List.of("order_record","inventory_hold")) assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type LIKE 'order.%'",Integer.class,tenant));
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
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox WHERE tenant_id=? AND consumer_id='order-payment-v1'",Integer.class,tenant));
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
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox WHERE tenant_id=? AND consumer_id='order-payment-v1'",Integer.class,tenant));assertEquals(1,stockValue("held"));
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
    private JsonNode payOrder(JsonNode order) throws Exception {var payment=startPayment(order);sandbox(payment,"PAID");reconcile(order);pump();return readOrder(order);}
    private void ship(JsonNode order) throws Exception {post("/v1/admin/fulfillments/"+order.path("orderId").asString()+"/ship",admin,"ship",Map.of("trackingNo","SANDBOX-TRACKING"));}
    private JsonNode requestReturn(JsonNode order,int quantity,String key) throws Exception {return post("/v1/aftersales",member,key,Map.of("orderId",order.path("orderId").asString(),"reason","隔离测试退货","items",List.of(Map.of("skuId","sku1","quantity",quantity))));}
    private JsonNode approve(JsonNode request,String key) throws Exception {return post("/v1/admin/aftersales/"+request.path("caseId").asString()+"/approve",admin,key,null);}
    private JsonNode finishRefund(JsonNode request,String key) throws Exception {
        String refund=request.path("refundId").asString();post("/v1/admin/sandbox/refunds/"+refund+"/success",admin,key,null);
        post("/v1/admin/refunds/"+refund+"/reconcile",admin,null,null);pump();
        return call("GET","/v1/aftersales/"+request.path("caseId").asString(),member,null,null).body();
    }
    @Test void shipmentAndDeliveryAdvanceOrderAndRejectTrackingReplacement() throws Exception {
        var order=payOrder(pendingOrder());ship(order);assertEquals("FULFILLING",readOrder(order).path("status").asString());
        assertEquals(409,call("POST","/v1/admin/fulfillments/"+order.path("orderId").asString()+"/ship",admin,"replace",Map.of("trackingNo","CHANGED")).status());
        post("/v1/admin/fulfillments/"+order.path("orderId").asString()+"/deliver",admin,"deliver",null);
        assertEquals("COMPLETED",readOrder(order).path("status").asString());
        post("/v1/admin/fulfillments/"+order.path("orderId").asString()+"/deliver",admin,"duplicate-delivery",null);
        assertEquals(404,call("GET","/v1/orders/"+order.path("orderId").asString()+"/fulfillment",other,null,null).status());
    }
    @Test void beforeShipmentRefundBlocksShippingAndWaitsForRealRefundFact() throws Exception {
        var order=payOrder(pendingOrder());var request=requestReturn(order,1,"r");String id=request.path("caseId").asString();
        assertEquals(409,call("POST","/v1/admin/fulfillments/"+order.path("orderId").asString()+"/ship",admin,"blocked",Map.of("trackingNo","TRACK")).status());
        var refunding=approve(request,"approve");assertEquals("REFUNDING",refunding.path("status").asString());assertEquals(2,stockValue("available"));assertEquals(0,stockValue("sold"));
        var unknown=post("/v1/admin/refunds/"+refunding.path("refundId").asString()+"/reconcile",admin,null,null);assertEquals("UNKNOWN",unknown.path("status").asString());
        assertEquals("REFUNDING",call("GET","/v1/aftersales/"+id,member,null,null).body().path("status").asString());
        assertEquals("COMPLETED",finishRefund(refunding,"refund").path("status").asString());
        assertEquals("CANCELLED",call("GET","/v1/orders/"+order.path("orderId").asString()+"/fulfillment",member,null,null).body().path("status").asString());
        // 模拟退款事件重复投递，库存与累计金额都不能再增加。
        jdbc.update("UPDATE platform_event SET status='PENDING',available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='refund.succeeded.v1'",tenant);pump();
        assertEquals(2,stockValue("available"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void partialReturnsUseOriginalAllocationAndNeverOverRefund() throws Exception {
        seed();stock("sku1",3);post("/v1/admin/campaigns",admin,"campaign",draft("partial",1,"1.00"));post("/v1/admin/campaigns/partial/1/publish",admin,"publish",Map.of("expectedVersion",0));
        var order=post("/v1/orders",member,"o",orderInput(post("/v1/quotes",member,"q",basket(3))));payOrder(order);ship(order);
        String[] amounts={"24.66","24.67","24.67"};
        for(int n=0;n<3;n++){
            var request=requestReturn(order,1,"return-"+n);assertEquals(amounts[n],request.path("refundAmount").asString());
            var approved=approve(request,"approve-"+n);assertEquals("WAIT_RETURN",approved.path("status").asString());assertEquals(3-n,stockValue("sold"));
            var refunding=post("/v1/admin/aftersales/"+request.path("caseId").asString()+"/receive-return",admin,"receive-"+n,null);
            assertEquals("REFUNDING",refunding.path("status").asString());assertEquals("COMPLETED",finishRefund(refunding,"refund-"+n).path("status").asString());
        }
        assertEquals(new java.math.BigDecimal("74.00"),jdbc.queryForObject("SELECT SUM(amount) FROM payment_refund WHERE tenant_id=?",java.math.BigDecimal.class,tenant));
        assertEquals(3,stockValue("available"));assertEquals(0,stockValue("sold"));
        assertEquals(400,call("POST","/v1/aftersales",member,"over-return",Map.of("orderId",order.path("orderId").asString(),"reason","多退","items",List.of(Map.of("skuId","sku1","quantity",1)))).status());
    }
    @Test void onlyOneActiveCaseAndRejectionReleasesShipmentHold() throws Exception {
        var order=payOrder(pendingOrder());var request=requestReturn(order,1,"r");
        assertEquals(409,call("POST","/v1/aftersales",member,"second",Map.of("orderId",order.path("orderId").asString(),"reason","重复申请","items",List.of(Map.of("skuId","sku1","quantity",1)))).status());
        post("/v1/admin/aftersales/"+request.path("caseId").asString()+"/reject",admin,"reject",null);ship(order);
        assertEquals("FULFILLING",readOrder(order).path("status").asString());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void returnApprovalCannotBypassPhysicalReceiptAndRejectsOverReturn() throws Exception {
        var order=payOrder(pendingOrder());ship(order);var request=requestReturn(order,1,"r");
        assertEquals(409,call("POST","/v1/admin/aftersales/"+request.path("caseId").asString()+"/receive-return",admin,"early",null).status());
        approve(request,"approve");assertEquals(1,stockValue("sold"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(403,call("POST","/v1/admin/aftersales/"+request.path("caseId").asString()+"/receive-return",member,"forged",null).status());
        assertEquals(404,call("GET","/v1/aftersales/"+request.path("caseId").asString(),other,null,null).status());
    }
    @Test void zeroValueReturnCompletesWithoutChannelRefund() throws Exception {
        seed();stock("sku1",1);post("/v1/admin/campaigns",admin,"c",draft("free",1,"100.00"));post("/v1/admin/campaigns/free/1/publish",admin,"p",Map.of("expectedVersion",0));
        var order=post("/v1/orders",member,"o",orderInput(post("/v1/quotes",member,"q",basket(1))));var request=requestReturn(order,1,"r");var approved=approve(request,"a");pump();
        assertEquals("COMPLETED",call("GET","/v1/aftersales/"+request.path("caseId").asString(),member,null,null).body().path("status").asString());
        assertEquals("NO_PAYMENT_REQUIRED",jdbc.queryForObject("SELECT provider FROM payment_refund WHERE tenant_id=?",String.class,tenant));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund_sandbox WHERE tenant_id=?",Integer.class,tenant));assertEquals(1,stockValue("available"));
    }
    @Test void refundEvidenceMismatchKeepsCaseOpenAndDoesNotRestoreInventoryTwice() throws Exception {
        var order=payOrder(pendingOrder());var approved=approve(requestReturn(order,1,"r"),"a");String id=approved.path("refundId").asString();
        post("/v1/admin/sandbox/refunds/"+id+"/success",admin,"success",null);jdbc.update("UPDATE payment_refund_sandbox SET amount=amount+1 WHERE tenant_id=?",tenant);
        assertEquals(409,call("POST","/v1/admin/refunds/"+id+"/reconcile",admin,null,null).status());
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM payment_refund WHERE tenant_id=?",String.class,tenant));assertEquals(2,stockValue("available"));
        assertEquals("REFUNDING",call("GET","/v1/aftersales/"+approved.path("caseId").asString(),member,null,null).body().path("status").asString());
    }
    @Test void concurrentRefundReservationsCannotExceedReceivedAmount() throws Exception {
        var order=payOrder(pendingOrder());String orderId=order.path("orderId").asString();var actor=new Actor(tenant,"admin",Actor.Role.ADMIN);
        try(var pool=Executors.newFixedThreadPool(2)){
            var latch=new CountDownLatch(1);List<Callable<Boolean>> tasks=new ArrayList<>();
            for(int n=0;n<2;n++){String caseId="capacity-"+n;tasks.add(()->{latch.await();try{commands.run(actor,"test.refund.capacity",caseId,caseId,String.class,()->{refunds.request(tenant,caseId,orderId,"25.00");return "ok";});return true;}catch(com.lrj.commerce.kernel.DomainException conflict){return false;}});}
            var first=pool.submit(tasks.get(0));var second=pool.submit(tasks.get(1));latch.countDown();assertNotEquals(first.get(),second.get());
        }
        assertEquals(new java.math.BigDecimal("25.00"),jdbc.queryForObject("SELECT refund_reserved FROM payment_attempt WHERE tenant_id=?",java.math.BigDecimal.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void simultaneousAftersaleApplicationsLeaveOneActiveCase() throws Exception {
        var order=payOrder(pendingOrder());Object input=Map.of("orderId",order.path("orderId").asString(),"reason","并发申请","items",List.of(Map.of("skuId","sku1","quantity",1)));
        try(var pool=Executors.newFixedThreadPool(2)){
            var latch=new CountDownLatch(1);var a=pool.submit(()->{latch.await();return call("POST","/v1/aftersales",member,"r1",input);});var b=pool.submit(()->{latch.await();return call("POST","/v1/aftersales",member,"r2",input);});latch.countDown();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(),b.get()).map(Reply::status).sorted().toList());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM aftersales_case WHERE tenant_id=?",Integer.class,tenant));
    }
    private void audience(String id,long version,List<String> members) throws Exception {
        post("/v1/admin/audiences",admin,"audience-"+id+"-"+version,Map.of("audienceId",id,"version",version,"name","可信人群","source","approved-test-import","watermark",Instant.now().minusSeconds(1).toString(),"validUntil",Instant.now().plusSeconds(3600).toString(),"memberIds",members));
    }
    private Map<String,Object> governed(String id,Object policy) {
        var result=new HashMap<>(draft(id,1,"3.00"));result.put("policy",policy);return result;
    }
    private void approveAndPublish(String id) throws Exception {
        post("/v1/admin/campaigns/"+id+"/1/submit",admin,"submit-"+id,Map.of("expectedVersion",0));
        post("/v1/admin/campaigns/"+id+"/1/approve",admin,"approve-"+id,Map.of("expectedVersion",1));
        post("/v1/admin/campaigns/"+id+"/1/publish",admin,"publish-"+id,Map.of("expectedVersion",2));
    }
    @Test void governedCampaignRequiresApprovalAndBindsAudienceSource() throws Exception {
        seed();audience("vip",1,List.of("m1"));post("/v1/admin/campaigns",admin,"c",governed("governed",Map.of("audience",Map.of("id","vip","version",1))));
        assertEquals(409,call("POST","/v1/admin/campaigns/governed/1/publish",admin,"skip-review",Map.of("expectedVersion",0)).status());approveAndPublish("governed");
        var quote=post("/v1/quotes",member,"q",basket(1));assertEquals("22.00",quote.path("payable").asString());assertEquals("HIT",quote.path("sources").get(0).path("match").asString());assertEquals("approved-test-import",quote.path("sources").get(0).path("source").asString());
        audience("vip",2,List.of());assertEquals("22.00",post("/v1/quotes",member,"q2",basket(1)).path("payable").asString());
        assertEquals(1,quote.path("sources").get(0).path("version").asInt());
    }
    @Test void staleAudienceIsUnknownAndCannotBeRescuedByNot() throws Exception {
        seed();audience("fresh",1,List.of("m1"));var campaign=governed("fresh",Map.of("audience",Map.of("id","fresh","version",1)));
        campaign.put("rule",Map.of("kind","NOT","children",List.of(Map.of("kind","COMPARE","field","memberLevel","operator","EQ","valueType","TEXT","value","BASIC"))));
        post("/v1/admin/campaigns",admin,"c",campaign);approveAndPublish("fresh");var old=post("/v1/quotes",member,"q1",basket(1));assertEquals("22.00",old.path("payable").asString());
        jdbc.update("UPDATE marketing_audience_snapshot SET watermark=?,valid_until=? WHERE tenant_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(120)),java.sql.Timestamp.from(Instant.now().minusSeconds(60)),tenant);
        var current=post("/v1/quotes",member,"q2",basket(1));assertEquals("25.00",current.path("payable").asString());assertEquals("UNKNOWN",current.path("sources").get(0).path("match").asString());assertEquals("CONDITION_UNKNOWN",current.path("trace").get(0).path("reason").asString());
        assertEquals(old,call("GET","/v1/quotes/"+old.path("quoteId").asString(),member,null,null).body());
    }
    @Test void audienceMissAndCrossTenantReferenceFailClosed() throws Exception {
        seed();audience("empty",1,List.of());post("/v1/admin/campaigns",admin,"c",governed("miss",Map.of("audience",Map.of("id","empty","version",1))));approveAndPublish("miss");
        var quote=post("/v1/quotes",member,"q",basket(1));assertEquals("25.00",quote.path("payable").asString());assertEquals("MISS",quote.path("sources").get(0).path("match").asString());
        assertEquals(404,call("POST","/v1/admin/campaigns",admin,"missing",governed("missing",Map.of("audience",Map.of("id","other-tenant-asset","version",1)))).status());
        assertEquals(403,call("GET","/v1/admin/audiences",member,null,null).status());
    }
    @Test void reusableRuleMustBePublishedAndCannotChangeFrozenCampaign() throws Exception {
        seed();Object rule=Map.of("kind","COMPARE","field","orderAmount","operator","GTE","valueType","DECIMAL","value","20.00");
        post("/v1/admin/rules",admin,"rule1",Map.of("ruleId","spend","version",1,"name","消费门槛","rule",rule));
        var campaign=governed("rule-bound",Map.of("rule",Map.of("id","spend","version",1)));campaign.remove("rule");
        assertEquals(409,call("POST","/v1/admin/campaigns",admin,"before-publish",campaign).status());
        post("/v1/admin/rules/spend/1/publish",admin,"rule-publish",null);post("/v1/admin/campaigns",admin,"c",campaign);approveAndPublish("rule-bound");
        assertEquals("22.00",post("/v1/quotes",member,"q",basket(1)).path("payable").asString());
        post("/v1/admin/rules",admin,"rule2",Map.of("ruleId","spend","version",2,"name","新门槛","rule",Map.of("kind","COMPARE","field","orderAmount","operator","GTE","valueType","DECIMAL","value","100.00")));
        post("/v1/admin/rules/spend/2/publish",admin,"rule-publish2",null);
        assertEquals("22.00",post("/v1/quotes",member,"q2",basket(1)).path("payable").asString());
        assertEquals(400,call("POST","/v1/admin/rules",admin,"untrusted",Map.of("ruleId","bad","version",1,"name","未知字段","rule",Map.of("kind","COMPARE","field","clientVip","operator","EQ","valueType","TEXT","value","yes"))).status());
    }
    @Test void governanceRejectAndOptimisticVersionCannotBeBypassed() throws Exception {
        seed();audience("vip",1,List.of("m1"));post("/v1/admin/campaigns",admin,"c",governed("rejected",Map.of("audience",Map.of("id","vip","version",1))));
        post("/v1/admin/campaigns/rejected/1/submit",admin,"submit",Map.of("expectedVersion",0));
        assertEquals(409,call("POST","/v1/admin/campaigns/rejected/1/approve",admin,"stale",Map.of("expectedVersion",0)).status());
        post("/v1/admin/campaigns/rejected/1/reject",admin,"reject",Map.of("expectedVersion",1));
        assertEquals(409,call("POST","/v1/admin/campaigns/rejected/1/publish",admin,"publish",Map.of("expectedVersion",2)).status());
        assertEquals(403,call("POST","/v1/admin/campaigns/rejected/1/approve",member,"bad-role",Map.of("expectedVersion",2)).status());
    }
    private void couponDefinition(String discount,boolean stackable,int quota) throws Exception {
        post("/v1/admin/coupon-definitions",admin,"definition",Map.of("definitionId","coupon-def","version",1,"storeId","store1","name","测试券","minimumSpend","0.00","discountAmount",discount,"validFrom",Instant.now().minusSeconds(10).toString(),"validTo",Instant.now().plusSeconds(3600).toString(),"quota",quota,"stackable",stackable));
    }
    private JsonNode claimCoupon(String token,String key) throws Exception {return post("/v1/coupons/coupon-def/1/claim",token,key,null);}
    private Object couponBasket(JsonNode coupon,int quantity) {return Map.of("storeId","store1","items",List.of(Map.of("skuId","sku1","quantity",quantity)),"couponId",coupon.path("couponId").asString());}
    private String couponState(JsonNode coupon){return jdbc.queryForObject("SELECT status FROM benefit_coupon WHERE tenant_id=? AND coupon_id=?",String.class,tenant,coupon.path("couponId").asString());}
    @Test void couponStacksAndCancellationReleasesOnlyOnce() throws Exception {
        seed();stock("sku1",3);couponDefinition("5.00",true,10);var coupon=claimCoupon(member,"claim");
        assertEquals(coupon,claimCoupon(member,"claim-again"));assertEquals(1,jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=?",Integer.class,tenant));
        post("/v1/admin/campaigns",admin,"c",draft("stack",1,"3.00"));post("/v1/admin/campaigns/stack/1/publish",admin,"p",Map.of("expectedVersion",0));
        var q=post("/v1/quotes",member,"q",couponBasket(coupon,1));assertEquals("17.00",q.path("payable").asString());assertEquals("3.00",q.path("campaignDiscount").asString());assertEquals("5.00",q.path("coupon").path("discount").asString());
        var order=post("/v1/orders",member,"o",orderInput(q));assertEquals("HELD",couponState(coupon));
        post("/v1/orders/"+order.path("orderId").asString()+"/cancel",member,"cancel",null);assertEquals("AVAILABLE",couponState(coupon));
        post("/v1/orders/"+order.path("orderId").asString()+"/cancel",member,"cancel2",null);assertEquals("AVAILABLE",couponState(coupon));
    }
    @Test void oneCouponCannotBeReservedByTwoConcurrentOrders() throws Exception {
        seed();stock("sku1",2);couponDefinition("5.00",true,10);var coupon=claimCoupon(member,"claim");var q1=post("/v1/quotes",member,"q1",couponBasket(coupon,1));var q2=post("/v1/quotes",member,"q2",couponBasket(coupon,1));
        try(var pool=Executors.newFixedThreadPool(2)){
            var latch=new CountDownLatch(1);var a=pool.submit(()->{latch.await();return call("POST","/v1/orders",member,"o1",orderInput(q1));});var b=pool.submit(()->{latch.await();return call("POST","/v1/orders",member,"o2",orderInput(q2));});latch.countDown();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(),b.get()).map(Reply::status).sorted().toList());
        }
        assertEquals(1,stockValue("held"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon_hold WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trade_quote WHERE tenant_id=? AND consumed_order_id IS NOT NULL",Integer.class,tenant));
    }
    @Test void inventoryFailureRollsBackCouponAndCanRetrySameOrderCommand() throws Exception {
        seed();couponDefinition("5.00",true,10);var coupon=claimCoupon(member,"claim");var quote=post("/v1/quotes",member,"q",couponBasket(coupon,1));
        assertEquals(409,call("POST","/v1/orders",member,"o",orderInput(quote)).status());assertEquals("AVAILABLE",couponState(coupon));
        stock("sku1",1);var order=post("/v1/orders",member,"o",orderInput(quote));startPayment(order);
        post("/v1/orders/"+order.path("orderId").asString()+"/cancel",member,"cancel",null);reconcile(order);pump();assertEquals("HELD",couponState(coupon));
    }
    @Test void couponQuotaIsSafeAcrossDifferentMembers() throws Exception {
        seed();couponDefinition("5.00",true,1);String second=token(tenant,"second","MEMBER");post("/v1/admin/members",admin,"m2",Map.of("memberId","m2","actorId","second","displayName","第二会员","memberLevel","VIP"));
        try(var pool=Executors.newFixedThreadPool(2)){
            var latch=new CountDownLatch(1);var a=pool.submit(()->{latch.await();return call("POST","/v1/coupons/coupon-def/1/claim",member,"claim",null);});var b=pool.submit(()->{latch.await();return call("POST","/v1/coupons/coupon-def/1/claim",second,"claim",null);});latch.countDown();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(),b.get()).map(Reply::status).sorted().toList());
        }
        assertEquals(1,jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=?",Integer.class,tenant));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void exclusiveCouponLosesToBetterCampaignWithoutBeingConsumed() throws Exception {
        seed();couponDefinition("5.00",false,10);var coupon=claimCoupon(member,"claim");post("/v1/admin/campaigns",admin,"c",draft("better",1,"10.00"));post("/v1/admin/campaigns/better/1/publish",admin,"p",Map.of("expectedVersion",0));
        var q=post("/v1/quotes",member,"q",couponBasket(coupon,1));assertEquals("15.00",q.path("payable").asString());assertEquals("NOT_SELECTED",q.path("couponStatus").asString());assertEquals("AVAILABLE",couponState(coupon));
        assertEquals(404,call("POST","/v1/quotes",other,"foreign",couponBasket(coupon,1)).status());
    }
    @Test void partialRefundKeepsCouponUsedFullRefundReturnsAndOldEventCannotReleaseNewHold() throws Exception {
        seed();stock("sku1",3);couponDefinition("5.00",true,10);var coupon=claimCoupon(member,"claim");var order=post("/v1/orders",member,"o",orderInput(post("/v1/quotes",member,"q",couponBasket(coupon,2))));payOrder(order);ship(order);assertEquals("USED",couponState(coupon));
        for(int n=0;n<2;n++){
            var request=requestReturn(order,1,"r"+n);approve(request,"a"+n);var receiving=post("/v1/admin/aftersales/"+request.path("caseId").asString()+"/receive-return",admin,"receive"+n,null);finishRefund(receiving,"refund"+n);pump();
            assertEquals(n==0?"USED":"AVAILABLE",couponState(coupon));
        }
        var second=post("/v1/orders",member,"second-order",orderInput(post("/v1/quotes",member,"second-quote",couponBasket(coupon,1))));assertEquals("HELD",couponState(coupon));
        jdbc.update("UPDATE platform_event SET status='PENDING',available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='aftersales.completed.v1'",tenant);pump();
        assertEquals("HELD",couponState(coupon));assertEquals(second.path("orderId").asString(),jdbc.queryForObject("SELECT order_id FROM benefit_coupon WHERE tenant_id=?",String.class,tenant));
    }
    @Test void couponCanMakeOrderFreeAndReturnWithoutFabricatedPayment() throws Exception {
        seed();stock("sku1",1);couponDefinition("100.00",true,1);var coupon=claimCoupon(member,"claim");var order=post("/v1/orders",member,"o",orderInput(post("/v1/quotes",member,"q",couponBasket(coupon,1))));
        assertEquals("PAID",order.path("status").asString());assertEquals("USED",couponState(coupon));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE tenant_id=?",Integer.class,tenant));
        approve(requestReturn(order,1,"r"),"a");pump();pump();assertEquals("AVAILABLE",couponState(coupon));
    }
    private void budgetCampaign(String cap,int percentage,int funding,String maximumDiscount) throws Exception {
        audience("budget-audience",1,List.of("m1"));var campaign=new HashMap<>(draft("budget",1,maximumDiscount));
        campaign.put("policy",Map.of("audience",Map.of("id","budget-audience","version",1),"terms",Map.of("percentageBps",percentage,"platformFundingBps",funding,"budget",cap)));
        post("/v1/admin/campaigns",admin,"budget-create",campaign);approveAndPublish("budget");
    }
    private java.math.BigDecimal budgetValue(String field){return jdbc.queryForObject("SELECT "+field+" FROM marketing_budget WHERE tenant_id=? AND campaign_id='budget'",java.math.BigDecimal.class,tenant);}
    @Test void percentageCapAndFundingComponentsConserveEveryCent() throws Exception {
        seed();budgetCampaign("100.00",2500,3333,"5.00");
        var definition=new HashMap<String,Object>(Map.of("definitionId","coupon-def","version",1,"storeId","store1","name","资方券","minimumSpend","0.00","discountAmount","2.00","validFrom",Instant.now().minusSeconds(10).toString(),"validTo",Instant.now().plusSeconds(3600).toString(),"quota",10,"stackable",true));definition.put("platformFundingBps",5000);
        post("/v1/admin/coupon-definitions",admin,"definition",definition);var coupon=claimCoupon(member,"claim");var quote=post("/v1/quotes",member,"q",couponBasket(coupon,1));
        assertEquals("5.00",quote.path("campaignDiscount").asString());assertEquals("18.00",quote.path("payable").asString());
        assertEquals("2.66",quote.path("funding").path("platformFunding").asString());assertEquals("4.34",quote.path("funding").path("merchantFunding").asString());
        var line=quote.path("funding").path("items").get(0);assertEquals("5.00",line.path("campaignDiscount").asString());assertEquals("2.00",line.path("couponDiscount").asString());
        assertEquals(new java.math.BigDecimal("7.00"),new java.math.BigDecimal(line.path("platformFunding").asString()).add(new java.math.BigDecimal(line.path("merchantFunding").asString())));
    }
    @Test void budgetRaceCannotOverspendAndCancellationReleasesBeforeRetry() throws Exception {
        seed();stock("sku1",3);budgetCampaign("3.00",0,5000,"3.00");var q1=post("/v1/quotes",member,"q1",basket(1));var q2=post("/v1/quotes",member,"q2",basket(1));
        Reply first,second;
        try(var pool=Executors.newFixedThreadPool(2)){
            var latch=new CountDownLatch(1);var a=pool.submit(()->{latch.await();return call("POST","/v1/orders",member,"o1",orderInput(q1));});var b=pool.submit(()->{latch.await();return call("POST","/v1/orders",member,"o2",orderInput(q2));});latch.countDown();first=a.get();second=b.get();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(first,second).map(Reply::status).sorted().toList());
        }
        assertEquals(new java.math.BigDecimal("3.00"),budgetValue("held"));var winner=first.status()==200?first.body():second.body();
        post("/v1/orders/"+winner.path("orderId").asString()+"/cancel",member,"cancel",null);assertEquals(new java.math.BigDecimal("0.00"),budgetValue("held"));
        var retried=post("/v1/orders",member,first.status()==200?"o2":"o1",orderInput(first.status()==200?q2:q1));payOrder(retried);
        assertEquals(new java.math.BigDecimal("0.00"),budgetValue("held"));assertEquals(new java.math.BigDecimal("3.00"),budgetValue("spent"));
        var refund=approve(requestReturn(retried,1,"r"),"a");finishRefund(refund,"refund");assertEquals(new java.math.BigDecimal("3.00"),budgetValue("spent"));
    }
    @Test void insufficientBudgetRollsBackPreviouslyReservedCouponAndQuote() throws Exception {
        seed();stock("sku1",1);budgetCampaign("1.00",0,0,"3.00");couponDefinition("5.00",true,10);var coupon=claimCoupon(member,"claim");var q=post("/v1/quotes",member,"q",couponBasket(coupon,1));
        assertEquals(409,call("POST","/v1/orders",member,"o",orderInput(q)).status());assertEquals("AVAILABLE",couponState(coupon));assertEquals(1,stockValue("available"));
        assertEquals(new java.math.BigDecimal("0.00"),budgetValue("held"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trade_quote WHERE tenant_id=? AND consumed_order_id IS NOT NULL",Integer.class,tenant));
    }
    @Test void unknownPaymentRetainsBudgetAndFundingSnapshotSurvivesPause() throws Exception {
        seed();stock("sku1",1);budgetCampaign("3.00",0,5000,"3.00");var q=post("/v1/quotes",member,"q",basket(1));
        post("/v1/admin/campaigns/budget/1/pause",admin,"pause",Map.of("expectedVersion",3));
        var order=post("/v1/orders",member,"o",orderInput(q));startPayment(order);post("/v1/orders/"+order.path("orderId").asString()+"/cancel",member,"cancel",null);reconcile(order);pump();
        assertEquals(new java.math.BigDecimal("3.00"),budgetValue("held"));assertEquals(new java.math.BigDecimal("1.50"),jdbc.queryForObject("SELECT platform_funding FROM marketing_budget_hold WHERE tenant_id=?",java.math.BigDecimal.class,tenant));
        assertEquals(q,call("GET","/v1/quotes/"+q.path("quoteId").asString(),member,null,null).body());
    }
    @Test void fundingAcrossMultipleSkusHasNoNegativeLineOrLostCent() throws Exception {
        seed();post("/v1/admin/skus",admin,"sku2",Map.of("skuId","sku2","storeId","store1","title","小额商品","unitPrice","1.00"));budgetCampaign("10.00",0,3333,"3.00");couponDefinition("2.00",true,10);var coupon=claimCoupon(member,"claim");
        var quote=post("/v1/quotes",member,"q",Map.of("storeId","store1","couponId",coupon.path("couponId").asString(),"items",List.of(Map.of("skuId","sku1","quantity",1),Map.of("skuId","sku2","quantity",1))));
        java.math.BigDecimal platform=java.math.BigDecimal.ZERO,merchant=java.math.BigDecimal.ZERO,discount=java.math.BigDecimal.ZERO,payable=java.math.BigDecimal.ZERO;
        for(var line:quote.path("funding").path("items")){
            var p1=new java.math.BigDecimal(line.path("platformFunding").asString());var m1=new java.math.BigDecimal(line.path("merchantFunding").asString());var c1=new java.math.BigDecimal(line.path("campaignDiscount").asString());var c2=new java.math.BigDecimal(line.path("couponDiscount").asString());
            assertEquals(c1.add(c2),p1.add(m1));assertTrue(p1.signum()>=0&&m1.signum()>=0);platform=platform.add(p1);merchant=merchant.add(m1);
        }
        for(var line:quote.path("items")){discount=discount.add(new java.math.BigDecimal(line.path("discount").asString()));var p1=new java.math.BigDecimal(line.path("payable").asString());assertTrue(p1.signum()>=0);payable=payable.add(p1);}
        assertEquals(new java.math.BigDecimal("0.99"),platform);assertEquals(new java.math.BigDecimal("4.01"),merchant);assertEquals(new java.math.BigDecimal("5.00"),discount);assertEquals(new java.math.BigDecimal("21.00"),payable);
    }
    private void entitlementCampaign(int quota) throws Exception {
        post("/v1/admin/entitlement-definitions",admin,"benefit-definition",Map.of("benefitId","credit","version",1,"storeId","store1","name","体验权益","units",3,"quota",quota,"validFrom",Instant.now().minusSeconds(120).toString(),"validTo",Instant.now().plusSeconds(7200).toString(),"validityDays",1));
        audience("benefit-audience",1,List.of("m1"));var campaign=new HashMap<>(draft("benefit-campaign",1,"1.00"));campaign.put("policy",Map.of("audience",Map.of("id","benefit-audience","version",1),"terms",Map.of("percentageBps",0,"platformFundingBps",0,"budget","20.00","grant",Map.of("benefitId","credit","version",1))));
        post("/v1/admin/campaigns",admin,"benefit-campaign",campaign);approveAndPublish("benefit-campaign");
    }
    private JsonNode entitlementOrder() throws Exception {
        seed();stock("sku1",3);entitlementCampaign(1);return post("/v1/orders",member,"o",orderInput(post("/v1/quotes",member,"q",basket(1))));
    }
    private JsonNode granted(JsonNode order) throws Exception {payOrder(order);pump();return call("GET","/v1/entitlements",member,null,null).body().get(0);}
    @Test void entitlementIsReservedThenGrantedOnceAfterTrustedPayment() throws Exception {
        var order=entitlementOrder();assertEquals("RESERVED",jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?",String.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT reserved FROM benefit_definition WHERE tenant_id=?",Integer.class,tenant));
        payOrder(order);assertEquals("REQUESTED",jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?",String.class,tenant));pump();
        assertEquals("AVAILABLE",jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?",String.class,tenant));
        assertEquals(3,jdbc.queryForObject("SELECT remaining_units FROM benefit_grant WHERE tenant_id=?",Integer.class,tenant));
        jdbc.update("UPDATE platform_event SET status='PENDING',available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='benefit.grant.requested.v1'",tenant);pump();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'",Integer.class,tenant));assertEquals(1,jdbc.queryForObject("SELECT issued FROM benefit_definition WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void entitlementQuotaFailureRollsBackOrderBudgetAndInventory() throws Exception {
        var first=entitlementOrder();var secondQuote=post("/v1/quotes",member,"q2",basket(1));
        assertEquals(409,call("POST","/v1/orders",member,"o2",orderInput(secondQuote)).status());
        assertEquals(1,stockValue("held"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_record WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(new java.math.BigDecimal("1.00"),jdbc.queryForObject("SELECT held FROM marketing_budget WHERE tenant_id=?",java.math.BigDecimal.class,tenant));
        post("/v1/orders/"+first.path("orderId").asString()+"/cancel",member,"cancel",null);
        assertEquals(0,jdbc.queryForObject("SELECT reserved FROM benefit_definition WHERE tenant_id=?",Integer.class,tenant));
        post("/v1/orders",member,"o2",orderInput(secondQuote));assertEquals(1,jdbc.queryForObject("SELECT reserved FROM benefit_definition WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void concurrentEntitlementConsumptionCannotProduceNegativeBalance() throws Exception {
        var grant=granted(entitlementOrder());String id=grant.path("grantId").asString();
        try(var pool=Executors.newFixedThreadPool(2)){
            var latch=new CountDownLatch(1);var a=pool.submit(()->{latch.await();return call("POST","/v1/entitlements/"+id+"/consume",member,"consume1",Map.of("units",2));});var b=pool.submit(()->{latch.await();return call("POST","/v1/entitlements/"+id+"/consume",member,"consume2",Map.of("units",2));});latch.countDown();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(),b.get()).map(Reply::status).sorted().toList());
        }
        assertEquals(1,jdbc.queryForObject("SELECT remaining_units FROM benefit_grant WHERE tenant_id=?",Integer.class,tenant));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='CONSUME'",Integer.class,tenant));
        var consumed=post("/v1/entitlements/"+id+"/consume",member,"last",Map.of("units",1));assertEquals("CONSUMED",consumed.path("status").asString());assertEquals(consumed,post("/v1/entitlements/"+id+"/consume",member,"last",Map.of("units",1)));
    }
    @Test void consumedEntitlementRefundCreatesExplicitCompensationDebt() throws Exception {
        var order=entitlementOrder();var grant=granted(order);String id=grant.path("grantId").asString();post("/v1/entitlements/"+id+"/consume",member,"consume",Map.of("units",2));
        var refund=approve(requestReturn(order,1,"r"),"a");finishRefund(refund,"refund");pump();
        assertEquals("COMPENSATION_REQUIRED",jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?",String.class,tenant));assertEquals(2,jdbc.queryForObject("SELECT debt_units FROM benefit_grant WHERE tenant_id=?",Integer.class,tenant));assertEquals(0,jdbc.queryForObject("SELECT remaining_units FROM benefit_grant WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(403,call("POST","/v1/admin/entitlements/"+id+"/resolve",member,"unauthorized",Map.of("resolution","WRITTEN_OFF","reference","test-decision")).status());
        var resolved=post("/v1/admin/entitlements/"+id+"/resolve",admin,"resolve",Map.of("resolution","WRITTEN_OFF","reference","approved-test-loss"));assertEquals("COMPENSATED",resolved.path("status").asString());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='WRITTEN_OFF'",Integer.class,tenant));assertEquals(1,jdbc.queryForObject("SELECT issued FROM benefit_definition WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void refundBeforeDelayedGrantCannotResurrectRevokedEntitlement() throws Exception {
        var order=entitlementOrder();payOrder(order);
        jdbc.update("UPDATE platform_event SET available_at=? WHERE tenant_id=? AND event_type='benefit.grant.requested.v1'",java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),tenant);
        var refund=approve(requestReturn(order,1,"r"),"a");finishRefund(refund,"refund");pump();assertEquals("REVOKED",jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?",String.class,tenant));
        jdbc.update("UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='benefit.grant.requested.v1'",tenant);pump();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'",Integer.class,tenant));assertEquals(0,jdbc.queryForObject("SELECT remaining_units FROM benefit_grant WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void expiredOrForeignEntitlementCannotBeConsumed() throws Exception {
        var grant=granted(entitlementOrder());String id=grant.path("grantId").asString();
        assertEquals(404,call("POST","/v1/entitlements/"+id+"/consume",other,"foreign",Map.of("units",1)).status());assertEquals(404,call("GET","/v1/entitlements/"+id+"/ledger",other,null,null).status());
        jdbc.update("UPDATE benefit_grant SET expires_at=? WHERE tenant_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),tenant);
        assertEquals(409,call("POST","/v1/entitlements/"+id+"/consume",member,"expired",Map.of("units",1)).status());assertEquals(3,jdbc.queryForObject("SELECT remaining_units FROM benefit_grant WHERE tenant_id=?",Integer.class,tenant));
    }
    private Map<String,Object> journey(String id,String trigger,int duration,List<Map<String,Object>> nodes) {
        return Map.of("journeyId",id,"version",1,"storeId","store1","name","会员关怀旅程","trigger",trigger,"validFrom",Instant.now().minusSeconds(30).toString(),"validTo",Instant.now().plusSeconds(300).toString(),"maxDurationSeconds",duration,"entry",nodes.getFirst().get("id"),"nodes",nodes);
    }
    private Map<String,Object> endNode(){return Map.of("id","end","kind","END");}
    private Map<String,Object> noticeNode(String next){return Map.of("id","notice","kind","NOTIFY","title","会员关怀","body","权益已进入您的账户","next",next);}
    private void publishJourney(Map<String,Object> definition) throws Exception {
        String id=(String)definition.get("journeyId");post("/v1/admin/journeys",admin,"create-"+id,definition);
        int version=0;for(String action:List.of("submit","approve","publish"))post("/v1/admin/journeys/"+id+"/1/"+action,admin,action+"-"+id,Map.of("expectedVersion",version++));
    }
    private JsonNode enroll(String id,String event) throws Exception {return post("/v1/admin/journey-instances",admin,"enroll-"+event,Map.of("journeyId",id,"version",1,"memberId","m1","eventKey",event));}
    private void journeyPump() throws Exception {post("/v1/admin/journeys/pump",admin,null,null);}
    private void journeyBenefit(int quota) throws Exception {
        post("/v1/admin/entitlement-definitions",admin,"journey-benefit",Map.of("benefitId","journey-credit","version",1,"storeId","store1","name","旅程体验权益","units",2,"quota",quota,"validFrom",Instant.now().minusSeconds(120).toString(),"validTo",Instant.now().plusSeconds(7200).toString(),"validityDays",1));
    }
    private Map<String,Object> grantNode(String next){return Map.of("id","grant","kind","GRANT","benefit",Map.of("benefitId","journey-credit","version",1),"next",next);}
    private String journeyState(String id){return jdbc.queryForObject("SELECT status FROM journey_instance WHERE tenant_id=? AND instance_id=?",String.class,tenant,id);}
    @Test void journeyRejectsCyclesUnreachableNodesAndUntrustedRules() throws Exception {
        seed();var cycle=journey("cycle","MANUAL",60,List.of(Map.of("id","wait","kind","WAIT","seconds",1,"next","wait")));
        assertEquals(400,call("POST","/v1/admin/journeys",admin,"cycle",cycle).status());
        assertEquals(400,call("POST","/v1/admin/journeys",admin,"unreachable",journey("unreachable","MANUAL",60,List.of(endNode(),Map.of("id","orphan","kind","END")))).status());
        var rule=Map.of("kind","COMPARE","field","clientDiscount","operator","EQ","valueType","TEXT","value","VIP");
        assertEquals(400,call("POST","/v1/admin/journeys",admin,"rule",journey("rule","MANUAL",60,List.of(Map.of("id","check","kind","DECIDE","rule",rule,"yesNext","end","noNext","end"),endNode()))).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM journey_definition WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void journeyRequiresApprovalAndPersistsWaitBeforeExactlyOneNotification() throws Exception {
        seed();var definition=journey("welcome","MANUAL",120,List.of(Map.of("id","wait","kind","WAIT","seconds",30,"next","notice"),noticeNode("end"),endNode()));
        post("/v1/admin/journeys",admin,"create",definition);assertEquals(409,call("POST","/v1/admin/journeys/welcome/1/publish",admin,"early",Map.of("expectedVersion",0)).status());
        int version=0;for(String action:List.of("submit","approve","publish"))post("/v1/admin/journeys/welcome/1/"+action,admin,action,Map.of("expectedVersion",version++));
        var instance=enroll("welcome","event1");assertEquals(instance,enroll("welcome","event1"));String id=instance.path("instanceId").asString();journeyPump();journeyPump();
        assertEquals("WAITING",journeyState(id));assertEquals(0,call("GET","/v1/notifications",member,null,null).body().size());
        jdbc.update("UPDATE journey_instance SET due_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=?",tenant);journeyPump();journeyPump();journeyPump();
        assertEquals("COMPLETED",journeyState(id));assertEquals(1,call("GET","/v1/notifications",member,null,null).body().size());assertEquals(3,jdbc.queryForObject("SELECT steps FROM journey_instance WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(403,call("POST","/v1/admin/journey-instances",member,"forbidden",Map.of("journeyId","welcome","version",1,"memberId","m1","eventKey","e2")).status());
    }
    @Test void journeyUnknownFactStopsWithoutTakingAwardBranch() throws Exception {
        seed();var rule=Map.of("kind","COMPARE","field","orderAmount","operator","GTE","valueType","DECIMAL","value","1");
        publishJourney(journey("unknown","MANUAL",120,List.of(Map.of("id","check","kind","DECIDE","rule",rule,"yesNext","notice","noNext","end"),noticeNode("end"),endNode())));
        var instance=enroll("unknown","event");journeyPump();assertEquals("COMPLETED",journeyState(instance.path("instanceId").asString()));
        assertEquals("RULE_UNKNOWN",jdbc.queryForObject("SELECT result FROM journey_instance WHERE tenant_id=?",String.class,tenant));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM journey_notification WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void journeyCancelAndDeadlinePreventFutureEffectsAndEnforceOwnership() throws Exception {
        seed();publishJourney(journey("stop","MANUAL",120,List.of(noticeNode("end"),endNode())));
        String first=enroll("stop","one").path("instanceId").asString(),second=enroll("stop","two").path("instanceId").asString();
        assertEquals(404,call("POST","/v1/admin/journey-instances/"+first+"/cancel",token("foreign-"+UUID.randomUUID(),"admin","ADMIN"),"cancel",null).status());
        post("/v1/admin/journey-instances/"+first+"/cancel",admin,"cancel",null);jdbc.update("UPDATE journey_instance SET deadline=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND instance_id=?",tenant,second);journeyPump();
        assertEquals("CANCELLED",journeyState(first));assertEquals("TIMED_OUT",journeyState(second));assertEquals(0,call("GET","/v1/notifications",member,null,null).body().size());
    }
    @Test void concurrentJourneyWorkersCommitOneGrantAndResumeNextNode() throws Exception {
        seed();journeyBenefit(1);publishJourney(journey("grant","MANUAL",120,List.of(grantNode("notice"),noticeNode("end"),endNode())));var instance=enroll("grant","one");
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{journeyPump();return true;});var b=pool.submit(()->{journeyPump();return true;});a.get();b.get();}
        journeyPump();journeyPump();pump();assertEquals("COMPLETED",journeyState(instance.path("instanceId").asString()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_grant WHERE tenant_id=? AND source_type='JOURNEY' AND order_id IS NULL",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'",Integer.class,tenant));assertEquals(1,call("GET","/v1/notifications",member,null,null).body().size());
    }
    @Test void failedJourneyNodeRetriesBoundedlyAndManualRetryKeepsCheckpoint() throws Exception {
        seed();journeyBenefit(1);publishJourney(journey("limited","MANUAL",120,List.of(grantNode("end"),endNode())));enroll("limited","first");journeyPump();journeyPump();
        var blocked=enroll("limited","second");String id=blocked.path("instanceId").asString();
        for(int i=0;i<5;i++){jdbc.update("UPDATE journey_instance SET due_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND instance_id=?",tenant,id);journeyPump();}
        assertEquals("ISOLATED",journeyState(id));assertEquals(0,jdbc.queryForObject("SELECT steps FROM journey_instance WHERE tenant_id=? AND instance_id=?",Integer.class,tenant,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_grant WHERE tenant_id=?",Integer.class,tenant));
        post("/v1/admin/journey-instances/"+id+"/retry",admin,"retry",null);assertEquals("RUNNING",journeyState(id));
        jdbc.update("UPDATE journey_instance SET deadline=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND instance_id=?",tenant,id);journeyPump();assertEquals("TIMED_OUT",journeyState(id));
    }
    @Test void paidJourneyGrantsAreReversedAndLaterNodesCancelledAfterFullRefund() throws Exception {
        var order=pendingOrder();journeyBenefit(1);publishJourney(journey("paid","ORDER_PAID",120,List.of(grantNode("wait"),Map.of("id","wait","kind","WAIT","seconds",60,"next","notice"),noticeNode("end"),endNode())));
        payOrder(order);pump();journeyPump();pump();journeyPump();
        assertEquals("AVAILABLE",jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?",String.class,tenant));
        var refund=approve(requestReturn(order,1,"r"),"a");finishRefund(refund,"refund");pump();
        assertEquals("REVOKED",jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?",String.class,tenant));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM journey_instance WHERE tenant_id=?",String.class,tenant));journeyPump();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM journey_notification WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void latePaidJourneyEventAfterRefundDoesNotEnroll() throws Exception {
        var order=pendingOrder();publishJourney(journey("late","ORDER_PAID",120,List.of(noticeNode("end"),endNode())));payOrder(order);
        jdbc.update("UPDATE platform_event SET available_at=? WHERE tenant_id=? AND event_type='order.paid.v1'",java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),tenant);
        var refund=approve(requestReturn(order,1,"r"),"a");finishRefund(refund,"refund");pump();
        jdbc.update("UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND event_type='order.paid.v1'",tenant);pump();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?",Integer.class,tenant));
    }
    private Map<String,Object> opsPage(long version){return Map.of("pageId","marketing-desk","version",version,"title","营销工作台","storeId","store1","sections",List.of(Map.of("id","coupons","title","优惠券","source","COUPONS")),"actions",List.of(Map.of("id","create-coupon","label","新建券","kind","CREATE_COUPON")));}
    private void publishPage(long version) throws Exception {post("/v1/admin/ops-pages",admin,"page-"+version,opsPage(version));int expected=0;for(String action:List.of("submit","approve","publish"))post("/v1/admin/ops-pages/marketing-desk/"+version+"/"+action,admin,action+"-page-"+version,Map.of("expectedVersion",expected++));}
    private Map<String,Object> pageCoupon(){return Map.of("definitionId","ops-coupon","version",1,"storeId","store1","name","页面创建优惠","minimumSpend","10.00","discountAmount","2.00","validFrom",Instant.now().minusSeconds(30).toString(),"validTo",Instant.now().plusSeconds(3600).toString(),"quota",10,"stackable",true);}
    @Test void lowcodePreviewReadsDatabaseWithoutSavingPageOrCommands() throws Exception {
        seed();couponDefinition("2.00",true,10);int before=jdbc.queryForObject("SELECT COUNT(*) FROM platform_command WHERE tenant_id=?",Integer.class,tenant);
        var preview=post("/v1/admin/ops-pages/preview",admin,null,opsPage(1));assertTrue(preview.path("preview").asBoolean());assertTrue(preview.path("bounded").asBoolean());assertEquals(1,preview.path("data").get(0).path("rows").size());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ops_page WHERE tenant_id=?",Integer.class,tenant));assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM platform_command WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(403,call("POST","/v1/admin/ops-pages/preview",member,null,opsPage(1)).status());
    }
    @Test void lowcodeRejectsUnknownDataSourceAndDuplicateComponentIds() throws Exception {
        seed();var bad=new HashMap<>(opsPage(1));bad.put("sections",List.of(Map.of("id","bad","title","不受控源","source","https://example.com/private")));
        assertEquals(400,call("POST","/v1/admin/ops-pages",admin,"bad-source",bad).status());
        bad.put("sections",List.of(Map.of("id","create-coupon","title","冲突标识","source","COUPONS")));
        assertEquals(400,call("POST","/v1/admin/ops-pages",admin,"bad-id",bad).status());
        var action=new HashMap<>(opsPage(1));action.put("actions",List.of(Map.of("id","run","label","脚本","kind","RUN_SCRIPT")));
        assertEquals(400,call("POST","/v1/admin/ops-pages",admin,"bad-action",action).status());
    }
    @Test void lowcodeActionRequiresPublishedDeclaredVersionAndIsIdempotent() throws Exception {
        seed();post("/v1/admin/ops-pages",admin,"draft",opsPage(1));var input=Map.of("coupon",pageCoupon());String path="/v1/admin/ops-pages/marketing-desk/1/actions/create-coupon";
        assertEquals(409,call("POST",path,admin,"execute",input).status());assertEquals(409,call("POST","/v1/admin/ops-pages/marketing-desk/1/publish",admin,"skip-review",Map.of("expectedVersion",0)).status());
        int expected=0;for(String action:List.of("submit","approve","publish"))post("/v1/admin/ops-pages/marketing-desk/1/"+action,admin,action,Map.of("expectedVersion",expected++));
        var result=post(path,admin,"execute",input);assertEquals(result,post(path,admin,"execute",input));assertEquals("ops-coupon",result.path("resourceId").asString());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon_definition WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(404,call("POST","/v1/admin/ops-pages/marketing-desk/1/actions/undeclared",admin,"no-action",input).status());
        assertEquals(403,call("POST",path,member,"forbidden",input).status());
    }
    @Test void lowcodeVersionRollbackRestoresPageWithoutUndoingBusinessData() throws Exception {
        seed();publishPage(1);post("/v1/admin/ops-pages/marketing-desk/1/actions/create-coupon",admin,"execute",Map.of("coupon",pageCoupon()));publishPage(2);
        assertEquals(2,call("GET","/v1/admin/ops-pages/marketing-desk/render",admin,null,null).body().path("page").path("content").path("version").asInt());
        assertEquals(409,call("POST","/v1/admin/ops-pages/marketing-desk/1/actions/create-coupon",admin,"old-page",Map.of("coupon",pageCoupon())).status());
        post("/v1/admin/ops-pages/marketing-desk/1/rollback",admin,"rollback",Map.of("expectedVersion",4));
        var render=call("GET","/v1/admin/ops-pages/marketing-desk/render",admin,null,null).body();assertEquals(1,render.path("page").path("content").path("version").asInt());assertEquals(1,render.path("data").get(0).path("rows").size());
        assertEquals(2,call("GET","/v1/admin/ops-pages/marketing-desk/versions",admin,null,null).body().size());
        assertEquals(404,call("GET","/v1/admin/ops-pages/marketing-desk/render",token("other-"+UUID.randomUUID(),"admin","ADMIN"),null,null).status());
    }
    @Test void lowcodeActionCannotChangeStoreOrSmuggleAnotherCommandType() throws Exception {
        seed();publishPage(1);var coupon=new HashMap<>(pageCoupon());coupon.put("storeId","other-store");String path="/v1/admin/ops-pages/marketing-desk/1/actions/create-coupon";
        assertEquals(400,call("POST",path,admin,"other-store",Map.of("coupon",coupon)).status());
        assertEquals(400,call("POST",path,admin,"mixed",Map.of("coupon",pageCoupon(),"enrollment",Map.of("journeyId","j","version",1,"memberId","m1","eventKey","e"))).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon_definition WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void consoleReadsRespectAdminTenantAndKeepMemberOrdersPrivate() throws Exception {
        var order=pendingOrder();String id=order.path("orderId").asString();var payment=startPayment(order);
        assertEquals(1,call("GET","/v1/stores",member,null,null).body().size());assertEquals(1,call("GET","/v1/admin/orders",admin,null,null).body().size());
        assertEquals(id,call("GET","/v1/admin/orders/"+id,admin,null,null).body().path("orderId").asString());
        assertFalse(call("GET","/v1/admin/orders/"+id,admin,null,null).body().has("address"));
        assertEquals(403,call("GET","/v1/admin/journey-instances",member,null,null).status());assertEquals(403,call("GET","/v1/admin/coupon-definitions?storeId=store1",member,null,null).status());assertEquals(403,call("GET","/v1/admin/orders",member,null,null).status());assertEquals(403,call("GET","/v1/admin/orders/"+id+"/payment",member,null,null).status());
        String foreign=token("foreign-"+UUID.randomUUID(),"admin","ADMIN");assertEquals(404,call("GET","/v1/admin/orders/"+id,foreign,null,null).status());assertEquals(404,call("POST","/v1/admin/orders/"+id+"/payment/reconcile",foreign,null,null).status());
        sandbox(payment,"PAID");assertEquals("PAID",post("/v1/admin/orders/"+id+"/payment/reconcile",admin,null,null).path("status").asString());pump();
        assertEquals("PAID",call("GET","/v1/admin/orders/"+id,admin,null,null).body().path("status").asString());
        assertTrue(call("GET","/v1/runtime-capabilities",member,null,null).body().path("sandboxEnabled").asBoolean());
        assertEquals(401,call("GET","/v1/runtime-capabilities",null,null,null).status());
    }
    @Test void protocolErrorsDoNotBecomeInternalServerFailures() throws Exception {
        assertEquals(400,call("GET","/v1/catalog",member,null,null).status());
        assertEquals(404,call("GET","/v1/no-such-endpoint",member,null,null).status());
        assertEquals(405,call("POST","/v1/me",member,null,null).status());
    }
    @Test void everyBusinessTableAndColumnHasComments() {
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' AND TABLE_COMMENT=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' AND COLUMN_COMMENT=''",Integer.class));
    }
}
