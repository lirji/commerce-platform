package com.lrj.commerce.app;
import com.lrj.commerce.runtime.EventDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
/** 调度线程只触发有界数据库任务，多实例竞争由行锁处理。 */
@Configuration @EnableScheduling
@ConditionalOnProperty(name="commerce.workers-enabled",havingValue="true")
public class EventWorker {
    private final com.lrj.commerce.journey.api.JourneyApi journeys;private final com.lrj.commerce.payment.api.RefundApi refunds;private final EventDispatcher events;private final com.lrj.commerce.payment.api.PaymentApi payments;
    public EventWorker(EventDispatcher events,com.lrj.commerce.payment.api.PaymentApi payments,com.lrj.commerce.payment.api.RefundApi refunds,com.lrj.commerce.journey.api.JourneyApi journeys){this.journeys=journeys;this.refunds=refunds;this.events=events;this.payments=payments;}
    /** 固定延迟避免单实例重入，不创建无界线程或任务队列。 */
    @Scheduled(fixedDelay=1000) public void deliver(){payments.tick();refunds.tick();events.tick();journeys.tick();}
}
