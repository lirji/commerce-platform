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
class PointOfferTest {
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
    private Object coupon(String mode,int quota) {
        return new com.lrj.commerce.benefit.api.CouponApi.Definition("exchange-coupon",1,"store1","积分专享券","0.00","5.00",testClock.instant().minusSeconds(10),testClock.instant().plusSeconds(3600),quota,true,10000,mode);
    }
    private Object offer(String id,String kind,String asset,int quota,int cap,long cost) {
        return new com.lrj.commerce.benefit.api.PointOfferApi.Offer(id,"store1","积分礼遇",com.lrj.commerce.benefit.api.PointOfferApi.Kind.valueOf(kind),asset,1,cost,quota,cap,testClock.instant(),testClock.instant().plusSeconds(1800));
    }
    private void couponOffer(int assetQuota,int quota,int cap) throws Exception {
        spending(10000,2000);
        post("/v1/admin/coupon-definitions",admin,"coupon",coupon("SOURCE_ONLY",assetQuota));
        post("/v1/admin/point-offers",admin,"offer",offer("coupon","COUPON","exchange-coupon",quota,cap,200));
    }
    private JsonNode redeem(String id,String key) throws Exception {return post("/v1/point-offers/"+id+"/redeem",member,key,null);}
    @Test void couponExchangeIsAtomicIdempotentAndCannotBeClaimedForFree() throws Exception {
        couponOffer(10,10,3);
        assertEquals(409,call("POST","/v1/coupons/exchange-coupon/1/claim",member,"free",null).status());
        assertEquals(0,call("GET","/v1/coupon-definitions?storeId=store1",member,null,null).body().size());
        var first=redeem("coupon","exchange-1");var replay=redeem("coupon","exchange-1");assertEquals(first,replay);
        var second=redeem("coupon","exchange-2");assertNotEquals(first.path("assetId"),second.path("assetId"));
        assertEquals(1600,wallet().path("available").asLong());
        assertEquals(2,call("GET","/v1/coupons",member,null,null).body().size());
        assertEquals(2,call("GET","/v1/point-redemptions",member,null,null).body().size());
        assertEquals(2,jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void exhaustedAssetRollsBackPointsOfferCountAndReceipt() throws Exception {
        couponOffer(1,10,10);redeem("coupon","first");
        var before=wallet();assertEquals(409,call("POST","/v1/point-offers/coupon/redeem",member,"second",null).status());assertEquals(before,wallet());
        assertEquals(1,jdbc.queryForObject("SELECT issued FROM benefit_point_offer WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM member_point_exchange WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT redeemed FROM benefit_point_offer_member WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void entitlementIsRequestedExactlyOnceThenDeliveredByExistingConsumer() throws Exception {
        spending(10000,2000);
        post("/v1/admin/entitlement-definitions",admin,"asset",new com.lrj.commerce.benefit.api.EntitlementApi.Definition("coffee",1,"store1","咖啡权益",2,10,testClock.instant().minusSeconds(1),testClock.instant().plusSeconds(3600),7));
        post("/v1/admin/point-offers",admin,"offer",offer("coffee","ENTITLEMENT","coffee",10,2,500));
        var receipt=redeem("coffee","coffee-1");redeem("coffee","coffee-1");
        assertEquals(1500,wallet().path("available").asLong());
        var pending=call("GET","/v1/entitlements",member,null,null).body().get(0);assertEquals("REQUESTED",pending.path("status").asString());assertEquals("POINTS",pending.path("sourceType").asString());
        assertEquals(receipt.path("redemptionId"),pending.path("sourceId"));
        for(int i=0;i<4;i++)post("/v1/admin/events/pump",admin,null,null);
        var delivered=call("GET","/v1/entitlements",member,null,null).body().get(0);assertEquals("AVAILABLE",delivered.path("status").asString());assertEquals(2,delivered.path("remainingUnits").asInt());
    }
    @Test void concurrentRedemptionsRespectMemberAndGlobalLimits() throws Exception {
        couponOffer(10,10,1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->call("POST","/v1/point-offers/coupon/redeem",member,"a",null));
            var b=pool.submit(()->call("POST","/v1/point-offers/coupon/redeem",member,"b",null));
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get().status(),b.get().status()).sorted().toList());
        }
        assertEquals(1800,wallet().path("available").asLong());
        String second=token(tenant,"buyer2","MEMBER");
        post("/v1/admin/members",admin,"member2",Map.of("memberId","m2","actorId","buyer2","displayName","会员二","memberLevel","BASIC"));
        post("/v1/admin/member-points/m2/adjust",admin,"points2",Map.of("expectedVersion",0,"delta",1000,"reason","并发全局限额测试"));
        post("/v1/admin/point-offers",admin,"global",offer("global","COUPON","exchange-coupon",1,10,200));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->call("POST","/v1/point-offers/global/redeem",member,"ga",null));
            var b=pool.submit(()->call("POST","/v1/point-offers/global/redeem",second,"gb",null));
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get().status(),b.get().status()).sorted().toList());
        }
        assertEquals(2600,wallet().path("available").asLong()+call("GET","/v1/members/me/points",second,null,null).body().path("available").asLong());
    }
    @Test void disabledExpiredFrozenAndCrossTenantRequestsCannotSpend() throws Exception {
        couponOffer(10,10,10);
        assertEquals(403,call("POST","/v1/point-offers/coupon/redeem",admin,"admin-spend",null).status());
        assertNotEquals(200,call("POST","/v1/point-offers/coupon/redeem",other,"foreign",null).status());
        post("/v1/admin/point-offers/coupon/status",admin,"off",Map.of("expectedVersion",0,"active",false,"reason","暂停兑换"));
        assertEquals(409,call("POST","/v1/point-offers/coupon/redeem",member,"inactive",null).status());
        assertEquals(0,call("GET","/v1/point-offers?storeId=store1",member,null,null).body().size());
        assertEquals(409,call("POST","/v1/admin/point-offers/coupon/status",admin,"stale",Map.of("expectedVersion",0,"active",true,"reason","过期版本")).status());
        post("/v1/admin/point-offers/coupon/status",admin,"on",Map.of("expectedVersion",1,"active",true,"reason","恢复兑换"));
        post("/v1/admin/members/m1/status",admin,"freeze",Map.of("expectedVersion",0,"value","FROZEN","reason","冻结校验"));
        assertEquals(409,call("POST","/v1/point-offers/coupon/redeem",member,"frozen",null).status());
        post("/v1/admin/members/m1/status",admin,"thaw",Map.of("expectedVersion",1,"value","ACTIVE","reason","恢复校验"));
        testClock.at=testClock.instant().plusSeconds(1800);
        assertEquals(409,call("POST","/v1/point-offers/coupon/redeem",member,"expired",null).status());
        assertEquals(2000,wallet().path("available").asLong());
    }
    @Test void exchangeConsumesOriginalLotsAndRefundCreatesDebtWithoutRevivingExpiredCredit() throws Exception {
        seed();var at=testClock.instant();policy(1,"1.00",at);fact("earn","earned","500.00",at,true,null,null);
        post("/v1/admin/coupon-definitions",admin,"coupon",coupon("SOURCE_ONLY",10));
        post("/v1/admin/point-offers",admin,"offer",offer("coupon","COUPON","exchange-coupon",10,10,200));
        redeem("coupon","spend");fact("refund","earned","500.00",at,true,"full-refund","500.00");
        assertEquals(200,wallet().path("debt").asLong());assertEquals(0,wallet().path("available").asLong());
        assertEquals(409,call("POST","/v1/point-offers/coupon/redeem",member,"insufficient",null).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM benefit_point_redemption WHERE tenant_id=?",Integer.class,tenant));
    }
}
