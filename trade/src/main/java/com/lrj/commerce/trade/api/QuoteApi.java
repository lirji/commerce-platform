package com.lrj.commerce.trade.api;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.marketing.api.DecisionModels;
import java.time.Instant;
import java.util.List;

/** 交易报价拥有完整快照；不向客户端接受价格或人群事实。 */
public interface QuoteApi {
    record Selection(String skuId,int quantity) { }
    record Request(String storeId,List<Selection> items,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String couponId) { }
    record Line(String skuId,long revision,String title,int quantity,String unitPrice,String gross,String discount,String payable) { }
    record FundingLine(String skuId,String campaignDiscount,String couponDiscount,String platformFunding,String merchantFunding) { }
    record Funding(String platformFunding,String merchantFunding,List<FundingLine> items) { public Funding { items=List.copyOf(items); } }
    record View(String quoteId,String memberId,String merchantId,String storeId,String currency,String gross,String discount,String payable,
                Instant createdAt,Instant expiresAt,List<Line> items,DecisionModels.Selection campaign,List<DecisionModels.Trace> trace,List<com.lrj.commerce.campaign.api.MarketingAssets.Source> sources,com.lrj.commerce.benefit.api.CouponApi.Application coupon,String campaignDiscount,String couponStatus,com.lrj.commerce.campaign.api.CampaignFundingApi.Commitment promotion,Funding funding) {
        public View { items=List.copyOf(items);trace=List.copyOf(trace);sources=sources==null?List.of():List.copyOf(sources);campaignDiscount=campaignDiscount==null?discount:campaignDiscount;couponStatus=couponStatus==null?"NOT_REQUESTED":couponStatus; }
    }
    View create(Actor actor,String key,Request input);
    View read(Actor actor,String id);
    /** 订单本地事务内消费一次报价，不能直接暴露为HTTP端点。 */
    View consume(Actor actor,String id,String orderId);
}
