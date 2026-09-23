package com.lrj.commerce.fulfillment.api;
/** 正式WMS接入时通过适配器取得可信证明，不把其DTO引入订单域。 */
public interface WmsPort {
    record Proof(String provider,String trackingNo) { }
    Proof shipment(String tenant,String order,String trackingNo);
    void delivered(String tenant,String order,String trackingNo);
}
