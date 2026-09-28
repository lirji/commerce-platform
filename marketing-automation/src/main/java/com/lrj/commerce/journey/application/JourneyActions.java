package com.lrj.commerce.journey.application;

import com.lrj.commerce.benefit.coupon.api.CouponApi;
import com.lrj.commerce.benefit.entitlement.api.EntitlementApi;
import com.lrj.commerce.journey.api.JourneyApi.*;
import com.lrj.commerce.journey.infrastructure.persistence.JourneyMapper;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 只注册已经存在的业务动作；编排不拥有额度、券或权益的业务规则。 */
final class JourneyActions {

	record Result(String outcome, String actionRef) {
	}

	@FunctionalInterface
	private interface Action {
		Result execute(String tenant, Instance instance, Definition definition, Node node, Instant at);
	}

	private final JourneyMapper mapper;

	private final EntitlementApi benefits;

	private final CouponApi coupons;

	private final Map<Kind, Action> handlers;

	JourneyActions(JourneyMapper mapper, EntitlementApi benefits, CouponApi coupons) {
		this.mapper = mapper;
		this.benefits = benefits;
		this.coupons = coupons;
		// Map.of拒绝重复键，新增动作必须明确注册，未知节点不会默认为通知。
		handlers = Map.of(Kind.GRANT, this::grant, Kind.COUPON, this::coupon, Kind.NOTIFY, this::notify);
	}

	/** 必须由实例锁所在的节点事务调用，来源键在实例和节点范围稳定。 */
	Result execute(String tenant, Instance instance, Definition definition, Node node, Instant at) {
		var action = handlers.get(node.kind());
		if (action == null)
			throw new DomainException(DomainException.Code.INVALID_INPUT, "未注册的旅程动作");
		return action.execute(tenant, instance, definition, node, at);
	}

	private Result grant(String tenant, Instance instance, Definition definition, Node node, Instant at) {
		var grant = benefits.grantFromJourney(tenant, instance.memberId(), definition.storeId(),
				JsonCodec.hash(instance.instanceId() + "/" + node.id()), instance.orderId(), node.benefit());
		mapper.effect(tenant, JsonCodec.hash("grant/" + instance.instanceId() + "/" + node.id()), definition,
				instance.memberId(), "BENEFIT_GRANTED", at);
		// 发放请求已经持久化；AVAILABLE仍由既有权益事件消费完成，不能伪称此处已履约。
		return new Result("BENEFIT_ACCEPTED", grant.grantId());
	}

	private Result coupon(String tenant, Instance instance, Definition definition, Node node, Instant at) {
		var coupon = coupons.grantFromJourney(tenant, instance.memberId(), definition.storeId(),
				JsonCodec.hash(instance.instanceId() + "/" + node.id()), node.coupon().definitionId(),
				node.coupon().version());
		mapper.effect(tenant, JsonCodec.hash("coupon/" + instance.instanceId() + "/" + node.id()), definition,
				instance.memberId(), "COUPON_GRANTED", at);
		return new Result("COUPON_GRANTED", coupon.couponId());
	}

	private Result notify(String tenant, Instance instance, Definition definition, Node node, Instant at) {
		boolean allowed = true;
		if (definition.controls() != null) {
			mapper.ensureCap(tenant, definition.journeyId(), instance.memberId());
			var cap = mapper.lockCap(tenant, definition.journeyId(), instance.memberId());
			long seconds = definition.controls().notificationWindowSeconds();
			long bucket = at.getEpochSecond() / seconds * seconds;
			int count = cap.notificationWindow() == bucket ? cap.notifications() : 0;
			allowed = count < definition.controls().notificationLimit();
			mapper.cap(tenant, definition.journeyId(), instance.memberId(), true, bucket,
					allowed ? count + 1 : count, !allowed);
		}
		String id = allowed ? UUID.randomUUID().toString() : null;
		if (allowed)
			mapper.notify(tenant, id, instance.instanceId(), node.id(), instance.memberId(), node.title(), node.body());
		String outcome = allowed ? "NOTIFIED" : "NOTIFY_SUPPRESSED";
		mapper.effect(tenant, JsonCodec.hash("notify/" + instance.instanceId() + "/" + node.id()), definition,
				instance.memberId(), outcome, at);
		return new Result(outcome, id);
	}

}
