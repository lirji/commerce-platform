package com.lrj.commerce.runtime.api;
import java.time.Instant;
import java.util.Set;
/** 本地事务消费者；禁止在handle中做远程IO，远程工作必须先持久化独立任务。 */
public interface EventHandler {
    /** attempts为非瞬时失败次数（毒事件预算），transientAttempts为依赖不可用等瞬时失败次数。 */
    record Event(String eventId,String tenantId,String eventType,String aggregateId,long aggregateVersion,String payloadJson,Instant createdAt,int attempts,int transientAttempts) { }
    String consumer();
    Set<String> types();
    void handle(Event event);
    /**
     * 消费者副作用分类：重放安全门据此判断能否对历史事件执行。PURE是只从权威数据重算的投影；IDEMPOTENT_WRITE由业务键或状态条件使重复执行无效；
     * DEDUP_PROTECTED只有Inbox保护；其余类别表示补偿、不可逆、外部或资金副作用。
     */
    enum SideEffect { PURE, IDEMPOTENT_WRITE, DEDUP_PROTECTED, COMPENSATABLE_SIDE_EFFECT, IRREVERSIBLE_SIDE_EFFECT, EXTERNAL_SIDE_EFFECT, FINANCIAL_SIDE_EFFECT }
    /**
     * 重放声明：effects为副作用分类；historicalReplay表示对从未处理过的历史事件执行在业务上安全；reprocess表示已处理过的事件也可重新执行（仅纯投影）。
     * evidence说明依据（业务键、约束、状态条件），出现在安全矩阵与拒绝原因中。
     */
    record ReplaySafety(Set<SideEffect> effects,boolean historicalReplay,boolean reprocess,String evidence) {
        public ReplaySafety {effects=Set.copyOf(effects);if(evidence==null||evidence.isBlank())throw new IllegalArgumentException("重放分类必须给出依据");}
        /** 已分类但不允许任何历史重放。 */
        public static ReplaySafety notReplayable(String evidence,SideEffect... effects){return new ReplaySafety(Set.of(effects),false,false,evidence);}
    }
    /** 未声明分类的消费者一律视为未分类，安全门拒绝任何重放（失败即关闭）。 */
    default ReplaySafety replaySafety(){return null;}
}
