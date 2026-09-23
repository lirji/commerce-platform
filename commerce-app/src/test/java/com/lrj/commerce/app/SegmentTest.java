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
class SegmentTest {
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
    private Map<String,Object> segment(String id,int max) {
        return Map.of("segmentId",id,"version",1,"name","VIP动态人群","rule",Map.of("kind","COMPARE","field","memberLevel","operator","EQ","valueType","TEXT","value","VIP"),"ttlSeconds",3600,"refreshSeconds",60,"maxMembers",max);
    }
    private void bulk(int count) {
        for(int i=0;i<count;i++)jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,?,?,?,?)",tenant,String.format("batch-%03d",i),"actor-"+i,"分群测试","VIP");
    }
    private void pump() throws Exception {post("/v1/admin/segments/pump",admin,null,null);}
    private JsonNode run(String id) throws Exception {return call("GET","/v1/admin/segments/"+id+"/runs",admin,null,null).body().get(0);}
    @Test void refreshPublishesOnlyCompleteSnapshotAndKeepsOldVersionImmutable() throws Exception {
        seed();bulk(120);var definition=post("/v1/admin/segments",admin,"s",segment("vip",1000));String audience=definition.path("audienceId").asString();
        var started=post("/v1/admin/segments/vip/refresh",admin,"r",null);
        assertEquals(started.path("runId"),post("/v1/admin/segments/vip/refresh",admin,"same-active",null).path("runId"));
        pump();assertEquals(100,run("vip").path("processed").asInt());assertEquals("RUNNING",run("vip").path("status").asString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM marketing_audience_snapshot WHERE tenant_id=?",Integer.class,tenant));
        jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,created_at) VALUES(?,?,?,?,?,?)",tenant,"a-late","late-actor","任务开始后创建","VIP",java.sql.Timestamp.from(Instant.parse(started.path("startedAt").asString()).plusSeconds(1)));
        pump();assertEquals("COMPLETED",run("vip").path("status").asString());assertEquals(121,run("vip").path("matched").asInt());
        assertEquals(121,jdbc.queryForObject("SELECT member_count FROM marketing_audience_snapshot WHERE tenant_id=? AND version=1",Integer.class,tenant));
        post("/v1/admin/members/m1/status",admin,"freeze",Map.of("expectedVersion",0,"value","FROZEN","reason","冻结后不能入群"));
        post("/v1/admin/segments/vip/refresh",admin,"r2",null);pump();pump();
        assertEquals(121,jdbc.queryForObject("SELECT member_count FROM marketing_audience_snapshot WHERE tenant_id=? AND version=1",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM marketing_audience_member WHERE tenant_id=? AND version=2 AND member_id='m1'",Integer.class,tenant));
        assertEquals(403,call("POST","/v1/admin/segments/vip/refresh",member,"deny",null).status());
        assertEquals(400,call("POST","/v1/admin/audiences",admin,"reserved",Map.of("audienceId",audience,"version",9,"name","手工抢占","source","test","watermark",Instant.now().toString(),"validUntil",Instant.now().plusSeconds(3600).toString(),"memberIds",List.of())).status());
    }
    @Test void failedBatchIsIsolatedAndCanResumeFromCommittedCheckpoint() throws Exception {
        seed();bulk(110);String audience=post("/v1/admin/segments",admin,"s",segment("recovery",1000)).path("audienceId").asString();
        String id=post("/v1/admin/segments/recovery/refresh",admin,"r",null).path("runId").asString();pump();
        // 在隔离租户注入下一批投影冲突，验证重试回滚不会前移检查点。
        jdbc.update("INSERT INTO marketing_audience_member(tenant_id,audience_id,version,member_id) VALUES(?,?,1,'batch-100')",tenant,audience);
        for(int i=0;i<5;i++){jdbc.update("UPDATE marketing_segment_run SET available_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND run_id=?",tenant,id);pump();}
        assertEquals("ISOLATED",run("recovery").path("status").asString());assertEquals(100,run("recovery").path("processed").asInt());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM marketing_audience_snapshot WHERE tenant_id=?",Integer.class,tenant));
        // 仅移除本测试注入的冲突行；正式恢复不删除完整快照。
        jdbc.update("DELETE FROM marketing_audience_member WHERE tenant_id=? AND audience_id=? AND version=1 AND member_id='batch-100'",tenant,audience);
        post("/v1/admin/segment-runs/"+id+"/retry",admin,"retry",null);jdbc.update("UPDATE marketing_segment_run SET available_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND run_id=?",tenant,id);pump();
        assertEquals("COMPLETED",run("recovery").path("status").asString());assertEquals(111,run("recovery").path("matched").asInt());
    }
    @Test void limitsCancellationAndSchedulesDoNotExposePartialResults() throws Exception {
        seed();bulk(101);post("/v1/admin/segments",admin,"s",segment("limited",100));
        post("/v1/admin/segments/limited/refresh",admin,"r",null);pump();pump();
        assertEquals("FAILED",run("limited").path("status").asString());assertEquals("MEMBER_LIMIT",run("limited").path("errorCode").asString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM marketing_audience_snapshot WHERE tenant_id=?",Integer.class,tenant));
        var second=post("/v1/admin/segments/limited/refresh",admin,"r2",null);post("/v1/admin/segment-runs/"+second.path("runId").asString()+"/cancel",admin,"cancel",null);pump();
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM marketing_segment_run WHERE tenant_id=? AND run_id=?",String.class,tenant,second.path("runId").asString()));
        post("/v1/admin/segments/limited/schedule",admin,"enable",Map.of("expectedVersion",0,"enabled",true));pump();
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM marketing_segment_run WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(409,call("POST","/v1/admin/segments/limited/schedule",admin,"stale",Map.of("expectedVersion",0,"enabled",false)).status());
        var invalid=new HashMap<>(segment("order-dependent",100));invalid.put("rule",Map.of("kind","COMPARE","field","orderAmount","operator","GTE","valueType","DECIMAL","value","10"));
        assertEquals(400,call("POST","/v1/admin/segments",admin,"bad",invalid).status());
    }
    @Test void tagFactsDriveRealQuotesAndDynamicMembership() throws Exception {
        seed();post("/v1/admin/member-tags",admin,"tag",Map.of("tagId","loyal","name","忠诚会员"));
        post("/v1/admin/member-tags/m1/assign",admin,"assign",Map.of("tagId","loyal","active",true,"expectedVersion",0,"reason","测试"));
        var rule=Map.of("kind","COMPARE","field","memberTags","operator","CONTAINS","valueType","TEXT","value","loyal");
        var segment=new HashMap<>(segment("tags",100));segment.put("rule",rule);post("/v1/admin/segments",admin,"tags",segment);post("/v1/admin/segments/tags/refresh",admin,"refresh",null);pump();assertEquals(1,run("tags").path("matched").asInt());
        var campaign=new HashMap<>(draft("tag-campaign",1,"3.00"));campaign.put("rule",rule);post("/v1/admin/campaigns",admin,"campaign",campaign);post("/v1/admin/campaigns/tag-campaign/1/publish",admin,"publish",Map.of("expectedVersion",0));
        assertEquals("22.00",post("/v1/quotes",member,"q",basket(1)).path("payable").asString());
        post("/v1/admin/member-tags/m1/assign",admin,"remove",Map.of("tagId","loyal","active",false,"expectedVersion",1,"reason","取消"));
        assertEquals("25.00",post("/v1/quotes",member,"q2",basket(1)).path("payable").asString());
    }
}
