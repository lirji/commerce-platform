package com.lrj.commerce.app;
import com.lrj.commerce.runtime.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** 后台车道低基数指标：唯一标签是固定车道名，不含租户、订单或事件标识；积压值来自WorkLanes的5秒缓存。 */
@Component
public class BackgroundLaneMetrics implements MeterBinder {
    /** 租户轮转车道的固定集合，事件车道另有commerce.events.*指标。 */
    static final List<String> LANES=List.of("payments","refunds","orders","segments","journeys","cycles","points","deliveries","catalog-jobs");
    private final WorkLanes lanes;private final LaneMonitor monitor;
    public BackgroundLaneMetrics(WorkLanes lanes,LaneMonitor monitor){this.lanes=lanes;this.monitor=monitor;}
    public void bindTo(MeterRegistry registry) {
        for(String lane:LANES) {
            counter(registry,lane,"commerce.lanes.items","领取的工作项数",s->s.items());
            counter(registry,lane,"commerce.lanes.completed","成功完成的工作项数",s->s.completed());
            counter(registry,lane,"commerce.lanes.transient.failures","依赖不可用、锁冲突、超时等瞬时失败数",s->s.transientFailures());
            counter(registry,lane,"commerce.lanes.failures","非瞬时失败数",s->s.otherFailures());
            counter(registry,lane,"commerce.lanes.breaker.trips","依赖熔断次数",s->s.breakerTrips());
            gauge(registry,lane,"commerce.lanes.breaker.open","依赖熔断是否打开",v->v.rotation().breakerOpen()?1:0);
            gauge(registry,lane,"commerce.lanes.rotation.last","最近一整圈租户轮转秒数",v->v.rotation().lastRotationMillis()/1000.0);
            gauge(registry,lane,"commerce.lanes.backlog.due","到期未处理工作项数",v->v.backlog()==null?Double.NaN:v.backlog().due());
            gauge(registry,lane,"commerce.lanes.backlog.oldest.age","最老到期工作等待秒数",v->v.backlog()==null||v.backlog().oldestDueAgeSeconds()==null?0:v.backlog().oldestDueAgeSeconds());
            gauge(registry,lane,"commerce.lanes.backlog.quarantined","已停止自动处理的工作项数",v->v.backlog()==null?Double.NaN:v.backlog().quarantined());
        }
        for(String lane:concat()) {
            Gauge.builder("commerce.lanes.start.lag",monitor,m->schedule(m,lane,true)).tag("lane",lane).description("最近一次开始比预定晚的毫秒数").baseUnit("milliseconds").register(registry);
            Gauge.builder("commerce.lanes.run.duration",monitor,m->schedule(m,lane,false)).tag("lane",lane).description("最近一次运行耗时毫秒").baseUnit("milliseconds").register(registry);
        }
    }
    private static List<String> concat(){var all=new java.util.ArrayList<>(LANES);all.add("events");return all;}
    private static double schedule(LaneMonitor monitor,String lane,boolean lag){
        for(var s:monitor.schedules())if(s.lane().equals(lane))return lag?s.lastStartLagMillis():s.lastDurationMillis();
        return Double.NaN;
    }
    private void counter(MeterRegistry registry,String lane,String name,String description,ToDoubleFunction<TenantRotation.Stats> value) {
        FunctionCounter.builder(name,lanes,l->{var v=l.snapshot().get(lane);return v==null?0:value.applyAsDouble(v.rotation());}).tag("lane",lane).description(description).register(registry);
    }
    private void gauge(MeterRegistry registry,String lane,String name,String description,ToDoubleFunction<WorkLanes.Lane> value) {
        Gauge.builder(name,lanes,l->{var v=l.snapshot().get(lane);return v==null?Double.NaN:value.applyAsDouble(v);}).tag("lane",lane).description(description).register(registry);
    }
}
