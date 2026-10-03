package com.lrj.commerce.merchant.application;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.merchant.api.MerchantApi;
import com.lrj.commerce.merchant.infrastructure.persistence.MerchantMapper;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.List;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;

/** Merchant用例负责权限与状态，SQL仅在本域Mapper。 */
@Service
public class MerchantService implements MerchantApi {

	private final MerchantMapper mapper;

	private final Commands commands;

	private final EmployeeAccess access;

	public MerchantService(MerchantMapper mapper, Commands commands, EmployeeAccess access) {
		this.access = access;
		this.mapper = mapper;
		this.commands = commands;
	}

	/** 权威主数据只能通过管理用例创建，输入和审计同事务。 */
	public View create(Actor actor, String key, Create input) {
		var permit = access.scope(actor, MERCHANT_CREATE);
		Inputs.require(input != null, "请求不能为空");
		Identifiers.require(input.merchantId());
		Inputs.text(input.name(), 128);
		Object command = permit.identity() == null ? input : new Object[] { input, permit.identity() };
		return commands.runGuarded(actor, "merchant.create", key, command, View.class, () -> access.lock(permit), () -> {

			mapper.insert(actor.tenantId(), input);
			access.audit(actor, permit, "merchant.create", key, input.merchantId());
			return requireActive(actor, input.merchantId());
		});
	}

	/** 缺失与非本租户统一拒绝，冻结资源不允许参与新交易。 */
	public View requireActive(Actor actor, String id) {
		Identifiers.require(id);
		var value = Inputs.found(mapper.find(actor.tenantId(), id));
		if (!value.status().equals("ACTIVE"))
			throw new DomainException(DomainException.Code.CONFLICT, "资源不可用");
		return value;
	}

	/** 查询同时校验管理权限和分页上限。 */
	public List<View> list(Actor actor, String after, int limit) {
		return list(actor, after, limit, ListFilter.none());
	}

	/** 筛选在数据库分页前执行，沿用本用例的身份与权限复核。 */
	public List<View> list(Actor actor, String after, int limit, ListFilter filter) {
		filter.requireNoEnabled();
		filter.requireNoTime();
		Inputs.page(after, limit);
		var before = access.scope(actor, MERCHANT_READ);
		if (before.filter().paths().stream().anyMatch(p -> !p.stores().isEmpty()))
			throw new DomainException(DomainException.Code.FORBIDDEN, "商家范围不能包含门店条件");
		var rows = mapper.listScoped(actor.tenantId(), before.filter(), after, limit, filter);
		EmployeeAccess.requireSame(before, access.scope(actor, MERCHANT_READ));
		return rows;
	}

}
