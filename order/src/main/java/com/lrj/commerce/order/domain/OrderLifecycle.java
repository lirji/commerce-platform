package com.lrj.commerce.order.domain;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.order.api.OrderEvent;
import com.lrj.commerce.order.api.OrderState;

/** 集中约束生命周期；数据库应用层仍须以旧版本条件更新并检查影响行数。 */
public record OrderLifecycle(OrderState state, long version) {
    public OrderLifecycle {
        if (state == null || version < 0) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "订单状态或版本无效");
        }
    }

    /** 新建订单无任何渠道资金事实；零元订单另由专门用例验证后完成支付阶段。 */
    public static OrderLifecycle start() { return new OrderLifecycle(OrderState.PENDING_PAYMENT, 0); }

    /** 拒绝非法或重复迁移，幂等消费应在事件持久化边界去重。 */
    public OrderLifecycle apply(OrderEvent event) {
        if (event == null) throw new DomainException(DomainException.Code.INVALID_INPUT, "订单事件不能为空");
        OrderState next = switch (state) {
            case PENDING_PAYMENT -> switch (event) {
                case START_PAYMENT -> OrderState.PAYMENT_IN_PROGRESS;
                case REQUEST_CANCEL -> OrderState.CANCELLED;
                case PAYMENT_CONFIRMED -> OrderState.PAID;
                default -> null;
            };
            case PAYMENT_IN_PROGRESS -> switch (event) {
                case REQUEST_CANCEL -> OrderState.CLOSING;
                case PAYMENT_CONFIRMED -> OrderState.PAID;
                default -> null;
            };
            case CLOSING -> switch (event) {
                // 已收款事实优先；后续取消诉求交给售后补偿，不能丢弃真实资金。
                case PAYMENT_CONFIRMED -> OrderState.PAID;
                case PAYMENT_ABSENCE_CONFIRMED -> OrderState.CANCELLED;
                default -> null;
            };
            case PAID -> event == OrderEvent.START_FULFILLMENT ? OrderState.FULFILLING : null;
            case FULFILLING -> event == OrderEvent.CONFIRM_DELIVERY ? OrderState.COMPLETED : null;
            case COMPLETED, CANCELLED -> null;
        };
        if (next == null) {
            throw new DomainException(DomainException.Code.ILLEGAL_TRANSITION, "订单当前状态不允许该事件");
        }
        if (version == Long.MAX_VALUE) {
            throw new DomainException(DomainException.Code.LIMIT_EXCEEDED, "订单版本超限");
        }
        return new OrderLifecycle(next, version + 1);
    }
}
