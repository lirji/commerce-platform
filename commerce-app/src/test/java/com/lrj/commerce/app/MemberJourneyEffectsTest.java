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
class MemberJourneyEffectsTest {
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
    private Map<String,Object> controls(String segment) {
        var value=new HashMap<String,Object>();value.put("maxEntries",1);value.put("entryWindowSeconds",86400);value.put("notificationLimit",1);value.put("notificationWindowSeconds",86400);if(segment!=null)value.put("segmentId",segment);return value;
    }
    private void journey(String id,String trigger,Object controls) throws Exception {
        var value=Map.of("journeyId",id,"version",1,"storeId","store1","name","会员事件旅程","trigger",trigger,"validFrom",Instant.now().minusSeconds(60).toString(),"validTo",Instant.now().plusSeconds(3600).toString(),"maxDurationSeconds",300,"entry","notice1","nodes",List.of(Map.of("id","notice1","kind","NOTIFY","next","notice2","title","第一条","body","欢迎会员"),Map.of("id","notice2","kind","NOTIFY","next","end","title","第二条","body","本条受频控"),Map.of("id","end","kind","END")));
        var draft=new HashMap<String,Object>(value);draft.put("controls",controls);post("/v1/admin/journeys",admin,"journey-"+id,draft);
        int expected=0;for(String action:List.of("submit","approve","publish"))post("/v1/admin/journeys/"+id+"/1/"+action,admin,id+action,Map.of("expectedVersion",expected++));
    }
    private void events() throws Exception {for(int i=0;i<10;i++)if(post("/v1/admin/events/pump",admin,null,null).asInt()==0)break;}
    private void execute() throws Exception {for(int i=0;i<5;i++)post("/v1/admin/journeys/pump",admin,null,null);}
    @Test void registrationOnlyTriggersPublishedJourneysAndNotificationCapsAreDurable() throws Exception {
        seed();journey("welcome","MEMBER_REGISTERED",controls(null));events();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?",Integer.class,tenant));
        post("/v1/admin/members",admin,"new",Map.of("memberId","m2","actorId","new-buyer","displayName","新会员","memberLevel","BASIC"));events();execute();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM journey_notification WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM journey_effect WHERE tenant_id=? AND kind='NOTIFY_SUPPRESSED'",Integer.class,tenant));
        jdbc.update("UPDATE platform_event SET status='PENDING',available_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND event_type='member.registered.v1'",tenant);events();execute();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?",Integer.class,tenant));
        String report="/v1/admin/journey-effects?storeId=store1&from="+Instant.now().minusSeconds(86400)+"&to="+Instant.now().plusSeconds(3600);
        var effect=call("GET",report,admin,null,null).body().get(0);assertEquals(1,effect.path("enrolled").asInt());assertEquals(1,effect.path("completed").asInt());assertEquals(1,effect.path("notificationSuppressed").asInt());
    }
    @Test void segmentEntryIsDiffedBetweenCompleteSnapshotsAndEntryCapsSurviveReentry() throws Exception {
        seed();journey("segment-welcome","SEGMENT_ENTERED",controls("vip"));
        post("/v1/admin/segments",admin,"segment",Map.of("segmentId","vip","version",1,"name","VIP人群","rule",Map.of("kind","COMPARE","field","memberLevel","operator","EQ","valueType","TEXT","value","VIP"),"ttlSeconds",3600,"refreshSeconds",0,"maxMembers",100));
        refreshSegment("one");events();execute();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?",Integer.class,tenant));
        refreshSegment("same");events();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='segment.member.entered.v1'",Integer.class,tenant));
        post("/v1/admin/members/m1/status",admin,"freeze",Map.of("expectedVersion",0,"value","FROZEN","reason","暂离"));refreshSegment("leave");events();
        post("/v1/admin/members/m1/status",admin,"resume",Map.of("expectedVersion",1,"value","ACTIVE","reason","恢复"));refreshSegment("reentry");events();execute();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM journey_effect WHERE tenant_id=? AND kind='ENTRY_SUPPRESSED'",Integer.class,tenant));
    }
    private void refreshSegment(String key) throws Exception {post("/v1/admin/segments/vip/refresh",admin,key,null);post("/v1/admin/segments/pump",admin,null,null);}
    @Test void levelChangeUsesEntryRuleAndFrozenMemberStopsPendingNodes() throws Exception {
        seed();var controls=controls(null);controls.put("entryRule",Map.of("kind","COMPARE","field","memberLevel","operator","EQ","valueType","TEXT","value","GOLD"));journey("upgrade","LEVEL_CHANGED",controls);
        post("/v1/admin/member-growth/policies",admin,"policy",Map.of("version",1,"effectiveFrom",Instant.now().toString(),"growthPerYuan","1","levels",List.of(Map.of("code","BASIC","minimumGrowth",0),Map.of("code","GOLD","minimumGrowth",20))));
        post("/v1/admin/member-growth/m1/recalculate",admin,"base",null);events();
        post("/v1/admin/member-growth/m1/adjust",admin,"grow",Map.of("expectedVersion",1,"delta",25,"reason","升级测试"));events();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?",Integer.class,tenant));
        long version=jdbc.queryForObject("SELECT version FROM member_record WHERE tenant_id=? AND member_id='m1'",Long.class,tenant);
        post("/v1/admin/members/m1/status",admin,"freeze",Map.of("expectedVersion",version,"value","FROZEN","reason","冻结中止触达"));execute();
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM journey_instance WHERE tenant_id=?",String.class,tenant));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM journey_notification WHERE tenant_id=?",Integer.class,tenant));
    }
    /** 真实数据库竞争下频控只允许一个入组；冲突请求可重试，不能额外记账或发通知。 */
    @Test void concurrentEnrollmentCannotExceedMemberCap() throws Exception {
        seed();journey("parallel","MANUAL",controls(null));
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var calls=new ArrayList<Future<Reply>>();
            for(int n=0;n<6;n++){int index=n;calls.add(pool.submit(()->{gate.await();return call("POST","/v1/admin/journey-instances",admin,"parallel-"+index,Map.of("journeyId","parallel","version",1,"memberId","m1","eventKey","source-"+index));}));}
            gate.countDown();int successes=0;
            for(var f:calls){var response=f.get(20,TimeUnit.SECONDS);assertTrue(Set.of(200,409).contains(response.status()),response.body().toString());if(response.status()==200)successes++;}
            assertEquals(1,successes);
        }
        execute();
        assertEquals(1,jdbc.queryForObject("SELECT entries FROM journey_member_cap WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM journey_notification WHERE tenant_id=?",Integer.class,tenant));
    }

}
