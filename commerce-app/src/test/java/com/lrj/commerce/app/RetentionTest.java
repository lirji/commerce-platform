package com.lrj.commerce.app;

import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.EventHandler;
import com.lrj.commerce.runtime.persistence.RetentionMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.6 保留期：默认关闭；开启时每类保留时长必须显式配置且不低于下限。只删除可证明安全的数据（终态事件连同其Inbox、已完成的命令），
 * 永不删除PENDING/ISOLATED事件及其Inbox、审计、恢复审计，也不删除运行中重放区间内的事件；有界批量、SKIP LOCKED、实时积压时让路。
 * 用固定在1991年的时钟与1990年的夹具，保证不会触及共享测试库中其他测试的数据。
 */
@SpringBootTest
class RetentionTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {Phase4Properties.register(registry);}
    static final Instant NOW=Instant.parse("1991-01-01T00:00:00Z");
    static final Timestamp OLD=Timestamp.from(Instant.parse("1990-06-01T00:00:00Z"));
    @Autowired JdbcTemplate jdbc;@Autowired RetentionMapper mapper;@Autowired PlatformTransactionManager transactions;@Autowired List<EventHandler> handlers;@Autowired RetentionLane configured;
    private String tenant;private final List<String> types=new ArrayList<>();
    @BeforeEach void setup(){tenant="rt-"+UUID.randomUUID();handlers.forEach(h->types.addAll(h.types()));}
    @AfterEach void cleanup() {
        jdbc.update("DELETE i FROM platform_inbox i JOIN platform_event e ON e.event_id=i.event_id WHERE e.tenant_id=?",tenant);
        jdbc.update("DELETE FROM platform_event WHERE tenant_id=?",tenant);jdbc.update("DELETE FROM platform_command WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM platform_audit WHERE tenant_id=?",tenant);jdbc.update("DELETE FROM platform_replay WHERE tenant_id=?",tenant);
    }
    private RetentionLane lane(RetentionLane.Policy policy,int liveYield){return new RetentionLane(mapper,transactions,Clock.fixed(NOW,ZoneOffset.UTC),policy,liveYield,types);}
    private static RetentionLane.Policy standard(){return new RetentionLane.Policy(true,Duration.ofDays(7),Duration.ofDays(7),Duration.ofDays(30));}

    @Test void retentionIsDisabledByDefaultAndRefusesDangerousConfiguration() {
        assertFalse(configured.policy().enabled(),"没有产品/法务决定前默认关闭");
        assertEquals(0,configured.tick());
        assertThrows(IllegalArgumentException.class,()->new RetentionLane.Policy(true,Duration.ofDays(1),null,null),"事件保留低于7天");
        assertThrows(IllegalArgumentException.class,()->new RetentionLane.Policy(true,null,Duration.ZERO,null),"零保留");
        assertThrows(IllegalArgumentException.class,()->new RetentionLane.Policy(true,null,null,Duration.ofDays(7)),"命令保留低于30天");
        assertThrows(IllegalArgumentException.class,()->new RetentionLane.Policy(true,null,null,null),"开启但未配置任何类别");
        assertEquals(Duration.ofDays(30),RetentionLane.duration("30d"));assertEquals(Duration.ofDays(90),RetentionLane.duration("P90D"));assertNull(RetentionLane.duration(" "));
        seedEvent("d-disabled","DELIVERED",OLD,List.of("c1"));
        assertEquals(0,lane(new RetentionLane.Policy(false,null,null,null),1_000_000).tick());assertEquals(1,count("d-disabled"));
    }
    /** R12：只删除终态事件及其Inbox（同一事务）、已完成的旧命令；未完成工作、去重边界、审计与重放依赖全部保留。 */
    @Test void onlyProvablySafeDataIsPurged() {
        seedEvent("d1","DELIVERED",OLD,List.of("c1","c2"));seedEvent("d2","DELIVERED",OLD,List.of("c1"));seedEvent("s1","SKIPPED",OLD,List.of());
        seedEvent("p1","PENDING",OLD,List.of("c1"));seedEvent("i1","ISOLATED",OLD,List.of("c1"));
        seedEvent("recent","DELIVERED",Timestamp.from(NOW.minus(Duration.ofDays(3))),List.of("c1"));
        command("done-old",OLD,"{}");command("inflight-old",OLD,null);command("done-recent",Timestamp.from(NOW.minus(Duration.ofDays(3))),"{}");
        jdbc.update("INSERT INTO platform_audit(tenant_id,actor_id,operation,command_key,created_at) VALUES(?,'a','op','done-old',?)",tenant,OLD);
        var lane=lane(standard(),1_000_000);int purged=lane.tick();
        assertEquals(0,count("d1")+count("d2")+count("s1"),"旧终态事件删除");assertEquals(0,inbox("d1")+inbox("d2"),"Inbox随事件删除");
        assertEquals(1,count("p1"));assertEquals(1,inbox("p1"),"未完成事件及其Inbox保留（部分成功的去重边界）");
        assertEquals(1,count("i1"));assertEquals(1,inbox("i1"),"隔离事件及其Inbox保留");
        assertEquals(1,count("recent"),"保留期内的事件保留");
        assertEquals(List.of("done-recent","inflight-old"),jdbc.queryForList("SELECT command_key FROM platform_command WHERE tenant_id=? ORDER BY command_key",String.class,tenant),"只删除已完成的旧命令");
        assertEquals(1,(int)jdbc.queryForObject("SELECT COUNT(*) FROM platform_audit WHERE tenant_id=?",Integer.class,tenant),"审计不删除");
        var stats=lane.stats();assertEquals(2,stats.deliveredPurged());assertEquals(1,stats.skippedPurged());assertEquals(3,stats.inboxPurged());assertEquals(1,stats.commandsPurged());
        assertEquals(4,purged);assertEquals(0,lane.tick(),"再次运行没有可删数据（删除天然幂等）");
    }
    /** 运行中或暂停的重放任务区间内的事件不删除，截止时间退到区间起点。 */
    @Test void activeReplayRangesAreRetained() {
        seedEvent("before-replay","DELIVERED",OLD,List.of("c1"));
        var inRange=Timestamp.from(Instant.parse("1990-11-01T00:00:00Z"));seedEvent("in-replay","DELIVERED",inRange,List.of("c1"));
        jdbc.update("INSERT INTO platform_replay(tenant_id,job_id,consumer_id,event_types,mode,from_at,to_at,max_events,status,reason,created_by) VALUES(?,'r1','marketing-effects-v1','order.created.v1','UNPROCESSED',?,?,10,'PAUSED','保留测试','t')",
            tenant,Timestamp.from(Instant.parse("1990-10-15T00:00:00Z")),Timestamp.from(Instant.parse("1990-12-01T00:00:00Z")));
        lane(standard(),1_000_000).tick();
        assertEquals(0,count("before-replay"));assertEquals(1,count("in-replay"),"重放可能需要的事件不删除");
        jdbc.update("UPDATE platform_replay SET status='COMPLETED' WHERE tenant_id=?",tenant);
        lane(standard(),1_000_000).tick();assertEquals(0,count("in-replay"),"任务结束后按保留期删除");
    }
    /** 被其他事务锁定（投递中、重放共享锁）的事件跳过，不等待；锁释放后下一轮删除。 */
    @Test void lockedRowsAreSkippedNotWaitedOn() throws Exception {
        seedEvent("held","DELIVERED",OLD,List.of("c1"));seedEvent("free","DELIVERED",OLD,List.of("c1"));
        var lane=lane(standard(),1_000_000);
        try(var holder=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            holder.setAutoCommit(false);
            try(var ps=holder.prepareStatement("SELECT event_id FROM platform_event WHERE event_id=? FOR SHARE")){ps.setString(1,id("held"));ps.executeQuery();}
            long started=System.nanoTime();lane.tick();
            assertTrue((System.nanoTime()-started)/1_000_000<5_000,"不等待锁");
            assertEquals(1,count("held"));assertEquals(0,count("free"));
            holder.rollback();
        }
        lane.tick();assertEquals(0,count("held"));
    }
    /** R14：每轮有行数上限（MAX_ROWS）与时间预算，分批短事务；剩余在后续轮次完成。 */
    @Test void cleanupIsBoundedPerRun() {
        var rows=new ArrayList<Object[]>();
        for(int i=0;i<RetentionLane.MAX_ROWS+600;i++){String id=tenant+"-bulk-"+i;rows.add(new Object[]{id,tenant,"order.created.v1",id,OLD,OLD});}
        jdbc.batchUpdate("INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,created_at,available_at) VALUES(?,?,?,?,1,'{}','DELIVERED',?,?)",rows);
        var lane=lane(new RetentionLane.Policy(true,Duration.ofDays(7),null,null),1_000_000);
        int first=lane.tick();
        assertTrue(first<=RetentionLane.MAX_ROWS&&first>0,"单轮删除 "+first);
        for(int i=0;i<10&&tenantEvents()>0;i++)lane.tick();
        assertEquals(0,tenantEvents());
    }
    /** 有消费者的实时到期事件达到阈值时整轮让路。 */
    @Test void cleanupYieldsToLiveWork() {
        seedEvent("old","DELIVERED",OLD,List.of("c1"));seedEvent("live","PENDING",OLD,List.of());
        var lane=lane(standard(),1);assertEquals(0,lane.tick());assertEquals(1,lane.stats().yielded());assertEquals(1,count("old"));
    }
    /** 清理失败只结束本轮并计数；连续3轮失败与滞后超过一天分别产生固定告警代码。 */
    @Test void failuresAndLagProduceFixedAlertCodes() {
        var broken=org.mockito.Mockito.mock(RetentionMapper.class);
        org.mockito.Mockito.when(broken.liveDue(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("数据库不可用"));
        var lane=new RetentionLane(broken,transactions,Clock.fixed(NOW,ZoneOffset.UTC),standard(),1_000_000,types);
        for(int i=0;i<3;i++)assertEquals(0,lane.tick());
        assertEquals(3,lane.stats().consecutiveFailures());assertEquals("DEPENDENCY_UNAVAILABLE",lane.stats().lastFailureClass());
        var failing=new BackgroundRuntime.RetentionView(lane.stats(),List.of());
        assertTrue(BackgroundRuntime.evaluate(null,view(failing)).contains("RETENTION_FAILURE"));
        var lagging=new BackgroundRuntime.RetentionView(new RetentionLane.Stats(true,1,0,0,0,0,0,0,0,NOW,1,null),List.of(new RetentionLane.ClassLag("DELIVERED_EVENTS",Duration.ofDays(7),8*86400L+86401,86401L)));
        assertEquals(List.of("RETENTION_LAG_HIGH"),BackgroundRuntime.evaluate(null,view(lagging)));
        var healthy=new BackgroundRuntime.RetentionView(new RetentionLane.Stats(true,1,0,0,0,0,0,0,0,NOW,1,null),List.of(new RetentionLane.ClassLag("DELIVERED_EVENTS",Duration.ofDays(7),7*86400L+60,60L)));
        assertEquals(List.of(),BackgroundRuntime.evaluate(null,view(healthy)));
    }

    // ---------------- helpers ----------------
    private static BackgroundRuntime.View view(BackgroundRuntime.RetentionView retention){return new BackgroundRuntime.View(NOW,null,Map.of(),new EventReplay.Stats(0,0,0,0,0),retention,List.of());}
    private String id(String name){return tenant+"-"+name;}
    private void seedEvent(String name,String status,Timestamp at,List<String> consumers) {
        jdbc.update("INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,created_at,available_at) VALUES(?,?,'order.created.v1',?,1,'{}',?,?,?)",id(name),tenant,id(name),status,at,at);
        for(var c:consumers)jdbc.update("INSERT INTO platform_inbox(consumer_id,event_id,tenant_id,processed_at) VALUES(?,?,?,?)",c+"-rt",id(name),tenant,at);
    }
    private void command(String key,Timestamp at,String response) {
        jdbc.update("INSERT INTO platform_command(tenant_id,actor_id,operation,command_key,request_hash,response_json,created_at) VALUES(?,'a','op',?,?,?,?)",tenant,key,"0".repeat(64),response,at);
    }
    private int count(String name){return jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE event_id=?",Integer.class,id(name));}
    private int inbox(String name){return jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox WHERE event_id=?",Integer.class,id(name));}
    private int tenantEvents(){return jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE tenant_id=?",Integer.class,tenant);}
}
