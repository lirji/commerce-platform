package com.lrj.commerce.member.api;
import com.lrj.commerce.runtime.api.Actor;

/** 积分消费端口由可信交易/售后编排调用，不直接提供任意冻结或返还HTTP接口。 */
public interface PointsSpendApi {
    record Application(long policyVersion,long points,String discount) { }
    /** 报价只计算可用额度，不冻结批次。 */
    Application preview(Actor actor,String memberId,long requested,String remainingAmount);
    /** 下单在本地事务中复核并冻结，与报价消费同成败。 */
    void reserve(Actor actor,String orderId,String memberId,Application application,String remainingAmount);
    /** 已证实付款或零现金订单确认后核销。 */
    void confirm(String tenant,String orderId,String memberId);
    /** 仅由明确取消调用，支付未知不得释放。 */
    void release(String tenant,String orderId,String memberId);
    /** 售后成功返还原分配积分；来源caseId持久化幂等。 */
    void refund(String tenant,String orderId,String memberId,String caseId,long points);
}
