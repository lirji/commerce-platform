package com.lrj.commerce.ordering.order.application;

import com.lrj.commerce.ordering.order.infrastructure.persistence.OrderMapper;
import com.lrj.commerce.ordering.address.infrastructure.security.AddressCipher;
import com.lrj.commerce.ordering.order.api.OrderApi;
import com.lrj.commerce.order.api.*;
import com.lrj.commerce.order.domain.OrderLifecycle;
import com.lrj.commerce.trade.api.QuoteApi;
import com.lrj.commerce.inventory.api.InventoryApi;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.lrj.commerce.ordering.expiry.application.OrderExpiryRecovery;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.event.Outbox;
import com.lrj.commerce.runtime.recovery.RecoveryAudit;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.work.FailureClass;
import com.lrj.commerce.runtime.work.RetryPolicy;
import com.lrj.commerce.runtime.work.TenantRotation;
import com.lrj.commerce.runtime.work.WorkLanes;

/** 订单用例编排同库端口，远程支付/物流不进入这段事务。 */
@Service
public class OrderService implements OrderApi {
	/** 仅隔离压测按需启用；不把订单、会员或租户标识写入性能日志。 */
	@org.springframework.beans.factory.annotation.Value("${commerce.marketing.profiling-enabled:false}")
	private boolean profilingEnabled;

	private static final org.slf4j.Logger PROFILE = org.slf4j.LoggerFactory.getLogger(OrderService.class);

	private final com.lrj.commerce.member.points.spend.api.PointsSpendApi points;

	private final com.lrj.commerce.benefit.entitlement.api.EntitlementApi entitlements;

	private final com.lrj.commerce.campaign.funding.api.CampaignFundingApi funding;

	private final com.lrj.commerce.campaign.execution.api.CampaignExecutionApi executions;

	private final com.lrj.commerce.benefit.coupon.api.CouponApi coupons;

	/** 生产容器强制注入，单独构造的后台车道测试不执行员工入口。 */
	@org.springframework.beans.factory.annotation.Autowired
	private EmployeeAccess access;

	private final OrderMapper mapper;

	private final Commands commands;

	private final QuoteApi quotes;

	private final InventoryApi inventory;

	private final MemberApi members;

	private final StoreApi stores;

	private final Outbox outbox;

	private final AddressCipher addresses;

	private final Clock clock;

	private final org.springframework.transaction.support.TransactionTemplate tx;

	private final TenantRotation expiry;

	/**
	 * 到期车道公平契约：每次访问一个租户最多10单（逐单事务约数十毫秒），每轮最多200单或500毫秒，
	 * 租户按游标轮转，任一有到期订单的租户最迟一整圈内被处理；单个坏订单退避重试，5次非瞬时失败后停止自动取消。
	 */
	public static final TenantRotation.Policy EXPIRY = new TenantRotation.Policy(10, 50,
			java.time.Duration.ofMillis(500), 200);

	public OrderService(OrderMapper mapper, Commands commands, QuoteApi quotes, InventoryApi inventory,
			MemberApi members, StoreApi stores, Outbox outbox, AddressCipher addresses, Clock clock,
			com.lrj.commerce.benefit.coupon.api.CouponApi coupons,
			com.lrj.commerce.campaign.funding.api.CampaignFundingApi funding,
			com.lrj.commerce.benefit.entitlement.api.EntitlementApi entitlements,
			com.lrj.commerce.campaign.execution.api.CampaignExecutionApi executions,
			com.lrj.commerce.member.points.spend.api.PointsSpendApi points,
			org.springframework.transaction.PlatformTransactionManager manager, WorkLanes lanes) {
		this.points = points;
		this.entitlements = entitlements;
		this.executions = executions;
		this.funding = funding;
		this.coupons = coupons;
		this.tx = new org.springframework.transaction.support.TransactionTemplate(manager);
		this.tx.setTimeout(10);
		expiry = lanes.rotation("orders", EXPIRY, () -> mapper.expiryBacklog(clock.instant(),
				RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget()));
		this.mapper = mapper;
		this.commands = commands;
		this.quotes = quotes;
		this.inventory = inventory;
		this.members = members;
		this.stores = stores;
		this.outbox = outbox;
		this.addresses = addresses;
		this.clock = clock;
	}

	/** 订单编号由服务端生成，失败预占会回滚报价消费，允许重试原命令。 */
	public View create(Actor actor, String key, Create input) {
		Inputs.require(input != null && input.address() != null, "实物订单需要收货地址");
		Identifiers.require(input.quoteId());
		Inputs.text(input.address().recipient(), 128);
		Inputs.text(input.address().phone(), 32);
		Inputs.text(input.address().detail(), 512);
		return commands.run(actor, "order.create", key, input, View.class, () -> {
			var member = members.current(actor);
			members.requireActive(actor, member.memberId());
			String id = UUID.randomUUID().toString();
			var quote = quotes.consume(actor, input.quoteId(), id);
			stores.requireActive(actor, quote.storeId());
			points.reserve(actor, id, member.memberId(), quote.points(),
					new BigDecimal(quote.payable())
						.add(quote.points() == null ? BigDecimal.ZERO : new BigDecimal(quote.points().discount()))
						.toPlainString());
			coupons.reserve(actor, id, quote.storeId(), quote.coupon());
			long quotaStarted = System.nanoTime();
			coupons.reserveCampaign(actor.tenantId(), id, member.memberId(), quote.storeId(),
					quote.promotion() == null ? null : quote.promotion().coupon());
			long couponReserved = System.nanoTime();
			funding.reserve(actor, id, quote.storeId(), quote.promotion());
			long budgetReserved = System.nanoTime();
			entitlements.reserveOrder(actor, id, member.memberId(), quote.storeId(),
					quote.promotion() == null ? null : quote.promotion().grant());
			long creditReserved = System.nanoTime();
			for (var line : quote.items().stream().sorted(Comparator.comparing(QuoteApi.Line::skuId)).toList())
				inventory.reserve(actor, id, quote.storeId(), line.skuId(), line.quantity());
			// 活动参与与所有订单预占同事务，保证额度争用失败时不会留下虚假的营销成功记录。
			long executionStarted = System.nanoTime();
			executions.recordOrder(actor, new com.lrj.commerce.campaign.execution.api.CampaignExecutionApi.OrderTrigger(
					id, quote.quoteId(), member.memberId(), quote.storeId(), quote.campaign(),
					quote.campaignDiscount(), quote.createdAt(), quote.trace(), quote.sources(),
					quote.promotion() == null ? null : quote.promotion().grant(),
					quote.promotion() == null ? null : quote.promotion().coupon()));
			long executionRecorded = System.nanoTime();
			var lifecycle = OrderLifecycle.start();
			boolean free = new BigDecimal(quote.payable()).signum() == 0;
			if (free) {
				lifecycle = lifecycle.apply(OrderEvent.PAYMENT_CONFIRMED);
				settle(actor.tenantId(), id, member.memberId(), true);
			}
			var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
			var view = new View(id, member.memberId(), quote.storeId(), quote.merchantId(), quote.quoteId(),
					quote.payable(), lifecycle.state().name(), free ? "NO_PAYMENT_REQUIRED" : "CHANNEL_REQUIRED",
					lifecycle.version(), now, now.plusSeconds(900), quote.items(), quote.channel());
			mapper.insert(actor.tenantId(), view, JsonCodec.write(view.items()),
					addresses.encrypt(actor.tenantId(), id, input.address()));
			outbox.append(actor.tenantId(), "order.created.v1", id, view.version(), view);
			// 零元单可以履约，但不能伪造OrderPaid渠道收款事实。
			if (free)
				outbox.append(actor.tenantId(), "order.ready.v1", id, view.version(), view);
			if (profilingEnabled)
				PROFILE.info("marketing_order_profile coupon_quota_us={} budget_us={} credit_quota_us={} inventory_us={} execution_us={} order_persistence_us={}",
						(couponReserved - quotaStarted) / 1000, (budgetReserved - couponReserved) / 1000,
						(creditReserved - budgetReserved) / 1000, (executionStarted - creditReserved) / 1000,
						(executionRecorded - executionStarted) / 1000,
						(System.nanoTime() - executionRecorded) / 1000);
			return view;
		});
	}

	/** 读取时只按可信会员归属查询。 */
	public View read(Actor actor, String id) {
		Identifiers.require(id);
		return view(Inputs.found(mapper.read(actor.tenantId(), members.current(actor).memberId(), id)));
	}

	/** 列表不加载明细快照，防止列表隐含N+1或放大响应。 */
	public List<View> list(Actor actor, String after, int limit) {
		Inputs.page(after, limit);
		return mapper.list(actor.tenantId(), members.current(actor).memberId(), after, limit)
			.stream()
			.map(this::view)
			.toList();
	}

	/** 未开始支付可以直接释放；重复取消从持久化状态回放，不重复发事件。 */
	public View cancel(Actor actor, String key, String id) {
		Identifiers.require(id);
		return commands.run(actor, "order.cancel", key, id, View.class, () -> {
			var row = Inputs.found(mapper.lock(actor.tenantId(), members.current(actor).memberId(), id));
			var state = OrderState.valueOf(row.status());
			if (state == OrderState.CANCELLED || state == OrderState.CLOSING)
				return view(row);
			var next = new OrderLifecycle(state, row.version()).apply(OrderEvent.REQUEST_CANCEL);
			if (mapper.change(actor.tenantId(), id, row.version(), next.state().name()) != 1)
				throw new DomainException(DomainException.Code.CONFLICT, "订单并发版本冲突");
			if (next.state() == OrderState.CANCELLED)
				settle(actor.tenantId(), id, row.memberId(), false);
			var result = view(mapper.read(actor.tenantId(), row.memberId(), id));
			outbox.append(actor.tenantId(),
					next.state() == OrderState.CANCELLED ? "order.cancelled.v1" : "order.closing.v1", id,
					result.version(), result);
			return result;
		});
	}

	private View view(OrderMapper.Row r) {
		List<QuoteApi.Line> items = r.itemsJson() == null ? List.of()
				: Arrays.asList(JsonCodec.read(r.itemsJson(), QuoteApi.Line[].class));
		return new View(r.orderId(), r.memberId(), r.storeId(), r.merchantId(), r.quoteId(), r.payable(), r.status(),
				r.paymentKind(), r.version(), r.createdAt(), r.expiresAt(), items, r.channel());
	}

	/** 同事务锁住订单，取消/启动支付只能有一个先发生。 */
	@org.springframework.transaction.annotation.Transactional(
			propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public View beginPayment(Actor actor, String id) {
		var row = Inputs.found(mapper.lock(actor.tenantId(), members.current(actor).memberId(), id));
		if (row.status().equals("PAYMENT_IN_PROGRESS"))
			return view(row);
		Inputs.require(row.paymentKind().equals("CHANNEL_REQUIRED"), "零元订单无需渠道支付");
		if (!clock.instant().isBefore(row.expiresAt()))
			throw new DomainException(DomainException.Code.CONFLICT, "订单已过期");
		transition(actor.tenantId(), row, OrderEvent.START_PAYMENT);
		return view(mapper.internalRead(actor.tenantId(), id));
	}

	/** 只有渠道已证实的金额匹配事实才能改变订单与库存。 */
	@org.springframework.transaction.annotation.Transactional(
			propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public View paymentFact(String tenant, String id, String amount, boolean paid) {
		var row = Inputs.found(mapper.internalLock(tenant, id));
		if (new BigDecimal(row.payable()).compareTo(new BigDecimal(amount)) != 0)
			throw new DomainException(DomainException.Code.CONFLICT, "支付金额不匹配");
		if ((paid && Set.of("PAID", "FULFILLING", "COMPLETED").contains(row.status()))
				|| (!paid && row.status().equals("CANCELLED")))
			return view(row);
		transition(tenant, row, paid ? OrderEvent.PAYMENT_CONFIRMED : OrderEvent.PAYMENT_ABSENCE_CONFIRMED);
		settle(tenant, id, row.memberId(), paid);
		var result = view(mapper.internalRead(tenant, id));
		outbox.append(tenant, paid ? "order.paid.v1" : "order.cancelled.v1", id, result.version(), result);
		return result;
	}

	/** 跨域只返回API投影，其他模块不读订单Mapper。 */
	/** 只读交易权威状态，不依赖营销投影消费延迟。 */
	public boolean hasPaidSince(String tenant, String member, String store, java.time.Instant since) {
		Identifiers.require(tenant);
		Identifiers.require(member);
		Identifiers.require(store);
		Inputs.require(since != null, "订单检查起点缺失");
		return mapper.hasPaidSince(tenant, member, store, since);
	}

	public View internalRead(String tenant, String id) {
		return view(Inputs.found(mapper.internalRead(tenant, id)));
	}

	/** 手工到期仅选择当前授权门店，最多20单；所有远程判权在事务外，事务内逐单核对原事实。 */
	public int expire(Actor actor, String key) {
		var scope = access.scope(actor, ORDER_EXPIRE);
		var candidates = mapper.expiredScoped(actor.tenantId(), scope.filter(), clock.instant(),
				RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget());
		// 原回执重新核对原批次目标，不因目标已离开到期队列而失去逐店授权栅栏。
		var prior = mapper.expiryFacts(actor, key);
		var ids = prior.isEmpty() ? candidates.stream().map(OrderMapper.Row::orderId).toList()
				: prior.stream().map(OrderMapper.ExpiryFact::orderId).toList();
		var permits = ids.stream().map(id -> authorize(actor, ORDER_EXPIRE, id)).toList();
		Object command = scope.identity() == null ? "expire" : Arrays.asList("expire", scope.identity(), scope.fingerprint());
		return commands.runGuarded(actor, "order.expire", key, command, Integer.class,
				() -> { access.lock(scope); permits.forEach(p -> lockAuthorization(actor, p)); }, () -> {
			int count = 0;
			for (var permit : permits) {
				var row = mapper.expiredLock(actor.tenantId(), permit.orderId(), clock.instant(),
						RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget());
				if (row == null) continue;
				expireRow(actor.tenantId(), row);
				mapper.expiryFact(actor, key, row.orderId(), permit.store().storeId(), permit.store().version());
				count++;
			}
			access.audit(actor, scope, "order.expire", key, key);
			return count;
		});
	}

	/** 后台到期：租户公平轮转，逐单事务，一个坏订单只影响它自己。 */
	public int tick() {
		return expiry.run((after, limit) -> mapper.expiryTenants(after, clock.instant(), limit,
				RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget()), this::expireTenant);
	}

	private int expireTenant(String tenant, TenantRotation.Run run) {
		int count = 0;
		for (var check : mapper.expiryDue(tenant, clock.instant(), run.limit(), RetryPolicy.POISON.budget(),
				RetryPolicy.TRANSIENT.budget())) {
			if (run.exhausted())
				break;
			run.attempted();
			try {
				// 事务内以SKIP LOCKED重新领取并复核到期条件，并发支付或取消已改变状态时跳过。
				var row = tx.execute(s -> {
					var locked = mapper.expiredLock(tenant, check.orderId(), clock.instant(),
							RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget());
					if (locked != null)
						expireRow(tenant, locked);
					return locked;
				});
				if (row != null)
					count++;
				run.succeeded();
			}
			catch (RuntimeException failure) {
				var type = FailureClass.of(failure);
				expiryFailed(tenant, check, type, failure);
				if (run.failed(type))
					break;
			}
		}
		return count;
	}

	/** 失败在独立事务记录：瞬时失败走长退避且不计入5次上限；证据只含分类与异常类型。 */
	private void expiryFailed(String tenant, OrderMapper.ExpiryCheck check, FailureClass type,
			RuntimeException failure) {
		boolean transientFailure = type.transientFailure();
		var policy = transientFailure ? RetryPolicy.TRANSIENT : RetryPolicy.POISON;
		int failures = (transientFailure ? check.expiryTransientAttempts() : check.expiryAttempts()) + 1;
		var retryAt = clock.instant()
			.plusMillis(policy.delayMillis(failures, java.util.concurrent.ThreadLocalRandom.current().nextDouble()));
		String error = type + ":" + (failure instanceof DomainException d ? "DomainException/" + d.code()
				: failure.getClass().getSimpleName());
		try {
			tx.executeWithoutResult(
					s -> mapper.expiryFailed(tenant, check.orderId(), transientFailure, retryAt, error));
		}
		catch (RuntimeException unrecorded) {
			org.slf4j.LoggerFactory.getLogger(getClass())
				.warn("order expiry failure not recorded order={} errorType={}", check.orderId(),
						unrecorded.getClass().getSimpleName());
		}
		if (policy.exhausted(failures))
			org.slf4j.LoggerFactory.getLogger(getClass())
				.warn("order expiry quarantined tenant={} order={} lastError={}", tenant, check.orderId(), error);
		else
			org.slf4j.LoggerFactory.getLogger(getClass())
				.warn("order expiry retry tenant={} order={} failureClass={} errorType={}", tenant, check.orderId(),
						type, failure.getClass().getSimpleName());
	}

	/** 停止自动取消的订单修复数据后由管理员审计重试，只清计数与退避，保留最近失败证据；恢复审计记录前后状态与分类。 */
	public int retryExpiry(Actor actor, String key, String id) {
		Identifiers.require(id);
		var permit = authorize(actor, ORDER_EXPIRY_RETRY, id);
		// 旧管理员路由仍保留平台恢复资格；中央员工使用独立 order.expiry.retry。
		if (permit.permit().identity() == null) actor.require(Actor.Capability.RUNTIME_RECOVERY_EXECUTE);
		return commands.runGuarded(actor, "order.expiry.retry", key,
				permit.permit().identity() == null ? id : Arrays.asList(id, permit.permit().identity()), Integer.class,
				() -> lockAuthorization(actor, permit), () -> {
			var before = mapper.expiryStoppedOne(actor.tenantId(), id, RetryPolicy.POISON.budget(),
					RetryPolicy.TRANSIENT.budget());
			if (mapper.expiryRetry(actor.tenantId(), id, RetryPolicy.POISON.budget(),
					RetryPolicy.TRANSIENT.budget()) != 1)
				throw new DomainException(DomainException.Code.CONFLICT, "订单不在停止自动到期状态");
			if (audit != null)
				audit.record(actor, "order.expiry.retry", key, OrderExpiryRecovery.WORK_TYPE, id, "RETRY",
						OrderExpiryRecovery.STOPPED, OrderExpiryRecovery.READY,
						OrderExpiryRecovery.failureClass(before.expiryError()), null, RecoveryAudit.APPLIED, null);
			access.audit(actor, permit.permit(), "order.expiry.retry", key);
			return 1;
		});
	}

	/** 手工构造的实例（多实例测试）没有审计组件时只执行状态变更。 */
	private RecoveryAudit audit;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	void audit(RecoveryAudit audit) {
		this.audit = audit;
	}

	private void expireRow(String tenant, OrderMapper.Row row) {
		var next = transition(tenant, row, OrderEvent.REQUEST_CANCEL);
		if (next.state() == OrderState.CANCELLED)
			settle(tenant, row.orderId(), row.memberId(), false);
		var result = view(mapper.internalRead(tenant, row.orderId()));
		outbox.append(tenant, next.state() == OrderState.CANCELLED ? "order.cancelled.v1" : "order.closing.v1",
				row.orderId(), result.version(), result);
	}

	/** 确认或释放与下单预占使用同一加锁顺序（积分→券→预算→权益→库存），避免与并发下单互相等待形成死锁。 */
	private void settle(String tenant, String order, String member, boolean confirm) {
		if (confirm) {
			points.confirm(tenant, order, member);
			coupons.confirm(tenant, order);
			long couponStarted = System.nanoTime();
			coupons.confirmCampaign(tenant, order);
			long couponGranted = System.nanoTime();
			funding.confirm(tenant, order);
			entitlements.confirmOrder(tenant, order);
			inventory.confirm(tenant, order);
			executions.settleOrder(tenant, order, true);
			if (profilingEnabled)
				PROFILE.info("marketing_settle_profile coupon_grant_us={} remaining_settle_us={}",
						(couponGranted - couponStarted) / 1000, (System.nanoTime() - couponGranted) / 1000);
		}
		else {
			points.release(tenant, order, member);
			coupons.release(tenant, order);
			coupons.releaseCampaign(tenant, order);
			funding.release(tenant, order);
			entitlements.releaseOrder(tenant, order);
			inventory.release(tenant, order);
			executions.settleOrder(tenant, order, false);
		}
	}

	private OrderLifecycle transition(String tenant, OrderMapper.Row row, OrderEvent event) {
		var next = new OrderLifecycle(OrderState.valueOf(row.status()), row.version()).apply(event);
		if (mapper.change(tenant, row.orderId(), row.version(), next.state().name()) != 1)
			throw new DomainException(DomainException.Code.CONFLICT, "订单并发版本冲突");
		return next;
	}

	/** 物流事实不倒退订单；重复由调用者与当前终态共同去重。 */
	@org.springframework.transaction.annotation.Transactional(
			propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public View fulfillmentFact(String tenant, String id, boolean delivered) {
		var row = Inputs.found(mapper.internalLock(tenant, id));
		String target = delivered ? "COMPLETED" : "FULFILLING";
		if (row.status().equals(target) || (!delivered && row.status().equals("COMPLETED")))
			return view(row);
		transition(tenant, row, delivered ? OrderEvent.CONFIRM_DELIVERY : OrderEvent.START_FULFILLMENT);
		var result = view(mapper.internalRead(tenant, id));
		outbox.append(tenant, delivered ? "order.completed.v1" : "order.fulfilling.v1", id, result.version(), result);
		return result;
	}

	/** 内部投影源不借管理员角色；应用层已判权，Owner仍校验租户及有界游标。 */
	public List<BehaviorSource> behaviorSources(String tenant, String after, int limit) {
		Identifiers.require(tenant);
		Inputs.require(limit > 0 && limit <= 50, "补建批次1至50");
		Inputs.page(after, limit);
		return mapper.behaviorSources(tenant, after, limit);
	}

	/** 效果重建只读内部 Owner 端口，资金与地址不可由返回值修改。 */
	public List<View> listForEffects(String tenant, String store, java.time.Instant from, java.time.Instant to, String after, int limit) {
		Identifiers.require(tenant);
		if (store != null) Identifiers.require(store);
		Inputs.require(((from == null && to == null) || (from != null && to != null && from.isBefore(to))) && limit >= 1 && limit <= 100, "效果订单窗口或批次无效");
		Inputs.page(after, limit);
		return mapper.listForEffects(tenant, store, from, to, after, limit).stream().map(this::view).toList();
	}

	/** 与本人列表分开，SQL 在 LIMIT 前使用实际 store_id；不返回地址。 */
	public List<View> adminList(Actor actor, String after, int limit) {
		Inputs.page(after, limit);
		var before = access.scope(actor, ORDER_READ);
		var result = mapper.scopedList(actor.tenantId(), before.filter(), after, limit).stream().map(this::view).toList();
		EmployeeAccess.requireSame(before, access.scope(actor, ORDER_READ));
		return result;
	}

	/** 只读取本租户商业快照，不返回加密地址。 */
	public View adminRead(Actor actor, String id) {
		var permit = authorize(actor, ORDER_READ, id);
		var result = internalRead(actor.tenantId(), id);
		checkAuthorization(actor, permit);
		return result;
	}

	/** 模块边界使用实际订单与门店 Owner 事实，调用者不能替换门店或版本。 */
	public Authorization authorize(Actor actor, EmployeeAccess.Capability capability, String id) {
		Identifiers.require(id);
		var order = Inputs.found(mapper.internalRead(actor.tenantId(), id));
		var store = stores.fact(actor, order.storeId());
		var permit = access.require(actor, capability, new EmployeeAccess.StoreFact(store.storeId(), store.version()));
		return new Authorization(id, store, permit);
	}

	/** 与订单生命周期相同加锁顺序；门店改变或路由停止时整条命令回滚。 */
	@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public void lockAuthorization(Actor actor, Authorization authorization) {
		var order = Inputs.found(mapper.internalLock(actor.tenantId(), authorization.orderId()));
		if (!order.storeId().equals(authorization.store().storeId())) throw authorizationChanged();
		stores.lockCurrent(actor, authorization.store());
		access.lock(authorization.permit());
	}

	/** 读路径两次实时门禁不以五秒准入资格作为结果缓存。 */
	public void checkAuthorization(Actor actor, Authorization authorization) {
		var after = authorize(actor, authorization.permit().capability(), authorization.orderId());
		var before = authorization.permit();
		if (!Objects.equals(before.identity(), after.permit().identity())
				|| !Objects.equals(before.route(), after.permit().route())
				|| !Objects.equals(before.fact(), after.permit().fact())) throw authorizationChanged();
	}

	private DomainException authorizationChanged() {
		return new DomainException(DomainException.Code.FORBIDDEN, "订单门店授权事实已变化");
	}

}
