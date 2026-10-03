package com.lrj.commerce.member.profile.application;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.member.profile.infrastructure.persistence.MemberMapper;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.List;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;

/** Member用例负责权限与状态，SQL仅在本域Mapper。 */
@Service
public class MemberService implements MemberApi {

	/** 封闭变更动作对应独立能力，未知字符串不能退化为资料修改。 */
	private enum ChangeKind {
		PROFILE("PROFILE", MEMBER_PROFILE_UPDATE), STATUS("STATUS", MEMBER_STATUS_UPDATE);
		final String code;
		final EmployeeAccess.Capability capability;
		ChangeKind(String code, EmployeeAccess.Capability capability) { this.code = code; this.capability = capability; }
		static ChangeKind parse(String value) {
			for (var kind : values()) if (kind.code.equals(value)) return kind;
			throw new DomainException(DomainException.Code.INVALID_INPUT, "变更类型无效");
		}
	}

	private final EmployeeAccess access;

	private final MemberMapper mapper;

	private final Commands commands;

	private final com.lrj.commerce.runtime.event.Outbox outbox;

	public MemberService(MemberMapper mapper, Commands commands, com.lrj.commerce.runtime.event.Outbox outbox, EmployeeAccess access) {
		this.access = access;
		this.mapper = mapper;
		this.commands = commands;
		this.outbox = outbox;
	}

	/** 会员属于租户而非单一门店，总览明确此口径。 */
	public Stats stats(Actor actor) {
		var before = access.scope(actor, MEMBER_READ);
		var result = mapper.stats(actor.tenantId());
		EmployeeAccess.requireSame(before, access.scope(actor, MEMBER_READ));
		return result;
	}

	/** 变更与审计共用事务；乐观锁避免不同运营覆盖彼此决定。 */
	public View change(Actor actor, String key, String id, String action, Change input) {
		var kind = ChangeKind.parse(action);
		var scope = access.scope(actor, kind.capability);
		Identifiers.require(id);
		Inputs.require(input != null && input.expectedVersion() >= 0, "变更版本无效");
		Inputs.text(input.reason(), 256);
		Inputs.text(input.value(), 128);
		var permit = access.resource(actor, scope, fact(Inputs.found(mapper.find(actor.tenantId(), id))));
		String operation = "member." + kind.code.toLowerCase(java.util.Locale.ROOT);
		Object command = scope.identity() == null ? new Object[] { id, input } : new Object[] { id, input, scope.identity() };
		return commands.runGuarded(actor, operation, key, command, View.class, () -> lock(actor, permit), () -> {
					var current = Inputs.found(mapper.find(actor.tenantId(), id));
					if (current.version() != input.expectedVersion() || current.status().equals("CLOSED"))
						throw new DomainException(DomainException.Code.CONFLICT, "会员版本已变化或已注销，请刷新");
					String before = current.displayName();
					if (kind == ChangeKind.STATUS) {
						before = current.status();
						Inputs.require(java.util.Set.of("ACTIVE", "FROZEN", "CLOSED").contains(input.value()), "状态无效");
						if (before.equals(input.value()))
							throw new DomainException(DomainException.Code.ILLEGAL_TRANSITION, "会员已处于目标状态");
					}
					if (mapper.change(actor.tenantId(), id, kind.code, input) != 1)
						throw new DomainException(DomainException.Code.CONFLICT, "会员已被其他操作更新");
					mapper.history(actor.tenantId(), id, kind.code, before, input, actor.actorId());
					access.audit(actor, permit.scope(), operation, key, id);
					return mapper.find(actor.tenantId(), id);
				});
	}

	/** 会员生命周期审计不向普通会员或其他租户暴露。 */
	public List<History> history(Actor actor, String id, long after, int limit) {
		var scope = access.scope(actor, MEMBER_READ);
		Identifiers.require(id);
		Inputs.require(after >= 0, "游标无效");
		Inputs.page("", limit);
		var permit = access.resource(actor, scope, fact(Inputs.found(mapper.find(actor.tenantId(), id))));
		var rows = mapper.changes(actor.tenantId(), id, after, limit);
		EmployeeAccess.requireSame(permit.scope(), access.scope(actor, MEMBER_READ));
		if (!permit.fact().equals(fact(Inputs.found(mapper.find(actor.tenantId(), id)))))
			throw new DomainException(DomainException.Code.CONFLICT, "会员版本已变化，请刷新");
		return rows;
	}

	/** 权威主数据只能通过管理用例创建，输入和审计同事务。 */
	public View create(Actor actor, String key, Create input) {
		var permit = access.scope(actor, MEMBER_CREATE);
		Inputs.require(input != null, "请求不能为空");
		Identifiers.require(input.memberId());
		Inputs.text(input.displayName(), 128);
		Object command = permit.identity() == null ? input : new Object[] { input, permit.identity() };
		return commands.runGuarded(actor, "member.create", key, command, View.class, () -> access.lock(permit), () -> {
			Identifiers.require(input.actorId());
			Inputs.text(input.memberLevel(), 64);
			mapper.insert(actor.tenantId(), input);
			access.audit(actor, permit, "member.create", key, input.memberId());
			outbox.append(actor.tenantId(), "member.registered.v1", input.memberId(), 0,
					new com.lrj.commerce.member.growth.api.MemberGrowthApi.Registered(input.memberId()));
			return requireActive(actor, input.memberId());
		});
	}

	/** 内部锁读不伪装管理员HTTP；调用者必须已有本地事务与目标租户。 */
	@org.springframework.transaction.annotation.Transactional(
			propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public View lockForOperation(String tenant, String id) {
		Identifiers.require(tenant);
		Identifiers.require(id);
		return mapper.lock(tenant, id);
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
		var before = access.scope(actor, MEMBER_READ);
		Inputs.page(after, limit);
		var rows = mapper.list(actor.tenantId(), after, limit, filter);
		EmployeeAccess.requireSame(before, access.scope(actor, MEMBER_READ));
		return rows;
	}

	/** 锁顺序为路由再会员，等待Owner锁后重核准入截止；旧回执也不能跳过。 */
	private void lock(Actor actor, EmployeeAccess.ResourcePermit permit) {
		access.lock(permit.scope());
		var current = Inputs.found(mapper.lock(actor.tenantId(), permit.fact().id()));
		if (!permit.fact().equals(fact(current)))
			throw new DomainException(DomainException.Code.CONFLICT, "会员版本已变化，请刷新");
		access.lock(permit.scope());
	}

	/** 类型由会员Owner固定，浏览器字段不能用作授权归属。 */
	private static EmployeeAccess.ResourceFact fact(View member) {
		return new EmployeeAccess.ResourceFact(MEMBER_READ.resourceType(), member.memberId(), member.version());
	}

	/** 会员身份取自数据库绑定，不相信请求中的memberId。 */
	public View current(Actor actor) {
		return Inputs.found(mapper.byActor(actor.tenantId(), actor.actorId()));
	}

}
