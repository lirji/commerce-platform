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
class CatalogMerchandisingTest {
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
    private void category(String id,String parent) throws Exception {var input=new HashMap<String,Object>();input.put("categoryId",id);input.put("storeId","store1");input.put("name","类目"+id);if(parent!=null)input.put("parentId",parent);post("/v1/operations/catalog-categories",admin,"category-"+id,input);}
    private void product(String id) throws Exception {post("/v1/operations/products",admin,"product-"+id,Map.of("productId",id,"storeId","store1","title","经营商品","category","旧文字分类","brand","品牌"));}
    private void template(long version,String value) throws Exception {post("/v1/operations/specification-templates",admin,"template-"+version,Map.of("templateId","color","version",version,"storeId","store1","name","颜色模板","fields",List.of(Map.of("name","颜色","values",List.of(value,"蓝色")))));}
    private Map<String,Object> profile(long version,String category,String template,Long templateVersion){var input=new HashMap<String,Object>();input.put("storeId","store1");input.put("expectedVersion",version);input.put("categoryId",category);input.put("templateId",template);input.put("templateVersion",templateVersion);input.put("description","品牌自营商品说明");input.put("images",List.of(Map.of("url","/media/coffee.svg","alt","商品示意图")));input.put("reason","完善经营资料");return input;}
    private Map<String,Object> variant(String id,String value){return Map.of("skuId",id,"productId","p1","storeId","store1","title","红色精品","unitPrice","30.00","specifications",List.of(Map.of("name","颜色","value",value)));}
    private Map<String,Object> barcode(long version,String value){return Map.of("storeId","store1","expectedVersion",version,"barcode",value,"reason","维护条码");}
    @Test void categoryDepthRetirementAndStoreAuthorizationAreEnforced() throws Exception {
        seed();category("root",null);category("child","root");category("leaf","child");
        assertEquals(400,call("POST","/v1/operations/catalog-categories",admin,"too-deep",Map.of("categoryId","deep","storeId","store1","parentId","leaf","name","过深")).status());
        assertEquals(409,call("POST","/v1/operations/catalog-categories/root",admin,"retire",Map.of("storeId","store1","expectedVersion",0,"name","根","status","RETIRED","reason","仍在使用")).status());
        product("p1");post("/v1/operations/products/p1/merchandising",admin,"bind",profile(0,"leaf",null,null));
        assertEquals(409,call("POST","/v1/operations/catalog-categories/leaf",admin,"retire-leaf",Map.of("storeId","store1","expectedVersion",0,"name","叶","status","RETIRED","reason","仍绑定商品")).status());
        post("/v1/operations/products/p1/merchandising",admin,"unbind",profile(1,null,null,null));post("/v1/operations/catalog-categories/leaf",admin,"retire-ok",Map.of("storeId","store1","expectedVersion",0,"name","叶","status","RETIRED","reason","清理完毕"));
        assertEquals(2,call("GET","/v1/catalog/categories?storeId=store1",member,null,null).body().size());
        assertEquals(409,call("POST","/v1/operations/products/p1/merchandising",admin,"retired-bind",profile(2,"leaf",null,null)).status());
        String operator=token(tenant,"operator","OPERATOR");assertEquals(403,call("GET","/v1/operations/catalog-categories?storeId=store1",operator,null,null).status());post("/v1/admin/store-grants",admin,"grant",Map.of("grantId","g1","actorId","operator","resourceType","STORE","resourceId","store1","permission","CATALOG","reason","目录经营"));
        assertEquals(200,call("GET","/v1/operations/products/p1/merchandising?storeId=store1",operator,null,null).status());post("/v1/admin/store-grants/g1/status",admin,"revoke",Map.of("expectedVersion",0,"active",false,"reason","撤销"));assertEquals(403,call("GET","/v1/operations/products/p1/merchandising?storeId=store1",operator,null,null).status());
        assertEquals(403,call("GET","/v1/operations/catalog-search?storeId=store1",member,null,null).status());assertEquals(404,call("GET","/v1/catalog/search?storeId=store1",other,null,null).status());
    }
    @Test void immutableTemplateRejectsUnknownMissingAndChangedSpecifications() throws Exception {
        seed();product("p1");template(1,"红色");post("/v1/operations/products/p1/merchandising",admin,"bind",profile(0,null,"color",1L));template(2,"绿色");
        assertEquals(400,call("POST","/v1/operations/skus",admin,"bad",variant("green","绿色")).status());post("/v1/operations/skus",admin,"red",variant("red","红色"));
        assertEquals(409,call("POST","/v1/operations/products/p1/merchandising",admin,"rebind",profile(1,null,"color",2L)).status());
        var extra=new HashMap<String,Object>(variant("extra","红色"));extra.put("specifications",List.of(Map.of("name","颜色","value","红色"),Map.of("name","款式","value","标准")));assertEquals(400,call("POST","/v1/operations/skus",admin,"extra",extra).status());
        assertEquals("红色",call("GET","/v1/operations/specification-templates/color/1?storeId=store1",admin,null,null).body().path("fields").get(0).path("values").get(0).asString());
        var bad=new HashMap<>(profile(1,null,"color",1L));bad.put("images",List.of(Map.of("url","javascript:alert(1)","alt","危险地址")));assertEquals(400,call("POST","/v1/operations/products/p1/merchandising",admin,"bad-image",bad).status());
        bad.put("images",List.of(Map.of("url","https://user:secret@example.test/a.png","alt","含凭据")));assertEquals(400,call("POST","/v1/operations/products/p1/merchandising",admin,"credentials-image",bad).status());
        assertEquals(409,call("POST","/v1/operations/products/p1/merchandising",admin,"stale",profile(0,null,"color",1L)).status());
    }
    @Test void templateBindingAndFreeVariantCreationCannotBothBypassValidation() throws Exception {
        seed();product("p1");template(1,"红色");var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{barrier.await();return call("POST","/v1/operations/products/p1/merchandising",admin,"bind",profile(0,null,"color",1L));});
            var b=pool.submit(()->{barrier.await();return call("POST","/v1/operations/skus",admin,"free",variant("green","绿色"));});
            int one=a.get(15,TimeUnit.SECONDS).status(),two=b.get(15,TimeUnit.SECONDS).status();assertTrue(one==200&&two==400||one==409&&two==200,"binding="+one+", variant="+two);
        }
    }
    @Test void barcodesAreUniqueCaseNormalizedAndVersionedWithoutPriceChanges() throws Exception {
        seed();product("p1");post("/v1/operations/skus",admin,"red",variant("red","红色"));var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{barrier.await();return call("POST","/v1/operations/skus/sku1/barcode",admin,"barcode-one",barcode(0,"abc-100"));});
            var b=pool.submit(()->{barrier.await();return call("POST","/v1/operations/skus/red/barcode",admin,"barcode-two",barcode(0,"ABC-100"));});
            var statuses=List.of(a.get(15,TimeUnit.SECONDS).status(),b.get(15,TimeUnit.SECONDS).status());assertTrue(statuses.contains(200)&&statuses.contains(409),statuses.toString());
        }
        String owner=jdbc.queryForObject("SELECT sku_id FROM catalog_sku_barcode WHERE tenant_id=? AND barcode='ABC-100'",String.class,tenant);
        assertEquals(409,call("POST","/v1/operations/skus/"+owner+"/barcode",admin,"stale",barcode(0,"NEW")).status());
        post("/v1/operations/skus/"+owner+"/barcode",admin,"clear",barcode(1,""));String other=owner.equals("sku1")?"red":"sku1";post("/v1/operations/skus/"+other+"/barcode",admin,"reuse",barcode(0,"abc-100"));
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM catalog_sku WHERE tenant_id=? AND sku_id='sku1'",Integer.class,tenant));
        assertEquals(400,call("POST","/v1/operations/skus/sku1/barcode",admin,"bad-barcode",barcode(1,"非法条码")).status());
        assertEquals(400,call("POST","/v1/operations/skus/sku1/barcode",admin,"unicode-fold",barcode(1,"ß")).status());
    }
    @Test void merchandisingSearchFiltersCatalogWithoutLeakingUnpublishedOrForeignData() throws Exception {
        seed();category("coffee",null);product("p1");post("/v1/operations/products/p1/merchandising",admin,"profile",profile(0,"coffee",null,null));post("/v1/operations/skus",admin,"red",variant("red","红色"));post("/v1/operations/skus/red/barcode",admin,"barcode",barcode(0,"SKU-RED"));
        assertEquals(0,call("GET","/v1/catalog/search?storeId=store1&q=SKU-RED",member,null,null).body().size());assertEquals(404,call("GET","/v1/catalog/items/red?storeId=store1",member,null,null).status());
        var adminRows=call("GET","/v1/operations/catalog-search?storeId=store1&status=FROZEN&categoryId=coffee",admin,null,null).body();assertEquals(1,adminRows.size());
        post("/v1/operations/skus/red",admin,"publish",Map.of("storeId","store1","expectedVersion",1,"title","红色精品","unitPrice","30.00","status","ACTIVE","reason","资料完备上架"));
        var search=call("GET","/v1/catalog/search?storeId=store1&q=sku-red&categoryId=coffee&minimumPrice=29.99&maximumPrice=30.00",member,null,null).body();assertEquals(1,search.size());assertEquals("SKU-RED",search.get(0).path("barcode").asString());assertEquals("/media/coffee.svg",search.get(0).path("images").get(0).path("url").asString());
        assertEquals("品牌自营商品说明",call("GET","/v1/catalog/items/red?storeId=store1",member,null,null).body().path("description").asString());
        assertEquals(0,call("GET","/v1/catalog/search?storeId=store1&q=%25",member,null,null).body().size());assertEquals(0,call("GET","/v1/catalog/search?storeId=store1&categoryId=coffee&after=red",member,null,null).body().size());
        assertEquals(400,call("GET","/v1/catalog/search?storeId=store1&minimumPrice=31&maximumPrice=30",member,null,null).status());
        post("/v1/admin/stores",admin,"store2",Map.of("storeId","store2","merchantId","merchant1","name","其他店"));assertEquals(404,call("GET","/v1/catalog/items/red?storeId=store2",member,null,null).status());
        assertEquals(2,call("GET","/v1/catalog?storeId=store1",member,null,null).body().size());
    }
}
