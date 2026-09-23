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
class MemberBehaviorTest {
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
    private Object signal(String id,String kind){return Map.of("eventId",id,"kind",kind,"storeId","store1","skuId","sku1");}
    private JsonNode record(String id,String kind) throws Exception {return post("/v1/members/me/behavior/events",member,"key-"+id,signal(id,kind));}
    private JsonNode detail() throws Exception{return call("GET","/v1/members/me/behavior",member,null,null).body();}
    private void fact(String order,String paid,Instant at,boolean complete,String refund,String amount) {
        var fact=new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact(order,"m1",paid,at,complete,refund,amount);
        commands.run(new Actor(tenant,"admin",Actor.Role.ADMIN),"test.behavior",UUID.randomUUID().toString(),fact,String.class,()->{growth.observe(tenant,fact);behavior.projectOrder(tenant,order,at);return "ok";});
    }
    @Test void sourceDeduplicationAndIdentityScopePreserveServerTime() throws Exception {
        seed();var event=record("view","BROWSE");
        var replay=post("/v1/members/me/behavior/events",member,"other-key",signal("view","BROWSE"));assertEquals(event,replay);
        assertEquals(testClock.instant(),Instant.parse(event.path("occurredAt").asString()));
        assertEquals(409,call("POST","/v1/members/me/behavior/events",member,"conflict",signal("view","ADD_TO_CART")).status());
        record("cart","ADD_TO_CART");var facts=detail().path("facts");assertEquals(1,facts.path("browse30").asInt());assertEquals(1,facts.path("cart30").asInt());assertTrue(facts.path("daysSinceOrder").isNull());
        assertEquals(403,call("GET","/v1/admin/member-behavior/m1",member,null,null).status());
        assertEquals(404,call("GET","/v1/admin/member-behavior/m1",token("foreign-"+UUID.randomUUID(),"admin","ADMIN"),null,null).status());
        assertNotEquals(200,call("POST","/v1/members/me/behavior/events",other,"other",signal("other","BROWSE")).status());
        assertEquals(400,call("POST","/v1/members/me/behavior/events",member,"missing-sku",Map.of("eventId","invalid","kind","BROWSE","storeId","store1")).status());
        post("/v1/operations/skus/sku1",admin,"off-sku",Map.of("storeId","store1","expectedVersion",1,"title","测试商品","unitPrice","25.00","status","FROZEN","reason","校验幂等回放"));
        assertEquals(event,record("view","BROWSE"),"商品下架后同幂等键仍回放原交互，不新增业务效果");
    }
    @Test void perMemberDailyCapAndUtcWindowAreEnforcedWithoutBlockingOtherMembers() throws Exception {
        seed();testClock.at=Instant.parse("2028-01-01T23:59:59Z");
        for(int i=0;i<200;i++)record("event-"+i,"BROWSE");
        assertEquals(409,call("POST","/v1/members/me/behavior/events",member,"over",signal("over","BROWSE")).status());
        post("/v1/members/me/behavior/events",member,"old-retry",signal("event-0","BROWSE"));
        var second=token(tenant,"buyer2","MEMBER");post("/v1/admin/members",admin,"second",Map.of("memberId","m2","actorId","buyer2","displayName","另一个会员","memberLevel","BASIC"));
        post("/v1/members/me/behavior/events",second,"independent",signal("event-0","BROWSE"));
        testClock.at=Instant.parse("2028-01-02T00:00:00Z");record("next-day","ADD_TO_CART");assertEquals(200,detail().path("facts").path("browse30").asInt());
        testClock.at=Instant.parse("2028-01-31T00:00:00Z");assertEquals(0,detail().path("facts").path("browse30").asInt());assertEquals(1,detail().path("facts").path("cart30").asInt());
    }
    @Test void birthdayPreferenceVersionAndLeapDayRemainExplicit() throws Exception {
        seed();post("/v1/members/me/behavior/profile",member,"birthday",Map.of("expectedVersion",0,"birthday","02-29","journeyEnabled",false,"reason","本人偏好"));
        testClock.at=Instant.parse("2028-02-29T12:00:00Z");assertTrue(detail().path("facts").path("birthdayToday").asBoolean());assertFalse(detail().path("facts").path("journeyEnabled").asBoolean());
        testClock.at=Instant.parse("2029-02-28T12:00:00Z");assertFalse(detail().path("facts").path("birthdayToday").asBoolean());
        assertEquals(409,call("POST","/v1/members/me/behavior/profile",member,"stale",Map.of("expectedVersion",0,"birthday","03-01","journeyEnabled",true,"reason","旧版本")).status());
        assertEquals(400,call("POST","/v1/members/me/behavior/profile",member,"invalid",Map.of("expectedVersion",1,"birthday","02-30","journeyEnabled",true,"reason","非法日期")).status());
    }
    @Test void completionRefundAndReplayKeepCountSeparateFromNetCash() throws Exception {
        seed();var now=testClock.instant();fact("order1","100.00",now.minusSeconds(86400),true,null,null);fact("order1","100.00",now.minusSeconds(86400),true,"refund1","40.00");fact("order1","100.00",now.minusSeconds(86400),true,"refund1","40.00");
        var facts=detail().path("facts");assertEquals(1,facts.path("completedOrders30").asInt());assertEquals("60.00",facts.path("netSpend30").asString());assertEquals(1,facts.path("daysSinceOrder").asLong());
        fact("order2","0.00",now,true,null,null);fact("old","20.00",now.minusSeconds(31*86400L),true,null,null);
        facts=detail().path("facts");assertEquals(2,facts.path("completedOrders30").asInt());assertEquals("60.00",facts.path("netSpend30").asString());assertEquals(now.toString(),facts.path("lastOrderAt").asString());
        fact("order1","100.00",now.minusSeconds(86400),true,"refund2","60.00");assertEquals("0.00",detail().path("facts").path("netSpend30").asString());
        assertEquals(2,detail().path("facts").path("completedOrders30").asInt());
    }
    @Test void newBehaviorFieldsSelectAudienceAndMissingRecencyStaysUnknown() throws Exception {
        seed();testClock.at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);record("cart","ADD_TO_CART");
        post("/v1/admin/segments",admin,"segment",Map.of("segmentId","cart","version",1,"name","加购人群","rule",Map.of("kind","COMPARE","field","memberCart30","operator","GTE","valueType","DECIMAL","value","1"),"ttlSeconds",3600,"refreshSeconds",0,"maxMembers",100));
        post("/v1/admin/segments/cart/refresh",admin,"refresh",null);post("/v1/admin/segments/pump",admin,null,null);
        var run=call("GET","/v1/admin/segments/cart/runs",admin,null,null).body().get(0);assertEquals("COMPLETED",run.path("status").asString());assertEquals(1,run.path("matched").asInt());
        var facts=com.lrj.commerce.campaign.api.MemberRuleFacts.from(growth.facts(tenant,"m1"),null);assertFalse(facts.containsKey("memberDaysSinceOrder"));assertTrue(facts.containsKey("memberCart30"));
    }
    @Test void actualOrderEventAndRebuildPopulateSameProjectionOnce() throws Exception {
        seed();post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",2));
        var q=post("/v1/quotes",member,"quote",Map.of("storeId","store1","items",List.of(Map.of("skuId","sku1","quantity",1))));
        var order=post("/v1/orders",member,"order",Map.of("quoteId",q.path("quoteId").asString(),"address",Map.of("recipient","行为验收","phone","13800000000","detail","隔离地址")));
        String id=order.path("orderId").asString();var paid=post("/v1/orders/"+id+"/payments",member,"pay",null);
        post("/v1/admin/sandbox/payments/"+paid.path("paymentId").asString()+"/fact",admin,"paid",Map.of("status","PAID"));
        post("/v1/orders/"+id+"/payment/reconcile",member,null,null);pump();
        post("/v1/admin/fulfillments/"+id+"/ship",admin,"ship",Map.of("trackingNo","BEHAVIOR-TRACK"));
        post("/v1/admin/fulfillments/"+id+"/deliver",admin,"deliver",null);pump();
        var facts=detail().path("facts");assertEquals(1,facts.path("completedOrders30").asInt());assertEquals("25.00",facts.path("netSpend30").asString());
        var rebuilt=post("/v1/admin/member-behavior/rebuild",admin,"rebuild",Map.of("after","","limit",50));assertEquals(1,rebuilt.path("scanned").asInt());assertTrue(rebuilt.path("done").asBoolean());
        post("/v1/admin/member-behavior/rebuild",admin,"rebuild-again",Map.of("after","","limit",50));
        assertEquals(1,detail().path("facts").path("completedOrders30").asInt());
    }
    private void pump() throws Exception {for(int i=0;i<4;i++)post("/v1/admin/events/pump",admin,null,null);}

}
