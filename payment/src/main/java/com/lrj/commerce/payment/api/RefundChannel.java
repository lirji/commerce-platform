package com.lrj.commerce.payment.api;
/** 正式退款适配应以原请求号查单和验签，不因超时创建第二笔退款。 */
public interface RefundChannel {
    record Evidence(String refundId,String tenantId,String orderId,String amount,String currency,String status,String transactionId) { }
    void ensure(String tenant,RefundApi.View refund);
    Evidence observe(String tenant,String refundId);
}
