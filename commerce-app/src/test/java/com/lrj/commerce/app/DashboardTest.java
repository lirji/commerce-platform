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
class DashboardTest {
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
    private JsonNode dashboard() throws Exception {var r=call("GET","/v1/admin/dashboard?storeId=store1",admin,null,null);assertEquals(200,r.status(),r.body().toString());return r.body();}
    @Test void countsComeFromEntireTenantAndStoreRatherThanFirstPage() throws Exception {
        seed();for(int i=0;i<53;i++)post("/v1/admin/members",admin,"m"+i,Map.of("memberId","extra"+i,"actorId","extra"+i,"displayName","额外会员","memberLevel","BASIC"));
        post("/v1/admin/members/extra0/status",admin,"freeze",Map.of("expectedVersion",0,"value","FROZEN","reason","测试冻结"));
        post("/v1/admin/stores",admin,"s2",Map.of("storeId","store2","merchantId","merchant1","name","第二门店"));post("/v1/admin/skus",admin,"s2sku",Map.of("skuId","sku2","storeId","store2","title","其他门店商品","unitPrice","18.00"));
        var result=dashboard();assertEquals(54,result.path("members").path("total").asInt());assertEquals(53,result.path("members").path("active").asInt());assertEquals(1,result.path("members").path("frozen").asInt());assertEquals(1,result.path("catalog").path("total").asInt());assertEquals(30,result.path("daily").size());assertEquals("0.00",result.path("totals").path("netReceipts").asString());
        assertEquals(403,call("GET","/v1/admin/dashboard?storeId=store1",member,null,null).status());assertEquals(403,call("GET","/v1/admin/dashboard?storeId=store1",token(tenant,"operator","OPERATOR"),null,null).status());assertEquals(404,call("GET","/v1/admin/dashboard?storeId=store1",token("foreign-"+UUID.randomUUID(),"admin","ADMIN"),null,null).status());
    }
    @Test void utcCohortAndSuccessfulRefundTotalsRemainExactAndScoped() throws Exception {
        seed();var today=testClock.at.atZone(ZoneOffset.UTC).toLocalDate();Instant beginning=today.minusDays(29).atStartOfDay(ZoneOffset.UTC).toInstant();Instant yesterday=today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        // 直接夹具仅注入本域可重建投影；真实订单支付退款到投影链路另由MarketingEffectsTest覆盖。
        projection("inside-start","store1",beginning,"0.10","0.00",true);projection("yesterday-end","store1",yesterday.plusSeconds(86399),"0.20","0.10",true);projection("outside","store1",beginning.minusMillis(1),"900.00","0.00",true);projection("foreign-store","store2",yesterday,"800.00","0.00",true);projection("unpaid","store1",yesterday,"700.00","0.00",false);
        var result=dashboard();assertEquals("0.30",result.path("totals").path("received").asString());assertEquals("0.10",result.path("totals").path("refunded").asString());assertEquals("0.20",result.path("totals").path("netReceipts").asString());assertEquals(2,result.path("totals").path("paidOrders").asInt());assertEquals(today.minusDays(29).toString(),result.path("daily").get(0).path("day").asString());assertEquals("0.10",result.path("daily").get(0).path("received").asString());assertEquals("0.20",result.path("daily").get(28).path("received").asString());assertEquals(2,result.path("daily").get(28).path("orders").asInt());
        jdbc.update("UPDATE marketing_effect_order SET refunded=0.20 WHERE tenant_id=? AND order_id='yesterday-end'",tenant);assertEquals("0.10",dashboard().path("totals").path("netReceipts").asString());
    }
    private void projection(String id,String store,Instant at,String paid,String refunded,boolean success){jdbc.update("INSERT INTO marketing_effect_order(tenant_id,order_id,store_id,series_id,ordered_at,paid,paid_amount,refunded,discount_amount,platform_funding,merchant_funding) VALUES(?,?,?,?,?,?,?, ?,0,0,0)",tenant,id,store,JsonCodec.hash(store),java.sql.Timestamp.from(at),success,paid,refunded);}
}
