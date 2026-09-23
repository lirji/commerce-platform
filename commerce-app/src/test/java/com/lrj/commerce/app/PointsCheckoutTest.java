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
class PointsCheckoutTest {
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
        testClock.at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);tenant="t-"+UUID.randomUUID();admin=token(tenant,"admin","ADMIN");member=token(tenant,"buyer","MEMBER");other=token("other-"+UUID.randomUUID(),"buyer","MEMBER");
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
    @Autowired com.lrj.commerce.member.api.MemberGrowthApi growth;
    private void policy(long version,String rate,Instant effective) throws Exception {
        post("/v1/admin/member-points/policies",admin,"points-policy"+version,Map.of("version",version,"effectiveFrom",effective.toString(),"earnPerYuan",rate,"expiryDays",1,"spendEnabled",true,"pointsPerYuan",100,"maxDeductionBps",5000));
    }
    private void observe(String key,com.lrj.commerce.member.api.MemberGrowthApi.OrderFact fact) {
        commands.run(new Actor(tenant,"admin",Actor.Role.ADMIN),"test.growth.fact",key,fact,String.class,()->{points.observe(tenant,fact);return "ok";});
    }
    private JsonNode wallet() throws Exception {return call("GET","/v1/members/me/points",member,null,null).body();}
    @org.springframework.boot.test.context.TestConfiguration
    static class ClockConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        TestClock testClock() { return new TestClock(); }
    }
    static class TestClock extends Clock {
        volatile Instant at=Instant.now();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return at; }
    }
    @Autowired TestClock testClock;
    @Autowired com.lrj.commerce.member.api.MemberPointsApi points;
    private void fact(String key,String order,String amount,Instant at,boolean completed,String refund,String refundAmount) {
        observe(key,new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact(order,"m1",amount,at,completed,refund,refundAmount));
    }
    private void spending(int cap,long balance) throws Exception {
        seed();Instant at=testClock.instant();
        post("/v1/admin/member-points/policies",admin,"spend-policy",Map.of("version",1,"effectiveFrom",at.toString(),"earnPerYuan","0.00","expiryDays",1,"spendEnabled",true,"pointsPerYuan",100,"maxDeductionBps",cap));
        post("/v1/admin/member-points/m1/adjust",admin,"points",Map.of("expectedVersion",0,"delta",balance,"reason","隔离积分测试"));
        post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",30));
    }
    private JsonNode quote(String key,int quantity,long points) throws Exception {return post("/v1/quotes",member,key,Map.of("storeId","store1","items",List.of(Map.of("skuId","sku1","quantity",quantity)),"redeemPoints",points));}
    private Object orderInput(JsonNode quote) {return Map.of("quoteId",quote.path("quoteId").asString(),"address",Map.of("recipient","积分测试","phone","13800000000","detail","隔离测试地址"));}
    private JsonNode order(String key,JsonNode quote) throws Exception {return post("/v1/orders",member,key,orderInput(quote));}
    private void pump() throws Exception {for(int i=0;i<8;i++)post("/v1/admin/events/pump",admin,null,null);}
    private String id(JsonNode order){return order.path("orderId").asString();}
    private void pay(JsonNode order) throws Exception {
        var payment=post("/v1/orders/"+id(order)+"/payments",member,"pay-"+id(order),null);
        post("/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",admin,"paid-"+id(order),Map.of("status","PAID"));
        post("/v1/orders/"+id(order)+"/payment/reconcile",member,null,null);pump();
    }
    private void deliver(JsonNode order) throws Exception {
        pump();post("/v1/admin/fulfillments/"+id(order)+"/ship",admin,"ship",Map.of("trackingNo","POINTS-TRACK"));
        post("/v1/admin/fulfillments/"+id(order)+"/deliver",admin,"deliver",null);pump();
    }
    private JsonNode returnPart(JsonNode order,int quantity,String key) throws Exception {
        var requested=post("/v1/aftersales",member,key,Map.of("orderId",id(order),"reason","积分退货验证","items",List.of(Map.of("skuId","sku1","quantity",quantity))));
        String caseId=requested.path("caseId").asString();
        var approved=post("/v1/admin/aftersales/"+caseId+"/approve",admin,key+"-approve",null);
        var refund=approved.path("returnRequired").asBoolean()?post("/v1/admin/aftersales/"+caseId+"/receive-return",admin,key+"-receive",null):approved;
        if(new java.math.BigDecimal(refund.path("refundAmount").asString()).signum()>0) {
            post("/v1/admin/sandbox/refunds/"+refund.path("refundId").asString()+"/success",admin,key+"-success",null);
            post("/v1/admin/refunds/"+refund.path("refundId").asString()+"/reconcile",admin,null,null);
        }
        pump();return call("GET","/v1/aftersales/"+caseId,member,null,null).body();
    }
    @Test void quoteDoesNotHoldAndConcurrentOrdersCannotDoubleSpend() throws Exception {
        spending(10000,3000);
        var first=quote("q1",1,2000);var second=quote("q2",1,2000);
        assertEquals("5.00",first.path("payable").asString());assertEquals(3000,wallet().path("available").asLong());assertEquals(0,wallet().path("held").asLong());
        Reply a,b;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var fa=pool.submit(()->call("POST","/v1/orders",member,"order-a",orderInput(first)));
            var fb=pool.submit(()->call("POST","/v1/orders",member,"order-b",orderInput(second)));a=fa.get();b=fb.get();
        }
        assertEquals(Set.of(200,409),Set.of(a.status(),b.status()));
        var winner=a.status()==200?a.body():b.body();var lostQuote=a.status()==200?second:first;
        assertEquals(1000,wallet().path("available").asLong());assertEquals(2000,wallet().path("held").asLong());
        post("/v1/orders/"+id(winner)+"/cancel",member,"cancel",null);
        assertEquals(3000,wallet().path("available").asLong());assertEquals(0,wallet().path("held").asLong());
        var retry=order("retry-after-release",lostQuote);assertEquals("5.00",retry.path("payable").asString(),"失败预留必须回滚报价消费");
    }
    @Test void unknownPaymentRetainsPointsUntilChannelConfirmsAbsence() throws Exception {
        spending(10000,3000);var order=order("order",quote("q",1,1000));
        var payment=post("/v1/orders/"+id(order)+"/payments",member,"pay",null);
        assertEquals("CLOSING",post("/v1/orders/"+id(order)+"/cancel",member,"cancel",null).path("status").asString());
        assertEquals(1000,wallet().path("held").asLong());assertEquals(2000,wallet().path("available").asLong());
        post("/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",admin,"open",Map.of("status","OPEN"));
        post("/v1/orders/"+id(order)+"/payment/reconcile",member,null,null);pump();
        assertEquals("CANCELLED",call("GET","/v1/orders/"+id(order),member,null,null).body().path("status").asString());
        assertEquals(3000,wallet().path("available").asLong());assertEquals(0,wallet().path("held").asLong());
    }
    @Test void zeroCashOrderConsumesAndRefundsPointsWithoutChannelMoney() throws Exception {
        spending(10000,2500);var quote=quote("q",1,2500);assertEquals("0.00",quote.path("payable").asString());
        var order=order("order",quote);assertEquals("NO_PAYMENT_REQUIRED",order.path("paymentKind").asString());
        assertEquals(0,wallet().path("available").asLong());assertEquals(0,wallet().path("held").asLong());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE tenant_id=?",Integer.class,tenant));
        pump();var returned=returnPart(order,1,"return");assertEquals("COMPLETED",returned.path("status").asString());
        assertEquals("0.00",returned.path("refundAmount").asString());assertEquals(2500,returned.path("items").get(0).path("points").asLong());
        assertEquals(2500,wallet().path("available").asLong());
        pump();assertEquals(2500,wallet().path("available").asLong());
    }
    @Test void partialReturnsConserveCashAndIntegerPoints() throws Exception {
        spending(5000,2000);var q=quote("q",3,1001);var order=order("order",q);pay(order);deliver(order);
        assertEquals("64.99",q.path("payable").asString());assertEquals(999,wallet().path("available").asLong());
        long returnedPoints=0;java.math.BigDecimal cash=java.math.BigDecimal.ZERO;
        for(int i=0;i<3;i++) {
            var returned=returnPart(order,1,"part-"+i);assertEquals("COMPLETED",returned.path("status").asString());
            long points=returned.path("items").get(0).path("points").asLong();assertEquals(i==0?333:334,points);returnedPoints+=points;
            cash=cash.add(new java.math.BigDecimal(returned.path("refundAmount").asString()));
        }
        assertEquals(1001,returnedPoints);assertEquals("64.99",cash.toPlainString());assertEquals(2000,wallet().path("available").asLong());
        assertEquals(1001,jdbc.queryForObject("SELECT returned_points FROM member_point_hold WHERE tenant_id=? AND order_id=?",Long.class,tenant,id(order)));
    }
    @Test void expiryReleaseAndRuleChangeCannotReviveOrOverspendPoints() throws Exception {
        spending(10000,3000);var at=testClock.instant();var stale=quote("stale",1,1000);
        testClock.at=at.plusSeconds(1);policy(2,"1.00",testClock.instant());
        assertEquals(409,call("POST","/v1/orders",member,"stale-order",orderInput(stale)).status());
        var order=order("valid",quote("valid-q",1,1000));
        testClock.at=at.plusSeconds(86401);
        assertEquals(0,wallet().path("available").asLong());assertEquals(1000,wallet().path("held").asLong());
        post("/v1/orders/"+id(order)+"/cancel",member,"cancel-expired",null);
        assertEquals(0,wallet().path("available").asLong());assertEquals(0,wallet().path("held").asLong());
        assertEquals(0,wallet().path("debt").asLong());
    }
    @Test void refundOfEarnedPointsWhileHeldIsOffsetWhenOrderIsCancelled() throws Exception {
        spending(10000,100);testClock.at=testClock.instant().plusSeconds(1);policy(2,"1.00",testClock.instant());
        var earnedAt=testClock.instant();fact("earn-source","original","1000.00",earnedAt,true,null,null);
        var order=order("order",quote("q",1,1000));assertEquals(1000,wallet().path("held").asLong());
        fact("refund-source","original","1000.00",earnedAt,true,"source-refund","1000.00");
        assertEquals(900,wallet().path("debt").asLong());
        post("/v1/orders/"+id(order)+"/cancel",member,"cancel",null);
        assertEquals(0,wallet().path("debt").asLong());assertEquals(100,wallet().path("available").asLong());
    }
    @Test void successfulReturnAfterOriginalExpiryRecordsForfeitureInsteadOfRenewingPoints() throws Exception {
        spending(10000,1000);var at=testClock.instant();var order=order("order",quote("q",1,1000));pay(order);
        testClock.at=at.plusSeconds(86401);
        var returned=returnPart(order,1,"expired-return");assertEquals("COMPLETED",returned.path("status").asString());
        assertEquals(0,wallet().path("available").asLong());assertEquals(0,wallet().path("debt").asLong());
        var records=call("GET","/v1/members/me/points/ledger",member,null,null).body();
        var last=records.get(records.size()-1);assertEquals("REFUND",last.path("action").asString());assertEquals(0,last.path("delta").asLong());
        assertTrue(last.path("reason").asString().contains("失效1000"));
    }
    @Test void legacyQuoteRequestHashAndOldLineSnapshotsRemainReadable() {
        var request=new com.lrj.commerce.trade.api.QuoteApi.Request("store1",List.of(new com.lrj.commerce.trade.api.QuoteApi.Selection("sku1",1)),null);
        assertFalse(JsonCodec.write(request).contains("redeemPoints"));
        var old=JsonCodec.read("{\"skuId\":\"sku1\",\"revision\":1,\"title\":\"旧订单\",\"quantity\":1,\"unitPrice\":\"1.00\",\"gross\":\"1.00\",\"discount\":\"0.00\",\"payable\":\"1.00\"}",com.lrj.commerce.trade.api.QuoteApi.Line.class);
        assertEquals(0L,old.points());assertEquals("0.00",old.pointDiscount());
        var oldReturn=JsonCodec.read("{\"skuId\":\"sku1\",\"quantity\":1,\"refundAmount\":\"1.00\"}",com.lrj.commerce.aftersales.api.AftersaleApi.Line.class);
        assertEquals(0L,oldReturn.points());
    }

}
