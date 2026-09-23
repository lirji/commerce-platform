package com.lrj.commerce.member.api;

import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;

/** 周期考核是独立等级规则，累计成长仍保留为经营事实。 */
public interface MemberCycleApi {
    record Level(String code, long minimumGrowth) { }
    record Policy(long version, Instant effectiveFrom, int periodDays, List<Level> levels) { }
    record View(String memberId, boolean enabled, long policyVersion, Instant cycleStart,
                Instant cycleEnd, long currentGrowth, long retentionGrowth, String memberLevel, long version) { }
    record Assessed(String memberId, long policyVersion, Instant cycleStart, Instant cycleEnd,
                    String memberLevel, long currentGrowth, long retentionGrowth) { }
    /** 发布不可变周期策略；未配置租户继续沿用累计成长等级。 */
    Policy publish(Actor actor, String key, Policy input);
    /** 查询历史策略。 */
    List<Policy> policies(Actor actor, long after, int limit);
    /** 权益绑定读取确切不可变版本，避免遍历历史策略。 */
    Policy policy(Actor actor, long version);
    /** 显式考核，允许对同一周期重复执行。 */
    View evaluate(Actor actor, String key, String memberId);
    /** 不隐含写入的管理/本人查询。 */
    View read(Actor actor, String memberId);
    /** 本人绑定来自认证。 */
    View current(Actor actor);
    /** 成长事务内记录来源净贡献，不能由公网伪造。 */
    void contribute(String tenant, String member, String source, Instant occurredAt, long contribution);
    /** 与成长写入共享会员行锁，返回是否由周期规则接管等级。 */
    boolean assess(String tenant, String member);
    /** 持久化边界驱动，单轮有界，不追补已过期周期奖励。 */
    void tick();
}
