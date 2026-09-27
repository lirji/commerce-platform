package com.lrj.commerce.app.runtime;

import com.lrj.commerce.runtime.EventDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 车道隔离：一个坏车道或慢车道不能饿死其他后台车道。 */
class EventWorkerLaneTest {

	private final EventDispatcher events = mock(EventDispatcher.class);

	private final com.lrj.commerce.payment.api.PaymentApi payments = mock(
			com.lrj.commerce.payment.api.PaymentApi.class);

	private final com.lrj.commerce.payment.api.RefundApi refunds = mock(com.lrj.commerce.payment.api.RefundApi.class);

	private final com.lrj.commerce.journey.api.JourneyApi journeys = mock(
			com.lrj.commerce.journey.api.JourneyApi.class);

	private final com.lrj.commerce.campaign.api.SegmentApi segments = mock(
			com.lrj.commerce.campaign.api.SegmentApi.class);

	private final com.lrj.commerce.member.api.MemberCycleApi cycles = mock(
			com.lrj.commerce.member.api.MemberCycleApi.class);

	private final com.lrj.commerce.member.api.MemberPointsApi points = mock(
			com.lrj.commerce.member.api.MemberPointsApi.class);

	private final com.lrj.commerce.journey.api.CouponDeliveryApi deliveries = mock(
			com.lrj.commerce.journey.api.CouponDeliveryApi.class);

	private final com.lrj.commerce.catalog.api.CatalogJobApi jobs = mock(
			com.lrj.commerce.catalog.api.CatalogJobApi.class);

	private final com.lrj.commerce.ordering.api.OrderApi orders = mock(com.lrj.commerce.ordering.api.OrderApi.class);

	private EventWorker worker(LaneMonitor monitor) {
		return new EventWorker(events, payments, refunds, journeys, segments, cycles, points, deliveries, jobs, orders,
				monitor, mock(BackgroundRuntime.class));
	}

	@Test void failingLaneDoesNotSkipOtherLanes() {
        when(payments.tick()).thenThrow(new IllegalStateException("渠道异常"));
        doThrow(new ArithmeticException("坏数据")).when(cycles).tick();
        var monitor=new LaneMonitor();var worker=worker(monitor);
        for(var lane:worker.lanes()){monitor.register(lane.name(),1000);monitor.run(lane.name(),lane.tick());}
        verify(refunds).tick();verify(orders).tick();verify(events).tick();verify(segments).tick();verify(journeys).tick();
        verify(points).tick();verify(deliveries).tick();verify(jobs).tick();
        var failed=monitor.schedules().stream().filter(s->s.failures()>0).map(LaneMonitor.LaneSchedule::lane).sorted().toList();
        assertEquals(List.of("cycles","payments"),failed);
    }

	/**
     * 跨车道公平：事件车道每次运行2.5秒（积压或慢依赖），生产拓扑（3线程）下其余车道仍约每秒运行一次；
     * 单线程对照组复现第二阶段的问题，其余车道被推迟到每轮2.5秒以上。
     */
    @Test void slowLaneDoesNotStarveOtherLanes() throws Exception {
        when(events.tick()).thenAnswer(i->{Thread.sleep(2500);return 0;});
        var threeThreads=schedule(3);
        var singleThread=schedule(1);
        for(var lane:List.of("payments","orders","refunds","points")) {
            var fair=threeThreads.get(lane);var starved=singleThread.get(lane);
            assertTrue(fair.runs()>=5,lane+"在3线程下应约每秒运行："+fair);
            assertTrue(fair.maxStartLagMillis()<700,lane+"在3线程下开始延迟应小于0.7秒："+fair);
            assertTrue(starved.maxStartLagMillis()>=2000,"单线程对照组应复现被慢车道推迟："+starved);
        }
    }

	private Map<String, LaneMonitor.LaneSchedule> schedule(int threads) throws Exception {
		var monitor = new LaneMonitor();
		var scheduler = EventWorker.scheduler(threads);
		scheduler.initialize();
		var registrar = new ScheduledTaskRegistrar();
		registrar.setTaskScheduler(scheduler);
		try {
			worker(monitor).configureTasks(registrar);
			registrar.afterPropertiesSet();
			Thread.sleep(7000);
		}
		finally {
			registrar.destroy();
			scheduler.shutdown();
		}
		var result = new HashMap<String, LaneMonitor.LaneSchedule>();
		for (var s : monitor.schedules())
			result.put(s.lane(), s);
		return result;
	}

}
