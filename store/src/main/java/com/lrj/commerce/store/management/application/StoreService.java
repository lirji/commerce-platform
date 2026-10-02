package com.lrj.commerce.store.management.application;

import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.store.management.infrastructure.persistence.StoreMapper;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.List;
import com.lrj.commerce.merchant.api.MerchantApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;

/** Store用例负责权限与状态，SQL仅在本域Mapper。 */
@Service
public class StoreService implements StoreApi {

	private final StoreMapper mapper;

	private final Commands commands;

	private final EmployeeAccess access;

	private final MerchantApi merchants;

	public StoreService(StoreMapper mapper, Commands commands, MerchantApi merchants, EmployeeAccess access) {
		this.access = access;
		this.mapper = mapper;
		this.commands = commands;
		this.merchants = merchants;
	}

	/** 权威主数据只能通过管理用例创建，输入和审计同事务。 */
	public View create(Actor actor, String key, Create input) {
		var permit = access.scope(actor, STORE_CREATE);
		Inputs.require(input != null, "请求不能为空");
		Identifiers.require(input.storeId());
		Inputs.text(input.name(), 128);
		Object command = permit.identity() == null ? input : new Object[] { input, permit.identity() };
		return commands.runGuarded(actor, "store.create", key, command, View.class, () -> access.lock(permit), () -> {
			merchants.requireActive(actor, input.merchantId());
			mapper.insert(actor.tenantId(), input);
			access.audit(actor, permit, "store.create", key, input.storeId());
			return requireActive(actor, input.storeId());
		});
	}

	/** 缺失与非本租户统一拒绝，冻结资源不允许参与新交易。 */
	public View requireActive(Actor actor, String id) {
		Identifiers.require(id);
		var value = Inputs.found(mapper.find(actor.tenantId(), id));
		if (!value.status().equals("ACTIVE"))
			throw new DomainException(DomainException.Code.CONFLICT, "资源不可用");
		merchants.requireActive(actor, value.merchantId());
		return value;
	}

	/** 冻结门店仍承担历史订单责任，此端口不赋予新交易资格且没有 HTTP 入口。 */
	public View fact(Actor actor, String id) {
		Identifiers.require(id);
		return Inputs.found(mapper.find(actor.tenantId(), id));
	}

	/** Owner持有行锁直到库存命令提交，不能用旧版本许可写入变更后的归属。 */
	@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public void lockCurrent(Actor actor, View expected) {
		var current = mapper.lock(actor.tenantId(), expected.storeId());
		if (!expected.equals(current))
			throw new DomainException(DomainException.Code.CONFLICT, "门店事实已变化，请重新判权");
	}

	/** 查询同时校验管理权限和分页上限。 */
	public List<View> list(Actor actor, String after, int limit) {
		Inputs.page(after, limit);
		var before = access.scope(actor, STORE_DIRECTORY_READ);
		var rows = mapper.listScoped(actor.tenantId(), before.filter(), after, limit);
		EmployeeAccess.requireSame(before, access.scope(actor, STORE_DIRECTORY_READ));
		return rows;
	}

	/** 目录不泄露内部配置，认证租户始终参与SQL过滤。 */
	public List<View> browse(Actor actor, String after, int limit) {
		Inputs.page(after, limit);
		return mapper.list(actor.tenantId(), after, limit);
	}

}
