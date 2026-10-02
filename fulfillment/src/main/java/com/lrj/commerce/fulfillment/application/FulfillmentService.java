package com.lrj.commerce.fulfillment.application;

import com.lrj.commerce.fulfillment.api.*;
import com.lrj.commerce.fulfillment.application.port.WmsPort;
import com.lrj.commerce.fulfillment.infrastructure.persistence.FulfillmentMapper;
import com.lrj.commerce.ordering.order.api.OrderApi;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import com.lrj.commerce.runtime.api.event.EventHandler;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;

/** 履约与订单生命周期经端口同事务协作，远程证明在命令事务前取得。 */
@Service
public class FulfillmentService implements FulfillmentApi, EventHandler {

	/** 生产容器强制注入，每个员工入口使用独立能力；后台责任不依赖此门禁。 */
	@org.springframework.beans.factory.annotation.Autowired
	private EmployeeAccess access;

	private final FulfillmentMapper mapper;

	private final OrderApi orders;

	private final Commands commands;

	private final WmsPort wms;

	public FulfillmentService(FulfillmentMapper mapper, OrderApi orders, Commands commands, WmsPort wms) {
		this.mapper = mapper;
		this.orders = orders;
		this.commands = commands;
		this.wms = wms;
	}

	public View read(Actor actor, String order) {
		orders.read(actor, order);
		return Inputs.found(mapper.find(actor.tenantId(), order));
	}

	public List<View> list(Actor actor, String after, int limit) {
		Inputs.page(after, limit);
		var permit = access.scope(actor, FULFILLMENT_READ);
		var result = mapper.scopedList(actor.tenantId(), permit.filter(), after, limit);
		EmployeeAccess.requireSame(permit, access.scope(actor, FULFILLMENT_READ));
		return result;
	}

	/** 发货与售后阻拦争同一履约锁；单个包裹只允许一个不可覆盖运单号。 */
	public View ship(Actor actor, String key, String order, Ship input) {
		Identifiers.require(order);
		Inputs.require(input != null, "缺少发货信息");
		Inputs.text(input.trackingNo(), 64);
		var permit = orders.authorize(actor, FULFILLMENT_SHIP, order);
		var proof = wms.shipment(actor.tenantId(), order, input.trackingNo());
		return commands.runGuarded(actor, "fulfillment.ship", key,
				permit.permit().identity() == null ? Map.of("order", order, "input", input) : Arrays.asList(order, input, permit.permit().identity()), View.class,
				() -> orders.lockAuthorization(actor, permit), () -> {
			access.audit(actor, permit.permit(), "fulfillment.ship", key);
			ensurePaid(actor.tenantId(), order);
			var current = mapper.lock(actor.tenantId(), order);
			if (Set.of("SHIPPED", "DELIVERED").contains(current.status())) {
				if (!current.trackingNo().equals(proof.trackingNo()))
					throw conflict();
				return current;
			}
			if (mapper.ship(actor.tenantId(), order, proof.trackingNo(), proof.provider(), current.version()) != 1)
				throw conflict();
			orders.fulfillmentFact(actor.tenantId(), order, false);
			return mapper.find(actor.tenantId(), order);
		});
	}

	/** 已送达再次证明不重复推进订单。 */
	public View deliver(Actor actor, String key, String order) {
		Identifiers.require(order);
		var permit = orders.authorize(actor, FULFILLMENT_DELIVER, order);
		var row = Inputs.found(mapper.find(actor.tenantId(), order));
		wms.delivered(actor.tenantId(), order, row.trackingNo());
		return commands.runGuarded(actor, "fulfillment.deliver", key,
				permit.permit().identity() == null ? order : Arrays.asList(order, permit.permit().identity()), View.class,
				() -> orders.lockAuthorization(actor, permit), () -> {
			access.audit(actor, permit.permit(), "fulfillment.deliver", key);
			var current = Inputs.found(mapper.lock(actor.tenantId(), order));
			if (current.status().equals("DELIVERED"))
				return current;
			if (mapper.deliver(actor.tenantId(), order, current.version()) != 1)
				throw conflict();
			orders.fulfillmentFact(actor.tenantId(), order, true);
			return mapper.find(actor.tenantId(), order);
		});
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean holdForAftersale(String tenant, String order) {
		ensurePaid(tenant, order);
		var current = mapper.lock(tenant, order);
		if (current.blocked() || current.status().equals("CANCELLED"))
			throw conflict();
		if (mapper.block(tenant, order, true, current.status(), current.version()) != 1)
			throw conflict();
		return !current.status().equals("READY");
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void finishAftersale(String tenant, String order, boolean refunded) {
		var current = Inputs.found(mapper.lock(tenant, order));
		if (!current.blocked())
			throw conflict();
		String status = refunded && current.status().equals("READY") ? "CANCELLED" : current.status();
		if (mapper.block(tenant, order, false, status, current.version()) != 1)
			throw conflict();
	}

	public String consumer() {
		return "fulfillment-order-v1";
	}

	/** 重放分类见phase4重放安全矩阵。 */
	@Override
	public com.lrj.commerce.runtime.api.event.EventHandler.ReplaySafety replaySafety() {
		return com.lrj.commerce.runtime.api.event.EventHandler.ReplaySafety.notReplayable(
				"履约单主键ON DUPLICATE KEY使重复执行无效；但对历史已付订单会生成待发货履约单，进入发货队列",
				com.lrj.commerce.runtime.api.event.EventHandler.SideEffect.IDEMPOTENT_WRITE);
	}

	public Set<String> types() {
		return Set.of("order.paid.v1", "order.ready.v1");
	}

	/** 根据权威订单复核，迟到重复事件不能重建已取消履约。 */
	public void handle(Event event) {
		ensurePaid(event.tenantId(), event.aggregateId());
	}

	private void ensurePaid(String tenant, String order) {
		var view = orders.internalRead(tenant, order);
		if (!Set.of("PAID", "FULFILLING", "COMPLETED").contains(view.status()))
			throw conflict();
		mapper.ensure(tenant, order);
	}

	private DomainException conflict() {
		return new DomainException(DomainException.Code.CONFLICT, "履约当前状态不允许操作");
	}

}
