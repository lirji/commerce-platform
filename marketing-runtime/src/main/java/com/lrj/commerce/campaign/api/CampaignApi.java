package com.lrj.commerce.campaign.api;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.marketing.api.DecisionModels.Offer;
import java.time.Instant;
import java.util.List;

/** 活动业务版本不可变，发布状态与并发版本单独维护。 */
public interface CampaignApi {
    record Draft(String campaignId,long version,String storeId,String name,Instant validFrom,Instant validTo,String minimumSpend,String discountAmount,RuleNode rule,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Policy policy) { }
    record Tier(String minimumSpend,String discountAmount,int percentageBps) { }
    record Pricing(List<String> includedSkuIds,List<String> excludedSkuIds,List<Tier> tiers) { }
    record Terms(int percentageBps,int platformFundingBps,String budget,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.lrj.commerce.benefit.api.EntitlementApi.Ref grant,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Pricing pricing) {
        public Terms(int percentageBps,int platformFundingBps,String budget,com.lrj.commerce.benefit.api.EntitlementApi.Ref grant){this(percentageBps,platformFundingBps,budget,grant,null);}
    }
    record PreviewItem(String skuId,int quantity) { }
    record Preview(String memberId,Instant at,List<PreviewItem> items) { }
    record PreviewLine(String skuId,String gross,String discount,String payable) { }
    record PreviewResult(String gross,String discount,String payable,List<PreviewLine> lines,List<com.lrj.commerce.marketing.api.DecisionModels.Trace> trace,List<MarketingAssets.Source> sources,String notice) { }
    record Policy(MarketingAssets.Ref audience,MarketingAssets.Ref rule,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Terms terms) { }
    record Candidates(List<Offer> offers,List<MarketingAssets.Source> sources) { }
    record View(Draft content,String merchantId,String status,long lockVersion) { }
    View create(Actor actor,String key,Draft input);
    View publish(Actor actor,String key,String id,long version,long expectedVersion);
    View pause(Actor actor,String key,String id,long version,long expectedVersion);
    List<View> list(Actor actor,String after,int limit);
    /** 增强活动必须走显式审批，旧无policy活动保留兼容发布。 */
    View review(Actor actor,String key,String id,long version,long expected,String action);
    /** 报价使用可信会员的人群快照，候选条件保留UNKNOWN语义。 */
    Candidates candidates(Actor actor,String storeId,String memberId,Instant now);
    /** 只读评估指定版本，不预占任何库存或预算。 */
    PreviewResult preview(Actor actor,String id,long version,Preview input);
}
