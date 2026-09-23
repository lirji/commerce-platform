package com.lrj.commerce.order.api;

/** 状态名称即稳定 code；持久化与事件使用 name，不使用 ordinal。 */
public enum OrderState {
    PENDING_PAYMENT, PAYMENT_IN_PROGRESS, CLOSING, PAID, FULFILLING, COMPLETED, CANCELLED
}
