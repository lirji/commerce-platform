package com.lrj.commerce.member.growth.application;

import com.lrj.commerce.member.growth.infrastructure.persistence.GrowthMapper;
import com.lrj.commerce.member.profile.infrastructure.persistence.MemberMapper;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.math.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import com.lrj.commerce.member.behavior.api.MemberBehaviorApi;
import com.lrj.commerce.member.cycle.api.MemberCycleApi;
import com.lrj.commerce.member.growth.api.MemberGrowthApi;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.event.Outbox;
import com.lrj.commerce.runtime.serialization.JsonCodec;

/** 以来源净贡献对账，重复/乱序完成和退款事实不会重复加减成长。 */
@Service
public class MemberGrowthService implements MemberGrowthApi {

	private final EmployeeAccess access;

	private final MemberBehaviorApi behavior;

	private final MemberCycleApi cycles;

	private final GrowthMapper mapper;

	private final MemberMapper members;

	private final Commands commands;

	private final Outbox outbox;

	private final Clock clock;

	public MemberGrowthService(GrowthMapper mapper, MemberMapper members, Commands commands, Outbox outbox, Clock clock,
			MemberCycleApi cycles, MemberBehaviorApi behavior, EmployeeAccess access) {
		this.access = access;
		this.behavior = behavior;
		this.cycles = cycles;
		this.mapper = mapper;
		this.members = members;
		this.commands = commands;
		this.outbox = outbox;
		this.clock = clock;
	}

	/** 不可变发布防止退款时使用新成长率，生效时间限定新订单。 */
	public Policy publish(Actor actor, String key, Policy input) {
		var permit = access.scope(actor, GROWTH_POLICY_PUBLISH);
		Inputs.require(input != null && input.version() > 0 && input.effectiveFrom() != null, "策略版本或时间无效");
		Inputs.require(input.levels() != null && !input.levels().isEmpty() && input.levels().size() <= 8, "等级数量需为1至8");
		Inputs.text(input.growthPerYuan(), 16);
		BigDecimal rate;
		try {
			rate = new BigDecimal(input.growthPerYuan()).setScale(2, RoundingMode.UNNECESSARY);
		}
		catch (ArithmeticException | NumberFormatException e) {
			throw new DomainException(DomainException.Code.INVALID_INPUT, "成长率最多两位小数");
		}
		Inputs.require(rate.signum() >= 0 && rate.compareTo(new BigDecimal("1000")) <= 0, "成长率超限");
		long prior = -1;
		var names = new HashSet<String>();
		for (var level : input.levels()) {
			Inputs.require(level != null, "等级不能为空");
			Identifiers.require(level.code());
			Inputs.require(level.minimumGrowth() > prior && level.minimumGrowth() <= 9000000000000000L
					&& names.add(level.code()), "门槛需递增且等级代码唯一");
			prior = level.minimumGrowth();
		}
		Inputs.require(input.levels().getFirst().minimumGrowth() == 0, "首档门槛必须为0");
		var normalized = new Policy(input.version(), input.effectiveFrom().truncatedTo(ChronoUnit.MILLIS),
				rate.toPlainString(), List.copyOf(input.levels()));
		Object command = permit.identity() == null ? normalized : new Object[] { normalized, permit.identity() };
		return commands.runGuarded(actor, "member.growth.policy", key, command, Policy.class, () -> access.lock(permit), () -> {
			Inputs.require(
					!normalized.effectiveFrom().isBefore(clock.instant().minusSeconds(60))
							&& !normalized.effectiveFrom().isAfter(clock.instant().plusSeconds(31536000)),
					"生效时间应为现在或未来一年内");
			mapper.policy(actor.tenantId(), normalized, JsonCodec.write(normalized));
			access.audit(actor, permit, "member.growth.policy", key, "growth-policy-" + normalized.version());
			return normalized;
		});
	}

	/** 配置保留版本供审计及回放。 */
	public List<Policy> policies(Actor actor, long after, int limit) {
		var before = access.scope(actor, GROWTH_POLICY_READ);
		page(after, limit);
		var result = mapper.policies(actor.tenantId(), after, limit).stream().map(this::policy).toList();
		EmployeeAccess.requireSame(before, access.scope(actor, GROWTH_POLICY_READ));
		return result;
	}

	/** 管理读取或本人读取，不允许会员替换memberId。 */
	public Wallet wallet(Actor actor, String id) {
		var permit = authorize(actor, id);
		var result = wallet(actor.tenantId(), id);
		recheck(actor, permit);
		return result;
	}

	/** 本人档案绑定来自数据库。 */
	public Wallet current(Actor actor) {
		return wallet(actor.tenantId(), Inputs.found(members.byActor(actor.tenantId(), actor.actorId())).memberId());
	}

	/** 账本不可修改，游标查询最多100项。 */
	public List<Entry> ledger(Actor actor, String id, long after, int limit) {
		var permit = authorize(actor, id);
		page(after, limit);
		var result = mapper.ledger(actor.tenantId(), id, after, limit);
		recheck(actor, permit);
		return result;
	}

	/** 人工校准独立来源，金额含义不伪装为订单支付。 */
	public Wallet adjust(Actor actor, String key, String id, Adjustment input) {
		var permit = permit(actor, GROWTH_ADJUST, id);
		Identifiers.require(id);
		Inputs.require(input != null && input.expectedVersion() >= 0 && input.delta() != 0
				&& input.delta() >= -1000000000L && input.delta() <= 1000000000L, "调整量或版本无效");
		Inputs.text(input.reason(), 256);
		Object command = permit.scope().identity() == null ? new Object[] { id, input } : new Object[] { id, input, permit.scope().identity() };
		return commands.runGuarded(actor, "member.growth.adjust", key, command, Wallet.class, () -> lockPermit(actor, permit), () -> {
			var member = lock(actor.tenantId(), id);
			Inputs.require(!member.status().equals("CLOSED"), "注销会员不可人工调整");
			var account = mapper.accountCurrent(actor.tenantId(), id);
			if (account.version() != input.expectedVersion())
				throw new DomainException(DomainException.Code.CONFLICT, "成长版本已变化");
			cycles.contribute(actor.tenantId(), id,
					"manual-" + JsonCodec.hash(actor.actorId() + ":" + key).substring(0, 48), clock.instant(),
					input.delta());
			apply(actor.tenantId(), member, account, input.delta(), new BigDecimal(account.netSpend()),
					"manual-" + JsonCodec.hash(key).substring(0, 32), 0, input.reason(), true);
			access.audit(actor, permit.scope(), "member.growth.adjust", key, id);
			return wallet(actor.tenantId(), id);
		});
	}

	/** 新门槛不隐含全库更新；运营可对明确会员重算等级。 */
	public Wallet recalculate(Actor actor, String key, String id) {
		var permit = permit(actor, GROWTH_RECALCULATE, id);
		Identifiers.require(id);
		Object command = permit.scope().identity() == null ? id : new Object[] { id, permit.scope().identity() };
		return commands.runGuarded(actor, "member.growth.recalculate", key, command, Wallet.class, () -> lockPermit(actor, permit), () -> {
			var member = lock(actor.tenantId(), id);
			var account = mapper.accountCurrent(actor.tenantId(), id);
			apply(actor.tenantId(), member, account, 0, new BigDecimal(account.netSpend()), "recalculate", 0,
					"按当前策略重算等级", false);
			access.audit(actor, permit.scope(), "member.growth.recalculate", key, id);
			return wallet(actor.tenantId(), id);
		});
	}

	/** 事件装配层提供权威订单事实；退款先于完成时只记录来源，完成后按净额入账。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void observe(String tenant, OrderFact fact) {
		Identifiers.require(tenant);
		Inputs.require(fact != null && fact.orderedAt() != null, "订单事实缺失");
		Identifiers.require(fact.orderId());
		Identifiers.require(fact.memberId());
		BigDecimal paid = new Money(new BigDecimal(fact.paid())).amount();
		var member = lock(tenant, fact.memberId());
		var source = mapper.source(tenant, fact.orderId());
		if (source == null) {
			var selected = policy(mapper.effective(tenant, fact.orderedAt()));
			mapper.insertSource(tenant, fact, selected == null ? 0 : selected.version(),
					selected == null ? "0.00" : selected.growthPerYuan());
			source = mapper.source(tenant, fact.orderId());
		}
		if (!source.memberId().equals(fact.memberId()) || new BigDecimal(source.paid()).compareTo(paid) != 0)
			throw new DomainException(DomainException.Code.CONFLICT, "订单来源事实不一致");
		if (fact.refundId() != null) {
			Identifiers.require(fact.refundId());
			BigDecimal amount = new Money(new BigDecimal(fact.refundAmount())).amount();
			Inputs.require(amount.signum() > 0, "退款事实金额无效");
			var old = mapper.refund(tenant, fact.refundId());
			if (old == null)
				mapper.insertRefund(tenant, fact);
			else if (!old.orderId().equals(fact.orderId()) || new BigDecimal(old.amount()).compareTo(amount) != 0)
				throw new DomainException(DomainException.Code.CONFLICT, "退款来源事实不一致");
		}
		BigDecimal refunded = new BigDecimal(mapper.refunds(tenant, fact.orderId()));
		Inputs.require(refunded.compareTo(paid) <= 0, "退款累计超过原实付");
		boolean completed = source.completed() || fact.completed();
		BigDecimal net = completed ? paid.subtract(refunded) : BigDecimal.ZERO;
		long contribution = net.multiply(new BigDecimal(source.growthRate()))
			.setScale(0, RoundingMode.DOWN)
			.longValueExact();
		long delta = contribution - source.contribution();
		var account = mapper.accountCurrent(tenant, fact.memberId());
		BigDecimal newNet = new BigDecimal(account.netSpend()).add(net.subtract(new BigDecimal(source.netSpend())));
		mapper.updateSource(tenant, fact.orderId(), completed, contribution, net.toPlainString());
		cycles.contribute(tenant, fact.memberId(), "order-" + fact.orderId(), fact.orderedAt(), contribution);
		if (delta != 0 || newNet.compareTo(new BigDecimal(account.netSpend())) != 0)
			apply(tenant, member, account, delta, newNet, fact.orderId(), source.policyVersion(),
					fact.refundId() == null ? "完成订单净消费成长" : "成功退款重算净成长", true);
		else
			cycles.assess(tenant, fact.memberId());
	}

	/** 规则所需数据从权威会员投影读取，不接收客户端自报标签。 */
	public Facts facts(String tenant, String id) {
		Identifiers.require(tenant);
		Identifiers.require(id);
		var member = Inputs.found(members.find(tenant, id));
		var account = mapper.account(tenant, id);
		return new Facts(id, member.memberLevel(), member.status(), account == null ? 0 : account.growth(),
				account == null ? "0.00" : account.netSpend(), mapper.tags(tenant, id),
				behavior.facts(tenant, List.of(id), clock.instant()).getFirst());
	}

	/** 单SQL批量投影，最多100会员及其64标签，避免分群时逐会员N+1。 */
	public List<Facts> scan(String tenant, String after, int limit, java.time.Instant before) {
		Identifiers.require(tenant);
		Inputs.page(after, limit);
		Inputs.require(before != null, "扫描截止时间缺失");
		var rows = mapper.scan(tenant, after, limit, before);
		var grouped = new LinkedHashMap<String, List<GrowthMapper.FactRow>>();
		for (var row : rows)
			grouped.computeIfAbsent(row.memberId(), ignored -> new ArrayList<>()).add(row);
		var behaviors = behavior.facts(tenant, new ArrayList<>(grouped.keySet()), clock.instant())
			.stream()
			.collect(java.util.stream.Collectors.toMap(MemberBehaviorApi.Facts::memberId, v -> v));
		return grouped.values().stream().map(group -> {
			var row = group.getFirst();
			return new Facts(row.memberId(), row.memberLevel(), row.status(), row.growth(), row.netSpend(),
					group.stream().map(GrowthMapper.FactRow::tagId).filter(Objects::nonNull).toList(),
					behaviors.get(row.memberId()));
		}).toList();
	}

	private MemberApi.View lock(String tenant, String id) {
		var member = Inputs.found(mapper.lockMember(tenant, id));
		mapper.ensureAccount(tenant, id);
		return member;
	}

	private void apply(String tenant, MemberApi.View member, GrowthMapper.Account account, long delta, BigDecimal net,
			String source, long sourcePolicy, String reason, boolean ledger) {
		long balance = Math.addExact(account.growth(), delta);
		Inputs.require(balance >= -9000000000000000L && balance <= 9000000000000000L, "成长余额超限");
		var active = policy(mapper.effective(tenant, clock.instant()));
		long policyVersion = active == null ? 0 : active.version();
		String level = member.memberLevel();
		if (active != null)
			for (var threshold : active.levels())
				if (Math.max(0, balance) >= threshold.minimumGrowth())
					level = threshold.code();
		if (mapper.accountChange(tenant, member.memberId(), balance, net.toPlainString(), policyVersion,
				account.version()) != 1)
			throw new DomainException(DomainException.Code.CONFLICT, "成长账本版本冲突");
		if (ledger)
			mapper.entry(tenant, member.memberId(), source, delta, balance, sourcePolicy, reason);
		if (cycles.assess(tenant, member.memberId()))
			return;
		if (!member.memberLevel().equals(level)) {
			mapper.level(tenant, member.memberId(), level);
			outbox.append(tenant, "member.level.changed.v1", member.memberId(), member.version() + 1,
					new LevelChanged(member.memberId(), member.memberLevel(), level, balance, policyVersion));
		}
	}

	private Wallet wallet(String tenant, String id) {
		var member = Inputs.found(members.find(tenant, id));
		var a = mapper.account(tenant, id);
		return new Wallet(id, a == null ? 0 : a.growth(), a == null ? "0.00" : a.netSpend(), member.memberLevel(),
				a == null ? 0 : a.policyVersion(), a == null ? 0 : a.version());
	}

	/** 客户本人读取仍按商城绑定；所有员工分支必须经过本族权威。 */
	private EmployeeAccess.ResourcePermit authorize(Actor actor, String id) {
		Identifiers.require(id);
		if (actor.role() != Actor.Role.MEMBER) return permit(actor, GROWTH_READ, id);
		if (!Inputs.found(members.byActor(actor.tenantId(), actor.actorId())).memberId().equals(id))
			throw new DomainException(DomainException.Code.FORBIDDEN, "不能读取其他会员成长");
		return null;
	}

	/** 会员归属和版本来自本域，账户请求参数不能替代Owner事实。 */
	private EmployeeAccess.ResourcePermit permit(Actor actor, EmployeeAccess.Capability capability, String id) {
		var scope = access.scope(actor, capability);
		Identifiers.require(id);
		return access.resource(actor, scope, fact(Inputs.found(members.find(actor.tenantId(), id))));
	}

	/** 员工读取返回前复核授权及会员版本，客户本人路径不借员工引用。 */
	private void recheck(Actor actor, EmployeeAccess.ResourcePermit permit) {
		if (permit == null) return;
		EmployeeAccess.requireSame(permit.scope(), access.scope(actor, GROWTH_READ));
		if (!permit.fact().equals(fact(Inputs.found(members.find(actor.tenantId(), permit.fact().id())))))
			throw new DomainException(DomainException.Code.CONFLICT, "会员版本已变化，请刷新");
	}

	/** 路由锁先于会员锁和旧回执；guard不创建账户，失败时没有额外业务效果。 */
	private void lockPermit(Actor actor, EmployeeAccess.ResourcePermit permit) {
		access.lock(permit.scope());
		if (!permit.fact().equals(fact(Inputs.found(members.lock(actor.tenantId(), permit.fact().id())))))
			throw new DomainException(DomainException.Code.CONFLICT, "会员版本已变化，请刷新");
		access.lock(permit.scope());
	}

	private static EmployeeAccess.ResourceFact fact(MemberApi.View member) {
		return new EmployeeAccess.ResourceFact(GROWTH_READ.resourceType(), member.memberId(), member.version());
	}

	private Policy policy(GrowthMapper.PolicyRow row) {
		return row == null ? null : JsonCodec.read(row.policyJson(), Policy.class);
	}

	private void page(long after, int limit) {
		Inputs.require(after >= 0, "游标无效");
		Inputs.page("", limit);
	}

}
