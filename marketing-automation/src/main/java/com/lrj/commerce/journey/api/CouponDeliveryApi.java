package com.lrj.commerce.journey.api;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.campaign.api.MarketingAssets;
import java.time.Instant;
import java.util.List;
/** 固定人群快照的可靠券发放，进度不等同于全部券仍可使用。 */
public interface CouponDeliveryApi {
    record Create(String batchId,String storeId,String name,String definitionId,long definitionVersion,MarketingAssets.Ref audience,Instant deadline,int minIntervalHours) { }
    record View(Create content,String status,String mode,int processed,int issued,int skipped,int revoked,int kept,String cursorMember,String revokeCursor,int attempts,String errorCode,long version) { }
    record Recipient(String memberId,String status,String couponId,String errorCode,Instant createdAt) { }
    record Control(long expectedVersion,String action,String reason) { }
    /** 创建固定来源版本批次。 */
    View create(Actor actor,String key,Create input);
    /** 目录与回执均限定本租户并有界。 */
    List<View> list(Actor actor,String store,String after,int limit);
    List<Recipient> recipients(Actor actor,String id,String after,int limit);
    /** 取消和撤销区分，已有核销不可被隐瞒。 */
    View control(Actor actor,String key,String id,Control input);
    /** 管理手动推进，仍遵循单轮预算。 */
    int pump(Actor actor);
    /** 小批租户轮转复用原Worker。 */
    int tick();
}
