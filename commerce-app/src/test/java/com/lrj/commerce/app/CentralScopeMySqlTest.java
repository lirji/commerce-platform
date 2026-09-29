package com.lrj.commerce.app;

import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.ScopeDtos.*;
import com.lrj.authz.protocol.ScopeAccessDtos.*;
import com.lrj.authz.protocol.CentralAccessDtos.Check;
import com.lrj.authz.sdk.*;
import com.lrj.authz.sdk.AccessDeniedException;
import com.lrj.commerce.app.iam.*;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.scope.ScopeQuery;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真MySQL证明SQL谓词、租户隔离和批次事务；auth边界为契约桩，真实HTTP另行验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CentralScopeMySqlTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        String url=System.getenv("COMMERCE_TEST_DB_URL");if(url==null||!url.contains("/commerce_test_20260923?"))throw new IllegalStateException("必须使用隔离测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));r.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        r.add("commerce.sandbox-enabled",()->true);r.add("commerce.workers-enabled",()->false);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CentralStoreBindingMapper bindings;
    @Autowired ScopeWorkMapper work;
    @Autowired Commands commands;
    @Autowired com.lrj.commerce.catalog.product.api.ScopedProductOperations products;
    @Autowired List<ScopeQuery> owners;
    private CentralAccessClient client;private CentralScopeService service;
    private String tenant,authTenant,principal,member,policy,directory,grant;
    private CentralStoreIdentity identity;
    private final AtomicLong epoch=new AtomicLong(1);
    private final AtomicBoolean denied=new AtomicBoolean();
    private List<Alternative> alternatives;
    private static String id(){return UUID.randomUUID().toString();}
    @BeforeEach void seed(){
        tenant="scope-"+id();authTenant=id();principal=id();member=id();policy=id();directory=id();grant=id();epoch.set(1);denied.set(false);
        jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,'OPERATOR',?)",JsonCodec.hash(id()),tenant,"operator",java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        bind();jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name,status,version) VALUES(?,'M','scope merchant','ACTIVE',0)",tenant);
        for(int i=1;i<=55;i++){
            String store="S%03d".formatted(i),product="P%03d".formatted(i);
            jdbc.update("INSERT INTO store_record(tenant_id,store_id,merchant_id,name,status,version) VALUES(?,?,'M',?,'ACTIVE',0)",tenant,store,"store-"+i);
            jdbc.update("INSERT INTO catalog_product(tenant_id,product_id,store_id,title,category,brand,version) VALUES(?,?,?,?, 'category','brand',1)",tenant,product,store,"product-"+i);
        }
        alternatives=List.of(new Alternative(grant,1,List.of(new Clause(Kind.TENANT_ALL,List.of(),false))));
        client=mock(CentralAccessClient.class);when(client.requireScope(anyString(),any())).thenAnswer(inv->{if(denied.get())throw new AccessDeniedException("revoked");return plan(inv.getArgument(1));});
        when(client.checkResource(anyString(),any(),any())).thenAnswer(inv->{Check c=inv.getArgument(1);Facts f=inv.getArgument(2);var p=plan(c);boolean allow=alternatives.stream().anyMatch(a->a.clauses().stream().allMatch(cl->switch(cl.kind()){case TENANT_ALL->true;case SPECIFIED_STORES->cl.values().contains(f.storeId());case SPECIFIED_RESOURCES->cl.values().contains(f.resourceId());default->false;}));
            return new ResourceDecision("1",c.requestId(),c.capability(),c.resourceType(),f.resourceId(),f.resourceVersion(),allow?"ALLOW":"DENY",id(),p.context(),p.validUntil());});
        service=new CentralScopeService(client,bindings,work,commands,owners);identity=service.authenticate("test-user",authTenant,"store");
    }
    private void bind(){jdbc.update("INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES(?,?,?,1,?,'operator','scope-test')",authTenant,principal,member,tenant);}
    private Plan plan(Check c){return new Plan("1",c.requestId(),c.capability(),c.resourceType(),"ALLOW",id(),new GovernanceDtos.AccessContext(principal,member,1,1,1,authTenant,"commerce","test","commerce-p3","HUMAN",id()),policy,epoch.get(),directory,1,1,1,Instant.now().plusSeconds(25).toString(),alternatives);}
    @Test void sqlKeepsPathAndsBeforePaginationSearchCountAndDetail(){
        alternatives=List.of(new Alternative(grant,1,List.of(new Clause(Kind.SPECIFIED_STORES,List.of("S001"),false),new Clause(Kind.SPECIFIED_RESOURCES,List.of("P001","P002"),false))),
            new Alternative(id(),1,List.of(new Clause(Kind.SPECIFIED_STORES,List.of("S002"),false),new Clause(Kind.SPECIFIED_RESOURCES,List.of("P002"),false))));
        var first=service.page(identity,"product","",1,"");assertEquals(2,first.total());assertEquals(2,first.stores());assertEquals("P001",first.items().getFirst().resourceId());assertNotNull(first.nextCursor());
        var next=service.page(identity,"product",first.nextCursor(),1,"");assertEquals("P002",next.items().getFirst().resourceId());assertNull(next.nextCursor());
        var hidden=service.page(identity,"product","",50,"product-3");assertEquals(0,hidden.total());assertEquals(0,hidden.stores());assertTrue(hidden.items().isEmpty());
        assertEquals("P001",service.detail(identity,"product","P001").resourceId());assertThrows(AccessDeniedException.class,()->service.detail(identity,"product","P003"));
        assertThrows(AccessDeniedException.class,()->service.page(identity,"product",first.nextCursor(),1,"other"));
        epoch.incrementAndGet();assertThrows(AccessDeniedException.class,()->service.page(identity,"product",first.nextCursor(),1,""));
    }
    @Test void exportBatchesArePersistentIdempotentAndResourceChangesBlockDownload(){
        var job=service.submit(identity,"product","",id());assertEquals("SUBMITTED",job.state());
        var running=service.start(identity,"product",job.id(),job.version(),id());String key=id();
        var batch=service.advance(identity,"product",job.id(),running.version(),key);assertEquals(50,batch.rowCount());assertEquals("RUNNING",batch.state());
        assertEquals(batch,service.advance(identity,"product",job.id(),running.version(),key));
        var restarted=new CentralScopeService(client,bindings,work,commands,owners);
        var complete=restarted.advance(identity,"product",job.id(),batch.version(),id());assertEquals(55,complete.rowCount());assertEquals("COMPLETED",complete.state());
        assertEquals(55,restarted.download(identity,"product",job.id()).rows().size());
        assertEquals(55,jdbc.queryForObject("SELECT count(*) FROM central_scope_export_row WHERE tenant_id=? AND job_id=?",Integer.class,tenant,job.id()));
        jdbc.update("UPDATE catalog_product SET store_id='S002',version=version+1 WHERE tenant_id=? AND product_id='P001'",tenant);
        assertThrows(DomainException.class,()->restarted.download(identity,"product",job.id()));
    }
    @Test void changedAuthorizationBlocksQueuedRunningAndCompletedWork(){
        var queued=service.submit(identity,"store","",id());denied.set(true);
        assertThrows(AccessDeniedException.class,()->service.start(identity,"store",queued.id(),queued.version(),id()));denied.set(false);
        var running=service.start(identity,"store",queued.id(),queued.version(),id());epoch.incrementAndGet();
        assertThrows(AccessDeniedException.class,()->service.advance(identity,"store",queued.id(),running.version(),id()));epoch.decrementAndGet();
        var batch=service.advance(identity,"store",queued.id(),running.version(),id());var complete=service.advance(identity,"store",queued.id(),batch.version(),id());
        denied.set(true);assertThrows(AccessDeniedException.class,()->service.download(identity,"store",complete.id()));
    }
    @Test void concurrentWorkersCannotCommitTheSameCheckpointTwice()throws Exception{
        var job=service.submit(identity,"store","",id());var running=service.start(identity,"store",job.id(),job.version(),id());
        var pool=Executors.newFixedThreadPool(2);var latch=new CountDownLatch(1);
        try{
            List<Future<Boolean>> futures=new ArrayList<>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{latch.await();try{service.advance(identity,"store",job.id(),running.version(),id());return true;}catch(DomainException e){assertEquals(DomainException.Code.CONFLICT,e.code());return false;}}));
            latch.countDown();int successes=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))successes++;assertEquals(1,successes);
            assertEquals(50,jdbc.queryForObject("SELECT count(*) FROM central_scope_export_row WHERE tenant_id=? AND job_id=?",Integer.class,tenant,job.id()));
        }finally{pool.shutdownNow();}
    }
    @Test void failedRowInsertRollsBackTheWholeBatchAndCanRetryTheSameCommand(){
        var job=service.submit(identity,"store","",id());var running=service.start(identity,"store",job.id(),job.version(),id());
        var row=owners.stream().filter(o->o.resourceType().equals("store")).findFirst().orElseThrow().fact(identity.actor(),"S002");
        // 只在本测试UUID任务注入冲突，第二行失败必须回滚先前第一行及命令领取。
        jdbc.update("INSERT INTO central_scope_export_row(tenant_id,job_id,sequence_no,resource_id,resource_version,snapshot_json) VALUES(?,?,2,'S002',0,?)",tenant,job.id(),JsonCodec.write(row));
        String key=id();assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.advance(identity,"store",job.id(),running.version(),key));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM central_scope_export_row WHERE tenant_id=? AND job_id=?",Integer.class,tenant,job.id()));
        assertEquals(running.version(),work.job(tenant,job.id()).version());assertEquals(0,work.job(tenant,job.id()).rowCount());
        jdbc.update("DELETE FROM central_scope_export_row WHERE tenant_id=? AND job_id=? AND sequence_no=2",tenant,job.id());
        assertEquals(50,service.advance(identity,"store",job.id(),running.version(),key).rowCount());
    }
    @Test void quotaIsPerRequesterAndOtherPrincipalCannotReuseCursorOrJob(){
        var page=service.page(identity,"store","",1,"");var job=service.submit(identity,"store","",id());service.submit(identity,"store","",id());
        var full=assertThrows(DomainException.class,()->service.submit(identity,"store","",id()));assertEquals(DomainException.Code.LIMIT_EXCEEDED,full.code());
        principal=id();member=id();bind();var another=service.authenticate("another-user",authTenant,"store");
        assertThrows(AccessDeniedException.class,()->service.page(another,"store",page.nextCursor(),1,""));assertThrows(AccessDeniedException.class,()->service.job(another,"store",job.id()));
        assertNotNull(service.submit(another,"store","",id()));
    }
    @Test void controlledWriteUsesItsOwnScopeAndAtomicVersionAndCommandReceipt(){
        alternatives=List.of(new Alternative(grant,1,List.of(new Clause(Kind.SPECIFIED_STORES,List.of("S001"),false))));
        var change=new com.lrj.commerce.catalog.product.api.ScopedProductOperations.MetadataChange(1,"new-title","category","brand");
        String key=id();var result=service.changeProduct(identity,"P001",change,key,products);
        assertEquals(2,result.version());assertEquals(result,service.changeProduct(identity,"P001",change,key,products));
        assertThrows(DomainException.class,()->service.changeProduct(identity,"P001",change,id(),products));
        assertThrows(AccessDeniedException.class,()->service.changeProduct(identity,"P002",change,id(),products));
        assertEquals("product-2",jdbc.queryForObject("SELECT title FROM catalog_product WHERE tenant_id=? AND product_id='P002'",String.class,tenant));
        verify(client,atLeastOnce()).requireScope(eq("test-user"),argThat(c->c.capability().equals("commerce.product.update")));
        principal=id();member=id();bind();var another=service.authenticate("another",authTenant,"product");
        assertThrows(DomainException.class,()->service.changeProduct(another,"P001",change,key,products));
        denied.set(true);assertThrows(AccessDeniedException.class,()->service.changeProduct(identity,"P001",change,key,products));
    }
    @Test void ownerRejectsStaleOrOutOfScopeWriteEvenIfCallerPassesAChangedResource(){
        var scope=new ScopeQuery.Filter(List.of(new ScopeQuery.Path(false,List.of("S001"),List.of())));
        var change=new com.lrj.commerce.catalog.product.api.ScopedProductOperations.MetadataChange(1,"new-title","category","brand");
        assertThrows(DomainException.class,()->products.changeScoped(identity.actor(),scope,Instant.now().plusSeconds(4),principal,id(),"P002","S002",change));
        assertThrows(DomainException.class,()->products.changeScoped(identity.actor(),scope,Instant.now().minusSeconds(1),principal,id(),"P001","S001",change));
        jdbc.update("UPDATE catalog_product SET store_id='S002',version=version+1 WHERE tenant_id=? AND product_id='P001'",tenant);
        assertThrows(DomainException.class,()->products.changeScoped(identity.actor(),scope,Instant.now().plusSeconds(4),principal,id(),"P001","S001",change));
        assertEquals("product-1",jdbc.queryForObject("SELECT title FROM catalog_product WHERE tenant_id=? AND product_id='P001'",String.class,tenant));
    }

}
