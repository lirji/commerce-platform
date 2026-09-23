package com.lrj.commerce.trade.api;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.marketing.api.DecisionModels;
import java.time.Instant;
import java.util.List;

/** 交易报价拥有完整快照；不向客户端接受价格或人群事实。 */
public interface QuoteApi {
    record Selection(String skuId,int quantity) { }
    record Request(String storeId,List<Selection> items,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String couponId,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long redeemPoints) { public Request(String storeId,List<Selection> items,String couponId){this(storeId,items,couponId,null);} }
    record Line(String skuId,long revision,String title,int quantity,String unitPrice,String gross,String discount,String payable,Long points,String pointDiscount,Long channelPriceVersion) {
        public Line { channelPriceVersion=channelPriceVersion==null?0L:channelPriceVersion;points=points==null?0L:points; pointDiscount=pointDiscount==null?"0.00":pointDiscount; }
        public Line(String skuId,long revision,String title,int quantity,String unitPrice,String gross,String discount,String payable,Long points,String pointDiscount){this(skuId,revision,title,quantity,unitPrice,gross,discount,payable,points,pointDiscount,0L);}
        public Line(String skuId,long revision,String title,int quantity,String unitPrice,String gross,String discount,String payable){this(skuId,revision,title,quantity,unitPrice,gross,discount,payable,0L,"0.00",0L);}
    }
    record FundingLine(String skuId,String campaignDiscount,String couponDiscount,String platformFunding,String merchantFunding,String pointDiscount) {
        public FundingLine { pointDiscount=pointDiscount==null?"0.00":pointDiscount; }
        public FundingLine(String skuId,String campaignDiscount,String couponDiscount,String platformFunding,String merchantFunding){this(skuId,campaignDiscount,couponDiscount,platformFunding,merchantFunding,"0.00");}
    }
    record Funding(String platformFunding,String merchantFunding,List<FundingLine> items) { public Funding { items=List.copyOf(items); } }
    record View(String quoteId,String memberId,String merchantId,String storeId,String currency,String gross,String discount,String payable,
                Instant createdAt,Instant expiresAt,List<Line> items,DecisionModels.Selection campaign,List<DecisionModels.Trace> trace,List<com.lrj.commerce.campaign.api.MarketingAssets.Source> sources,com.lrj.commerce.benefit.api.CouponApi.Application coupon,String campaignDiscount,String couponStatus,com.lrj.commerce.campaign.api.CampaignFundingApi.Commitment promotion,Funding funding,com.lrj.commerce.member.api.PointsSpendApi.Application points,Actor.Channel channel) {
        public View { channel=channel==null?Actor.Channel.WEB:channel;items=List.copyOf(items);trace=List.copyOf(trace);sources=sources==null?List.of():List.copyOf(sources);campaignDiscount=campaignDiscount==null?discount:campaignDiscount;couponStatus=couponStatus==null?"NOT_REQUESTED":couponStatus; }
    }
    View create(Actor actor,String key,Request input);
    View read(Actor actor,String id);
    /** 订单本地事务内消费一次报价，不能直接暴露为HTTP端点。 */
    View consume(Actor actor,String id,String orderId);
    /** 内部只读批次供对账投影使用，不开放HTTP任意会员查询。 */
    List<View> internalBatch(String tenant,List<String> quoteIds);
}
