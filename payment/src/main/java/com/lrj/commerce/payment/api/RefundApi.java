package com.lrj.commerce.payment.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;
/** 退款是新资金效果，不回退原支付记录；UNKNOWN不能展示为成功。 */
public interface RefundApi {
    record View(String refundId,String caseId,String orderId,String amount,String currency,String provider,String status,long version) { }
    View request(String tenant,String caseId,String orderId,String amount);
    View internalRead(String tenant,String refundId);
    List<View> list(Actor actor,String after,int limit);
    View reconcile(Actor actor,String refundId);
    View sandboxSuccess(Actor actor,String key,String refundId);
    int tick();
}
