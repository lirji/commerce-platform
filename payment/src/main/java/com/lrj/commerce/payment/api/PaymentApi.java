package com.lrj.commerce.payment.api;
import com.lrj.commerce.runtime.api.Actor;
/** 支付金额来自订单；HTTP调用者不能提交成功状态或自报金额。 */
public interface PaymentApi {
    enum Status { UNKNOWN,OPEN,PAID,CLOSED }
    record View(String paymentId,String orderId,String amount,String currency,String provider,Status status,long version) { }
    record SandboxFact(Status status) { }
    View start(Actor actor,String key,String orderId);
    View read(Actor actor,String orderId);
    View reconcile(Actor actor,String orderId);
    View sandboxFact(Actor actor,String key,String paymentId,SandboxFact fact);
    /** 有界后台核对，五次未知后保留记录供人工查询。 */
    int tick();
    /** 运营读取和核对必须独立验证管理员与订单租户。 */
    View adminRead(Actor actor,String orderId);
    View adminReconcile(Actor actor,String orderId);
}
