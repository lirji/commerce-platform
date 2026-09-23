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
class MarketingEffectsTest {
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
    private void events() throws Exception {for(int i=0;i<10;i++)if(post("/v1/admin/events/pump",admin,null,null).asInt()==0)break;}
    @Test void paidRefundAndRebuildProduceOneAuthoritativeSeriesWithoutInventedProfit() throws Exception {
        seed();post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",2));
        post("/v1/admin/campaigns",admin,"campaign",draft("effects",1,"3.00"));post("/v1/admin/campaigns/effects/1/publish",admin,"publish",Map.of("expectedVersion",0));
        var quote=post("/v1/quotes",member,"quote",basket(1));
        var order=post("/v1/orders",member,"order",Map.of("quoteId",quote.path("quoteId").asString(),"address",Map.of("recipient","测试","phone","13800000000","detail","隔离分析测试地址")));String id=order.path("orderId").asString();
        String report="/v1/admin/marketing-effects?storeId=store1&from="+Instant.now().minusSeconds(3600)+"&to="+Instant.now().plusSeconds(3600);
        events();var initial=call("GET",report,admin,null,null).body().path("rows").get(0);assertEquals(1,initial.path("orders").asInt());assertEquals(0,initial.path("paidOrders").asInt());
        var payment=post("/v1/orders/"+id+"/payments",member,"pay",null);post("/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",admin,"paid",Map.of("status","PAID"));post("/v1/orders/"+id+"/payment/reconcile",member,null,null);events();
        var paid=call("GET",report,admin,null,null).body().path("rows").get(0);assertEquals("22.00",paid.path("received").asString());assertEquals("3.00",paid.path("merchantFunding").asString());
        var request=post("/v1/aftersales",member,"return",Map.of("orderId",id,"reason","分析退款","items",List.of(Map.of("skuId","sku1","quantity",1))));
        var approved=post("/v1/admin/aftersales/"+request.path("caseId").asString()+"/approve",admin,"approve",null);String refund=approved.path("refundId").asString();
        post("/v1/admin/sandbox/refunds/"+refund+"/success",admin,"refund",null);post("/v1/admin/refunds/"+refund+"/reconcile",admin,null,null);events();
        var row=call("GET",report,admin,null,null).body().path("rows").get(0);assertEquals("22.00",row.path("refunded").asString());assertEquals("0.00",row.path("netReceipts").asString());assertEquals("3.00",row.path("discountGranted").asString());
        jdbc.update("UPDATE platform_event SET status='PENDING',available_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND event_type IN ('refund.succeeded.v1','order.created.v1')",tenant);events();
        post("/v1/admin/marketing-effects/rebuild",admin,"rebuild",Map.of("after","","limit",100));post("/v1/admin/marketing-effects/rebuild",admin,"rebuild-again",Map.of("after","","limit",100));
        var repeated=call("GET",report,admin,null,null).body().path("rows").get(0);assertEquals("22.00",repeated.path("refunded").asString());assertEquals(1,repeated.path("paidOrders").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM marketing_effect_order WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(403,call("GET",report,member,null,null).status());assertEquals(404,call("GET",report,token("foreign-"+UUID.randomUUID(),"admin","ADMIN"),null,null).status());
        assertEquals(400,call("GET","/v1/admin/marketing-effects?storeId=store1&from="+Instant.now().minusSeconds(86400*100)+"&to="+Instant.now(),admin,null,null).status());
    }
}
