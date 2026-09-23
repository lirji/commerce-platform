package com.lrj.commerce.benefit.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;
/** 券钱包是资格与占用权威，报价命中不代表已经占到券。 */
public interface CouponApi {
    record Definition(String definitionId,long version,String storeId,String name,String minimumSpend,String discountAmount,Instant validFrom,Instant validTo,int quota,boolean stackable,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Integer platformFundingBps,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String issuanceMode) {
        public Definition(String definitionId,long version,String storeId,String name,String minimumSpend,String discountAmount,Instant validFrom,Instant validTo,int quota,boolean stackable,Integer platformFundingBps){this(definitionId,version,storeId,name,minimumSpend,discountAmount,validFrom,validTo,quota,stackable,platformFundingBps,null);}
    }
    record DefinitionView(Definition content,int issued) { }
    record Coupon(String couponId,String definitionId,long version,String memberId,String storeId,String name,String status,String discountAmount,String minimumSpend,Instant validFrom,Instant validTo,boolean stackable,int platformFundingBps) { }
    record Application(String couponId,String definitionId,long version,String discount,int platformFundingBps) { }
    DefinitionView create(Actor actor,String key,Definition input);
    List<DefinitionView> definitions(Actor actor,String store,String after,int limit);
    /** 受控兑换的定义必须禁止公开领取。 */
    void validateExchange(String tenant,String store,String definition,long version,Instant from,Instant to);
    /** 来源发放不冒用会员身份；调用者已有会员授权及本地事务。 */
    Coupon grantFromPoints(String tenant,String member,String store,String source,String definition,long version);
    Coupon claim(Actor actor,String key,String definition,long version);
    List<Coupon> wallet(Actor actor,String after,int limit);
    Coupon eligible(Actor actor,String coupon,String store,String gross);
    void reserve(Actor actor,String order,String store,Application coupon);
    void confirm(String tenant,String order);
    void release(String tenant,String order);
    void refund(String tenant,String order);
}
