package com.lrj.commerce.payment.charge.application;

import com.lrj.commerce.ordering.order.api.OrderApi;
import com.lrj.commerce.payment.charge.infrastructure.persistence.PaymentMapper;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.api.event.EventHandler;
import org.springframework.stereotype.Component;
import java.util.Set;

/** 订单进入CLOSING时重新安排渠道核对，自动重查次数已用尽的未知支付也能由后台关闭或确认。 */
@Component
public class PaymentClosingHandler implements EventHandler {

	private final PaymentMapper mapper;

	public PaymentClosingHandler(PaymentMapper mapper) {
		this.mapper = mapper;
	}

	public String consumer() {
		return "payment-order-closing-v1";
	}

	/** 重放分类见phase4重放安全矩阵。 */
	@Override
	public com.lrj.commerce.runtime.api.event.EventHandler.ReplaySafety replaySafety() {
		return com.lrj.commerce.runtime.api.event.EventHandler.ReplaySafety.notReplayable(
				"只重置UNKNOWN/OPEN支付的核对计数，但会重新触发支付渠道远程关单",
				com.lrj.commerce.runtime.api.event.EventHandler.SideEffect.IDEMPOTENT_WRITE,
				com.lrj.commerce.runtime.api.event.EventHandler.SideEffect.EXTERNAL_SIDE_EFFECT,
				com.lrj.commerce.runtime.api.event.EventHandler.SideEffect.FINANCIAL_SIDE_EFFECT);
	}

	public Set<String> types() {
		return Set.of("order.closing.v1");
	}

	/** 只重置核对计划，不改变支付或订单状态；终态尝试不受影响。 */
	public void handle(Event event) {
		mapper.rearmCheck(event.tenantId(), JsonCodec.read(event.payloadJson(), OrderApi.View.class).orderId());
	}

}
