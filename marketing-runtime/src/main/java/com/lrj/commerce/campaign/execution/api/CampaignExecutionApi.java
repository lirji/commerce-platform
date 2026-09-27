package com.lrj.commerce.campaign.execution.api;

import com.lrj.commerce.benefit.entitlement.api.EntitlementApi;
import com.lrj.commerce.campaign.asset.api.MarketingAssets;
import com.lrj.commerce.marketing.api.DecisionModels;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.time.Instant;
import java.util.List;

/** 订单营销参与的固定版本与履约结果，权益余额仍由权益模块负责。 */
public interface CampaignExecutionApi {

	record OrderTrigger(String orderId, String quoteId, String memberId, String storeId,
			DecisionModels.Selection campaign, String campaignDiscount, Instant evaluatedAt,
			List<DecisionModels.Trace> trace, List<MarketingAssets.Source> audiences, EntitlementApi.Ref benefit) {
	}

	record View(String orderId, String campaignId, long campaignVersion, String quoteId, String memberId,
			String storeId, String ruleId, Long ruleVersion, String audienceId, Long audienceVersion,
			String benefitId, Long benefitVersion, String grantId, String discountAmount, String reasonCode,
			String status, String grantStatus, String eventId, String eventStatus, String failureClass,
			Instant evaluatedAt, Instant createdAt, Instant updatedAt, long lockVersion) {
	}

	/** 必须与订单消费报价、预算和权益预占处于同一数据库事务。 */
	void recordOrder(Actor actor, OrderTrigger trigger);

	/** 支付/取消确认沿用订单事务，不单独构造第二条发放链。 */
	void settleOrder(String tenant, String orderId, boolean paid);

	View find(Actor actor, String orderId, String campaignId);

	List<View> list(Actor actor, String afterOrder, int limit);

}
