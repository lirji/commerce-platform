package com.lrj.commerce.campaign.api;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.marketing.api.DecisionModels.Offer;
import java.time.Instant;
import java.util.List;

/** 活动业务版本不可变，发布状态与并发版本单独维护。 */
public interface CampaignApi {
    record Draft(String campaignId,long version,String storeId,String name,Instant validFrom,Instant validTo,String minimumSpend,String discountAmount,RuleNode rule) { }
    record View(Draft content,String merchantId,String status,long lockVersion) { }
    View create(Actor actor,String key,Draft input);
    View publish(Actor actor,String key,String id,long version,long expectedVersion);
    View pause(Actor actor,String key,String id,long version,long expectedVersion);
    List<View> list(Actor actor,String after,int limit);
    List<Offer> published(Actor actor,String storeId);
}
