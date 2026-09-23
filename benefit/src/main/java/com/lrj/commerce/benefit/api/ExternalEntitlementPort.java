package com.lrj.commerce.benefit.api;
/** 外部权益接入预留；当前无实现/无外部调用，不能把内部CREDIT当作三方发奖。 */
public interface ExternalEntitlementPort {
    record Request(String tenantId,String requestId,String memberId,String benefitId,long version,int units) { }
    enum Status { NOT_CONFIGURED, UNKNOWN, SUCCEEDED, REVOKED }
    record Evidence(String requestId,Status status,String providerReceipt) { }
    Evidence grant(Request request);
    Evidence query(String tenantId,String requestId);
    Evidence revoke(String tenantId,String requestId);
}
