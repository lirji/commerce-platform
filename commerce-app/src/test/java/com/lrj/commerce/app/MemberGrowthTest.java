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
class MemberGrowthTest {
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
    @Autowired com.lrj.commerce.member.api.MemberGrowthApi growth;
    private void policy(long version,String rate,Instant effective) throws Exception {
        post("/v1/admin/member-growth/policies",admin,"policy"+version,Map.of("version",version,"effectiveFrom",effective.toString(),"growthPerYuan",rate,"levels",List.of(Map.of("code","BASIC","minimumGrowth",0),Map.of("code","GOLD","minimumGrowth",20))));
    }
    private void observe(String key,com.lrj.commerce.member.api.MemberGrowthApi.OrderFact fact) {
        commands.run(new Actor(tenant,"admin",Actor.Role.ADMIN),"test.growth.fact",key,fact,String.class,()->{growth.observe(tenant,fact);return "ok";});
    }
    private JsonNode wallet() throws Exception {return call("GET","/v1/members/me/growth",member,null,null).body();}
    @Test void sourceNetRecalculationHandlesRefundBeforeCompletionDuplicatesAndPolicyChanges() throws Exception {
        seed();Instant ordered=Instant.now();policy(1,"2.00",ordered.minusSeconds(1));
        var early=new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact("order1","m1","25.00",ordered,false,"refund1","5.00");
        observe("early",early);assertEquals(0,wallet().path("growth").asLong());
        observe("complete",new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact("order1","m1","25.00",ordered,true,null,null));
        assertEquals(40,wallet().path("growth").asLong());assertEquals("GOLD",wallet().path("memberLevel").asString());
        observe("repeat",early);assertEquals(40,wallet().path("growth").asLong());
        policy(2,"10.00",Instant.now());
        observe("refund2",new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact("order1","m1","25.00",ordered,true,"refund2","15.00"));
        assertEquals(10,wallet().path("growth").asLong());assertEquals("BASIC",wallet().path("memberLevel").asString());assertEquals("5.00",wallet().path("netSpend").asString());
        assertEquals(2,call("GET","/v1/members/me/growth/ledger",member,null,null).body().size());
        var old=new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact("historical","m1","100.00",ordered.minusSeconds(3600),true,null,null);
        observe("historical",old);assertEquals(10,wallet().path("growth").asLong());assertEquals("105.00",wallet().path("netSpend").asString());
        assertEquals(403,call("GET","/v1/admin/member-growth/m1",member,null,null).status());
        var foreign=token("foreign-"+UUID.randomUUID(),"admin","ADMIN");assertEquals(404,call("GET","/v1/admin/member-growth/m1",foreign,null,null).status());
    }
    @Test void adjustmentDebtTagsAndConcurrentFactsPreserveBalances() throws Exception {
        seed();Instant at=Instant.now();policy(1,"1.00",at.minusSeconds(1));
        observe("completed",new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact("order1","m1","25.00",at,true,null,null));
        var input=Map.of("expectedVersion",wallet().path("version").asLong(),"delta",-20,"reason","人工纠错");
        var adjusted=post("/v1/admin/member-growth/m1/adjust",admin,"adjust",input);assertEquals(adjusted,post("/v1/admin/member-growth/m1/adjust",admin,"adjust",input));
        assertEquals(409,call("POST","/v1/admin/member-growth/m1/adjust",admin,"stale",input).status());
        var refund=new com.lrj.commerce.member.api.MemberGrowthApi.OrderFact("order1","m1","25.00",at,true,"refund1","25.00");
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->observe("refund-a",refund));var b=pool.submit(()->observe("refund-b",refund));a.get();b.get();
        }
        assertEquals(-20,wallet().path("growth").asLong());assertEquals("BASIC",wallet().path("memberLevel").asString());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM member_growth_ledger WHERE tenant_id=?",Integer.class,tenant));
        post("/v1/admin/member-tags",admin,"tag",Map.of("tagId","high-value","name","高价值会员"));
        var assignment=Map.of("tagId","high-value","active",true,"expectedVersion",0,"reason","人工确认");
        post("/v1/admin/member-tags/m1/assign",admin,"assign",assignment);
        assertEquals(List.of("high-value"),growth.facts(tenant,"m1").tags());
        assertEquals(409,call("POST","/v1/admin/member-tags/m1/assign",admin,"stale-tag",assignment).status());
        post("/v1/admin/member-tags/m1/assign",admin,"revoke",Map.of("tagId","high-value","active",false,"expectedVersion",1,"reason","撤销"));
        assertTrue(growth.facts(tenant,"m1").tags().isEmpty());
        assertEquals(400,call("POST","/v1/admin/member-growth/policies",admin,"bad-policy",Map.of("version",3,"effectiveFrom",Instant.now().toString(),"growthPerYuan","1","levels",List.of(Map.of("code","A","minimumGrowth",10)))).status());
    }
    @Test void completedOrderAndSuccessfulRefundFlowThroughRealOutbox() throws Exception {
        seed();policy(1,"2.00",Instant.now().minusSeconds(1));
        post("/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",2));
        var quote=post("/v1/quotes",member,"q",basket(1));
        var order=post("/v1/orders",member,"order",Map.of("quoteId",quote.path("quoteId").asString(),"address",Map.of("recipient","测试","phone","13800000000","detail","隔离测试地址")));
        String id=order.path("orderId").asString();
        var payment=post("/v1/orders/"+id+"/payments",member,"pay",null);
        post("/v1/admin/sandbox/payments/"+payment.path("paymentId").asString()+"/fact",admin,"paid",Map.of("status","PAID"));
        post("/v1/orders/"+id+"/payment/reconcile",member,null,null);pumpAll();
        assertEquals(0,wallet().path("growth").asLong());
        post("/v1/admin/fulfillments/"+id+"/ship",admin,"ship",Map.of("trackingNo","GROWTH-TEST"));
        post("/v1/admin/fulfillments/"+id+"/deliver",admin,"deliver",null);pumpAll();
        assertEquals(50,wallet().path("growth").asLong());
        var request=post("/v1/aftersales",member,"return",Map.of("orderId",id,"reason","测试退货","items",List.of(Map.of("skuId","sku1","quantity",1))));
        String caseId=request.path("caseId").asString();post("/v1/admin/aftersales/"+caseId+"/approve",admin,"approve",null);
        var receiving=post("/v1/admin/aftersales/"+caseId+"/receive-return",admin,"receive",null);String refund=receiving.path("refundId").asString();
        post("/v1/admin/sandbox/refunds/"+refund+"/success",admin,"refund",null);post("/v1/admin/refunds/"+refund+"/reconcile",admin,null,null);pumpAll();
        assertEquals(0,wallet().path("growth").asLong());assertEquals("0.00",wallet().path("netSpend").asString());
        assertEquals(2,call("GET","/v1/members/me/growth/ledger",member,null,null).body().size());
    }
    private void pumpAll() throws Exception {for(int i=0;i<6;i++)post("/v1/admin/events/pump",admin,null,null);}
}
