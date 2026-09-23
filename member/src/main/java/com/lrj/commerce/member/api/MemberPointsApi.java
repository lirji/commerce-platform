package com.lrj.commerce.member.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;

/** 积分是可消费的独立账本，不复用等级成长余额。 */
public interface MemberPointsApi {
    /** 稳定持久化代码，新增动作不依赖枚举序号。 */
    enum Action {
        EARN("EARN"), ADJUST("ADJUST"), REVOKE("REVOKE"), EXPIRE("EXPIRE");
        private final String code;
        Action(String code){this.code=code;}
        @com.fasterxml.jackson.annotation.JsonValue public String getCode(){return code;}
    }
    record Policy(long version,Instant effectiveFrom,String earnPerYuan,int expiryDays,boolean spendEnabled,int pointsPerYuan,int maxDeductionBps) { }
    record Wallet(String memberId,long available,long held,long debt,long credit,long version) { }
    record Adjustment(long expectedVersion,long delta,String reason) { }
    record Entry(long sequenceId,Action action,String sourceId,long delta,long available,long debt,long policyVersion,String reason,Instant createdAt) { }
    /** 规则只追加，退款读取原订单所选版本。 */
    Policy publish(Actor actor,String key,Policy input);
    /** 策略历史分页。 */
    List<Policy> policies(Actor actor,long after,int limit);
    /** 账户按实际有效期计算，不依赖任务及时性。 */
    Wallet wallet(Actor actor,String memberId);
    /** 仅本人积分账户。 */
    Wallet current(Actor actor);
    /** 不可变账本，会员仅能读取本人。 */
    List<Entry> ledger(Actor actor,String memberId,long after,int limit);
    /** 管理校准必须给出原因和并发版本。 */
    Wallet adjust(Actor actor,String key,String memberId,Adjustment input);
    /** 明确推进有限批次到期，任务延迟不使积分继续可消费。 */
    Wallet expire(Actor actor,String key,String memberId);
    /** 权威订单及现金退款事实只由装配层提供。 */
    void observe(String tenant,MemberGrowthApi.OrderFact fact);
    /** 有界到期清理，保留账本和批次作退款审计。 */
    void tick();
}
