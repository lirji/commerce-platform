package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.ExecutionAccessDtos.Reference;
import com.lrj.authz.protocol.ScopeAccessDtos.ResourceDecision;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.catalog.product.api.ProductOperationsApi;
import com.lrj.commerce.catalog.job.api.CatalogJobApi;
import com.lrj.commerce.catalog.pricing.api.ChannelPriceApi;
import com.lrj.commerce.catalog.merchandising.api.CatalogMerchandisingApi;
import com.lrj.commerce.store.access.api.StoreAccessApi;
import com.lrj.commerce.app.iam.CentralCatalogService;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真MySQL验证经营效果、冻结和任务检查点；中央SDK为契约桩，真实授权图另有跨进程验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"commerce.iam.store-read.enabled=true","commerce.iam.catalog.enabled=true"})
class CentralCatalogMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        String url=System.getenv("COMMERCE_TEST_DB_URL");if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @MockitoBean CentralAccessClient client;
    @Autowired CentralCatalogService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductOperationsApi products;
    @Autowired CatalogJobApi jobs;
    @Autowired ChannelPriceApi prices;
    @Autowired CatalogMerchandisingApi merchandising;
    @Autowired StoreAccessApi access;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @LocalServerPort int port;
    private String tenant,central,principal,member,adminToken;
    private Actor actor,admin;
    private final AtomicBoolean denied=new AtomicBoolean();
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed(){
        denied.set(false);tenant="p6-"+id();central=id();principal=id();member=id();adminToken=id();
        admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,'OPERATOR',?)",JsonCodec.hash(id()),tenant,"operator",java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,'admin','ADMIN',?)",JsonCodec.hash(adminToken),tenant,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','p6-test')",central,principal,member,tenant);
        jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,'M','merchant','ACTIVE',0)",tenant);
        for(String store:List.of("S1","S2"))jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,?,'M',?,'ACTIVE',0)",tenant,store,store);
        jdbc.update("INSERT INTO catalog_authority_route(tenant_id,auth_tenant_id,state) VALUES(?,?,'SHADOW')",tenant,central);
        jdbc.update("UPDATE catalog_authority_route SET state='CENTRAL',frozen=TRUE,ever_central=TRUE,version=version+1 WHERE tenant_id=?",tenant);
        when(client.issueExecution(anyString(),any(),any())).thenAnswer(inv->{
            assertEquals("user-token",inv.getArgument(0));CentralAccessDtos.Check c=inv.getArgument(1);assertEquals("commerce.catalog.operate",c.capability());
            if(denied.get())throw new AccessDeniedException("revoked");
            return new Reference("1",c.requestId(),id(),context(),c.capability(),c.resourceType(),((Instant)inv.getArgument(2)).toString());
        });
        when(client.checkExecution(anyString(),any(),any())).thenAnswer(inv->{
            CentralAccessDtos.Check c=inv.getArgument(1);ScopeDtos.Facts f=inv.getArgument(2);
            return new ResourceDecision("1",c.requestId(),c.capability(),c.resourceType(),f.resourceId(),f.resourceVersion(),!denied.get()&&f.storeId().equals("S1")?"ALLOW":"DENY",id(),context(),Instant.now().plusSeconds(25).toString());
        });
        actor=service.authenticate("user-token",central,Instant.now().plusSeconds(600));
    }
    private GovernanceDtos.AccessContext context(){return new GovernanceDtos.AccessContext(principal,member,1,1,1,central,"commerce","test","commerce-p6","HUMAN",id());}
    private ProductOperationsApi.Sku sku(){
        products.create(actor,id(),new ProductOperationsApi.ProductInput("P","S1","product","category","brand"));
        return products.variant(actor,id(),new ProductOperationsApi.Variant("SKU","P","S1","sku","10.00",List.of(new ProductOperationsApi.Specification("size","S"))));
    }
    @Test void fullCatalogKeepsPricePublishMerchandisingAndBackgroundEffects(){
        var sku=sku();var changed=products.change(actor,id(),"SKU",new ProductOperationsApi.Change("S1",sku.revision(),"sku","11.00","ACTIVE","publish"));
        assertEquals("11.00",changed.unitPrice());
        assertEquals(1,prices.change(actor,id(),"SKU",new ChannelPriceApi.Change("S1",Actor.Channel.MINI_APP,0,"9.00",Instant.now().minusSeconds(1),Instant.now().plusSeconds(300),true,"channel price")).version());
        var category=merchandising.createCategory(actor,id(),new CatalogMerchandisingApi.CategoryInput("C","S1",null,"category"));
        merchandising.createTemplate(actor,id(),new CatalogMerchandisingApi.Template("T",1,"S1","template",List.of(new CatalogMerchandisingApi.Attribute("size",List.of("S","M")))));
        assertEquals(1,merchandising.changeProfile(actor,id(),"P",new CatalogMerchandisingApi.ProfileChange("S1",0,category.categoryId(),null,null,"description",List.of(),"profile")).version());
        assertEquals("12345678",merchandising.changeBarcode(actor,id(),"SKU",new CatalogMerchandisingApi.BarcodeChange("S1",0,"12345678","barcode")).barcode());
        String job=id();var definition=new CatalogJobApi.Create(job,"S1","batch",CatalogJobApi.Action.PRICE,null,Instant.now().plusSeconds(300),List.of(new CatalogJobApi.Target("SKU",changed.revision(),"12.00")),"batch price");
        jobs.create(actor,job,definition);assertEquals(1,jobs.pump(actor,"S1"));
        assertEquals("12.00",products.skus(actor,"S1","",10).getFirst().unitPrice());
        String stored=jdbc.queryForObject("SELECT creator_json FROM catalog_operation_job WHERE tenant_id=? AND job_id=?",String.class,tenant,job);
        assertTrue(stored.contains(actor.executionId()));assertFalse(stored.contains("user-token"));
        assertNull(JsonCodec.read("{\"tenantId\":\"legacy\",\"actorId\":\"old\",\"role\":\"OPERATOR\",\"channel\":\"WEB\"}",Actor.class).executionId());
    }
    @Test void revokedExpiredCrossStoreAndOldAdminCannotExecuteOrReplay(){
        var sku=sku();String key=id();var change=new ProductOperationsApi.Change("S1",sku.revision(),"sku","11.00","ACTIVE","publish");products.change(actor,key,"SKU",change);
        String job=id();jobs.create(actor,job,new CatalogJobApi.Create(job,"S1","blocked",CatalogJobApi.Action.PRICE,null,Instant.now().plusSeconds(300),List.of(new CatalogJobApi.Target("SKU",sku.revision()+1,"20.00")),"revocation"));
        assertThrows(RuntimeException.class,()->products.skus(admin,"S1","",10));
        assertThrows(RuntimeException.class,()->products.skus(actor,"S2","",10));
        denied.set(true);assertThrows(RuntimeException.class,()->products.change(actor,key,"SKU",change));
        jobs.tick();assertEquals(0,jdbc.queryForObject("SELECT cursor_index FROM catalog_operation_job WHERE tenant_id=? AND job_id=?",Integer.class,tenant,job));
        denied.set(false);jdbc.update("UPDATE platform_credential SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE tenant_id=? AND actor_id='operator'",tenant);
        assertThrows(RuntimeException.class,()->products.skus(actor,"S1","",10));
    }
    @Test void databaseFreezeAndSafeStopPreventLegacyRegression(){
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO store_operator_grant(tenant_id,grant_id,actor_id,resource_type,resource_id,permission,reason) VALUES(?,'g','operator','STORE','S1','CATALOG','test')",tenant));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE catalog_authority_route SET state='LEGACY',ever_central=FALSE,frozen=FALSE,version=version+1 WHERE tenant_id=?",tenant));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM catalog_authority_route WHERE tenant_id=?",tenant));
        jdbc.update("UPDATE catalog_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=?",tenant);
        assertThrows(RuntimeException.class,()->products.skus(actor,"S1","",10));
        assertThrows(RuntimeException.class,()->products.skus(admin,"S1","",10));
    }
    @Test void cutoverWaitsForAlreadyAuthorizedLegacyTransaction()throws Exception {
        String race="p6-race-"+id();
        jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,'M','race','ACTIVE',0)",race);
        jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,'S','M','race','ACTIVE',0)",race);
        jdbc.update("INSERT INTO catalog_authority_route(tenant_id,auth_tenant_id,state) VALUES(?,?,'SHADOW')",race,id());
        var legacy=new Actor(race,"admin",Actor.Role.ADMIN);var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        try {
            var future=tx.execute(status->{
                access.requireCatalog(legacy,"S");
                var task=pool.submit(()->jdbc.update("UPDATE catalog_authority_route SET state='CENTRAL',frozen=TRUE,ever_central=TRUE,version=version+1 WHERE tenant_id=?",race));
                assertThrows(java.util.concurrent.TimeoutException.class,()->task.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
                return task;
            });
            assertEquals(1,future.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(RuntimeException.class,()->access.requireCatalog(legacy,"S"));
        }finally{pool.shutdownNow();}
    }
    @Test void httpUsesCentralIdentityAndOldBearerCannotBypassRoute()throws Exception{
        var http=HttpClient.newHttpClient();String path="http://127.0.0.1:"+port+"/v1/operations/products?storeId=S1";
        var current=http.send(HttpRequest.newBuilder(URI.create(path)).header("Authorization","Bearer user-token").header("X-Tenant-Id",central).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,current.statusCode(),current.body());
        var old=http.send(HttpRequest.newBuilder(URI.create(path)).header("Authorization","Bearer "+adminToken).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,old.statusCode(),old.body());
    }
}
