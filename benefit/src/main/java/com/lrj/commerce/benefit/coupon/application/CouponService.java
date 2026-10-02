package com.lrj.commerce.benefit.coupon.application;

import com.lrj.commerce.benefit.coupon.api.CouponApi;
import com.lrj.commerce.benefit.coupon.infrastructure.persistence.CouponMapper;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;

/** 券发行、使用与订单锁协作；失败时随订单事务整体回滚。 */
@Service
public class CouponService implements CouponApi {

	private final CouponMapper mapper;

	private final MemberApi members;

	private final StoreApi stores;

	private final Commands commands;

	private final Clock clock;

	private final EmployeeAccess access;

	public CouponService(CouponMapper mapper, MemberApi members, StoreApi stores, Commands commands, Clock clock, EmployeeAccess access) {
		this.mapper = mapper;
		this.members = members;
		this.stores = stores;
		this.commands = commands;
		this.clock = clock;
		this.access = access;
	}

	/** 定义版本创建后不可修改，配额在领取事务中扣减。 */
	public DefinitionView create(Actor actor, String key, Definition input) {
		return prepareDefinition(actor, key, input).execute();
	}

	/** 组合页面不得在持有页面/业务锁时远程判权；冻结深层输入与真实门店版本。 */
	public PreparedCreation prepareDefinition(Actor actor, String key, Definition requested) {
		Inputs.require(requested != null, "请求不能为空");
		final var input = com.lrj.commerce.runtime.serialization.JsonCodec.read(
				com.lrj.commerce.runtime.serialization.JsonCodec.write(requested), Definition.class);
		var permit = access.scope(actor, COUPON_DEFINITION_CREATE);
		Inputs.require(input != null && input.version() > 0 && input.quota() > 0 && input.quota() <= 1000000
				&& input.validFrom() != null && input.validTo() != null && input.validFrom().isBefore(input.validTo()),
				"券定义无效");
		Inputs.require(input.platformFundingBps() == null
				|| (input.platformFundingBps() >= 0 && input.platformFundingBps() <= 10000), "券资方比例无效");
		Inputs.require(input.validityDays() == null || (input.validityDays() >= 0 && input.validityDays() <= 366),
				"相对券有效天数无效");
		Inputs.require(input.issuanceMode() == null || Set.of("PUBLIC", "SOURCE_ONLY").contains(input.issuanceMode()),
				"券发行方式无效");
		Identifiers.require(input.definitionId());
		Inputs.text(input.name(), 128);
		money(input.minimumSpend());
		Inputs.require(money(input.discountAmount()).compareTo(Money.ZERO) > 0, "券金额必须大于零");
		// 保留旧模式原始输入摘要；中央主体代际稳定绑定，临时执行引用不影响原键重试。
		Object command = permit.identity() == null ? input : new Object[] { input, permit.identity() };
		// 预读只选历史方向，最终仍由当前身份栅栏和原命令锁决定，绝不事务外返回回执。
		var completed = completedCreation(actor, key, command, permit);
		if (completed != null) return completed;
		final StoreApi.View preparedStore;
		try {
			preparedStore = stores.requireActive(actor, input.storeId());
		} catch (DomainException unavailableStore) {
			// 另一请求可在预读后完成并冻结/删除门店；只对业务资源失败补读一次，不吞权限或系统异常。
			if (unavailableStore.code() != DomainException.Code.CONFLICT
					&& unavailableStore.code() != DomainException.Code.NOT_FOUND) throw unavailableStore;
			var concurrentReceipt = completedCreation(actor, key, command, permit);
			if (concurrentReceipt == null) throw unavailableStore;
			return concurrentReceipt;
		}
		return () -> commands.runGuarded(actor, "coupon.definition", key, command, DefinitionView.class,
				() -> access.lock(permit), () -> {
			// 只在首次效果执行时核对已冻结的 ACTIVE 门店；并发先完成的原回执不重复验证新交易前提。
			stores.lockCurrent(actor, preparedStore);

			mapper.definition(actor.tenantId(), input);
			access.audit(actor, permit, "coupon.definition", key, input.definitionId());
			return definition(mapper.definitionFind(actor.tenantId(), input.definitionId(), input.version()));
		});
	}

	/** 历史回执不要求旧门店仍可参与新交易；回执并发清理时禁止降级为新的实际创建。 */
	private PreparedCreation completedCreation(Actor actor, String key, Object command, EmployeeAccess.ScopePermit permit) {
		if (commands.completedReceipt(actor, "coupon.definition", key, command, DefinitionView.class) == null) return null;
		return () -> commands.runGuarded(actor, "coupon.definition", key, command, DefinitionView.class,
				() -> access.lock(permit), () -> {
					throw new DomainException(DomainException.Code.CONFLICT, "原命令回执已清理，请核验原意图");
				});
	}

	/** 消费者可见本店定义，但领取仍需服务端资格校验。 */
	public List<DefinitionView> definitions(Actor actor, String store, String after, int limit) {
		// 共享客户目录的非会员同样执行员工门禁，接管后旧ADMIN不能从别名入口旁路。
		boolean customer = actor.role() == Actor.Role.MEMBER;
		var permit = customer ? null : access.scope(actor, COUPON_DEFINITION_READ);
		stores.requireActive(actor, store);
		Inputs.page(after, limit);
		var result = mapper.definitions(actor.tenantId(), store, after, limit, !customer)
			.stream()
			.map(this::definition)
			.toList();
		if (permit != null) EmployeeAccess.requireSame(permit, access.scope(actor, COUPON_DEFINITION_READ));
		return result;
	}

	/** 锁定义序列化配额，当前读检查是否已领，重试不能多占额度。 */
	public Coupon claim(Actor actor, String key, String id, long version) {
		Identifiers.require(id);
		Inputs.require(version > 0, "券版本无效");
		return commands.run(actor, "coupon.claim", key, List.of(id, version), Coupon.class, () -> {
			var member = members.current(actor);
			members.requireActive(actor, member.memberId());
			var definition = Inputs.found(mapper.definitionLock(actor.tenantId(), id, version));
			stores.requireActive(actor, definition.storeId());
			if (!definition.issuanceMode().equals("PUBLIC"))
				throw conflict("此券只能通过受控活动获得");
			var existing = mapper.existing(actor.tenantId(), member.memberId(), id, version);
			if (existing != null)
				return existing;
			if (clock.instant().isBefore(definition.validFrom()) || !clock.instant().isBefore(definition.validTo())
					|| mapper.issue(actor.tenantId(), id, version) != 1)
				throw conflict("券未生效、已过期或配额不足");
			String coupon = UUID.randomUUID().toString();
			var validity = validity(definition);
			mapper.coupon(actor.tenantId(), member.memberId(), coupon, definition, validity.from(), validity.to());
			return mapper.find(actor.tenantId(), member.memberId(), coupon);
		});
	}

	/** 兑换目录只绑定受控券，且发行窗口必须完整覆盖兑换窗口。 */
	public void validateExchange(String tenant, String store, String id, long version, java.time.Instant from,
			java.time.Instant to) {
		var d = Inputs.found(mapper.definitionFind(tenant, id, version));
		Inputs.require(d.issuanceMode().equals("SOURCE_ONLY") && d.storeId().equals(store)
				&& !from.isBefore(d.validFrom()) && !to.isAfter(d.validTo()), "券需为同店受控券，且发行窗口覆盖发放期间");
	}

	/** 来源与发行配额同事务，重复来源绝不再发券。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Coupon grantFromPoints(String tenant, String member, String store, String source, String id, long version) {
		return grantSource(tenant, member, store, source, id, version, "POINTS");
	}

	/** 批次发券保持独立来源，不能与积分兑换来源混用。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Coupon grantTargeted(String tenant, String member, String store, String source, String id, long version) {
		return grantSource(tenant, member, store, source, id, version, "TARGETED");
	}

	/** 旅程来源不能冒用公开领取或定向批次标识。 */
	@org.springframework.transaction.annotation.Transactional(
			propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public Coupon grantFromJourney(String tenant, String member, String store, String source, String id, long version) {
		return grantSource(tenant, member, store, source, id, version, "JOURNEY");
	}

	/** 活动券在支付后才进入钱包，因此必须使用相对有效期，避免已预留订单过期失约。 */
	public void validateCampaignBinding(String tenant, String store, Ref ref, Instant from, Instant to) {
		Inputs.require(ref != null && ref.version() > 0, "活动券引用无效");
		validateExchange(tenant, store, ref.definitionId(), ref.version(), from, to);
		var definition = Inputs.found(mapper.definitionFind(tenant, ref.definitionId(), ref.version()));
		Inputs.require(definition.validityDays() != null && definition.validityDays() > 0,
				"活动券必须配置领取后相对有效期");
	}

	/** 券定义行锁和额度条件更新与订单事务共提交；重复订单只能拿到原券 ID。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public CampaignHold reserveCampaign(String tenant, String order, String member, String store, Ref ref) {
		if (ref == null)
			return null;
		Identifiers.require(tenant);
		Identifiers.require(order);
		Identifiers.require(member);
		Identifiers.require(store);
		Identifiers.require(ref.definitionId());
		Inputs.require(ref.version() > 0, "活动券版本无效");
		var definition = Inputs.found(mapper.definitionLock(tenant, ref.definitionId(), ref.version()));
		var old = mapper.campaignHold(tenant, order);
		if (old != null) {
			if (!old.memberId().equals(member) || !old.storeId().equals(store)
					|| !old.definitionId().equals(ref.definitionId()) || old.version() != ref.version())
				throw conflict("活动券订单来源冲突");
			return old;
		}
		if (!definition.storeId().equals(store) || !definition.issuanceMode().equals("SOURCE_ONLY")
				|| definition.validityDays() == null || definition.validityDays() <= 0
				|| clock.instant().isBefore(definition.validFrom()) || !clock.instant().isBefore(definition.validTo())
				|| mapper.reserveCampaignQuota(tenant, ref.definitionId(), ref.version()) != 1)
			throw conflict("活动券未生效或发行额度不足");
		mapper.insertCampaignHold(tenant, order, member, store, ref, UUID.randomUUID().toString());
		return Inputs.found(mapper.campaignHold(tenant, order));
	}

	/** 付款与发券在同一事务：扣预留、计已发、写钱包和状态任一步失败均回滚。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void confirmCampaign(String tenant, String order) {
		var hold = mapper.lockCampaignHold(tenant, order);
		if (hold == null || hold.status().equals("ISSUED"))
			return;
		if (!hold.status().equals("HELD"))
			throw conflict("活动券预留已释放");
		var definition = Inputs.found(mapper.definitionLock(tenant, hold.definitionId(), hold.version()));
		if (mapper.issueCampaignQuota(tenant, hold.definitionId(), hold.version()) != 1)
			throw conflict("活动券预留额度冲突");
		var validity = validity(definition);
		mapper.sourceCoupon(tenant, hold.memberId(), hold.couponId(), order, "CAMPAIGN", definition,
				validity.from(), validity.to());
		if (mapper.campaignHoldStatus(tenant, order, "HELD", "ISSUED") != 1)
			throw conflict("活动券发放状态冲突");
	}

	/** 取消只退还未发行额度；支付后的退款不暗中撤销已到账券。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseCampaign(String tenant, String order) {
		var hold = mapper.lockCampaignHold(tenant, order);
		if (hold == null || hold.status().equals("RELEASED"))
			return;
		if (!hold.status().equals("HELD")
				|| mapper.releaseCampaignQuota(tenant, hold.definitionId(), hold.version()) != 1
				|| mapper.campaignHoldStatus(tenant, order, "HELD", "RELEASED") != 1)
			throw conflict("活动券释放状态冲突");
	}

	/** 营销执行只引用固定来源和券 ID，不复制券余额权威。 */
	public CampaignHold campaignHold(String tenant, String order) {
		Identifiers.require(tenant);
		Identifiers.require(order);
		return mapper.campaignHold(tenant, order);
	}

	private Coupon grantSource(String tenant, String member, String store, String source, String id, long version,
			String type) {
		Identifiers.require(source);
		var d = Inputs.found(mapper.definitionLock(tenant, id, version));
		var old = mapper.bySource(tenant, source, type);
		if (old != null) {
			if (!old.memberId().equals(member) || !old.definitionId().equals(id) || old.version() != version)
				throw conflict("券兑换来源冲突");
			return old;
		}
		if (!d.storeId().equals(store) || !d.issuanceMode().equals("SOURCE_ONLY")
				|| clock.instant().isBefore(d.validFrom()) || !clock.instant().isBefore(d.validTo())
				|| mapper.issue(tenant, id, version) != 1)
			throw conflict("兑换券状态、有效期或额度不足");
		var coupon = UUID.randomUUID().toString();
		var validity = validity(d);
		mapper.sourceCoupon(tenant, member, coupon, source, type, d, validity.from(), validity.to());
		return mapper.find(tenant, member, coupon);
	}

	/** 已占用或核销的券不强行撤回，避免破坏在途交易承诺。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public String revokeTargeted(String tenant, String member, String coupon, String source) {
		var current = Inputs.found(mapper.bySource(tenant, source, "TARGETED"));
		if (!current.couponId().equals(coupon) || !current.memberId().equals(member))
			throw conflict("撤销券来源不一致");
		if (current.status().equals("REVOKED"))
			return "REVOKED";
		if (!current.status().equals("AVAILABLE") || !clock.instant().isBefore(current.validTo()))
			return "COUPON_" + (clock.instant().isBefore(current.validTo()) ? current.status() : "EXPIRED");
		if (mapper.revoke(tenant, coupon) != 1)
			throw conflict("券撤销状态已变化");
		return "REVOKED";
	}

	private record Validity(java.time.Instant from, java.time.Instant to) {
	}

	/** 一次读取Clock，起止共享毫秒精度，避免有效期出现请求内漂移。 */
	private Validity validity(CouponMapper.DefinitionRow d) {
		var at = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		return d.validityDays() != null && d.validityDays() > 0
				? new Validity(at, at.plus(java.time.Duration.ofDays(d.validityDays())))
				: new Validity(d.validFrom(), d.validTo());
	}

	public List<Coupon> wallet(Actor actor, String after, int limit) {
		Inputs.page(after, limit);
		return mapper.wallet(actor.tenantId(), members.current(actor).memberId(), after, limit);
	}

	/** 报价只校验资格，不消费券，最终占用发生在下单事务。 */
	public Coupon eligible(Actor actor, String id, String store, String gross) {
		Identifiers.require(id);
		var coupon = Inputs.found(mapper.find(actor.tenantId(), members.current(actor).memberId(), id));
		validate(coupon, store);
		if (money(gross).compareTo(money(coupon.minimumSpend())) < 0)
			throw conflict("未达到券门槛");
		return coupon;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void reserve(Actor actor, String order, String store, Application selected) {
		if (selected == null)
			return;
		var locked = Inputs
			.found(mapper.lock(actor.tenantId(), members.current(actor).memberId(), selected.couponId()));
		// 定义不可变只需普通读，避免锁券后反向锁定义与领取形成死锁。
		var d = Inputs.found(mapper.definitionFind(actor.tenantId(), locked.definitionId(), locked.version()));
		var coupon = new Coupon(locked.couponId(), locked.definitionId(), locked.version(), locked.memberId(),
				d.storeId(), d.name(), locked.status(), d.discountAmount(), d.minimumSpend(),
				locked.validFrom() == null ? d.validFrom() : locked.validFrom(), locked.validTo(), d.stackable(),
				d.platformFundingBps());
		validate(coupon, store);
		if (!coupon.definitionId().equals(selected.definitionId()) || coupon.version() != selected.version()
				|| coupon.platformFundingBps() != selected.platformFundingBps()
				|| money(selected.discount()).compareTo(money(coupon.discountAmount())) > 0
				|| money(selected.discount()).compareTo(Money.ZERO) <= 0)
			throw conflict("券快照不匹配");
		if (mapper.reserve(actor.tenantId(), coupon.couponId(), order) != 1)
			throw conflict("券已被其他订单占用");
		mapper.insertHold(actor.tenantId(), order, coupon.couponId(), selected.discount());
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void confirm(String tenant, String order) {
		finish(tenant, order, "HELD", "USED", "USED");
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void release(String tenant, String order) {
		finish(tenant, order, "HELD", "AVAILABLE", "RELEASED");
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void refund(String tenant, String order) {
		finish(tenant, order, "USED", "AVAILABLE", "REFUNDED");
	}

	private void finish(String tenant, String order, String expected, String target, String holdTarget) {
		var hold = mapper.hold(tenant, order);
		if (hold == null || hold.status().equals(holdTarget))
			return;
		if (!hold.status().equals(expected))
			throw conflict("券占用终态冲突");
		if (mapper.finish(tenant, order, hold.couponId(), expected, target) != 1
				|| mapper.holdStatus(tenant, order, expected, holdTarget) != 1)
			throw conflict("券状态并发冲突");
	}

	private void validate(Coupon coupon, String store) {
		if (!coupon.storeId().equals(store) || !coupon.status().equals("AVAILABLE")
				|| clock.instant().isBefore(coupon.validFrom()) || !clock.instant().isBefore(coupon.validTo()))
			throw conflict("券不属于本店、已占用或失效");
	}

	private DefinitionView definition(CouponMapper.DefinitionRow r) {
		return new DefinitionView(new Definition(r.definitionId(), r.version(), r.storeId(), r.name(), r.minimumSpend(),
				r.discountAmount(), r.validFrom(), r.validTo(), r.quota(), r.stackable(), r.platformFundingBps(),
				r.issuanceMode(), r.validityDays()), r.issued());
	}

	private Money money(String value) {
		Inputs.text(value, 32);
		try {
			return new Money(new BigDecimal(value));
		}
		catch (NumberFormatException ex) {
			throw new DomainException(DomainException.Code.INVALID_INPUT, "金额格式无效");
		}
	}

	private DomainException conflict(String message) {
		return new DomainException(DomainException.Code.CONFLICT, message);
	}

}
