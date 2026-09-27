package com.lrj.commerce.app;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.runtime.persistence.EventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/**
 * 平台运维视图与车道告警契约：事件运行时、各车道轮转/积压/调度延迟以及当前告警代码，全部是跨租户聚合，不含租户标识。
 * 只有具备EVENT_RUNTIME_METRICS_READ能力的平台运维可读；告警代码每分钟最多评估并记录一次。
 */
@Component
public class BackgroundRuntime {
    /** 车道等待开始超过30秒（正常约1秒）即判定饥饿。 */
    static final long STARVATION_MILLIS=30_000;
    /** 车道最老到期工作超过5分钟未处理。 */
    static final long BACKLOG_AGE_SECONDS=300;
    /** 车道一整圈租户轮转超过5分钟，即单租户最坏等待超过5分钟。 */
    static final long ROTATION_SLOW_MILLIS=300_000;
    /** 保留期清理最老可清理数据超出保留期一天，说明清理跟不上增长或长期未运行。 */
    static final long RETENTION_LAG_SECONDS=86_400;
    /** 保留期清理连续失败轮数。 */
    static final int RETENTION_FAILURES=3;
    public record EventView(EventMapper.Health health,EventDispatcher.Stats stats,long skipped) { }
    public record LaneView(TenantRotation.Stats rotation,WorkLanes.Backlog backlog,LaneMonitor.LaneSchedule schedule) { }
    public record RetentionView(RetentionLane.Stats stats,List<RetentionLane.ClassLag> lag) { }
    public record View(Instant observedAt,EventView events,Map<String,LaneView> lanes,EventReplay.Stats replay,RetentionView retention,List<String> alerts) { }
    private static final Logger log=LoggerFactory.getLogger(BackgroundRuntime.class);
    private final EventDispatcher events;private final WorkLanes work;private final LaneMonitor monitor;private final Outbox outbox;private final EventReplay replay;private final RetentionLane retention;private final OperationalAlertPublisher alerts;
    private volatile View previous;
    public BackgroundRuntime(EventDispatcher events,WorkLanes work,LaneMonitor monitor,Outbox outbox,EventReplay replay,RetentionLane retention,OperationalAlertPublisher alerts){this.events=events;this.work=work;this.monitor=monitor;this.outbox=outbox;this.replay=replay;this.retention=retention;this.alerts=alerts;}

    /** 跨租户聚合只对平台运维开放，租户管理员不隐含此能力。 */
    public View view(Actor actor){actor.require(Actor.Capability.EVENT_RUNTIME_METRICS_READ);return snapshot(Instant.now());}
    View snapshot(Instant now) {
        var schedules=new HashMap<String,LaneMonitor.LaneSchedule>();for(var s:monitor.schedules())schedules.put(s.lane(),s);
        var lanes=new TreeMap<String,LaneView>();
        for(var e:work.snapshot().entrySet())lanes.put(e.getKey(),new LaneView(e.getValue().rotation(),e.getValue().backlog(),schedules.get(e.getKey())));
        lanes.put("events",new LaneView(events.rotationStats(),null,schedules.get("events")));
        var eventView=new EventView(events.health((String)null),events.stats(),outbox.skipped());
        RetentionView retentionView;
        try{retentionView=new RetentionView(retention.stats(),retention.lag());}
        catch(RuntimeException unavailable){log.warn("retention lag unavailable errorType={}",unavailable.getClass().getSimpleName());retentionView=new RetentionView(retention.stats(),List.of());}
        var view=new View(now,eventView,lanes,replay.stats(),retentionView,List.of());
        return new View(now,eventView,lanes,view.replay(),retentionView,evaluate(previous,view));
    }
    /** 每分钟由调度线程调用：有告警时经告警出口发布固定代码，不含租户与载荷；评估或发布失败不影响任何车道。 */
    void logHealth() {
        View current;
        try{current=snapshot(Instant.now());previous=current;}
        catch(RuntimeException failure){log.warn("background runtime health unavailable errorType={}",failure.getClass().getSimpleName());return;}
        if(current.alerts().isEmpty())return;
        try{alerts.publish(current.observedAt(),current.alerts());}
        catch(RuntimeException failure){log.warn("background runtime alert publish failed errorType={} codes={}",failure.getClass().getSimpleName(),current.alerts());}
    }
    /** 纯函数规则，便于测试告警契约；代码格式为CODE:车道，车道名是固定集合。 */
    public static List<String> evaluate(View previous,View current) {
        var alerts=new ArrayList<String>();
        for(var e:current.lanes().entrySet()) {
            String lane=e.getKey();var v=e.getValue();var before=previous==null?null:previous.lanes().get(lane);
            var s=v.schedule();
            if(s!=null&&s.lastFinishedAt()!=null&&(s.lastStartedAt()==null||!s.lastStartedAt().isAfter(s.lastFinishedAt()))&&Duration.between(s.lastFinishedAt(),current.observedAt()).toMillis()>STARVATION_MILLIS
                ||s!=null&&s.lastStartLagMillis()>STARVATION_MILLIS)alerts.add("LANE_STARVATION:"+lane);
            if(v.rotation().breakerOpen()||before!=null&&v.rotation().breakerTrips()>before.rotation().breakerTrips())alerts.add("LANE_DEPENDENCY_UNAVAILABLE:"+lane);
            if(v.rotation().lastRotationMillis()>ROTATION_SLOW_MILLIS)alerts.add("LANE_ROTATION_SLOW:"+lane);
            if(v.backlog()!=null) {
                if(v.backlog().oldestDueAgeSeconds()!=null&&v.backlog().oldestDueAgeSeconds()>BACKLOG_AGE_SECONDS)alerts.add("LANE_BACKLOG_AGE:"+lane);
                if(before!=null&&before.backlog()!=null&&v.backlog().quarantined()>before.backlog().quarantined())alerts.add("LANE_QUARANTINE_GROWTH:"+lane);
            }
        }
        if(current.retention()!=null) {
            var r=current.retention();
            if(r.stats().enabled()&&r.lag().stream().anyMatch(l->l.lagSeconds()!=null&&l.lagSeconds()>RETENTION_LAG_SECONDS))alerts.add("RETENTION_LAG_HIGH");
            if(r.stats().consecutiveFailures()>=RETENTION_FAILURES)alerts.add("RETENTION_FAILURE");
        }
        // 安全门拒绝过重放（创建或执行时）：数据或部署与重放声明不一致，需要人工确认，不自动重试。
        if(current.replay()!=null&&previous!=null&&previous.replay()!=null&&current.replay().blocked()>previous.replay().blocked())alerts.add("REPLAY_BLOCKED");
        return alerts;
    }
}
