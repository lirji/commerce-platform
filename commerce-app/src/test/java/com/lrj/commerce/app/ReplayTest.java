package com.lrj.commerce.app;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.runtime.api.EventHandler.ReplaySafety;
import com.lrj.commerce.runtime.api.EventHandler.SideEffect;
import com.lrj.commerce.runtime.persistence.ReplayMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.4/P4.5 重放：安全门由消费者声明的副作用分类驱动，资金、外部、不可逆副作用与未分类一律拒绝；
 * 只有已证明安全的纯投影（marketing-effects-v1）可以历史重放。重放有范围、试运行、预算、暂停/恢复/取消、审计，
 * 对真实副作用（营销效果投影、Inbox）验证，多实例并发推进同一任务不重复执行。
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReplayTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {Phase4Properties.register(registry);}
    static final String EFFECTS="marketing-effects-v1",CREATED="order.created.v1";
    @LocalServerPort int port;@Autowired JdbcTemplate jdbc;@Autowired EventReplay replay;@Autowired ReplayMapper replayMapper;@Autowired Commands commands;@Autowired RecoveryAudit audit;
    @Autowired PlatformTransactionManager transactions;@Autowired List<EventHandler> handlers;
    private Phase4Http http;private String tenant,admin,member;private Instant started;
    @BeforeEach void setup(){http=new Phase4Http(jdbc,port);tenant="rp-"+UUID.randomUUID();admin=http.token(tenant,"ops-admin","ADMIN");member=http.token(tenant,"buyer","MEMBER");started=Instant.now().minusSeconds(5);}
    @AfterEach void cleanup(){jdbc.update("UPDATE platform_replay SET status='CANCELLED' WHERE tenant_id=? AND status IN ('RUNNING','PAUSED')",tenant);jdbc.update("DELETE FROM platform_inbox WHERE consumer_id LIKE 'stub-%'");}

    /** 每个已登记消费者都有分类与依据；只有纯投影允许历史重放与重新执行。 */
    @Test void everyConsumerIsClassifiedAndOnlyThePureProjectionIsReplayable() throws Exception {
        var list=http.ok("GET","/v1/admin/runtime/replay/classifications",admin,null,null);
        assertEquals(handlers.size(),list.size());assertTrue(list.size()>=10);
        for(var c:list) {
            assertFalse(c.path("effects").isEmpty(),"未分类："+c);assertFalse(c.path("evidence").asString().isBlank());
            boolean pure=c.path("consumer").asString().equals(EFFECTS);
            assertEquals(pure,c.path("unprocessed").path("allowed").asBoolean(),c.toString());assertEquals(pure,c.path("reprocess").path("allowed").asBoolean(),c.toString());
        }
    }
    /** 安全门硬规则：资金/外部/不可逆即使声明可重放也拒绝；未分类拒绝；重新执行只允许纯投影。 */
    @Test void safetyGateFailsClosed() {
        assertEquals("UNCLASSIFIED",ReplayGate.check(stub("stub-a",null,e->{}),ReplayGate.Mode.UNPROCESSED).code());
        for(var unsafe:List.of(SideEffect.FINANCIAL_SIDE_EFFECT,SideEffect.EXTERNAL_SIDE_EFFECT,SideEffect.IRREVERSIBLE_SIDE_EFFECT))
            assertEquals("REPLAY_NOT_SUPPORTED",ReplayGate.check(stub("stub-a",new ReplaySafety(Set.of(SideEffect.PURE,unsafe),true,true,"声明可重放"),e->{}),ReplayGate.Mode.UNPROCESSED).code(),unsafe.name());
        var idempotent=stub("stub-a",new ReplaySafety(Set.of(SideEffect.IDEMPOTENT_WRITE),true,false,"业务键"),e->{});
        assertTrue(ReplayGate.check(idempotent,ReplayGate.Mode.UNPROCESSED).allowed());
        assertEquals("REPROCESS_NOT_SUPPORTED",ReplayGate.check(idempotent,ReplayGate.Mode.REPROCESS).code());
        assertEquals("REPROCESS_NOT_SUPPORTED",ReplayGate.check(stub("stub-a",new ReplaySafety(Set.of(SideEffect.PURE),true,false,"投影"),e->{}),ReplayGate.Mode.REPROCESS).code());
        assertEquals("UNKNOWN_CONSUMER",ReplayGate.check(null,ReplayGate.Mode.UNPROCESSED).code());
    }
    /** 资金类消费者：试运行显示拒绝且不执行；创建任务409、不写任务、计入拒绝次数。 */
    @Test void sensitiveConsumersAreRejectedBeforeAnyWork() throws Exception {
        long blocked=replay.stats().blocked();
        var dry=http.ok("POST","/v1/admin/runtime/replay/dry-run",admin,null,scope("order-payment-v1",List.of("payment.paid.v1"),"UNPROCESSED"));
        assertFalse(dry.path("gate").path("allowed").asBoolean());assertEquals("REPLAY_NOT_SUPPORTED",dry.path("gate").path("code").asString());assertEquals(0,dry.path("wouldExecute").asInt());
        for(var c:List.of(new String[]{"order-payment-v1","payment.paid.v1"},new String[]{"payment-order-closing-v1","order.closing.v1"},new String[]{"member-growth-v1","order.completed.v1"},new String[]{"journey-order-paid-v1","order.paid.v1"})) {
            var r=http.call("POST","/v1/admin/runtime/replays",admin,"blocked-"+c[0],create("job-"+c[0],c[0],List.of(c[1]),"UNPROCESSED"));
            assertEquals(409,r.status(),c[0]+" "+r.body());
        }
        assertEquals(blocked+4,replay.stats().blocked());
        assertEquals(0,(int)jdbc.queryForObject("SELECT COUNT(*) FROM platform_replay WHERE tenant_id=?",Integer.class,tenant));
    }
    /** 端到端：投影丢失且从未被该消费者处理的历史事件，试运行只读，UNPROCESSED重放恰好补齐这些事件；再次REPROCESS收敛不变。 */
    @Test void unprocessedReplayRestoresTheProjectionExactlyOnce() throws Exception {
        var orders=orders(3);var lost=orders.subList(0,2);
        for(var o:lost){jdbc.update("DELETE FROM marketing_effect_order WHERE tenant_id=? AND order_id=?",tenant,o);jdbc.update("DELETE FROM platform_inbox WHERE consumer_id=? AND event_id=?",EFFECTS,createdEvent(o));}
        var projectionBefore=projection();long inboxBefore=otherInbox();
        var dry=http.ok("POST","/v1/admin/runtime/replay/dry-run",admin,null,scope(EFFECTS,List.of(CREATED),"UNPROCESSED"));
        assertTrue(dry.path("gate").path("allowed").asBoolean());assertEquals(3,dry.path("events").asInt());assertEquals(1,dry.path("alreadyProcessed").asInt());assertEquals(2,dry.path("wouldExecute").asInt());
        assertEquals(projectionBefore,projection(),"试运行不改变业务状态");
        var job=http.ok("POST","/v1/admin/runtime/replays",admin,"create-1",create("restore-1",EFFECTS,List.of(CREATED),"UNPROCESSED"));
        assertEquals("RUNNING",job.path("status").asString());
        var done=runUntilFinished("restore-1");
        assertEquals("COMPLETED",done.status());assertEquals(3,done.examined());assertEquals(2,done.executed());assertEquals(1,done.alreadyProcessed());assertEquals(0,done.failed());
        assertEquals(3,projection().size(),"丢失的两单投影已补齐");
        for(var o:orders){assertEquals(1,(int)jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox WHERE consumer_id=? AND event_id=?",Integer.class,EFFECTS,createdEvent(o)));
            assertEquals("DELIVERED",jdbc.queryForObject("SELECT status FROM platform_event WHERE event_id=?",String.class,createdEvent(o)),"重放不改变事件状态");}
        assertEquals(inboxBefore,otherInbox(),"重放不执行其他消费者");
        var restored=projection();
        // REPROCESS：纯投影重新执行全部事件，从权威数据重算，结果不变（不叠加）。
        http.ok("POST","/v1/admin/runtime/replays",admin,"create-2",create("reprocess-1",EFFECTS,List.of(CREATED),"REPROCESS"));
        var again=runUntilFinished("reprocess-1");assertEquals(3,again.executed());assertEquals(0,again.alreadyProcessed());
        assertEquals(restored,projection(),"重新执行收敛");
        // 第三次UNPROCESSED：全部已处理，不执行。
        http.ok("POST","/v1/admin/runtime/replays",admin,"create-3",create("restore-2",EFFECTS,List.of(CREATED),"UNPROCESSED"));
        var noop=runUntilFinished("restore-2");assertEquals(0,noop.executed());assertEquals(3,noop.alreadyProcessed());
        assertEquals(3,(int)jdbc.queryForObject("SELECT COUNT(*) FROM platform_recovery WHERE tenant_id=? AND work_type='event.replay' AND action='REPLAY_CREATE'",Integer.class,tenant),"创建写审计");
    }
    /** 暂停后不推进，恢复继续，取消终止；版本不符拒绝；控制写审计。 */
    @Test void jobsArePausableResumableAndCancelable() throws Exception {
        orders(2);
        var job=http.ok("POST","/v1/admin/runtime/replays",admin,"c1",create("ctl-1",EFFECTS,List.of(CREATED),"REPROCESS"));
        var paused=http.ok("POST","/v1/admin/runtime/replays/ctl-1/control",admin,"p1",Map.of("action","PAUSE","expectedVersion",job.path("version").asLong(),"reason","高峰期暂停"));
        assertEquals("PAUSED",paused.path("status").asString());
        for(int i=0;i<5;i++)replay.tick();
        assertEquals(0,replayMapper.find(tenant,"ctl-1").examined(),"暂停中不推进");
        assertEquals(409,http.call("POST","/v1/admin/runtime/replays/ctl-1/control",admin,"p2",Map.of("action","RESUME","expectedVersion",job.path("version").asLong(),"reason","旧版本")).status());
        http.ok("POST","/v1/admin/runtime/replays/ctl-1/control",admin,"p3",Map.of("action","RESUME","expectedVersion",paused.path("version").asLong(),"reason","恢复"));
        var cancelled=http.ok("POST","/v1/admin/runtime/replays/ctl-1/control",admin,"p4",Map.of("action","CANCEL","expectedVersion",paused.path("version").asLong()+1,"reason","取消"));
        assertEquals("CANCELLED",cancelled.path("status").asString());
        for(int i=0;i<5;i++)replay.tick();
        assertEquals(0,replayMapper.find(tenant,"ctl-1").examined(),"取消后不推进");
        assertEquals(409,http.call("POST","/v1/admin/runtime/replays/ctl-1/control",admin,"p5",Map.of("action","RESUME","expectedVersion",cancelled.path("version").asLong(),"reason","已终结")).status());
        assertEquals(List.of("REPLAY_CREATE","REPLAY_PAUSE","REPLAY_RESUME","REPLAY_CANCEL"),jdbc.queryForList("SELECT action FROM platform_recovery WHERE tenant_id=? AND work_id='ctl-1' ORDER BY id",String.class,tenant));
    }
    /** 范围与预算：区间不超过31天且不在未来，类型必须由消费者处理，上限不超过MAX_EVENTS，每租户最多3个活动任务。 */
    @Test void scopeLimitsAreEnforced() throws Exception {
        var now=Instant.now();
        assertEquals(400,http.call("POST","/v1/admin/runtime/replays",admin,"s1",create("s1",EFFECTS,List.of(CREATED),"UNPROCESSED",now.minus(Duration.ofDays(32)),now,null)).status());
        assertEquals(400,http.call("POST","/v1/admin/runtime/replays",admin,"s2",create("s2",EFFECTS,List.of(CREATED),"UNPROCESSED",now.minusSeconds(60),now.plusSeconds(3600),null)).status());
        assertEquals(400,http.call("POST","/v1/admin/runtime/replays",admin,"s3",create("s3",EFFECTS,List.of("payment.paid.v1"),"UNPROCESSED",now.minusSeconds(60),now,null)).status());
        assertEquals(400,http.call("POST","/v1/admin/runtime/replays",admin,"s4",create("s4",EFFECTS,List.of(CREATED),"UNPROCESSED",now.minusSeconds(60),now,EventReplay.MAX_EVENTS+1)).status());
        assertEquals(404,http.call("POST","/v1/admin/runtime/replays",admin,"s5",create("s5","nope-v1",List.of(CREATED),"UNPROCESSED",now.minusSeconds(60),now,null)).status());
        for(int i=0;i<EventReplay.MAX_ACTIVE;i++)http.ok("POST","/v1/admin/runtime/replays",admin,"a"+i,create("active-"+i,EFFECTS,List.of(CREATED),"UNPROCESSED",now.minusSeconds(60),now,null));
        var over=http.call("POST","/v1/admin/runtime/replays",admin,"a9",create("active-9",EFFECTS,List.of(CREATED),"UNPROCESSED",now.minusSeconds(60),now,null));
        assertNotEquals(200,over.status(),over.body().toString());
    }
    /** R13：有消费者的实时到期事件达到阈值时整轮让路，任务不推进（共享测试库有测试遗留的到期PENDING事件）。 */
    @Test void replayYieldsToLiveWork() throws Exception {
        orders(1);http.ok("POST","/v1/admin/runtime/replays",admin,"y1",create("yield-1",EFFECTS,List.of(CREATED),"REPROCESS"));
        var yielding=new EventReplay(replayMapper,handlers,commands,audit,transactions,Clock.systemUTC(),new WorkLanes(),1);
        // 制造一条有消费者的实时到期事件（测试关闭Worker，它保持PENDING）。
        String live=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status) VALUES(?,?,?,?,1,'{}','PENDING')",live,tenant,CREATED,"live-"+live);
        try {
            assertEquals(0,yielding.tick());assertEquals(1,yielding.stats().yielded());
            assertEquals(0,replayMapper.find(tenant,"yield-1").examined(),"让路时不推进");
        } finally {jdbc.update("DELETE FROM platform_event WHERE event_id=?",live);}
    }
    /** 执行时安全门：部署后分类变为资金副作用时，运行中的任务在下一项前失败，消费者不被调用。 */
    @Test void executionTimeGateStopsARunningJobWhenTheClassificationChanges() {
        var calls=new AtomicInteger();var unsafe=stub("stub-unsafe-v1",ReplaySafety.notReplayable("改为资金副作用",SideEffect.FINANCIAL_SIDE_EFFECT),e->calls.incrementAndGet());
        var instance=new EventReplay(replayMapper,List.of(unsafe),commands,audit,transactions,Clock.systemUTC(),new WorkLanes(),1_000_000);
        insertJob("gate-1","stub-unsafe-v1","UNPROCESSED");
        for(int i=0;i<3;i++)instance.tick();
        var job=replayMapper.find(tenant,"gate-1");assertEquals("FAILED",job.status());assertEquals("GATE:REPLAY_NOT_SUPPORTED",job.lastError());assertEquals(0,calls.get());
    }
    /** 消费者失败：该事件回滚后计数并跳过，任务继续；失败达到上限任务失败。证据只含事件标识、分类与异常类型。 */
    @Test void consumerFailuresAreCountedSkippedAndBounded() throws Exception {
        var orders=orders(EventReplay.MAX_FAILURES+2);String poison=createdEvent(orders.get(1));
        var calls=new AtomicInteger();
        var flaky=stub("stub-flaky-v1",new ReplaySafety(Set.of(SideEffect.PURE),true,true,"测试投影"),e->{calls.incrementAndGet();if(e.eventId().equals(poison))throw new DomainException(DomainException.Code.CONFLICT,"坏数据");});
        var instance=new EventReplay(replayMapper,List.of(flaky),commands,audit,transactions,Clock.systemUTC(),new WorkLanes(),1_000_000);
        insertJob("flaky-1","stub-flaky-v1","UNPROCESSED");
        for(int i=0;i<50&&replayMapper.find(tenant,"flaky-1").status().equals("RUNNING");i++)instance.tick();
        var job=replayMapper.find(tenant,"flaky-1");
        assertEquals("COMPLETED",job.status());assertEquals(orders.size(),job.examined());assertEquals(1,job.failed());assertEquals(orders.size()-1,job.executed());
        assertTrue(job.lastError().startsWith(poison+":BUSINESS_REJECTED:DomainException/CONFLICT"),job.lastError());
        assertEquals(0,(int)jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox WHERE consumer_id='stub-flaky-v1' AND event_id=?",Integer.class,poison),"失败事件的Inbox随回滚撤销");
        var broken=stub("stub-broken-v1",new ReplaySafety(Set.of(SideEffect.PURE),true,true,"测试投影"),e->{throw new IllegalStateException("总是失败");});
        var failing=new EventReplay(replayMapper,List.of(broken),commands,audit,transactions,Clock.systemUTC(),new WorkLanes(),1_000_000);
        insertJob("broken-1","stub-broken-v1","UNPROCESSED");
        for(int i=0;i<50&&replayMapper.find(tenant,"broken-1").status().equals("RUNNING");i++)failing.tick();
        var failed=replayMapper.find(tenant,"broken-1");assertEquals("FAILED",failed.status());assertEquals(EventReplay.MAX_FAILURES,failed.failed());
    }
    /** R11：两个实例并发推进同一任务：每个事件恰好检查一次，消费者效果不重复，计数一致。 */
    @Test void twoInstancesAdvanceOneJobWithoutDuplicateExecution() throws Exception {
        var orders=orders(12);var executed=new ConcurrentHashMap<String,AtomicInteger>();
        var counting=stub("stub-count-v1",new ReplaySafety(Set.of(SideEffect.PURE),true,true,"测试投影"),e->executed.computeIfAbsent(e.eventId(),k->new AtomicInteger()).incrementAndGet());
        var a=new EventReplay(replayMapper,List.of(counting),commands,audit,transactions,Clock.systemUTC(),new WorkLanes(),1_000_000);
        var b=new EventReplay(replayMapper,List.of(counting),commands,audit,transactions,Clock.systemUTC(),new WorkLanes(),1_000_000);
        insertJob("multi-1","stub-count-v1","UNPROCESSED");
        var pool=Executors.newFixedThreadPool(2);
        try {
            for(int round=0;round<40&&replayMapper.find(tenant,"multi-1").status().equals("RUNNING");round++) {
                var fa=pool.submit(a::tick);var fb=pool.submit(b::tick);fa.get(30,TimeUnit.SECONDS);fb.get(30,TimeUnit.SECONDS);
            }
        } finally {pool.shutdownNow();}
        var job=replayMapper.find(tenant,"multi-1");
        assertEquals("COMPLETED",job.status());assertEquals(orders.size(),job.examined());assertEquals(orders.size(),job.executed());
        assertEquals(orders.size(),executed.size());for(var c:executed.values())assertEquals(1,c.get(),"每个事件只执行一次");
    }

    // ---------------- helpers ----------------
    private List<String> orders(int n) throws Exception {
        http.ok("POST","/v1/admin/members",admin,"member",Map.of("memberId","m1","actorId","buyer","displayName","重放会员","memberLevel","VIP"));
        http.ok("POST","/v1/admin/merchants",admin,"merchant",Map.of("merchantId","merchant1","name","重放商家"));
        http.ok("POST","/v1/admin/stores",admin,"store",Map.of("storeId","store1","merchantId","merchant1","name","重放店铺"));
        http.ok("POST","/v1/admin/skus",admin,"sku",Map.of("skuId","sku1","storeId","store1","title","重放商品","unitPrice","25.00"));
        http.ok("POST","/v1/admin/inventory/receipts",admin,"stock",Map.of("storeId","store1","skuId","sku1","quantity",n+5));
        var ids=new ArrayList<String>();
        for(int i=0;i<n;i++){var q=http.ok("POST","/v1/quotes",member,"q"+i,Map.of("storeId","store1","items",List.of(Map.of("skuId","sku1","quantity",1))));
            ids.add(http.ok("POST","/v1/orders",member,"o"+i,Map.of("quoteId",q.path("quoteId").asString(),"address",Map.of("recipient","重放","phone","13800000000","detail","重放测试地址123")))
                .path("orderId").asString());}
        for(int i=0;i<20;i++)if(http.ok("POST","/v1/admin/events/pump",admin,null,null).asInt()==0)break;
        for(var o:ids)assertEquals("DELIVERED",jdbc.queryForObject("SELECT status FROM platform_event WHERE event_id=?",String.class,createdEvent(o)));
        return ids;
    }
    private String createdEvent(String order){return jdbc.queryForObject("SELECT event_id FROM platform_event WHERE tenant_id=? AND event_type=? AND aggregate_id=?",String.class,tenant,CREATED,order);}
    private long otherInbox(){return jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox i JOIN platform_event e ON e.event_id=i.event_id WHERE e.tenant_id=? AND i.consumer_id<>?",Long.class,tenant,EFFECTS);}
    private List<Map<String,Object>> projection(){return jdbc.queryForList("SELECT order_id,paid,paid_amount,refunded,discount_amount FROM marketing_effect_order WHERE tenant_id=? ORDER BY order_id",tenant);}
    private Map<String,Object> scope(String consumer,List<String> types,String mode){return Map.of("consumer",consumer,"eventTypes",types,"from",started.toString(),"to",Instant.now().toString(),"mode",mode);}
    private Map<String,Object> create(String id,String consumer,List<String> types,String mode){return create(id,consumer,types,mode,started,Instant.now(),null);}
    private Map<String,Object> create(String id,String consumer,List<String> types,String mode,Instant from,Instant to,Integer max) {
        var m=new HashMap<String,Object>(Map.of("jobId",id,"consumer",consumer,"eventTypes",types,"from",from.toString(),"to",to.toString(),"mode",mode,"reason","补齐营销效果投影"));
        if(max!=null)m.put("maxEvents",max);return m;
    }
    private ReplayMapper.Job runUntilFinished(String id){for(int i=0;i<100&&replayMapper.find(tenant,id).status().equals("RUNNING");i++)replay.tick();return replayMapper.find(tenant,id);}
    private void insertJob(String id,String consumer,String mode) {
        jdbc.update("INSERT INTO platform_replay(tenant_id,job_id,consumer_id,event_types,mode,from_at,to_at,max_events,status,reason,created_by) VALUES(?,?,?,?,?,?,?,1000,'RUNNING','测试任务','tester')",
            tenant,id,consumer,CREATED,mode,java.sql.Timestamp.from(started),java.sql.Timestamp.from(Instant.now()));
    }
    private static EventHandler stub(String consumer,ReplaySafety safety,java.util.function.Consumer<EventHandler.Event> effect) {
        return new EventHandler() {
            public String consumer(){return consumer;}
            public Set<String> types(){return Set.of(CREATED);}
            public void handle(Event event){effect.accept(event);}
            @Override public ReplaySafety replaySafety(){return safety;}
        };
    }
}
