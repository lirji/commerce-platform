package com.lrj.commerce.campaign.asset.application;

import com.lrj.commerce.campaign.asset.infrastructure.persistence.AssetMapper;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import com.lrj.commerce.campaign.asset.api.MarketingAssets;
import com.lrj.commerce.campaign.rule.api.RuleNode;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.serialization.JsonCodec;

/** 可信快照失效后资格未知；旧规则版本永不原位修改。 */
@Service
public class MarketingAssetService implements MarketingAssets {

	private final AssetMapper mapper;

	private final Commands commands;

	private final Clock clock;

	private final EmployeeAccess access;

	/** 规则与人群共用持久层，分别按独立能力族接管员工权限。 */
	public MarketingAssetService(AssetMapper mapper, Commands commands, Clock clock, EmployeeAccess access) {
		this.mapper = mapper;
		this.commands = commands;
		this.clock = clock;
		this.access = access;
	}

	/** 导入只取集合许可；成员编号来自快照，不把导入当成会员创建或成员有效性证明。 */
	public AudienceView createAudience(Actor actor, String key, Audience input) {
		var permit = access.scope(actor, AUDIENCE_CREATE);
		Inputs.require(input != null && input.memberIds() != null && input.memberIds().size() <= 500, "人群单批最多500会员");
		Identifiers.require(input.audienceId());
		Inputs.require(!input.audienceId().startsWith("dyn-"), "dyn-前缀保留给动态人群任务");
		Inputs.text(input.name(), 128);
		Inputs.text(input.source(), 128);
		Inputs.require(input.version() > 0 && input.watermark() != null && input.validUntil() != null, "人群版本或时间缺失");
		Inputs.require(
				!input.watermark().isAfter(clock.instant()) && input.validUntil().isAfter(input.watermark())
						&& Duration.between(input.watermark(), input.validUntil()).compareTo(Duration.ofHours(24)) <= 0,
				"人群快照新鲜度窗口无效");
		input.memberIds().forEach(Identifiers::require);
		Inputs.require(new HashSet<>(input.memberIds()).size() == input.memberIds().size(), "快照成员重复");
		// 保持旧模式输入摘要；中央只增加稳定主体代际，执行引用nonce不参与幂等摘要。
		Object command = permit.identity() == null ? input : new Object[] { input, permit.identity() };
		return commands.runGuarded(actor, "audience.create", key, command, AudienceView.class, () -> access.lock(permit), () -> {
			mapper.audience(actor.tenantId(), input, input.memberIds().size());
			if (!input.memberIds().isEmpty())
				mapper.members(actor.tenantId(), input);
			access.audit(actor, permit, "audience.create", key, input.audienceId());
			return mapper.audienceFind(actor.tenantId(), input.audienceId(), input.version());
		});
	}

	/** 最新版本摘要不携成员清单，返回前复核身份、路由与完整租户范围。 */
	public List<AudienceView> audiences(Actor actor, String after, int limit) {
		var permit = access.scope(actor, AUDIENCE_READ);
		Inputs.page(after, limit);
		var result = mapper.audiences(actor.tenantId(), after, limit);
		EmployeeAccess.requireSame(permit, access.scope(actor, AUDIENCE_READ));
		return result;
	}

	/** 一次批量读取所有固定版本，缺失版本拒绝而非默认命中。 */
	public List<Source> sources(String tenant, String member, List<Ref> refs, Instant now) {
		if (refs.isEmpty())
			return List.of();
		Inputs.require(refs.size() <= 100, "人群引用数量超限");
		var unique = refs.stream().distinct().toList();
		var rows = mapper.sources(tenant, member, unique);
		if (rows.size() != unique.size())
			throw new DomainException(DomainException.Code.CONFLICT, "活动引用人群版本缺失");
		return rows.stream()
			.map(r -> new Source(r.audienceId(), r.version(), r.source(), r.watermark(), r.validUntil(),
					now.isBefore(r.watermark()) || !now.isBefore(r.validUntil()) ? "UNKNOWN"
							: r.matched() ? "HIT" : "MISS"))
			.toList();
	}

	/** 固定版本需存在已发布头，不能暴露刷新中成员。 */
	public List<String> members(String tenant, Ref ref, String after, int limit) {
		Identifiers.require(tenant);
		Inputs.require(ref != null && ref.version() > 0, "人群引用无效");
		Identifiers.require(ref.id());
		Inputs.page(after, limit);
		Inputs.found(mapper.audienceFind(tenant, ref.id(), ref.version()));
		return mapper.audienceMembers(tenant, ref, after, limit);
	}

	public void requireFresh(String tenant, Ref ref, Instant now) {
		validate(ref);
		var row = Inputs.found(mapper.audienceFind(tenant, ref.id(), ref.version()));
		if (now.isBefore(row.watermark()) || !now.isBefore(row.validUntil()))
			throw new DomainException(DomainException.Code.CONFLICT, "发布引用人群快照已失效");
	}

	/** 创建只取集合许可；原规则树约束继续是权威，不能注入任意事实字段。 */
	public RuleView createRule(Actor actor, String key, Rule input) {
		var permit = access.scope(actor, RULE_CREATE);
		Inputs.require(input != null && input.rule() != null && input.version() > 0, "规则资产无效");
		Identifiers.require(input.ruleId());
		Inputs.text(input.name(), 128);
		input.rule().requireTrustedFields();
		// 中央只增加稳定身份，旧模式意图和重试摘要保持不变。
		Object command = permit.identity() == null ? input : new Object[] { input, permit.identity() };
		return commands.runGuarded(actor, "rule.create", key, command, RuleView.class, () -> access.lock(permit), () -> {
			mapper.rule(actor.tenantId(), input, JsonCodec.write(input.rule()));
			access.audit(actor, permit, "rule.create", key, input.ruleId());
			return view(mapper.ruleFind(actor.tenantId(), input.ruleId(), input.version()));
		});
	}

	/** 发布绑定实际资产版本；已发布版本按原语义返回成功，不人为新增状态或修订号。 */
	public RuleView publishRule(Actor actor, String key, String id, long version) {
		var scope = access.scope(actor, RULE_PUBLISH);
		Identifiers.require(id);
		Inputs.require(version > 0, "规则版本无效");
		var current = Inputs.found(mapper.ruleFind(actor.tenantId(), id, version));
		var permit = access.resource(actor, scope, fact(current));
		Object command = scope.identity() == null ? List.of(id, version) : List.of(id, version, scope.identity());
		return commands.runGuarded(actor, "rule.publish", key, command, RuleView.class, () -> lockPermit(actor, permit), () -> {
			var row = Inputs.found(mapper.ruleFind(actor.tenantId(), id, version));
			if (!row.status().equals("PUBLISHED") && mapper.publishRule(actor.tenantId(), id, version) != 1)
				throw new DomainException(DomainException.Code.CONFLICT, "规则版本状态冲突");
			access.audit(actor, permit.scope(), "rule.publish", key, id);
			return view(mapper.ruleFind(actor.tenantId(), id, version));
		});
	}

	/** 最新版本目录与可信字段独立于写权限，返回前复核完整租户范围。 */
	public List<RuleView> rules(Actor actor, String after, int limit) {
		var permit = access.scope(actor, RULE_READ);
		Inputs.page(after, limit);
		var result = mapper.rules(actor.tenantId(), after, limit).stream().map(this::view).toList();
		EmployeeAccess.requireSame(permit, access.scope(actor, RULE_READ));
		return result;
	}

	/** 字段列表虽是固定协议元数据，仍按已批准的rule.read边界返回。 */
	public Map<String, String> ruleFields(Actor actor) {
		var permit = access.scope(actor, RULE_READ);
		EmployeeAccess.requireSame(permit, access.scope(actor, RULE_READ));
		return TRUSTED_FIELDS;
	}

	/** 路由锁先于真实版本锁和旧回执；等待行锁后重新检查许可期限。 */
	private void lockPermit(Actor actor, EmployeeAccess.ResourcePermit permit) {
		access.lock(permit.scope());
		var row = Inputs.found(mapper.ruleLock(actor.tenantId(), permit.fact().id(), permit.fact().version()));
		if (!permit.fact().equals(fact(row))) throw new DomainException(DomainException.Code.CONFLICT, "规则版本事实变化");
		access.lock(permit.scope());
	}

	private static EmployeeAccess.ResourceFact fact(AssetMapper.RuleRow row) {
		return new EmployeeAccess.ResourceFact(RULE_READ.resourceType(), row.ruleId(), row.version());
	}

	/** 已发布固定版本仍供可信活动/交易引用，员工撤权不能撤销已经承诺的规则。 */
	public RuleNode publishedRule(String tenant, Ref ref) {
		validate(ref);
		var row = Inputs.found(mapper.ruleFind(tenant, ref.id(), ref.version()));
		if (!row.status().equals("PUBLISHED"))
			throw new DomainException(DomainException.Code.CONFLICT, "活动必须引用已发布规则");
		return JsonCodec.read(row.ruleJson(), RuleNode.class);
	}

	private void validate(Ref ref) {
		Inputs.require(ref != null && ref.version() > 0, "资产引用无效");
		Identifiers.require(ref.id());
	}

	private RuleView view(AssetMapper.RuleRow row) {
		return new RuleView(
				new Rule(row.ruleId(), row.version(), row.name(), JsonCodec.read(row.ruleJson(), RuleNode.class)),
				row.status());
	}

}
