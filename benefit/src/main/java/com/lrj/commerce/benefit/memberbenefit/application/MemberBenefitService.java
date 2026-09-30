package com.lrj.commerce.benefit.memberbenefit.application;

import com.lrj.commerce.benefit.memberbenefit.infrastructure.persistence.MemberBenefitMapper;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.lrj.commerce.benefit.entitlement.api.EntitlementApi;
import com.lrj.commerce.benefit.memberbenefit.api.MemberBenefitApi;
import com.lrj.commerce.member.cycle.api.MemberCycleApi;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.runtime.api.event.EventHandler;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.serialization.JsonCodec;

/** 周期礼包在一笔本地事务中受理，实际权益到账复用可靠消费者。 */
@Service
public class MemberBenefitService implements MemberBenefitApi, EventHandler {

	private final MemberBenefitMapper mapper;

	private final MemberCycleApi cycles;

	private final MemberApi members;

	private final EntitlementApi entitlements;

	private final Commands commands;

	private final Clock clock;

	private final EmployeeAccess access;

	public MemberBenefitService(MemberBenefitMapper mapper, MemberCycleApi cycles, MemberApi members,
			EntitlementApi entitlements, Commands commands, Clock clock, EmployeeAccess access) {
		this.mapper = mapper;
		this.access = access;
		this.cycles = cycles;
		this.members = members;
		this.entitlements = entitlements;
		this.commands = commands;
		this.clock = clock;
	}

	/** 绑定前验证等级与权益有效窗口，避免可见配置无法发放。 */
	public Bundle publish(Actor actor, String key, Bundle input) {
		var permit = access.scope(actor, CYCLE_BENEFIT_DEFINE);
		Inputs.require(input != null && input.policyVersion() > 0 && input.validUntil() != null, "礼包策略或期限无效");
		Identifiers.require(input.bindingId());
		Identifiers.require(input.level());
		Identifiers.require(input.storeId());
		Inputs.require(input.benefits() != null && !input.benefits().isEmpty() && input.benefits().size() <= 8,
				"礼包需包含1至8项权益");
		var refs = new HashSet<EntitlementApi.Ref>();
		for (var ref : input.benefits()) {
			Inputs.require(ref != null && ref.version() > 0 && refs.add(ref), "权益引用重复或无效");
			Identifiers.require(ref.benefitId());
		}
		var bundle = new Bundle(input.bindingId(), input.policyVersion(), input.level(), input.storeId(),
				input.validUntil().truncatedTo(ChronoUnit.MILLIS), List.copyOf(input.benefits()));
		Object command = permit.identity() == null ? bundle : new Object[] { bundle, permit.identity() };
		return commands.runGuarded(actor, "member.benefit.bundle", key, command, Bundle.class, () -> access.lock(permit), () -> {
			var policy = cycles.policyForOperation(actor.tenantId(), bundle.policyVersion());
			Inputs.require(policy.levels().stream().anyMatch(level -> level.code().equals(bundle.level())),
					"周期策略中没有该等级");
			Inputs.require(
					bundle.validUntil().isAfter(policy.effectiveFrom()) && bundle.validUntil().isAfter(clock.instant()),
					"礼包发放期限无效");
			for (var ref : bundle.benefits())
				entitlements.validateBinding(actor.tenantId(), bundle.storeId(), ref, policy.effectiveFrom(),
						bundle.validUntil());
			mapper.insert(actor.tenantId(), bundle, JsonCodec.write(bundle));
			access.audit(actor, permit, "member.benefit.bundle", key, bundle.bindingId());
			return bundle;
		});
	}

	/** 历史礼包保留，不因失效隐藏运营事实。 */
	public List<Bundle> list(Actor actor, long policyVersion) {
		var permit = access.scope(actor, CYCLE_BENEFIT_READ);
		Inputs.require(policyVersion > 0, "策略版本无效");
		var result = mapper.list(actor.tenantId(), policyVersion)
			.stream()
			.map(value -> JsonCodec.read(value, Bundle.class))
			.toList();
		EmployeeAccess.requireSame(permit, access.scope(actor, CYCLE_BENEFIT_READ));
		return result;
	}

	/** 补发独立许可与真实Member事实，不能指定历史周期或隐式获得周期读取权。 */
	public Receipt grant(Actor actor, String key, String memberId) {
		Identifiers.require(memberId);
		var scope = access.scope(actor, CYCLE_BENEFIT_GRANT);
		var permit = access.resource(actor, scope, fact(members.requireActive(actor, memberId)));
		Object command = permit.scope().identity() == null ? memberId : new Object[] { memberId, permit.scope().identity() };
		return commands.runGuarded(actor, "member.benefit.grant", key, command, Receipt.class, () -> {
			access.lock(permit.scope());
			var owner = Inputs.found(members.lockForOperation(actor.tenantId(), memberId));
			if (!permit.fact().equals(fact(owner)))
				throw new DomainException(DomainException.Code.CONFLICT, "会员版本已变化，请刷新");
			requireActive(owner);
			access.lock(permit.scope());
		}, () -> {
			cycles.assess(actor.tenantId(), memberId);
			var result = award(actor.tenantId(), cycles.viewForOperation(actor.tenantId(), memberId));
			access.audit(actor, permit.scope(), "member.benefit.grant", key, memberId);
			return result;
		});
	}

	private static EmployeeAccess.ResourceFact fact(MemberApi.View member) {
		return new EmployeeAccess.ResourceFact(CYCLE_BENEFIT_GRANT.resourceType(), member.memberId(), member.version());
	}

	/** 内部履约持真实会员锁，沿原ACTIVE约束，不伪造员工ADMIN身份。 */
	private static void requireActive(MemberApi.View member) {
		if (!MemberApi.Status.ACTIVE.code().equals(member.status()))
			throw new DomainException(DomainException.Code.CONFLICT, "会员状态不可用");
	}

	public String consumer() {
		return "member-cycle-benefit-v1";
	}

	/** 重放分类见phase4重放安全矩阵。 */
	@Override
	public com.lrj.commerce.runtime.api.event.EventHandler.ReplaySafety replaySafety() {
		return com.lrj.commerce.runtime.api.event.EventHandler.ReplaySafety.notReplayable(
				"来源摘要唯一键与当前周期校验使重复执行无效；发放等级权益属权益副作用",
				com.lrj.commerce.runtime.api.event.EventHandler.SideEffect.IDEMPOTENT_WRITE,
				com.lrj.commerce.runtime.api.event.EventHandler.SideEffect.COMPENSATABLE_SIDE_EFFECT);
	}

	public Set<String> types() {
		return Set.of("member.cycle.assessed.v1");
	}

	/** 迟到事件以最新周期为准，旧周期权益不能靠重试复活。 */
	public void handle(Event event) {
		var signal = JsonCodec.read(event.payloadJson(), MemberCycleApi.Assessed.class);
		cycles.assess(event.tenantId(), signal.memberId());
		var current = cycles.viewForOperation(event.tenantId(), signal.memberId());
		if (!current.enabled() || current.policyVersion() != signal.policyVersion()
				|| !Objects.equals(current.cycleStart(), signal.cycleStart())
				|| !current.memberLevel().equals(signal.memberLevel()))
			return;
		// 非活跃会员拒绝授予，失败事件由既有重试/隔离机制保留供运营核查。
		requireActive(Inputs.found(members.lockForOperation(event.tenantId(), signal.memberId())));
		award(event.tenantId(), current);
	}

	private Receipt award(String tenant, MemberCycleApi.View cycle) {
		if (!cycle.enabled())
			return new Receipt(cycle.memberId(), List.of());
		String raw = mapper.find(tenant, cycle.policyVersion(), cycle.memberLevel());
		if (raw == null)
			return new Receipt(cycle.memberId(), List.of());
		var bundle = JsonCodec.read(raw, Bundle.class);
		if (!clock.instant().isBefore(bundle.validUntil()))
			return new Receipt(cycle.memberId(), List.of());
		var grants = new ArrayList<EntitlementApi.View>();
		for (var ref : bundle.benefits()) {
			String source = JsonCodec.hash(JsonCodec
				.write(List.of(cycle.memberId(), cycle.policyVersion(), cycle.cycleStart(), cycle.memberLevel(), ref)));
			grants.add(entitlements.grantFromLevel(tenant, cycle.memberId(), bundle.storeId(), source, ref));
		}
		return new Receipt(cycle.memberId(), List.copyOf(grants));
	}

}
