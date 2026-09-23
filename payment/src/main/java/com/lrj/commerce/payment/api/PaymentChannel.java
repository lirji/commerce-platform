package com.lrj.commerce.payment.api;
/** 渠道适配端口；所有远程IO只能在业务事务之外执行。正式实现需验签、商户校验与可信查询。 */
public interface PaymentChannel {
    record Evidence(String paymentId,String tenantId,String orderId,String amount,String currency,PaymentApi.Status status,String transactionId) { }
    String provider();
    void ensure(String tenant,PaymentApi.View payment);
    Evidence observe(String tenant,String paymentId);
    Evidence close(String tenant,String paymentId);
}
