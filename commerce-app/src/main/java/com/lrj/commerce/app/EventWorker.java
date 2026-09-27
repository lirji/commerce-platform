package com.lrj.commerce.app;
import com.lrj.commerce.runtime.EventDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import java.time.Duration;
import java.util.List;
/**
 * 后台车道拓扑：每个车道是独立的固定延迟任务，共享有界调度线程池（默认3线程）。调度器按下次触发时间先后取任务，
 * 一个车道慢只占住一个线程，其余车道由其他线程按到期先后执行；每个车道单次运行受自身预算约束，
 * 因此任一车道等待开始的时间不超过约⌈(车道数-1)/线程数⌉个车道运行时长。同一车道上一次结束后才排下一次，不会重入；
 * 多实例竞争由行锁处理。每个后台线程同一时刻最多占用一个数据库连接，3线程在8连接池中给请求线程保留5个。
 */
@Configuration @EnableScheduling
@ConditionalOnProperty(name="commerce.workers-enabled",havingValue="true")
public class EventWorker implements SchedulingConfigurer {
    /** 每个车道运行结束到下次开始的固定延迟。 */
    static final Duration DELAY=Duration.ofSeconds(1);
    /** 保留期清理每5秒一轮：删除不追求实时，拉长间隔减少与实时车道争用线程与连接。 */
    static final Duration RETENTION_DELAY=Duration.ofSeconds(5);
    /** 车道名、一次有界运行与固定延迟。 */
    record Lane(String name,Runnable tick,Duration delay) { Lane(String name,Runnable tick){this(name,tick,DELAY);} }
    /** 车道告警每分钟评估一次。 */
    static final Duration HEALTH_INTERVAL=Duration.ofMinutes(1);
    private final List<Lane> lanes;private final LaneMonitor monitor;private final BackgroundRuntime runtime;
    /** 第三阶段的十条业务车道（测试直接构造时只含这些）。 */
    public EventWorker(EventDispatcher events,com.lrj.commerce.payment.api.PaymentApi payments,com.lrj.commerce.payment.api.RefundApi refunds,com.lrj.commerce.journey.api.JourneyApi journeys,com.lrj.commerce.campaign.api.SegmentApi segments,com.lrj.commerce.member.api.MemberCycleApi cycles,com.lrj.commerce.member.api.MemberPointsApi points,com.lrj.commerce.journey.api.CouponDeliveryApi deliveries,com.lrj.commerce.catalog.api.CatalogJobApi catalogJobs,com.lrj.commerce.ordering.api.OrderApi orders,LaneMonitor monitor,BackgroundRuntime runtime){
        this(events,payments,refunds,journeys,segments,cycles,points,deliveries,catalogJobs,orders,monitor,runtime,null,null);
    }
    /** 另加历史重放车道（小预算、实时积压时让路）与保留期清理车道（默认关闭，5秒一轮）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public EventWorker(EventDispatcher events,com.lrj.commerce.payment.api.PaymentApi payments,com.lrj.commerce.payment.api.RefundApi refunds,com.lrj.commerce.journey.api.JourneyApi journeys,com.lrj.commerce.campaign.api.SegmentApi segments,com.lrj.commerce.member.api.MemberCycleApi cycles,com.lrj.commerce.member.api.MemberPointsApi points,com.lrj.commerce.journey.api.CouponDeliveryApi deliveries,com.lrj.commerce.catalog.api.CatalogJobApi catalogJobs,com.lrj.commerce.ordering.api.OrderApi orders,LaneMonitor monitor,BackgroundRuntime runtime,com.lrj.commerce.runtime.EventReplay replay,com.lrj.commerce.runtime.RetentionLane retention){
        this.monitor=monitor;this.runtime=runtime;
        var all=new java.util.ArrayList<>(List.of(new Lane("payments",payments::tick),new Lane("refunds",refunds::tick),new Lane("orders",orders::tick),new Lane("events",events::tick),new Lane("segments",segments::tick),
            new Lane("journeys",journeys::tick),new Lane("cycles",cycles::tick),new Lane("points",points::tick),new Lane("deliveries",deliveries::tick),new Lane("catalog-jobs",catalogJobs::tick)));
        if(replay!=null)all.add(new Lane("replay",replay::tick));
        if(retention!=null)all.add(new Lane("retention",retention::tick,RETENTION_DELAY));
        lanes=List.copyOf(all);
    }
    /** 有界调度线程；关闭时等待正在运行的车道结束（单次运行受预算约束，最长约一个事务超时）。 */
    @Bean static ThreadPoolTaskScheduler taskScheduler(@Value("${commerce.worker-threads:3}") int threads){return scheduler(threads);}
    static ThreadPoolTaskScheduler scheduler(int threads) {
        if(threads<1||threads>6)throw new IllegalArgumentException("后台线程数须在1到6之间，避免占满8连接的数据库池");
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(threads);scheduler.setThreadNamePrefix("commerce-lane-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(15);scheduler.setRemoveOnCancelPolicy(true);return scheduler;
    }
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        for(var lane:lanes){monitor.register(lane.name(),lane.delay().toMillis());registrar.addFixedDelayTask(()->monitor.run(lane.name(),lane.tick()),lane.delay());}
        registrar.addFixedDelayTask(new org.springframework.scheduling.config.FixedDelayTask(runtime::logHealth,HEALTH_INTERVAL,HEALTH_INTERVAL));
    }
    List<Lane> lanes(){return lanes;}
}
