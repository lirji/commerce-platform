package com.lrj.commerce.app;
import com.lrj.commerce.runtime.api.EventHandler;
import com.lrj.commerce.runtime.JsonCodec;
import com.lrj.commerce.aftersales.api.AftersaleApi;
import com.lrj.commerce.benefit.api.CouponApi;
import org.springframework.stereotype.Component;
import java.util.Set;
/** 装配层连接售后和权益API，避免订单依赖权益后再引入反向模块循环。 */
@Component
public class BenefitCompensationHandler implements EventHandler {
    private final com.lrj.commerce.journey.api.JourneyApi journeys;private final com.lrj.commerce.benefit.api.EntitlementApi entitlements;private final CouponApi coupons;
    public BenefitCompensationHandler(CouponApi coupons,com.lrj.commerce.benefit.api.EntitlementApi entitlements,com.lrj.commerce.journey.api.JourneyApi journeys){this.journeys=journeys;this.entitlements=entitlements;this.coupons=coupons;}
    public String consumer(){return "benefit-refund-v1";}
    public Set<String> types(){return Set.of("aftersales.completed.v1");}
    /** 只在累计全量退货事实成立后返券，Inbox和返还同事务。 */
    public void handle(Event event){var completion=JsonCodec.read(event.payloadJson(),AftersaleApi.Completion.class);if(completion.fullReturn()){journeys.cancelForOrder(event.tenantId(),completion.orderId());coupons.refund(event.tenantId(),completion.orderId());entitlements.reverseOrder(event.tenantId(),completion.orderId());}}
}
