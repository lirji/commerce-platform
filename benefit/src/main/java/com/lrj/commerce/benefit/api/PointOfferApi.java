package com.lrj.commerce.benefit.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;
/** 积分兑换由权益域编排，积分权威仍在会员域。 */
public interface PointOfferApi {
    /** 封闭资产类型采用稳定代码。 */
    enum Kind { COUPON, ENTITLEMENT }
    record Offer(String offerId,String storeId,String name,Kind kind,String assetId,long assetVersion,long points,int quota,int perMemberLimit,Instant validFrom,Instant validTo) { }
    record View(Offer content,String status,int issued,long version) { }
    record Status(long expectedVersion,boolean active,String reason) { }
    record Receipt(String redemptionId,String offerId,String memberId,long points,Kind kind,String assetId,Instant createdAt) { }
    /** 创建不可变兑换规则。 */
    View create(Actor actor,String key,Offer input);
    /** 停启使用版本锁并记录原因。 */
    View status(Actor actor,String key,String id,Status input);
    /** 稳定游标，会员只看当前有效目录。 */
    List<View> list(Actor actor,String store,String after,int limit);
    /** 会员本人原子扣分及资产受理。 */
    Receipt redeem(Actor actor,String key,String id);
    /** 本人兑换回执。 */
    List<Receipt> receipts(Actor actor,String after,int limit);
}
