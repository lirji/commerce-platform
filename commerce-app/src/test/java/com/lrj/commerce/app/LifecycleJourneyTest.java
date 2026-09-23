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
class LifecycleJourneyTest {
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
    @Autowired com.lrj.commerce.member.api.MemberGrowthApi growth;
    @Autowired com.lrj.commerce.member.api.MemberBehaviorApi behavior;
    private void setup() throws Exception {seed();testClock.at=Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);}
    private Object node(String id,String kind,String next){return kind.equals("END")?Map.of("id",id,"kind",kind):kind.equals("WAIT")?Map.of("id",id,"kind",kind,"next",next,"seconds",60):Map.of("id",id,"kind",kind,"next",next,"title","生命周期关怀","body","会员站内关怀");}
    private Map<String,Object> definition(String id,String trigger,List<Object> nodes){
        var value=new LinkedHashMap<String,Object>();value.put("journeyId",id);value.put("version",1);value.put("storeId","store1");value.put("name","生命周期测试");value.put("trigger",trigger);value.put("validFrom",testClock.instant().minusSeconds(1));value.put("validTo",testClock.instant().plusSeconds(3*86400));value.put("maxDurationSeconds",3600);value.put("entry",((Map<?,?>)nodes.getFirst()).get("id"));value.put("nodes",nodes);
        value.put("controls",Map.of("maxEntries",2,"entryWindowSeconds",86400,"notificationLimit",2,"notificationWindowSeconds",86400));
        if(!trigger.equals("MANUAL"))value.put("lifecycle",Map.of("thresholdDays",7,"cartDelaySeconds",60,"scanIntervalSeconds",300,"conversionWindowDays",7));return value;
    }
    private void publish(String id,String trigger,List<Object> nodes) throws Exception {post("/v1/admin/journeys",admin,"create-"+id,definition(id,trigger,nodes));int version=0;for(String action:List.of("submit","approve","publish"))post("/v1/admin/journeys/"+id+"/1/"+action,admin,id+"-"+action,Map.of("expectedVersion",version++));}
    private void profile(String id,String birthday,boolean enabled,long version) throws Exception {post("/v1/admin/member-behavior/"+id+"/profile",admin,UUID.randomUUID().toString(),Map.of("expectedVersion",version,"birthday",birthday,"journeyEnabled",enabled,"reason","生命周期验证"));}
    private void pump() throws Exception {post("/v1/admin/journeys/pump",admin,null,null);}
    private JsonNode instances() throws Exception {return call("GET","/v1/admin/journey-instances",admin,null,null).body();}
    private void events() throws Exception {for(int i=0;i<10;i++)if(post("/v1/admin/events/pump",admin,null,null).asInt()==0)break;}
    private void fact(String id,int days,String amount){var at=testClock.instant().minusSeconds(days*86400L);var f=new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact(id,"m1",amount,at,true,null,null);commands.run(new Actor(tenant,"admin",Actor.Role.ADMIN),"test.lifecycle.fact",id,f,String.class,()->{growth.observe(tenant,f);behavior.projectOrder(tenant,id,at);return "ok";});}
    @Test void birthdayWaitHonorsPreferenceAndManualEnrollmentAlsoRejectsDisabledMember() throws Exception {
        setup();profile("m1",MonthDay.from(testClock.instant().atZone(ZoneOffset.UTC)).toString().substring(2),true,0);
        publish("birthday","BIRTHDAY",List.of(node("wait","WAIT","notify"),node("notify","NOTIFY","end"),node("end","END",null)));
        pump();assertEquals("WAITING",instances().get(0).path("status").asString());profile("m1","01-01",false,1);testClock.at=testClock.instant().plusSeconds(61);pump();
        assertEquals("CANCELLED",instances().get(0).path("status").asString());assertEquals("MEMBER_DISABLED",instances().get(0).path("result").asString());assertEquals(0,call("GET","/v1/notifications",member,null,null).body().size());
        publish("manual","MANUAL",List.of(node("end","END",null)));assertEquals(409,call("POST","/v1/admin/journey-instances",admin,"off",Map.of("journeyId","manual","version",1,"memberId","m1","eventKey","off")).status());
        assertEquals(403,call("GET","/v1/admin/journey-scans",member,null,null).status());
    }
    @Test void leapBirthdayGrantsCouponExactlyOnceAcrossRepeatedScans() throws Exception {
        setup();testClock.at=Instant.parse("2028-02-29T08:00:00Z");profile("m1","02-29",true,0);
        post("/v1/admin/coupon-definitions",admin,"coupon",new com.lrj.commerce.benefit.api.CouponApi.Definition("birthday",1,"store1","生日礼券","0.00","5.00",testClock.instant().minusSeconds(1),testClock.instant().plusSeconds(5*86400),10,true,10000,"SOURCE_ONLY",7));
        publish("birthday","BIRTHDAY",List.of(Map.of("id","coupon","kind","COUPON","next","end","coupon",Map.of("definitionId","birthday","version",1)),node("end","END",null)));
        pump();pump();testClock.at=testClock.instant().plusSeconds(301);pump();pump();assertEquals(1,instances().size());
        var wallet=call("GET","/v1/coupons",member,null,null).body();assertEquals(1,wallet.size());assertEquals("JOURNEY",jdbc.queryForObject("SELECT source_type FROM benefit_coupon WHERE tenant_id=?",String.class,tenant));assertEquals(7*86400,Duration.between(Instant.parse(wallet.get(0).path("validFrom").asString()),Instant.parse(wallet.get(0).path("validTo").asString())).getSeconds());
        testClock.at=Instant.parse("2029-02-28T08:00:00Z");publish("nonleap","BIRTHDAY",List.of(node("end","END",null)));pump();assertEquals(1,instances().size());
    }
    @Test void dormantAndRepurchaseUseCompletedNetFactsAndNewMembersDoNotQualify() throws Exception {
        setup();Instant originalAt=testClock.instant().minusSeconds(10*86400L);fact("old",10,"100.00");post("/v1/admin/members",admin,"new-member",Map.of("memberId","m2","actorId","new","displayName","新会员","memberLevel","BASIC"));testClock.at=Instant.now().plusSeconds(3);
        publish("dormant","DORMANT",List.of(node("end","END",null)));publish("repeat","REPURCHASE",List.of(node("end","END",null)));pump();pump();assertEquals(2,instances().size());for(var row:instances())assertEquals("m1",row.path("memberId").asString());
        var f=new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact("old","m1","100.00",originalAt,true,"full","100.00");commands.run(new Actor(tenant,"admin",Actor.Role.ADMIN),"test.lifecycle.refund","full",f,String.class,()->{growth.observe(tenant,f);behavior.projectOrder(tenant,"old",originalAt);return "ok";});
        publish("repeat-after-return","REPURCHASE",List.of(node("end","END",null)));pump();assertEquals(2,instances().size());
    }
    private String pay(String coupon,String key) throws Exception {
        var basket=new LinkedHashMap<String,Object>();basket.put("storeId","store1");basket.put("items",List.of(Map.of("skuId","sku1","quantity",1)));if(coupon!=null)basket.put("couponId",coupon);
        var q=post("/v1/quotes",member,"quote-"+key,basket);var o=post("/v1/orders",member,"order-"+key,Map.of("quoteId",q.path("quoteId").asString(),"address",Map.of("recipient","旅程测试","phone","13800000000","detail","隔离地址")));String id=o.path("orderId").asString();var p=post("/v1/orders/"+id+"/payments",member,"pay-"+key,null);post("/v1/admin/sandbox/payments/"+p.path("paymentId").asString()+"/fact",admin,"paid-"+key,Map.of("status","PAID"));post("/v1/orders/"+id+"/payment/reconcile",member,null,null);return id;
    }
    @Test void cartTriggerIsStoreScopedAndRechecksRealPaidOrderAfterWaiting() throws Exception {
        setup();post("/v1/admin/stores",admin,"store2",Map.of("storeId","store2","merchantId","merchant1","name","另一门店"));post("/v1/admin/skus",admin,"sku2",Map.of("skuId","sku2","storeId","store2","title","另一商品","unitPrice","25.00"));
        post("/v1/members/me/behavior/events",member,"cart2",Map.of("eventId","cart2","kind","ADD_TO_CART","storeId","store2","skuId","sku2"));testClock.at=testClock.instant().plusSeconds(61);
        publish("cart","CART_ABANDONED",List.of(node("wait","WAIT","notify"),node("notify","NOTIFY","end"),node("end","END",null)));pump();assertEquals(0,instances().size());
        post("/v1/members/me/behavior/events",member,"cart1",Map.of("eventId","cart1","kind","ADD_TO_CART","storeId","store1","skuId","sku1"));testClock.at=testClock.instant().plusSeconds(301);pump();assertEquals("WAITING",instances().get(0).path("status").asString());
        post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",3));testClock.at=testClock.instant().plusSeconds(1);pay(null,"cart");events();testClock.at=testClock.instant().plusSeconds(61);pump();assertEquals("CART_PURCHASED",instances().get(0).path("result").asString());
        publish("already-paid","CART_ABANDONED",List.of(node("end","END",null)));pump();assertEquals(1,instances().size());
    }
    @Test void concurrentScannersKeepCheckpointAndCorruptDefinitionIsIsolatedRecoverably() throws Exception {
        setup();String birthday=MonthDay.from(testClock.instant().atZone(ZoneOffset.UTC)).toString().substring(2);profile("m1",birthday,true,0);
        for(int i=2;i<=10;i++){post("/v1/admin/members",admin,"m"+i,Map.of("memberId","m"+i,"actorId","a"+i,"displayName","扫描会员","memberLevel","BASIC"));profile("m"+i,birthday,true,0);}testClock.at=Instant.now().plusSeconds(3);
        publish("scan","BIRTHDAY",List.of(node("end","END",null)));
        String saved=jdbc.queryForObject("SELECT CAST(definition_json AS CHAR) FROM journey_definition WHERE tenant_id=?",String.class,tenant);
        // 在隔离库注入持久配置损坏，证明失败事务不会留下实例或推进游标。
        jdbc.update("UPDATE journey_definition SET definition_json=JSON_REMOVE(definition_json,'$.controls') WHERE tenant_id=?",tenant);
        for(int i=0;i<5;i++){pump();testClock.at=testClock.instant().plusSeconds(120);}var scan=call("GET","/v1/admin/journey-scans",admin,null,null).body().get(0);assertEquals("ISOLATED",scan.path("status").asString());assertEquals(0,instances().size());assertEquals(0,scan.path("scanned").asInt());
        jdbc.update("UPDATE journey_definition SET definition_json=? WHERE tenant_id=?",saved,tenant);post("/v1/admin/journey-scans/scan/1/retry",admin,"retry",Map.of("expectedVersion",scan.path("version").asLong(),"reason","已恢复定义"));
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->call("POST","/v1/admin/journeys/pump",admin,null,null));var b=pool.submit(()->call("POST","/v1/admin/journeys/pump",admin,null,null));assertEquals(200,a.get().status());assertEquals(200,b.get().status());}for(int i=0;i<5;i++)pump();assertEquals(10,instances().size());
        scan=call("GET","/v1/admin/journey-scans",admin,null,null).body().get(0);assertEquals(10,scan.path("enrolled").asInt());assertEquals("IDLE",scan.path("status").asString());
    }
    @Test void comparisonUsesUniqueMembersActualCouponAndRefundsWithoutDoubleCounting() throws Exception {
        setup();post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",3));
        publish("compare","MANUAL",List.of(node("notify","NOTIFY","end"),node("end","END",null)));
        for(int i=0;i<2;i++)post("/v1/admin/journey-instances",admin,"enroll-"+i,Map.of("journeyId","compare","version",1,"memberId","m1","eventKey","entry-"+i));pump();pump();
        post("/v1/admin/coupon-definitions",admin,"coupon",new com.lrj.commerce.benefit.api.CouponApi.Definition("target",1,"store1","分析券","0.00","5.00",testClock.instant().minusSeconds(1),testClock.instant().plusSeconds(86400),10,true,10000,"SOURCE_ONLY",7));
        post("/v1/admin/audiences",admin,"audience",new com.lrj.commerce.campaign.api.MarketingAssets.Audience("group",1,"人群","TEST",testClock.instant(),testClock.instant().plusSeconds(7200),List.of("m1")));
        post("/v1/admin/coupon-deliveries",admin,"batch",new com.lrj.commerce.journey.api.CouponDeliveryApi.Create("batch","store1","比较批次","target",1,new com.lrj.commerce.campaign.api.MarketingAssets.Ref("group",1),testClock.instant().plusSeconds(3600),1));post("/v1/admin/coupon-deliveries/pump",admin,null,null);
        String coupon=call("GET","/v1/coupons",member,null,null).body().get(0).path("couponId").asString();testClock.at=testClock.instant().plusSeconds(1);String order=pay(coupon,"compare");events();
        String query="?storeId=store1&from="+testClock.instant().minusSeconds(60)+"&to="+testClock.instant().plusSeconds(60);
        var row=call("GET","/v1/admin/marketing-effects/journeys"+query,admin,null,null).body().path("rows").get(0);assertEquals(1,row.path("enrolledMembers").asInt());assertEquals(1,row.path("paidMembers").asInt());assertEquals(1,row.path("paidOrders").asInt());assertEquals("20.00",row.path("received").asString());assertEquals(0,row.path("matureMembers").asInt());
        testClock.at=testClock.instant().plusSeconds(8*86400L);
        var request=post("/v1/aftersales",member,"return",Map.of("orderId",order,"reason","效果退款","items",List.of(Map.of("skuId","sku1","quantity",1))));var approved=post("/v1/admin/aftersales/"+request.path("caseId").asString()+"/approve",admin,"approve",null);String refund=approved.path("refundId").asString();post("/v1/admin/sandbox/refunds/"+refund+"/success",admin,"refund",null);post("/v1/admin/refunds/"+refund+"/reconcile",admin,null,null);events();
        post("/v1/admin/marketing-effects/rebuild",admin,"rebuild",Map.of("after","","limit",100));post("/v1/admin/marketing-effects/rebuild",admin,"rebuild-again",Map.of("after","","limit",100));
        row=call("GET","/v1/admin/marketing-effects/journeys"+query,admin,null,null).body().path("rows").get(0);assertEquals("20.00",row.path("refunded").asString());assertEquals("0.00",row.path("netReceipts").asString());assertEquals(1,row.path("paidOrders").asInt());assertEquals(1,row.path("matureMembers").asInt());
        row=call("GET","/v1/admin/marketing-effects/deliveries"+query,admin,null,null).body().path("rows").get(0);assertEquals("batch",row.path("batchId").asString());assertEquals("5.00",row.path("couponDiscount").asString());assertEquals("0.00",row.path("netReceipts").asString());assertEquals(1,row.path("paidOrders").asInt());
        assertEquals(403,call("GET","/v1/admin/marketing-effects/journeys"+query,member,null,null).status());
    }
}
