package com.lrj.commerce.campaign.api;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.marketing.api.DecisionModels.Selection;
import java.util.List;
/** 活动预算权威与成交资方快照，不能由缓存或报价资格代替额度预占。 */
public interface CampaignFundingApi {
    record Commitment(String campaignId,long version,String discount,String platformFunding,String merchantFunding,com.lrj.commerce.benefit.api.EntitlementApi.Ref grant) { }
    record Budget(String budgetId,String campaignId,long version,String cap,String held,String spent) { }
    Commitment commitment(Actor actor,Selection campaign,String discount);
    void reserve(Actor actor,String order,String store,Commitment commitment);
    void confirm(String tenant,String order);
    void release(String tenant,String order);
    List<Budget> budgets(Actor actor,String after,int limit);
}
