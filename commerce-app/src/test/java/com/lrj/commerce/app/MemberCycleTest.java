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
class MemberCycleTest {
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
        post("/v1/admin/member-growth/policies",admin,"policy"+version,Map.of("version",version,"effectiveFrom",effective.toString(),"growthPerYuan",rate,"levels",List.of(Map.of("code","BASIC","minimumGrowth",0),Map.of("code","GOLD","minimumGrowth",20))));
    }
    private void observe(String key,com.lrj.commerce.member.api.MemberGrowthApi.OrderFact fact) {
        commands.run(new Actor(tenant,"admin",Actor.Role.ADMIN),"test.growth.fact",key,fact,String.class,()->{growth.observe(tenant,fact);return "ok";});
    }
    private JsonNode wallet() throws Exception {return call("GET","/v1/members/me/growth",member,null,null).body();}
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
    @Autowired com.lrj.commerce.member.api.MemberCycleApi cycles;
    private void cyclePolicy(long version,int days,Instant at) throws Exception {
        post("/v1/admin/member-cycles/policies",admin,"cycle-policy"+version,Map.of("version",version,"effectiveFrom",at.toString(),"periodDays",days,"levels",List.of(Map.of("code","BASIC","minimumGrowth",0),Map.of("code","GOLD","minimumGrowth",100))));
    }
    private JsonNode evaluate(String key) throws Exception { return post("/v1/admin/member-cycles/m1/evaluate",admin,key,null); }
    private JsonNode cycle() throws Exception { return call("GET","/v1/members/me/cycle",member,null,null).body(); }
    private void fact(String key,String order,long amount,Instant at,String refund,String refundAmount) {
        observe(key,new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact(order,"m1",amount+".00",at,true,refund,refundAmount));
    }
    @Test void boundaryRetentionExpiryAndOriginalCycleRefundAreConsistent() throws Exception {
        seed();Instant start=testClock.instant();policy(1,"1.00",start);cyclePolicy(1,1,start);
        fact("earned","first",120,start.plusSeconds(1),null,null);
        assertEquals("GOLD",cycle().path("memberLevel").asString());
        assertEquals(120,cycle().path("currentGrowth").asLong());
        testClock.at=start.plusSeconds(86400);cycles.tick();
        assertEquals(0,cycle().path("currentGrowth").asLong());assertEquals(120,cycle().path("retentionGrowth").asLong());
        assertEquals("GOLD",cycle().path("memberLevel").asString());
        fact("refund","first",120,start.plusSeconds(1),"r1","100.00");
        assertEquals(20,cycle().path("retentionGrowth").asLong());assertEquals(0,cycle().path("currentGrowth").asLong());
        assertEquals("BASIC",cycle().path("memberLevel").asString());
        fact("current-earned","second",130,start.plusSeconds(86401),null,null);
        assertEquals("GOLD",cycle().path("memberLevel").asString());
        testClock.at=start.plusSeconds(4*86400L);cycles.tick();
        assertEquals("BASIC",cycle().path("memberLevel").asString());
        assertEquals(0,cycle().path("currentGrowth").asLong());assertEquals(0,cycle().path("retentionGrowth").asLong());
        assertEquals(150,wallet().path("growth").asLong(),"累计成长仍保留，不再覆盖周期等级");
        post("/v1/admin/member-growth/m1/recalculate",admin,"legacy-recalc",null);
        assertEquals("BASIC",wallet().path("memberLevel").asString());
    }
    @Test void concurrentEvaluationAndFactsDoNotDuplicateAssessments() throws Exception {
        seed();Instant start=testClock.instant();policy(1,"1.00",start);cyclePolicy(1,1,start);
        evaluate("initial");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->fact("a","order1",120,start.plusSeconds(1),null,null));
            var b=pool.submit(()->fact("b","order1",120,start.plusSeconds(1),null,null));a.get();b.get();
        }
        long version=cycle().path("version").asLong();
        evaluate("again");cycles.tick();assertEquals(version,cycle().path("version").asLong());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM member_cycle_contribution WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='member.cycle.assessed.v1'",Integer.class,tenant));
        var payload=Map.of("expectedVersion",wallet().path("version").asLong(),"delta",-100,"reason","人工纠错");
        post("/v1/admin/member-growth/m1/adjust",admin,"manual",payload);
        post("/v1/admin/member-growth/m1/adjust",admin,"manual",payload);
        assertEquals(20,cycle().path("currentGrowth").asLong());assertEquals("BASIC",cycle().path("memberLevel").asString());
    }
    @Test void dormantPolicyIsCompatibleAndBoundariesRejectUnauthorizedChanges() throws Exception {
        seed();Instant start=testClock.instant();policy(1,"1.00",start);
        fact("legacy","old",25,start,null,null);
        assertFalse(cycle().path("enabled").asBoolean());assertEquals("GOLD",wallet().path("memberLevel").asString());
        cyclePolicy(1,1,start.plusSeconds(60));evaluate("before");assertFalse(cycle().path("enabled").asBoolean());
        testClock.at=start.plusSeconds(60);cycles.tick();assertEquals("BASIC",cycle().path("memberLevel").asString());
        assertEquals(0,cycle().path("currentGrowth").asLong(),"启用前订单不得追溯参与");
        assertEquals(403,call("POST","/v1/admin/member-cycles/m1/evaluate",member,"forbidden",null).status());
        String foreign=token("foreign-"+UUID.randomUUID(),"admin","ADMIN");
        assertEquals(404,call("GET","/v1/admin/member-cycles/m1",foreign,null,null).status());
        assertEquals(400,call("POST","/v1/admin/member-cycles/policies",admin,"invalid",Map.of("version",2,"effectiveFrom",testClock.instant().toString(),"periodDays",0,"levels",List.of(Map.of("code","BASIC","minimumGrowth",0)))).status());
        var replay=evaluate("same-key");assertEquals(replay,evaluate("same-key"));
    }
}
