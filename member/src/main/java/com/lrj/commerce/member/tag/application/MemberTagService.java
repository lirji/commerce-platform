package com.lrj.commerce.member.tag.application;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.member.growth.infrastructure.persistence.GrowthMapper;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.List;
import com.lrj.commerce.member.tag.api.MemberTagApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.member.profile.infrastructure.persistence.MemberMapper;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;

/** 人工标签关联与原因进入命令审计；成员锁限制活跃标签数量并防止丢失更新。 */
@Service
public class MemberTagService implements MemberTagApi {

	private final GrowthMapper mapper;

	private final Commands commands;
	private final EmployeeAccess access;
	private final MemberMapper members;

	public MemberTagService(GrowthMapper mapper, Commands commands, EmployeeAccess access, MemberMapper members) {
		this.mapper = mapper;
		this.commands = commands;
		this.access = access;
		this.members = members;
	}

	/** 标签标识创建后稳定，供规则版本引用。 */
	public Definition create(Actor actor, String key, Definition input) {
		var permit = access.scope(actor, MEMBER_TAG_DEFINE);
		Inputs.require(input != null, "标签不能为空");
		Identifiers.require(input.tagId());
		Inputs.text(input.name(), 64);
		Object command = permit.identity() == null ? input : new Object[] { input, permit.identity() };
		return commands.runGuarded(actor, "member.tag.create", key, command, Definition.class, () -> access.lock(permit), () -> {
			mapper.tag(actor.tenantId(), input);
			access.audit(actor, permit, "member.tag.create", key, input.tagId());
			return input;
		});
	}

	/** 字典有界查询。 */
	public List<Definition> definitions(Actor actor, String after, int limit) {
		return definitions(actor, after, limit, ListFilter.none());
	}

	/** 只读条件先筛选再分页，保持原用例的权限复核。 */
	public List<Definition> definitions(Actor actor, String after, int limit, ListFilter filter) {
		filter.requireNoStatus();
		filter.requireNoEnabled();
		filter.requireNoTime();
		var before = access.scope(actor, MEMBER_TAG_READ);
		Inputs.page(after, limit);
		var result = mapper.tagDefinitions(actor.tenantId(), after, limit, filter);
		EmployeeAccess.requireSame(before, access.scope(actor, MEMBER_TAG_READ));
		return result;
	}

	/** 原因与赋值同事务，撤销保留关联和版本方便审计。 */
	public Assignment assign(Actor actor, String key, String id, Assign input) {
		var permit = permit(actor, MEMBER_TAG_ASSIGN, id);
		Identifiers.require(id);
		Inputs.require(input != null && input.expectedVersion() >= 0, "标签版本无效");
		Identifiers.require(input.tagId());
		Inputs.text(input.reason(), 256);
		Object command = permit.scope().identity() == null ? new Object[] { id, input } : new Object[] { id, input, permit.scope().identity() };
		return commands.runGuarded(actor, "member.tag.assign", key, command, Assignment.class, () -> lockPermit(actor, permit), () -> {
			var member = Inputs.found(mapper.lockMember(actor.tenantId(), id));
			Inputs.require(!member.status().equals("CLOSED"), "注销会员不能变更标签");
			Inputs.found(mapper.tagDefinition(actor.tenantId(), input.tagId()));
			var old = mapper.assignment(actor.tenantId(), id, input.tagId());
			long version = old == null ? 0 : old.version();
			if (version != input.expectedVersion())
				throw new DomainException(DomainException.Code.CONFLICT, "标签关联版本已变化");
			if (input.active() && (old == null || !old.active()))
				Inputs.require(mapper.tags(actor.tenantId(), id).size() < 64, "单会员活跃标签最多64个");
			mapper.assign(actor.tenantId(), id, input);
			access.audit(actor, permit.scope(), "member.tag.assign", key, id);
			return mapper.assignment(actor.tenantId(), id, input.tagId());
		});
	}

	/** 员工读取真实会员的标签关联，冻结状态不阻止合法审查。 */
	public List<Assignment> assignments(Actor actor, String id, String after, int limit) {
		var permit = permit(actor, MEMBER_TAG_READ, id);
		Identifiers.require(id);
		Inputs.page(after, limit);/* 列表不使用requireActive，冻结后仍可审查标签。 */
		var result = mapper.assignments(actor.tenantId(), id, after, limit);
		EmployeeAccess.requireSame(permit.scope(), access.scope(actor, MEMBER_TAG_READ));
		if (!permit.fact().equals(fact(Inputs.found(members.find(actor.tenantId(), id)))))
			throw new DomainException(DomainException.Code.CONFLICT, "会员版本已变化，请刷新");
		return result;
	}

	/** 范围资格先于目标读取，标签编号不能代替实际会员Owner事实。 */
	private EmployeeAccess.ResourcePermit permit(Actor actor, EmployeeAccess.Capability capability, String id) {
		var scope = access.scope(actor, capability);
		Identifiers.require(id);
		return access.resource(actor, scope, fact(Inputs.found(members.find(actor.tenantId(), id))));
	}

	/** 路由/会员锁在旧回执之前，guard无业务写入，失败不会产生标签关联。 */
	private void lockPermit(Actor actor, EmployeeAccess.ResourcePermit permit) {
		access.lock(permit.scope());
		if (!permit.fact().equals(fact(Inputs.found(members.lock(actor.tenantId(), permit.fact().id())))))
			throw new DomainException(DomainException.Code.CONFLICT, "会员版本已变化，请刷新");
		access.lock(permit.scope());
	}

	private static EmployeeAccess.ResourceFact fact(MemberApi.View member) {
		return new EmployeeAccess.ResourceFact(MEMBER_TAG_READ.resourceType(), member.memberId(), member.version());
	}

}
