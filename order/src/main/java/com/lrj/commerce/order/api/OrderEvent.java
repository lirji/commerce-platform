package com.lrj.commerce.order.api;

/** 事实事件须由可信用例产生；超时不是未支付证明。 */
public enum OrderEvent {
    START_PAYMENT, REQUEST_CANCEL, PAYMENT_CONFIRMED, PAYMENT_ABSENCE_CONFIRMED,
    START_FULFILLMENT, CONFIRM_DELIVERY
}
