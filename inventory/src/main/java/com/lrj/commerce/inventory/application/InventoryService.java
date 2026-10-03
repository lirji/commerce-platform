package com.lrj.commerce.inventory.application;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.inventory.api.InventoryApi;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.inventory.infrastructure.persistence.InventoryMapper;
import com.lrj.commerce.catalog.assortment.api.CatalogApi;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.kernel.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;

/** 员工经营先实时判权；内部订单库存仍在同一事务内有条件扣减防止超卖。 */
@Service
public class InventoryService implements InventoryApi {

	private final InventoryMapper mapper;

	private final Commands commands;

	private final CatalogApi catalog;

	private final StoreApi stores;

	private final EmployeeAccess access;

	public InventoryService(InventoryMapper mapper, Commands commands, CatalogApi catalog, StoreApi stores, EmployeeAccess access) {
		this.access = access;
		this.mapper = mapper;
		this.commands = commands;
		this.catalog = catalog;
		this.stores = stores;
	}

	/** 收货不是前端直接改库存，使用受审计的增量命令。 */
	public Stock receive(Actor actor, String key, Receipt input) {
		Inputs.require(input != null && input.quantity() > 0 && input.quantity() <= 1000000, "入库数量无效");
		var store = centralStore(actor, input.storeId());
		var permit = access.require(actor, INVENTORY_RECEIVE, fact(store));
		Object command = permit.identity() == null ? input : new Object[] { input, permit.identity() };
		return commands.runGuarded(actor, "inventory.receive", key, command, Stock.class, () -> {
			access.lock(permit);
			if (store != null) stores.lockCurrent(actor, store);
			// Owner行锁可能等待，取得全部本地锁后再次核对五秒许可。
			access.lock(permit);
		}, () -> {
			stores.requireActive(actor, input.storeId());
			catalog.published(actor, input.storeId(), List.of(input.skuId()));
			access.audit(actor, permit, "inventory.receive", key);
			mapper.receive(actor.tenantId(), input);
			return mapper.find(actor.tenantId(), input.storeId(), input.skuId());
		});
	}

	/** 必填单门店，在SQL分页前完成该门店能力检查，读取后复核当前授权。 */
	public List<Stock> list(Actor actor, String storeId, String after, int limit) {
		return list(actor, storeId, after, limit, ListFilter.none());
	}

	/** 只读条件先筛选再分页，保持原用例的权限复核。 */
	public List<Stock> list(Actor actor, String storeId, String after, int limit, ListFilter filter) {
		filter.requireNoEnabled();
		filter.requireNoStatus();
		filter.requireNoTime();
		Identifiers.require(storeId);
		Inputs.page(after, limit);
		var store = centralStore(actor, storeId);
		var before = access.require(actor, INVENTORY_READ, fact(store));
		var rows = mapper.list(actor.tenantId(), storeId, after, limit, filter);
		var afterPermit = access.require(actor, INVENTORY_READ, fact(centralStore(actor, storeId)));
		if (!java.util.Objects.equals(before.route(), afterPermit.route())
				|| !java.util.Objects.equals(before.fact(), afterPermit.fact())
				|| !java.util.Objects.equals(before.identity(), afterPermit.identity()))
			throw new DomainException(DomainException.Code.FORBIDDEN, "库存授权上下文已变化");
		return rows;
	}

	/** 旧路径保留原资源行为；中央路径跨租户缺失不暴露资源存在性。 */
	private StoreApi.View centralStore(Actor actor, String storeId) {
		if (actor.executionId() == null) return null;
		if (actor.role() != Actor.Role.OPERATOR)
			throw new DomainException(DomainException.Code.FORBIDDEN, "需要中央运营身份");
		try { return stores.requireActive(actor, storeId); }
		catch (DomainException failure) {
			if (failure.code() == DomainException.Code.NOT_FOUND)
				throw new DomainException(DomainException.Code.FORBIDDEN, "没有该门店库存权限");
			throw failure;
		}
	}

	private EmployeeAccess.StoreFact fact(StoreApi.View store) {
		return store == null ? null : new EmployeeAccess.StoreFact(store.storeId(), store.version());
	}

	/** 订单用例已按SKU排序；一项不足抛出异常回滚此前所有项。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserve(Actor actor, String orderId, String storeId, String skuId, int quantity) {
		Identifiers.require(orderId);
		Identifiers.require(storeId);
		Identifiers.require(skuId);
		Inputs.require(quantity > 0 && quantity <= 10000, "预占数量无效");
		if (mapper.reserve(actor.tenantId(), storeId, skuId, quantity) != 1)
			throw new DomainException(DomainException.Code.CONFLICT, "可售库存不足");
		mapper.insertHold(actor.tenantId(), orderId, storeId, skuId, quantity);
	}

	/** 已确认保持幂等，已释放则不能确认。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void confirm(String tenant, String order) {
		finish(tenant, order, true);
	}

	/** 已释放保持幂等，已确认不能通过取消归还。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(String tenant, String order) {
		finish(tenant, order, false);
	}

	private void finish(String tenant, String order, boolean confirm) {
		for (var hold : mapper.holds(tenant, order)) {
			String target = confirm ? "CONFIRMED" : "RELEASED";
			if (hold.status().equals(target))
				continue;
			if (!hold.status().equals("RESERVED"))
				throw new DomainException(DomainException.Code.CONFLICT, "库存预占终态冲突");
			int changed = confirm ? mapper.confirmStock(tenant, hold) : mapper.releaseStock(tenant, hold);
			if (changed != 1 || mapper.terminal(tenant, order, hold.skuId(), target) != 1)
				throw new DomainException(DomainException.Code.CONFLICT, "库存并发状态冲突");
		}
	}

	/** 原预占行锁串行累计退货数量，退货台账和可售回补同事务。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void returnItems(String tenant, String order, String caseId, String sku, int quantity) {
		Inputs.require(quantity > 0 && quantity <= 10000, "退货数量无效");
		var hold = mapper.holds(tenant, order)
			.stream()
			.filter(h -> h.skuId().equals(sku))
			.findFirst()
			.orElseThrow(() -> new DomainException(DomainException.Code.NOT_FOUND, "原库存确认不存在"));
		Integer previous = mapper.returned(tenant, caseId, sku);
		if (previous != null) {
			if (previous != quantity)
				throw new DomainException(DomainException.Code.CONFLICT, "退货幂等数量冲突");
			return;
		}
		if (mapper.addReturned(tenant, order, sku, quantity) != 1
				|| mapper.restore(tenant, hold.storeId(), sku, quantity) != 1)
			throw new DomainException(DomainException.Code.CONFLICT, "累计退货超出已确认数量");
		mapper.recordReturn(tenant, caseId, sku, quantity);
	}

}
