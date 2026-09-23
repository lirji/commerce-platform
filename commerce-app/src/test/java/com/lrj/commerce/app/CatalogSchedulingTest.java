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
class CatalogSchedulingTest {
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
    private Object basket(){return Map.of("storeId","store1","items",List.of(Map.of("skuId","sku1","quantity",1)));}
    private Object orderInput(JsonNode q){return Map.of("quoteId",q.path("quoteId").asString(),"address",Map.of("recipient","渠道测试","phone","13800000000","detail","隔离测试地址"));}
    private Map<String,Object> price(long version,String amount,Instant from,Instant to,boolean active){return Map.of("storeId","store1","channel","MINI_APP","expectedVersion",version,"unitPrice",amount,"validFrom",from.toString(),"validTo",to.toString(),"active",active,"reason","渠道经营");}
    private String mini(){String token=token(tenant,"buyer","MEMBER");jdbc.update("UPDATE platform_credential SET sales_channel='MINI_APP' WHERE token_hash=?",JsonCodec.hash(token));return token;}
    private Map<String,Object> job(String id,String action,Instant run,List<?> targets){var input=new HashMap<String,Object>();input.put("jobId",id);input.put("storeId","store1");input.put("name","经营计划");input.put("action",action);input.put("runAt",run==null?null:run.toString());input.put("deadline",testClock.at.plusSeconds(7200).toString());input.put("targets",targets);input.put("reason","批量经营验收");return input;}
    private Object target(String sku,long revision,String amount){var value=new HashMap<String,Object>();value.put("skuId",sku);value.put("expectedRevision",revision);if(amount!=null)value.put("unitPrice",amount);return value;}
    private JsonNode jobs() throws Exception {return call("GET","/v1/operations/catalog-jobs?storeId=store1",admin,null,null).body();}
    private void pumpJobs() throws Exception {post("/v1/operations/catalog-jobs/pump?storeId=store1",admin,null,null);}
    @Test void trustedChannelFlowsThroughSearchQuoteAndOrderWithFrozenPrice() throws Exception {
        seed();String mini=mini();Instant now=testClock.at,end=now.plusSeconds(120);
        post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",10));
        post("/v1/operations/skus/sku1/channel-prices",admin,"p1",price(0,"18.50",now,end,true));
        assertEquals("25.00",call("GET","/v1/catalog/items/sku1?storeId=store1",member,null,null).body().path("unitPrice").asString());
        assertEquals("18.50",call("GET","/v1/catalog/items/sku1?storeId=store1",mini,null,null).body().path("unitPrice").asString());
        assertEquals(1,call("GET","/v1/catalog/search?storeId=store1&maximumPrice=20",mini,null,null).body().size());
        assertEquals(0,call("GET","/v1/catalog/search?storeId=store1&maximumPrice=20",member,null,null).body().size());
        assertEquals("25.00",call("GET","/v1/operations/catalog-search?storeId=store1",admin,null,null).body().get(0).path("unitPrice").asString());
        assertEquals("18.50",call("GET","/v1/catalog?storeId=store1",mini,null,null).body().get(0).path("unitPrice").asString());
        var quote=post("/v1/quotes",mini,"quote",basket());assertEquals("MINI_APP",quote.path("channel").asString());assertEquals("18.50",quote.path("payable").asString());assertEquals(end,Instant.parse(quote.path("expiresAt").asString()));assertEquals(1,quote.path("items").get(0).path("channelPriceVersion").asInt());
        assertEquals(409,call("POST","/v1/quotes",member,"quote",basket()).status());
        assertEquals(403,call("POST","/v1/orders",member,"cross-channel",orderInput(quote)).status());
        post("/v1/operations/skus/sku1/channel-prices",admin,"p2",price(1,"16.00",now,end,true));
        var order=post("/v1/orders",mini,"order",orderInput(quote));assertEquals("MINI_APP",order.path("channel").asString());assertEquals("18.50",order.path("payable").asString());assertEquals("MINI_APP",call("GET","/v1/orders/"+order.path("orderId").asString(),mini,null,null).body().path("channel").asString());
        assertEquals(2,call("GET","/v1/operations/skus/sku1/channel-prices/MINI_APP/history?storeId=store1",admin,null,null).body().size());
        var forged=new HashMap<String,Object>();forged.put("storeId","store1");forged.put("items",List.of(Map.of("skuId","sku1","quantity",1)));forged.put("channel","MINI_APP");assertEquals(400,call("POST","/v1/quotes",member,"forged",forged).status());
        testClock.at=end;assertEquals("25.00",post("/v1/quotes",mini,"expired",basket()).path("payable").asString());
        // 同键回放不重新校验已经过期的配置时间。
        assertEquals(200,call("POST","/v1/operations/skus/sku1/channel-prices",admin,"p1",price(0,"18.50",now,end,true)).status());
    }
    @Test void channelChangesAreCasProtectedAndLegacySnapshotsRemainReadable() throws Exception {
        seed();String mini=mini();Instant now=testClock.at;var input=price(0,"10.00",now.plusSeconds(60),now.plusSeconds(3600),true);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{barrier.await();return call("POST","/v1/operations/skus/sku1/channel-prices",admin,"a",input).status();});var b=pool.submit(()->{barrier.await();return call("POST","/v1/operations/skus/sku1/channel-prices",admin,"b",input).status();});var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertTrue(results.contains(200)&&results.contains(409),results.toString());}
        assertEquals("25.00",post("/v1/quotes",mini,"not-started",basket()).path("payable").asString());testClock.at=now.plusSeconds(90);assertEquals("10.00",post("/v1/quotes",mini,"started",basket()).path("payable").asString());
        post("/v1/operations/skus/sku1/channel-prices",admin,"disable",price(1,"10.00",now,now.plusSeconds(3600),false));assertEquals("25.00",post("/v1/quotes",mini,"disabled",basket()).path("payable").asString());
        var legacy=post("/v1/quotes",member,"legacy",basket());var node=(tools.jackson.databind.node.ObjectNode)legacy;node.remove("channel");((tools.jackson.databind.node.ObjectNode)node.path("items").get(0)).remove("channelPriceVersion");var old=JsonCodec.read(node.toString(),com.lrj.commerce.trade.api.QuoteApi.View.class);assertEquals(Actor.Channel.WEB,old.channel());assertEquals(0L,old.items().getFirst().channelPriceVersion());
        assertEquals(403,call("POST","/v1/operations/skus/sku1/channel-prices",member,"denied",input).status());
    }
    @Test void scheduledBatchSurvivesConcurrentPumpsAndSkipsStaleRevisions() throws Exception {
        seed();post("/v1/admin/skus",admin,"sku2",Map.of("skuId","sku2","storeId","store1","title","第二商品","unitPrice","30.00"));Instant run=testClock.at.plusSeconds(60);
        var input=job("batch","PRICE",run,List.of(target("sku1",1,"12.00"),target("sku2",1,"13.00")));post("/v1/operations/catalog-jobs",admin,"batch",input);pumpJobs();assertEquals(0,jobs().get(0).path("processed").asInt());
        post("/v1/operations/skus/sku1",admin,"manual",Map.of("storeId","store1","expectedVersion",1,"title","测试商品","unitPrice","26.00","status","ACTIVE","reason","调价后不允许旧任务覆盖"));testClock.at=run;
        var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{barrier.await();pumpJobs();return true;});var b=pool.submit(()->{barrier.await();pumpJobs();return true;});assertTrue(a.get(15,TimeUnit.SECONDS));assertTrue(b.get(15,TimeUnit.SECONDS));}
        var row=jobs().get(0);assertEquals("COMPLETED",row.path("status").asString());assertEquals(1,row.path("succeeded").asInt());assertEquals(1,row.path("conflicted").asInt());
        var items=call("GET","/v1/operations/catalog-jobs/batch/items?storeId=store1",admin,null,null).body();assertEquals("CONFLICT",items.get(0).path("status").asString());assertEquals("SUCCEEDED",items.get(1).path("status").asString());
        assertEquals("26.00",call("GET","/v1/catalog/items/sku1?storeId=store1",member,null,null).body().path("unitPrice").asString());assertEquals("13.00",call("GET","/v1/catalog/items/sku2?storeId=store1",member,null,null).body().path("unitPrice").asString());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_revision WHERE tenant_id=? AND sku_id='sku2'",Integer.class,tenant));
        post("/v1/operations/catalog-jobs",admin,"batch",input);pumpJobs();assertEquals(2,call("GET","/v1/operations/catalog-jobs/batch/items?storeId=store1",admin,null,null).body().size());
    }
    @Test void revokedOperatorIsIsolatedAndCanResumeOnlyAfterPermissionRestoration() throws Exception {
        seed();String operator=token(tenant,"operator","OPERATOR");post("/v1/admin/store-grants",admin,"grant",Map.of("grantId","g1","actorId","operator","resourceType","STORE","resourceId","store1","permission","CATALOG","reason","商品经营"));
        post("/v1/operations/catalog-jobs",operator,"job",job("batch","UNPUBLISH",null,List.of(target("sku1",1,null))));
        post("/v1/admin/store-grants/g1/status",admin,"revoke",Map.of("expectedVersion",0,"active",false,"reason","撤销权限"));
        for(int i=0;i<5;i++){pumpJobs();testClock.at=testClock.at.plusSeconds(65);}
        var isolated=jobs().get(0);assertEquals("ISOLATED",isolated.path("status").asString());assertEquals(0,isolated.path("processed").asInt());assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM catalog_sku WHERE tenant_id=? AND sku_id='sku1'",String.class,tenant));
        post("/v1/admin/store-grants/g1/status",admin,"restore",Map.of("expectedVersion",1,"active",true,"reason","恢复权限"));post("/v1/operations/catalog-jobs/batch/control",operator,"retry",Map.of("storeId","store1","expectedVersion",isolated.path("version").asLong(),"action","RETRY","reason","权限已核验"));pumpJobs();assertEquals("COMPLETED",jobs().get(0).path("status").asString());assertEquals("FROZEN",jdbc.queryForObject("SELECT status FROM catalog_sku WHERE tenant_id=? AND sku_id='sku1'",String.class,tenant));
    }
    @Test void cancellationExpirationAndInactiveCreatorNeverApplyRemainingWork() throws Exception {
        seed();var input=job("cancel","UNPUBLISH",null,List.of(target("sku1",1,null)));post("/v1/operations/catalog-jobs",admin,"cancel",input);post("/v1/operations/catalog-jobs/cancel/control",admin,"stop",Map.of("storeId","store1","expectedVersion",0,"action","CANCEL","reason","撤销未来效果"));pumpJobs();assertEquals("CANCELLED",jobs().get(0).path("status").asString());
        var expired=job("expire","UNPUBLISH",null,List.of(target("sku1",1,null)));expired.put("deadline",testClock.at.plusSeconds(1).toString());post("/v1/operations/catalog-jobs",admin,"expire",expired);testClock.at=testClock.at.plusSeconds(2);pumpJobs();assertEquals("EXPIRED",jobs().get(1).path("status").asString());post("/v1/operations/catalog-jobs",admin,"expire",expired);
        String operator=token(tenant,"operator","OPERATOR");post("/v1/admin/store-grants",admin,"grant",Map.of("grantId","g1","actorId","operator","resourceType","STORE","resourceId","store1","permission","CATALOG","reason","商品经营"));post("/v1/operations/catalog-jobs",operator,"inactive",job("inactive","UNPUBLISH",null,List.of(target("sku1",1,null))));jdbc.update("UPDATE platform_credential SET active=FALSE WHERE tenant_id=? AND actor_id='operator'",tenant);pumpJobs();assertEquals(1,jobs().get(2).path("attempts").asInt());assertEquals(0,jobs().get(2).path("processed").asInt());assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM catalog_sku WHERE tenant_id=? AND sku_id='sku1'",String.class,tenant));
    }
}
