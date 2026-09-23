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
class StoreAccessTest {
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
    @Test void grantsAreScopedRevocableAndNeverGrantPlatformRights() throws Exception {
        seed();String operator=token(tenant,"operator","OPERATOR");
        post("/v1/admin/stores",admin,"s2",Map.of("storeId","store2","merchantId","merchant1","name","第二门店"));
        assertEquals(0,call("GET","/v1/operations/stores",operator,null,null).body().size());
        var grant=Map.of("grantId","g1","actorId","operator","resourceType","STORE","resourceId","store1","permission","CATALOG","reason","店员任职");
        post("/v1/admin/store-grants",admin,"g1",grant);
        var visible=call("GET","/v1/operations/stores",operator,null,null).body();
        assertEquals(1,visible.size());assertEquals("store1",visible.get(0).path("storeId").asString());
        assertEquals(403,call("GET","/v1/admin/members",operator,null,null).status());
        assertEquals(403,call("GET","/v1/stores",operator,null,null).status());
        assertEquals(403,call("POST","/v1/admin/store-grants",operator,"elevate",grant).status());
        assertEquals(403,call("GET","/v1/operations/stores",member,null,null).status());
        assertEquals(0,call("GET","/v1/operations/stores",token("x-"+UUID.randomUUID(),"operator","OPERATOR"),null,null).body().size());
        var change=Map.of("expectedVersion",0,"active",false,"reason","离店撤权");
        var revoked=post("/v1/admin/store-grants/g1/status",admin,"revoke",change);
        assertEquals(revoked,post("/v1/admin/store-grants/g1/status",admin,"revoke",change));
        assertEquals(0,call("GET","/v1/operations/stores",operator,null,null).body().size());
        assertEquals(409,call("POST","/v1/admin/store-grants/g1/status",admin,"stale",change).status());
        post("/v1/admin/store-grants",admin,"merchantGrant",Map.of("grantId","gm","actorId","operator","resourceType","MERCHANT","resourceId","merchant1","permission","CATALOG","reason","商家管理员"));
        assertEquals(2,call("GET","/v1/operations/stores",operator,null,null).body().size());
        post("/v1/admin/stores",admin,"s3",Map.of("storeId","store3","merchantId","merchant1","name","新增门店"));
        assertEquals(3,call("GET","/v1/operations/stores",operator,null,null).body().size());
        assertEquals(400,call("POST","/v1/admin/store-grants",admin,"unknown",Map.of("grantId","gx","actorId","no-identity","resourceType","STORE","resourceId","store1","permission","CATALOG","reason","无身份")).status());
    }
}
