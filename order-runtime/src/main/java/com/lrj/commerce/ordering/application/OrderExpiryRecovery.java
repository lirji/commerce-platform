package com.lrj.commerce.ordering.application;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.ordering.infrastructure.persistence.OrderMapper;
import com.lrj.commerce.runtime.RetryPolicy;
import com.lrj.commerce.runtime.api.RecoverableWork;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/**
 * 停止自动到期（5次非瞬时失败或瞬时预算用尽）的未支付订单的恢复入口：RETRY清零两类计数与退避，保留最近失败证据；
 * 订单已支付或已取消时不再是停止项，恢复被拒绝。不支持SKIP：未支付订单不能被“跳过”而永久占用库存与权益。
 * 订单只保存最近一次失败，没有首次失败时间（第三阶段已记录的限制）。
 */
@Component
public class OrderExpiryRecovery implements RecoverableWork {

	public static final String WORK_TYPE = "order.expiry", STOPPED = "EXPIRY_STOPPED", READY = "READY";

	private final OrderMapper mapper;

	public OrderExpiryRecovery(OrderMapper mapper) {
		this.mapper = mapper;
	}

	public String workType() {
		return WORK_TYPE;
	}

	public Set<Action> actions() {
		return EnumSet.of(Action.RETRY);
	}

	public List<Stopped> stopped(String tenant, String failureClass, String after, int limit) {
		return mapper
			.expiryStopped(tenant, failureClass, after, limit, RetryPolicy.POISON.budget(),
					RetryPolicy.TRANSIENT.budget())
			.stream()
			.map(OrderExpiryRecovery::view)
			.toList();
	}

	public Stopped find(String tenant, String id) {
		var row = mapper.expiryStoppedOne(tenant, id, RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget());
		return row == null ? null : view(row);
	}

	public Transition recover(String tenant, String id, Action action, Instant now) {
		var row = mapper.expiryStoppedOne(tenant, id, RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget());
		if (row == null || action != Action.RETRY
				|| mapper.expiryRetry(tenant, id, RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget()) != 1)
			throw new DomainException(DomainException.Code.CONFLICT, "订单不在停止自动到期状态");
		return new Transition(STOPPED, READY, failureClass(row.expiryError()));
	}

	/** 证据格式为“分类:异常类型”，取分类部分。 */
	static String failureClass(String error) {
		if (error == null)
			return null;
		int i = error.indexOf(':');
		return i < 0 ? error : error.substring(0, i);
	}

	private static Stopped view(OrderMapper.ExpiryStopped r) {
		return new Stopped(WORK_TYPE, r.orderId(), STOPPED, failureClass(r.expiryError()), r.expiryError(),
				r.expiryAttempts(), r.expiryTransientAttempts(), null, null, 0);
	}

}
