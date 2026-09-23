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
class PersistedCommerceTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?")) throw new IllegalStateException("必须显式指定本项目隔离测试库");
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));
        registry.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired Commands commands;
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
    @Test void authenticationAndPermissionsFailClosed() throws Exception {
        assertEquals(401,call("GET","/v1/me",null,null,null).status());
        assertEquals(401,call("GET","/v1/me","invalid",null,null).status());
        assertEquals(403,call("GET","/v1/admin/members",member,null,null).status());
        jdbc.update("UPDATE platform_credential SET active=FALSE WHERE token_hash=?",JsonCodec.hash(member));
        assertEquals(401,call("GET","/v1/me",member,null,null).status());
    }
    @Test void publishesAndPersistsAuthoritativeQuoteWithReplay() throws Exception {
        seed();post("/v1/admin/campaigns",admin,"campaign",draft("c1",1,"3.00"));
        post("/v1/admin/campaigns/c1/1/publish",admin,"publish",Map.of("expectedVersion",0));
        var q=post("/v1/quotes",member,"q1",basket(2));
        assertEquals("50.00",q.path("gross").asString());assertEquals("47.00",q.path("payable").asString());
        assertEquals("c1",q.path("campaign").path("campaignId").asString());
        assertEquals(q,post("/v1/quotes",member,"q1",basket(2)));
        assertEquals(q,call("GET","/v1/quotes/"+q.path("quoteId").asString(),member,null,null).body());
        assertEquals(409,call("POST","/v1/quotes",member,"q1",basket(3)).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trade_quote WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void tenantAndMemberOwnershipAreEnforcedInSql() throws Exception {
        seed();var q=post("/v1/quotes",member,"q1",basket(1));
        assertEquals(404,call("GET","/v1/quotes/"+q.path("quoteId").asString(),other,null,null).status());
        String second=token(tenant,"other-buyer","MEMBER");
        post("/v1/admin/members",admin,"member2",Map.of("memberId","m2","actorId","other-buyer","displayName","第二会员","memberLevel","VIP"));
        assertEquals(404,call("GET","/v1/quotes/"+q.path("quoteId").asString(),second,null,null).status());
        assertEquals(404,call("GET","/v1/catalog?storeId=store1",other,null,null).status());
    }
    @Test void simultaneousDuplicateRequestsHaveOneEffect() throws Exception {
        seed();var pool=Executors.newFixedThreadPool(6);
        try {
            List<Callable<Reply>> tasks=new ArrayList<>();
            for(int i=0;i<6;i++) tasks.add(()->call("POST","/v1/quotes",member,"parallel",basket(1)));
            var results=pool.invokeAll(tasks);Set<String> ids=new HashSet<>();
            for(var future:results) {var result=future.get();assertEquals(200,result.status(),result.body().toString());ids.add(result.body().path("quoteId").asString());}
            assertEquals(1,ids.size());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trade_quote WHERE tenant_id=?",Integer.class,tenant));
        } finally {pool.shutdownNow();}
    }
    @Test void failureRollsBackEffectsCommandAndAuditTogether() {
        var actor=new Actor(tenant,"admin",Actor.Role.ADMIN);
        assertThrows(IllegalStateException.class,()->commands.run(actor,"test.rollback","rollback",Map.of("value",1),String.class,()->{
            jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name) VALUES(?,?,?)",tenant,"rollback","不应提交");
            throw new IllegalStateException("故障注入");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM merchant_record WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_command WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_audit WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void snapshotSurvivesCampaignChangeAndNewVersionIsAtomic() throws Exception {
        seed();post("/v1/admin/campaigns",admin,"c1",draft("c1",1,"3.00"));
        post("/v1/admin/campaigns/c1/1/publish",admin,"p1",Map.of("expectedVersion",0));
        var old=post("/v1/quotes",member,"q1",basket(1));
        post("/v1/admin/campaigns",admin,"c2",draft("c1",2,"5.00"));
        post("/v1/admin/campaigns/c1/2/publish",admin,"p2",Map.of("expectedVersion",0));
        assertEquals("20.00",post("/v1/quotes",member,"q2",basket(1)).path("payable").asString());
        assertEquals(old,call("GET","/v1/quotes/"+old.path("quoteId").asString(),member,null,null).body());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign WHERE tenant_id=? AND status='PUBLISHED'",Integer.class,tenant));
        assertEquals(409,call("POST","/v1/admin/campaigns/c1/2/pause",admin,"bad-version",Map.of("expectedVersion",0)).status());
    }
    @Test void rejectsPriceInjectionInvalidBodiesAndMissingKeys() throws Exception {
        seed();assertEquals(400,call("POST","/v1/quotes",member,null,basket(1)).status());
        assertEquals(400,call("POST","/v1/quotes",member,"price",Map.of("storeId","store1","items",List.of(Map.of("skuId","sku1","quantity",1,"unitPrice","0.01")))).status());
        assertEquals(413,call("POST","/v1/quotes",member,"huge",Map.of("padding","x".repeat(66000))).status());
        assertEquals(400,call("GET","/v1/catalog?storeId=store1&limit=101",member,null,null).status());
    }
    @Test void everyBusinessTableAndColumnHasComments() {
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' AND TABLE_COMMENT=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' AND COLUMN_COMMENT=''",Integer.class));
    }
}
