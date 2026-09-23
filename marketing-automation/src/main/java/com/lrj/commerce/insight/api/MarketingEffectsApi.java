package com.lrj.commerce.insight.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;

/** 商业效果明确投影覆盖和资金口径，不将统计读模型当作资金权威。 */
public interface MarketingEffectsApi {
 record Series(String seriesId,String campaignId,Long campaignVersion,long orders,long paidOrders,String received,String refunded,String netReceipts,String discountGranted,String platformFunding,String merchantFunding,Instant updatedAt) { }
 record Report(List<Series> rows,String cohort,String coverage,String costBasis) { }
 record Rebuild(String after,int limit) { }
 record RebuildResult(int processed,String nextAfter,boolean hasMore) { }
 Report report(Actor actor,String store,Instant from,Instant to,String after,int limit);
 RebuildResult rebuild(Actor actor,String key,Rebuild input);
}
