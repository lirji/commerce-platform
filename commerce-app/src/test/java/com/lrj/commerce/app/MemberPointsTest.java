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
class MemberPointsTest {
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
    @Test void netCashSourcesHandleEarlyRefundDuplicatesAndOriginalPolicy() throws Exception {
        seed();Instant at=testClock.instant();policy(1,"2.00",at);
        fact("early","order1","25.00",at,false,"r1","5.00");assertEquals(0,wallet().path("available").asLong());
        fact("complete","order1","25.00",at,true,null,null);assertEquals(40,wallet().path("available").asLong());
        testClock.at=at.plusSeconds(5);policy(2,"10.00",testClock.instant());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->fact("ra","order1","25.00",at,true,"r2","15.00"));
            var b=pool.submit(()->fact("rb","order1","25.00",at,true,"r2","15.00"));a.get();b.get();
        }
        assertEquals(10,wallet().path("available").asLong());assertEquals(0,wallet().path("debt").asLong());
        assertEquals(2,call("GET","/v1/members/me/points/ledger",member,null,null).body().size());
        assertEquals(403,call("GET","/v1/admin/member-points/m1",member,null,null).status());
        String foreign=token("foreign-"+UUID.randomUUID(),"admin","ADMIN");assertEquals(404,call("GET","/v1/admin/member-points/m1",foreign,null,null).status());
    }
    @Test void expiredPointsCannotBeUsedBeforeSweepAndRefundDoesNotChargeThemAgain() throws Exception {
        seed();Instant at=testClock.instant();policy(1,"1.00",at);
        fact("earn","order1","100.00",at,true,null,null);assertEquals(100,wallet().path("available").asLong());
        testClock.at=at.plusSeconds(86400);
        assertEquals(0,wallet().path("available").asLong(),"读取即排除过期，无需先等任务");
        post("/v1/admin/member-points/m1/expire",admin,"expire",null);
        fact("refund","order1","100.00",at,true,"r1","100.00");
        assertEquals(0,wallet().path("debt").asLong());
        var ledger=call("GET","/v1/members/me/points/ledger",member,null,null).body();assertEquals(3,ledger.size());
        assertEquals("EXPIRE",ledger.get(1).path("action").asString());assertEquals(0,ledger.get(2).path("delta").asLong());
        assertTrue(ledger.get(2).path("reason").asString().contains("已过期免扣100"));
        post("/v1/admin/member-points/m1/expire",admin,"repeat-expire",null);assertEquals(3,call("GET","/v1/members/me/points/ledger",member,null,null).body().size());
    }
    @Test void adjustmentDebtAndNewCreditsAreAuditedAndVersionGuarded() throws Exception {
        seed();Instant at=testClock.instant();policy(1,"1.00",at);
        fact("earn","order1","100.00",at,true,null,null);
        var input=Map.of("expectedVersion",wallet().path("version").asLong(),"delta",-150,"reason","校准错误奖励");
        var result=post("/v1/admin/member-points/m1/adjust",admin,"debit",input);assertEquals(50,result.path("debt").asLong());assertEquals(0,result.path("available").asLong());
        assertEquals(result,post("/v1/admin/member-points/m1/adjust",admin,"debit",input));
        assertEquals(409,call("POST","/v1/admin/member-points/m1/adjust",admin,"stale",input).status());
        fact("new-earn","order2","80.00",at,true,null,null);
        assertEquals(0,wallet().path("debt").asLong());assertEquals(30,wallet().path("available").asLong());
        fact("reverse-offset","order2","80.00",at,true,"r2","80.00");
        assertEquals(50,wallet().path("debt").asLong(),"已用于抵偿欠项的奖励退款仍需扣回");assertEquals(0,wallet().path("available").asLong());
        assertEquals(400,call("POST","/v1/admin/member-points/policies",admin,"bad-rate",Map.of("version",2,"effectiveFrom",at.toString(),"earnPerYuan","1.001","expiryDays",1,"spendEnabled",true,"pointsPerYuan",100,"maxDeductionBps",5000)).status());
    }
    @Test void completedOrderAndSuccessfulRefundFlowThroughRealOutbox() throws Exception {
        seed();policy(1,"2.00",Instant.now().minusSeconds(1));
        post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",2));
        var quote=post("/v1/quotes",member,"q",basket(1));
        var order=post("/v1/orders",member,"order",Map.of("quoteId",quote.path("quoteId").asString(),"address",Map.of("recipient","测试","phone","13800000000","detail","隔离测试地址")));
        String id=order.path("orderId").asString();
        var payment=post("/v1/orders/"+id+"/payments",member,"pay",null);
        post("/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",admin,"paid",Map.of("status","PAID"));
        post("/v1/orders/"+id+"/payment/reconcile",member,null,null);pumpAll();
        assertEquals(0,wallet().path("available").asLong());
        post("/v1/admin/fulfillments/"+id+"/ship",admin,"ship",Map.of("trackingNo","GROWTH-TEST"));
        post("/v1/admin/fulfillments/"+id+"/deliver",admin,"deliver",null);pumpAll();
        assertEquals(50,wallet().path("available").asLong());
        var request=post("/v1/aftersales",member,"return",Map.of("orderId",id,"reason","测试退货","items",List.of(Map.of("skuId","sku1","quantity",1))));
        String caseId=request.path("caseId").asString();post("/v1/admin/aftersales/"+caseId+"/approve",admin,"approve",null);
        var receiving=post("/v1/admin/aftersales/"+caseId+"/receive-return",admin,"receive",null);String refund=receiving.path("refundId").asString();
        post("/v1/admin/sandbox/refunds/"+refund+"/success",admin,"refund",null);post("/v1/admin/refunds/"+refund+"/reconcile",admin,null,null);pumpAll();
        assertEquals(0,wallet().path("available").asLong());
        assertEquals(2,call("GET","/v1/members/me/points/ledger",member,null,null).body().size());
    }
    private void pumpAll() throws Exception {for(int i=0;i<6;i++)post("/v1/admin/events/pump",admin,null,null);}
    @Test void distinctConcurrentRefundsUseCurrentRowsAfterWaitingForMemberLock() throws Exception {
        seed();Instant at=testClock.instant();policy(1,"1.00",at);fact("earned","order1","100.00",at,true,null,null);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++) {
                int index=i;
                tasks.add(pool.submit(()->commands.run(new Actor(tenant,"admin",Actor.Role.ADMIN),"test.points.concurrent","refund-"+index,index,String.class,()->{
                    // 先建立旧RR快照，强制覆盖真实在途事务等待会员锁的失败窗口。
                    jdbc.queryForObject("SELECT COUNT(*) FROM member_point_ledger WHERE tenant_id=?",Integer.class,tenant);
                    try { barrier.await(5,TimeUnit.SECONDS); } catch(Exception e){throw new IllegalStateException(e);}
                    points.observe(tenant,new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact("order1","m1","100.00",at,true,"refund-"+index,index==0?"20.00":"30.00"));return "ok";
                })));
            }
            for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }
        assertEquals(50,wallet().path("available").asLong());
        assertEquals(50,jdbc.queryForObject("SELECT contribution FROM member_point_order WHERE tenant_id=? AND order_id='order1'",Long.class,tenant));
    }

}
