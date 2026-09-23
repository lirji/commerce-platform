package com.lrj.commerce.benefit.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;
/** 券钱包是资格与占用权威，报价命中不代表已经占到券。 */
public interface CouponApi {
    record Definition(String definitionId,long version,String storeId,String name,String minimumSpend,String discountAmount,Instant validFrom,Instant validTo,int quota,boolean stackable) { }
    record DefinitionView(Definition content,int issued) { }
    record Coupon(String couponId,String definitionId,long version,String memberId,String storeId,String name,String status,String discountAmount,String minimumSpend,Instant validFrom,Instant validTo,boolean stackable) { }
    record Application(String couponId,String definitionId,long version,String discount) { }
    DefinitionView create(Actor actor,String key,Definition input);
    List<DefinitionView> definitions(Actor actor,String store,String after,int limit);
    Coupon claim(Actor actor,String key,String definition,long version);
    List<Coupon> wallet(Actor actor,String after,int limit);
    Coupon eligible(Actor actor,String coupon,String store,String gross);
    void reserve(Actor actor,String order,String store,Application coupon);
    void confirm(String tenant,String order);
    void release(String tenant,String order);
    void refund(String tenant,String order);
}
