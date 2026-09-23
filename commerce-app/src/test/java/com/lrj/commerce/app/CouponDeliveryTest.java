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
class CouponDeliveryTest {
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
    @org.springframework.boot.test.context.TestConfiguration static class ClockConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary TestClock testClock(){return new TestClock();}
    }
    static class TestClock extends Clock {
        volatile Instant at=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return at;}
    }
    @Autowired TestClock testClock;
    private void assets(int quota,Integer validity) throws Exception {
        seed();post("/v1/admin/coupon-definitions",admin,"coupon",new com.lrj.commerce.benefit.api.CouponApi.Definition("target",1,"store1","定向关怀券","0.00","5.00",testClock.instant().minusSeconds(1),testClock.instant().plusSeconds(7*86400),quota,true,10000,"SOURCE_ONLY",validity));
    }
    private void audience(long version,List<String> ids) throws Exception {post("/v1/admin/audiences",admin,"audience-"+version,new com.lrj.commerce.campaign.api.MarketingAssets.Audience("group",version,"固定人群","TEST",testClock.instant(),testClock.instant().plusSeconds(7200),ids));}
    private void add(String id) throws Exception {post("/v1/admin/members",admin,"member-"+id,Map.of("memberId",id,"actorId","actor-"+id,"displayName",id,"memberLevel","BASIC"));}
    private JsonNode batch(String id,long version,int hours) throws Exception {return post("/v1/admin/coupon-deliveries",admin,"batch-"+id,new com.lrj.commerce.journey.api.CouponDeliveryApi.Create(id,"store1","人群关怀","target",1,new com.lrj.commerce.campaign.api.MarketingAssets.Ref("group",version),testClock.instant().plusSeconds(3600),hours));}
    private JsonNode read(String id) throws Exception {for(var b:call("GET","/v1/admin/coupon-deliveries?storeId=store1",admin,null,null).body())if(b.path("content").path("batchId").asString().equals(id))return b;throw new AssertionError("batch missing");}
    private JsonNode control(String id,String action) throws Exception {return post("/v1/admin/coupon-deliveries/"+id+"/control",admin,UUID.randomUUID().toString(),Map.of("expectedVersion",read(id).path("version").asLong(),"action",action,"reason","隔离发券验证"));}
    private void pump() throws Exception {post("/v1/admin/coupon-deliveries/pump",admin,null,null);}
    private JsonNode wallet() throws Exception {return call("GET","/v1/coupons",member,null,null).body();}
    @Test void fixedAudienceSurvivesNewVersionAndConcurrentWorkersDoNotDuplicate() throws Exception {
        assets(100,7);var ids=new ArrayList<String>();ids.add("m1");for(int i=0;i<22;i++){String id=String.format("n%02d",i);add(id);ids.add(id);}
        post("/v1/admin/members/n02/status",admin,"freeze",Map.of("expectedVersion",0,"value","FROZEN","reason","冻结会员不发券"));
        audience(1,ids);var initial=batch("fixed",1,24);assertEquals(initial,batch("fixed",1,24));audience(2,List.of("m1"));
        pump();assertEquals(20,read("fixed").path("processed").asInt());assertEquals("RUNNING",read("fixed").path("status").asString());
        try(var pool=Executors.newFixedThreadPool(2)) {var a=pool.submit(()->call("POST","/v1/admin/coupon-deliveries/pump",admin,null,null));var b=pool.submit(()->call("POST","/v1/admin/coupon-deliveries/pump",admin,null,null));assertEquals(200,a.get().status());assertEquals(200,b.get().status());}
        pump();var done=read("fixed");assertEquals("COMPLETED",done.path("status").asString());assertEquals(23,done.path("processed").asInt());assertEquals(22,done.path("issued").asInt());assertEquals(1,done.path("skipped").asInt());
        assertEquals(22,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void memberFrequencyCannotBeReducedByAnotherBatch() throws Exception {
        assets(20,1);audience(1,List.of("m1"));batch("a",1,24);pump();batch("b",1,1);pump();
        assertEquals(1,read("b").path("skipped").asInt());assertEquals("FREQUENCY_LIMIT",call("GET","/v1/admin/coupon-deliveries/b/recipients",admin,null,null).body().get(0).path("errorCode").asString());
        testClock.at=testClock.instant().plusSeconds(86400);audience(2,List.of("m1"));batch("c",2,1);pump();assertEquals(1,read("c").path("issued").asInt());assertEquals(2,wallet().size());
    }
    @Test void quotaFailurePreservesCursorAndCanBeIsolatedRetriedStoppedAndRevoked() throws Exception {
        assets(1,7);add("m2");audience(1,List.of("m1","m2"));batch("limited",1,1);pump();
        assertEquals(1,read("limited").path("processed").asInt());assertEquals("m1",read("limited").path("cursorMember").asString());
        for(int i=0;i<4;i++){testClock.at=testClock.instant().plusSeconds(120);pump();}
        assertEquals("ISOLATED",read("limited").path("status").asString());assertEquals(5,read("limited").path("attempts").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM automation_coupon_recipient WHERE tenant_id=?",Integer.class,tenant));
        control("limited","RETRY");assertEquals("RUNNING",read("limited").path("status").asString());control("limited","CANCEL");control("limited","REVOKE");pump();
        assertEquals("REVOCATION_DONE",read("limited").path("status").asString());assertEquals(1,read("limited").path("revoked").asInt());assertEquals("REVOKED",wallet().get(0).path("status").asString());
        assertEquals(1,jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void cancellationExpirationAndAuthorizationDoNotCreateEffects() throws Exception {
        assets(10,1);audience(1,List.of("m1"));batch("cancel",1,1);control("cancel","CANCEL");pump();assertEquals(0,wallet().size());
        batch("expire",1,1);testClock.at=testClock.instant().plusSeconds(3600);pump();assertEquals("EXPIRED",read("expire").path("status").asString());assertEquals(0,wallet().size());
        assertEquals(403,call("POST","/v1/admin/coupon-deliveries/pump",member,null,null).status());
        assertEquals(404,call("GET","/v1/admin/coupon-deliveries/cancel/recipients",token("foreign-"+UUID.randomUUID(),"admin","ADMIN"),null,null).status());
        assertEquals(400,call("POST","/v1/admin/coupon-deliveries/cancel/control",admin,"bad",Map.of("expectedVersion",1,"reason","缺少动作")).status());
    }
    private JsonNode quote(String coupon,String key) throws Exception {return post("/v1/quotes",member,key,Map.of("storeId","store1","couponId",coupon,"items",List.of(Map.of("skuId","sku1","quantity",1))));}
    private JsonNode order(JsonNode quote) throws Exception{return post("/v1/orders",member,"order",Map.of("quoteId",quote.path("quoteId").asString(),"address",Map.of("recipient","定向券验收","phone","13800000000","detail","隔离地址")));}
    @Test void relativeCouponSurvivesIssuanceWindowAndCancellationNeverRenewsIt() throws Exception {
        seed();var start=testClock.instant();
        post("/v1/admin/coupon-definitions",admin,"relative",new com.lrj.commerce.benefit.api.CouponApi.Definition("relative",1,"store1","领取后一天有效","0.00","5.00",start.minusSeconds(1),start.plusSeconds(3600),10,true,10000,"PUBLIC",1));
        var coupon=post("/v1/coupons/relative/1/claim",member,"claim",null);assertEquals(start.plusSeconds(86400),Instant.parse(coupon.path("validTo").asString()));
        post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",2));
        testClock.at=start.plusSeconds(7200);var order=order(quote(coupon.path("couponId").asString(),"quote-after-issue"));
        testClock.at=start.plusSeconds(86401);post("/v1/orders/"+order.path("orderId").asString()+"/cancel",member,"cancel",null);
        assertEquals(coupon.path("validTo"),wallet().get(0).path("validTo"));
        assertEquals(409,call("POST","/v1/quotes",member,"expired",Map.of("storeId","store1","couponId",coupon.path("couponId").asString(),"items",List.of(Map.of("skuId","sku1","quantity",1)))).status());
    }
    @Test void revocationPreservesHeldCouponsAndReportsTheReason() throws Exception {
        assets(10,7);audience(1,List.of("m1"));batch("held",1,1);pump();
        post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",2));order(quote(wallet().get(0).path("couponId").asString(),"quote"));
        control("held","REVOKE");pump();assertEquals(1,read("held").path("kept").asInt());assertEquals(0,read("held").path("revoked").asInt());
        assertEquals("COUPON_HELD",call("GET","/v1/admin/coupon-deliveries/held/recipients",admin,null,null).body().get(0).path("errorCode").asString());assertEquals("HELD",wallet().get(0).path("status").asString());
    }
}
