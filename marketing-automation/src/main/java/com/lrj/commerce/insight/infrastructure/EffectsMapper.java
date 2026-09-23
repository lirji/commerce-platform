package com.lrj.commerce.insight.infrastructure;
import com.lrj.commerce.insight.api.MarketingEffectsApi;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 只写分析自身投影，金额更新单调合并防止乱序回退。 */
@Mapper
public interface EffectsMapper {
 record Fact(String orderId,String storeId,String seriesId,String campaignId,Long campaignVersion,Instant orderedAt,boolean paid,String paidAmount,String refunded,String discountAmount,String platformFunding,String merchantFunding,String memberId,String couponId,String couponDiscount) { }
 List<MarketingEffectsApi.JourneySeries> journeys(String tenant,String store,Instant from,Instant to,String after,int limit,Instant now);
 List<MarketingEffectsApi.DeliverySeries> deliveries(String tenant,String store,Instant from,Instant to,String after,int limit);
 void observe(@Param("tenant") String tenant,@Param("input") Fact input);
 List<MarketingEffectsApi.Series> report(@Param("tenant") String tenant,@Param("store") String store,@Param("from") Instant from,@Param("to") Instant to,@Param("after") String after,@Param("limit") int limit);
}
