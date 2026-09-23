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
class ProductOperationsTest {
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
    @Test void variantsAreUniqueScopedAndVersionedWithoutRepricingExistingQuotes() throws Exception {
        seed();String operator=token(tenant,"operator","OPERATOR");
        post("/v1/admin/store-grants",admin,"g",Map.of("grantId","g1","actorId","operator","resourceType","STORE","resourceId","store1","permission","CATALOG","reason","门店运营"));
        var product=Map.of("productId","p1","storeId","store1","title","棉质T恤","category","服装","brand","自营");
        post("/v1/operations/products",operator,"p",product);
        var variant=Map.of("skuId","variant1","productId","p1","storeId","store1","title","红色M码","unitPrice","30.00","specifications",List.of(Map.of("name","颜色","value","红色"),Map.of("name","尺码","value","M")));
        assertEquals("FROZEN",post("/v1/operations/skus",operator,"sku",variant).path("status").asString());
        var reversed=new HashMap<String,Object>(variant);reversed.put("skuId","duplicate");reversed.put("specifications",List.of(Map.of("name","尺码","value","M"),Map.of("name","颜色","value","红色")));
        assertEquals(409,call("POST","/v1/operations/skus",operator,"duplicate",reversed).status());
        assertEquals(1,call("GET","/v1/catalog?storeId=store1",member,null,null).body().size());
        assertEquals(2,call("GET","/v1/operations/skus?storeId=store1",operator,null,null).body().size());
        var publish=Map.of("storeId","store1","expectedVersion",1,"title","红色M码","unitPrice","30.00","status","ACTIVE","reason","首发上架");
        post("/v1/operations/skus/variant1",operator,"publish",publish);
        var basket=Map.of("storeId","store1","items",List.of(Map.of("skuId","variant1","quantity",1)));
        var quote=post("/v1/quotes",member,"q",basket);
        assertEquals("30.00",quote.path("gross").asString());
        var change=Map.of("storeId","store1","expectedVersion",2,"title","红色M码","unitPrice","35.00","status","ACTIVE","reason","调价");
        var revised=post("/v1/operations/skus/variant1",operator,"price",change);
        assertEquals(revised,post("/v1/operations/skus/variant1",operator,"price",change));
        assertEquals(409,call("POST","/v1/operations/skus/variant1",operator,"stale",publish).status());
        assertEquals("35.00",post("/v1/quotes",member,"new-quote",basket).path("gross").asString());
        assertEquals("30.00",call("GET","/v1/quotes/"+quote.path("quoteId").asString(),member,null,null).body().path("gross").asString());
        post("/v1/operations/skus/variant1",operator,"off",Map.of("storeId","store1","expectedVersion",3,"title","红色M码","unitPrice","35.00","status","FROZEN","reason","补货下架"));
        assertEquals(404,call("POST","/v1/quotes",member,"off-quote",basket).status());
        var history=call("GET","/v1/operations/skus/variant1/history?storeId=store1",operator,null,null).body();
        assertEquals(4,history.size());assertEquals("30.00",history.get(0).path("unitPrice").asString());
        post("/v1/admin/store-grants/g1/status",admin,"revoke",Map.of("expectedVersion",0,"active",false,"reason","撤销"));
        assertEquals(403,call("POST","/v1/operations/skus/variant1",operator,"denied",change).status());
        assertEquals(403,call("GET","/v1/operations/skus/variant1/history?storeId=store1",operator,null,null).status());
    }
    @Test void resourceMismatchAndInvalidSpecificationsFailClosed() throws Exception {
        seed();String operator=token(tenant,"operator","OPERATOR");
        post("/v1/admin/store-grants",admin,"g",Map.of("grantId","g1","actorId","operator","resourceType","STORE","resourceId","store1","permission","CATALOG","reason","任职"));
        post("/v1/admin/stores",admin,"s2",Map.of("storeId","store2","merchantId","merchant1","name","其他门店"));
        assertEquals(403,call("GET","/v1/operations/skus?storeId=store2",operator,null,null).status());
        assertEquals(404,call("POST","/v1/operations/skus/sku1",admin,"mismatch",Map.of("storeId","store2","expectedVersion",1,"title","错误换店","unitPrice","1.00","status","ACTIVE","reason","拒绝换绑")).status());
        post("/v1/operations/products",admin,"p",Map.of("productId","p1","storeId","store1","title","商品","category","分类","brand","品牌"));
        assertEquals(400,call("POST","/v1/operations/skus",admin,"duplicate-fields",Map.of("skuId","bad","productId","p1","storeId","store1","title","错误规格","unitPrice","1.00","specifications",List.of(Map.of("name","size","value","M"),Map.of("name","size","value","L")))).status());
        assertEquals(403,call("GET","/v1/operations/products?storeId=store1",member,null,null).status());
        assertEquals(404,call("GET","/v1/operations/products?storeId=store1",token("x-"+UUID.randomUUID(),"admin","ADMIN"),null,null).status());
        var original=post("/v1/operations/skus/sku1",admin,"legacy",Map.of("storeId","store1","expectedVersion",1,"title","历史SKU调价","unitPrice","26.00","status","ACTIVE","reason","兼容调价"));
        assertEquals("26.00",original.path("unitPrice").asString());
        assertEquals(2,call("GET","/v1/operations/skus/sku1/history?storeId=store1",admin,null,null).body().size());
    }
}
