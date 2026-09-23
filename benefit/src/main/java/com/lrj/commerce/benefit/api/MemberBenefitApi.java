package com.lrj.commerce.benefit.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;

/** 等级权益由权益域授予，周期策略由会员域提供公开契约。 */
public interface MemberBenefitApi {
    record Bundle(String bindingId,long policyVersion,String level,String storeId,Instant validUntil,List<EntitlementApi.Ref> benefits) { }
    record Receipt(String memberId,List<EntitlementApi.View> grants) { }
    /** 每策略每档一次配置，调整需要发布新周期版本。 */
    Bundle publish(Actor actor,String key,Bundle input);
    /** 最多8档，与周期等级上限一致。 */
    List<Bundle> list(Actor actor,long policyVersion);
    /** 补发仅针对当前考核，仍与自动发放使用同一来源键。 */
    Receipt grant(Actor actor,String key,String memberId);
}
