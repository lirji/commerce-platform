package com.lrj.commerce.member.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;
import java.time.Instant;

/** 成长是会员域事实，不与权益余额或支付积分混用。 */
public interface MemberGrowthApi {
 record Level(String code,long minimumGrowth) { }
 record Policy(long version,Instant effectiveFrom,String growthPerYuan,List<Level> levels) { }
 record Wallet(String memberId,long growth,String netSpend,String memberLevel,long policyVersion,long version) { }
 record Adjustment(long expectedVersion,long delta,String reason) { }
 record Entry(long sequenceId,String sourceId,long delta,long balance,long policyVersion,String reason,Instant createdAt) { }
 record OrderFact(String orderId,String memberId,String paid,Instant orderedAt,boolean completed,String refundId,String refundAmount) { }
 record LevelChanged(String memberId,String beforeLevel,String afterLevel,long growth,long policyVersion) { }
 record Facts(String memberId,String memberLevel,String status,long growth,String netSpend,List<String> tags) { }
 Policy publish(Actor actor,String key,Policy input);
 List<Policy> policies(Actor actor,long after,int limit);
 Wallet wallet(Actor actor,String memberId);
 Wallet current(Actor actor);
 List<Entry> ledger(Actor actor,String memberId,long after,int limit);
 Wallet adjust(Actor actor,String key,String memberId,Adjustment input);
 Wallet recalculate(Actor actor,String key,String memberId);
 /** 内部可信事实入口，强制加入调用者事务，不开放HTTP。 */
 void observe(String tenant,OrderFact fact);
 Facts facts(String tenant,String memberId);
}
