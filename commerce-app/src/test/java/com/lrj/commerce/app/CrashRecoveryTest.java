package com.lrj.commerce.app;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.runtime.api.EventHandler.ReplaySafety;
import com.lrj.commerce.runtime.persistence.EventMapper;
import com.lrj.commerce.runtime.persistence.ReplayMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.7 崩溃与重启：在第n次开启事务或第n次提交时模拟进程终止（抛出Error，业务代码的catch(RuntimeException)不会拦截，
 * 未提交的工作全部回滚，也不会记录任何失败）。“重启”用全新的调度器/重放实例：没有任何进程内游标、熔断或统计。
 * 断言对象是真实副作用行（每个消费者对每个事件的效果行数），不只是调度状态。
 */
@SpringBootTest
class CrashRecoveryTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {Phase4Properties.register(registry);}
    static final EventDispatcher.Budget BUDGET=new EventDispatcher.Budget(5,50,Duration.ofSeconds(60),200,Duration.ofSeconds(10));
    @Autowired JdbcTemplate jdbc;@Autowired EventMapper mapper;@Autowired PlatformTransactionManager transactions;@Autowired Commands commands;
    @Autowired ReplayMapper replayMapper;@Autowired RecoveryAudit audit;
    private String run,type,prefix;private final Map<String,RuntimeException> failing=new ConcurrentHashMap<>();
    @BeforeEach void run(){run=UUID.randomUUID().toString().substring(0,8);type="crash."+run+".v1";prefix="crash-"+run+"-";}
    @AfterEach void clean() {
        jdbc.update("DELETE FROM platform_inbox WHERE consumer_id LIKE ?",prefix+"%");jdbc.update("DELETE FROM platform_audit WHERE actor_id=?",prefix);
        jdbc.update("DELETE FROM platform_event WHERE event_type=?",type);jdbc.update("DELETE FROM platform_replay WHERE tenant_id LIKE ?",prefix+"%");
    }

    /** 模拟进程终止。 */
    static final class Crash extends Error { Crash(String where){super(where);} }
    /** 包装真实事务管理器：在第beginAt次开启事务时终止，或在第commitAt次提交前回滚并终止。 */
    static final class CrashingTransactions implements PlatformTransactionManager {
        final PlatformTransactionManager delegate;final int beginAt,commitAt;final AtomicInteger begins=new AtomicInteger(),commitsSeen=new AtomicInteger();
        CrashingTransactions(PlatformTransactionManager delegate,int beginAt,int commitAt){this.delegate=delegate;this.beginAt=beginAt;this.commitAt=commitAt;}
        public TransactionStatus getTransaction(TransactionDefinition definition){if(begins.incrementAndGet()==beginAt)throw new Crash("begin#"+beginAt);return delegate.getTransaction(definition);}
        public void commit(TransactionStatus status){if(commitsSeen.incrementAndGet()==commitAt){delegate.rollback(status);throw new Crash("commit#"+commitAt);}delegate.commit(status);}
        public void rollback(TransactionStatus status){delegate.rollback(status);}
    }

    /** C1：开启事务前终止：没有任何状态变化；重启后全部投递，每个效果恰好一次。 */
    @Test void c1CrashBeforeTheWorkTransactionStarts() {
        var ids=List.of(insert(prefix+"a"),insert(prefix+"a"),insert(prefix+"b"));
        assertThrows(Crash.class,()->dispatcher(new CrashingTransactions(transactions,1,-1),handler("c0")).tick());
        for(var id:ids){assertEquals("PENDING",status(id));assertEquals(0,attempts(id));assertEquals(0,effects("c0",id));}
        drain(dispatcher(transactions,handler("c0")));
        for(var id:ids){assertEquals("DELIVERED",status(id));assertEquals(1,effects("c0",id));}
    }
    /** C2：数据库工作进行中终止（效果已写、未提交）：效果与Inbox一起回滚，事件未计失败；重启后恰好一次。 */
    @Test void c2CrashDuringDatabaseWork() {
        String id=insert(prefix+"a");
        assertThrows(Crash.class,()->dispatcher(new CrashingTransactions(transactions,-1,1),handler("c0")).tick());
        assertEquals("PENDING",status(id));assertEquals(0,attempts(id));assertEquals(0,effects("c0",id));assertEquals(0,inbox("c0",id));
        drain(dispatcher(transactions,handler("c0")));
        assertEquals("DELIVERED",status(id));assertEquals(1,effects("c0",id));
    }
    /** C3：业务效果已执行、进度（重放游标）未提交时终止：二者同一事务回滚；重启后从已提交游标继续，每个事件的效果恰好一次。 */
    @Test void c3CrashAfterBusinessChangeBeforeProgressUpdate() {
        String tenant=prefix+"r";var ids=List.of(insert(tenant),insert(tenant),insert(tenant));
        jdbc.update("UPDATE platform_event SET status='DELIVERED' WHERE event_type=?",type);
        jdbc.update("INSERT INTO platform_replay(tenant_id,job_id,consumer_id,event_types,mode,from_at,to_at,max_events,status,reason,created_by) VALUES(?,'j1',?,?,'UNPROCESSED',?,?,100,'RUNNING','崩溃测试','t')",
            tenant,prefix+"proj",type,Timestamp.from(Instant.now().minusSeconds(600)),Timestamp.from(Instant.now().plusSeconds(1)));
        var projection=replayHandler("proj");
        // 第2次提交（第二个事件的效果与游标推进）前终止。
        var crashing=new EventReplay(replayMapper,List.of(projection),commands,audit,new CrashingTransactions(transactions,-1,2),Clock.systemUTC(),new WorkLanes(),1_000_000);
        assertThrows(Crash.class,crashing::tick);
        var job=replayMapper.find(tenant,"j1");assertEquals(1,job.examined());assertEquals(1,job.executed());
        assertEquals(1,ids.stream().mapToInt(id->effects("proj",id)).sum(),"只有已提交项的效果存在");
        var restarted=new EventReplay(replayMapper,List.of(projection),commands,audit,transactions,Clock.systemUTC(),new WorkLanes(),1_000_000);
        for(int i=0;i<10&&replayMapper.find(tenant,"j1").status().equals("RUNNING");i++)restarted.tick();
        job=replayMapper.find(tenant,"j1");assertEquals("COMPLETED",job.status());assertEquals(3,job.examined());assertEquals(3,job.executed());
        for(var id:ids)assertEquals(1,effects("proj",id),"每个事件恰好一次");
    }
    /** C4：一个消费者成功提交后、另一个开始前终止：重启只执行未完成的消费者（R7）。 */
    @Test void c4CrashAfterOneConsumerSucceededBeforeTheOther() {
        String id=insert(prefix+"a");
        assertThrows(Crash.class,()->dispatcher(new CrashingTransactions(transactions,2,-1),handler("c0"),handler("c1")).tick());
        assertEquals(1,effects("c0",id));assertEquals(0,effects("c1",id));assertEquals("PENDING",status(id));assertEquals(0,attempts(id));
        drain(dispatcher(transactions,handler("c0"),handler("c1")));
        assertEquals("DELIVERED",status(id));assertEquals(1,effects("c0",id),"已成功的消费者不重复执行");assertEquals(1,effects("c1",id));
    }
    /** C5：消费者失败后、失败计数写入时终止：计数不被部分更新，事件保持到期；重启后健康消费者投递。 */
    @Test void c5CrashWhileRetryStateIsBeingUpdated() {
        for(int crashAtCommit:new int[]{-1,2}) {
            String id=insert(prefix+"a");failing.put("c0",new DomainException(DomainException.Code.CONFLICT,"坏数据"));
            // 单消费者：第1个事务是消费者（失败回滚），第2个是失败记录。
            var tx=crashAtCommit<0?new CrashingTransactions(transactions,2,-1):new CrashingTransactions(transactions,-1,1);
            assertThrows(Crash.class,()->dispatcher(tx,handler("c0")).tick());
            assertEquals("PENDING",status(id));assertEquals(0,attempts(id),"失败未记录");assertNull(jdbc.queryForObject("SELECT last_error FROM platform_event WHERE event_id=?",String.class,id));
            failing.clear();drain(dispatcher(transactions,handler("c0")));
            assertEquals("DELIVERED",status(id));assertEquals(1,effects("c0",id));
        }
    }
    /** C6：积压中重启：新实例没有游标与熔断状态，按租户轮转清空全部积压，公平恢复；隔离事件保持隔离；进程内熔断随重启清除。 */
    @Test void c6RestartWithBacklogResumesFairlyWithoutProcessLocalState() {
        var all=new ArrayList<String>();for(int t=0;t<20;t++)for(int i=0;i<10;i++)all.add(insert(prefix+String.format("t%02d",t)));
        String isolated=insert(prefix+"z");jdbc.update("UPDATE platform_event SET status='ISOLATED',attempts=5,failure_class='BUSINESS_REJECTED' WHERE event_id=?",isolated);
        // 实例A：依赖故障使熔断打开后“崩溃”。
        failing.put("c0",new org.springframework.jdbc.CannotGetJdbcConnectionException("连接池耗尽"));
        var a=dispatcher(transactions,handler("c0"));a.tick();assertTrue(a.stats().breakerOpen(),"实例A熔断打开");
        failing.clear();jdbc.update("UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(3) WHERE event_type=? AND status='PENDING'",type);
        // 实例B（重启）：首轮即访问全部20个租户（每租户至多一个配额），随后清空。
        var b=dispatcher(transactions,handler("c0"));assertFalse(b.stats().breakerOpen(),"熔断是进程内优化状态，重启后不残留");
        b.tick();
        assertEquals(20,(int)jdbc.queryForObject("SELECT COUNT(DISTINCT tenant_id) FROM platform_event WHERE event_type=? AND status='DELIVERED'",Integer.class,type),"首轮访问全部租户");
        drain(b);
        for(var id:all){assertEquals("DELIVERED",status(id));assertEquals(1,effects("c0",id));}
        assertEquals("ISOLATED",status(isolated),"隔离状态在重启后保持");assertEquals(0,effects("c0",isolated));
        assertEquals(0,(int)jdbc.queryForObject("SELECT COALESCE(SUM(attempts),0) FROM platform_event WHERE event_type=? AND status='DELIVERED'",Integer.class,type),"瞬时失败未消耗毒预算");
    }

    // ---------------- helpers ----------------
    private EventDispatcher dispatcher(PlatformTransactionManager tx,EventHandler... handlers){return new EventDispatcher(mapper,List.of(handlers),tx,commands,BUDGET);}
    private void drain(EventDispatcher d){for(int i=0;i<200&&(int)jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE event_type=? AND status='PENDING'",Integer.class,type)>0;i++)d.tick();}
    /** 消费者在同一事务写一行效果（审计表：operation=消费者，command_key=事件），用于统计真实执行次数。 */
    private EventHandler handler(String name) {
        String consumer=prefix+name;
        return new EventHandler() {
            public String consumer(){return consumer;}
            public Set<String> types(){return Set.of(type);}
            public void handle(Event event){effect(event.tenantId(),consumer,event.eventId());var f=failing.get(name);if(f!=null)throw f;}
        };
    }
    private EventHandler replayHandler(String name) {
        String consumer=prefix+name;
        return new EventHandler() {
            public String consumer(){return consumer;}
            public Set<String> types(){return Set.of(type);}
            public void handle(Event event){effect(event.tenantId(),consumer,event.eventId());}
            @Override public ReplaySafety replaySafety(){return new ReplaySafety(Set.of(SideEffect.PURE),true,true,"测试投影");}
        };
    }
    private void effect(String tenant,String consumer,String event){jdbc.update("INSERT INTO platform_audit(tenant_id,actor_id,operation,command_key) VALUES(?,?,?,?)",tenant,prefix,consumer.substring(0,Math.min(64,consumer.length())),event.substring(0,Math.min(64,event.length())));}
    private int effects(String name,String event){return jdbc.queryForObject("SELECT COUNT(*) FROM platform_audit WHERE actor_id=? AND operation=? AND command_key=?",Integer.class,prefix,prefix+name,event);}
    private int inbox(String name,String event){return jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox WHERE consumer_id=? AND event_id=?",Integer.class,prefix+name,event);}
    private String insert(String tenant) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,available_at) VALUES(?,?,?,?,1,'{}','PENDING',TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)))",id,tenant,type,id);
        return id;
    }
    private String status(String id){return jdbc.queryForObject("SELECT status FROM platform_event WHERE event_id=?",String.class,id);}
    private int attempts(String id){return jdbc.queryForObject("SELECT attempts FROM platform_event WHERE event_id=?",Integer.class,id);}
}
