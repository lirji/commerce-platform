package com.lrj.commerce.insight.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;

/** 商业效果明确投影覆盖和资金口径，不将统计读模型当作资金权威。 */
public interface MarketingEffectsApi {
 record Daily(java.time.LocalDate day,long orders,long paidOrders,String received,String refunded,String netReceipts,String discountGranted,String platformFunding,String merchantFunding,Instant updatedAt) { }
 /** 以订单投影唯一键按UTC日聚合，不跨旅程版本叠加。 */
 List<Daily> daily(Actor actor,String store,Instant from,Instant to);
 record Series(String seriesId,String campaignId,Long campaignVersion,long orders,long paidOrders,String received,String refunded,String netReceipts,String discountGranted,String platformFunding,String merchantFunding,Instant updatedAt) { }
 record Report(List<Series> rows,String cohort,String coverage,String costBasis) { }
 record JourneySeries(String seriesId,String journeyId,long journeyVersion,int observationDays,long enrolledMembers,long matureMembers,long notified,long benefitsGranted,long couponsGranted,long paidMembers,long paidOrders,String received,String refunded,String netReceipts,String discountGranted,String platformFunding,String merchantFunding,Instant updatedAt) { }
 record DeliverySeries(String seriesId,String batchId,long issued,long skipped,long revoked,long kept,long paidOrders,String received,String refunded,String netReceipts,String couponDiscount,Instant updatedAt) { }
 record JourneyReport(List<JourneySeries> rows,String basis,String coverage,String costBasis) { }
 record DeliveryReport(List<DeliverySeries> rows,String basis,String coverage,String costBasis) { }
 /** 版本队列的描述性比较，不能跨行相加或推断因果。 */
 JourneyReport journeys(Actor actor,String store,Instant from,Instant to,String after,int limit);
 /** 真实使用批次券的订单及其退款与券优惠。 */
 DeliveryReport deliveries(Actor actor,String store,Instant from,Instant to,String after,int limit);
 record Rebuild(String after,int limit) { }
 record RebuildResult(int processed,String nextAfter,boolean hasMore) { }
 Report report(Actor actor,String store,Instant from,Instant to,String after,int limit);
 RebuildResult rebuild(Actor actor,String key,Rebuild input);
}
