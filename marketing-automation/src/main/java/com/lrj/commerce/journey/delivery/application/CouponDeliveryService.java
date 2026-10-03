package com.lrj.commerce.journey.delivery.application;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.journey.delivery.api.CouponDeliveryApi;
import com.lrj.commerce.journey.delivery.infrastructure.persistence.CouponDeliveryMapper;
import com.lrj.commerce.campaign.asset.api.MarketingAssets;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.benefit.coupon.api.CouponApi;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.recovery.RecoveryAudit;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.work.FailureClass;
import com.lrj.commerce.runtime.work.RetryPolicy;
import com.lrj.commerce.runtime.work.TenantRotation;
import com.lrj.commerce.runtime.work.WorkLanes;

/** 逐收件人提交并持久化恢复位置，不用内存队列代表发券成功。 */
@Service
public class CouponDeliveryService implements CouponDeliveryApi {

	private final CouponDeliveryMapper mapper;

	private final MarketingAssets audiences;

	private final MemberApi members;

	private final CouponApi coupons;

	private final StoreApi stores;

	private final Commands commands;

	private final EmployeeAccess access;

	private final Clock clock;

	private final TransactionTemplate tx;

	/** 定向发券车道：每次访问一个租户推进一个批次最多20名会员（逐名事务），每轮最多200步或500毫秒，租户轮转。 */
	public static final TenantRotation.Policy DELIVERY = new TenantRotation.Policy(20, 50, Duration.ofMillis(500), 200);

	private final TenantRotation deliveries;

	public CouponDeliveryService(CouponDeliveryMapper mapper, MarketingAssets audiences, MemberApi members,
			CouponApi coupons, StoreApi stores, Commands commands, Clock clock, PlatformTransactionManager manager,
			WorkLanes lanes, EmployeeAccess access) {
		this.access = access;
		this.mapper = mapper;
		this.audiences = audiences;
		this.members = members;
		this.coupons = coupons;
		this.stores = stores;
		this.commands = commands;
		this.clock = clock;
		tx = new TransactionTemplate(manager);
		tx.setTimeout(10);
		deliveries = lanes.rotation("deliveries", DELIVERY, null);
	}

	/** 固定版本与截止时间，创建后不追随更新的人群。 */
	public View create(Actor actor, String key, Create input) {
		var permit = access.scope(actor, COUPON_DELIVERY_CREATE);
		Inputs.require(
				input != null && input.definitionVersion() > 0 && input.deadline() != null && input.audience() != null,
				"发券批次参数无效");
		Identifiers.require(input.batchId());
		Identifiers.require(input.definitionId());
		Inputs.text(input.name(), 128);
		Inputs.require(input.minIntervalHours() >= 1 && input.minIntervalHours() <= 720, "会员发券间隔应为1至720小时");
		var prior = mapper.find(actor.tenantId(), input.batchId());
		var originalGate = prior == null ? (Runnable) () -> {} : sourceGate(actor.tenantId(), prior, Direction.ISSUE);
		var source = executionSource(actor, permit, key);
		return commands.runGuarded(actor, "coupon.delivery.create", key, command(permit, input), View.class, () -> {
			access.lock(permit);
			originalGate.run();
		}, receipt -> {
			// 首次查询后并发创建的回执必须显式原键重试，再在事务外核对实际原来源。
			if (prior == null) throw conflict("批次来源已变化，请使用原键重试");
		}, () -> {
			Inputs.require(input.deadline().isAfter(now()) && !input.deadline().isAfter(now().plus(Duration.ofDays(7))),
					"截止时间需在未来7天内");
			stores.requireActive(actor, input.storeId());
			audiences.requireFresh(actor.tenantId(), input.audience(), now());
			audiences.requireFresh(actor.tenantId(), input.audience(), input.deadline().minusMillis(1));
			coupons.validateExchange(actor.tenantId(), input.storeId(), input.definitionId(), input.definitionVersion(),
					now(), input.deadline());
			mapper.insert(actor.tenantId(), input, JsonCodec.write(input), now(), JsonCodec.write(source));
			access.lock(permit);
			checkSourceExpiry(source);
			access.auditVersion(actor, permit, "coupon.delivery.create", key, input.batchId(), 1);
			return view(mapper.find(actor.tenantId(), input.batchId()));
		});
	}

	/** 管理列表稳定批次游标。 */
	public List<View> list(Actor actor, String store, String after, int limit) {
		return list(actor, store, after, limit, ListFilter.none());
	}

	/** 只读条件先筛选再分页，保持原用例的权限复核。 */
	public List<View> list(Actor actor, String store, String after, int limit, ListFilter filter) {
		filter.requireNoEnabled();
		filter.requireNoTime();
		var permit = access.scope(actor, COUPON_DELIVERY_READ);
		Inputs.page(after, limit);
		stores.requireActive(actor, store);
		var rows = mapper.list(actor.tenantId(), store, after, limit, filter).stream().map(this::view).toList();
		EmployeeAccess.requireSame(permit, access.scope(actor, COUPON_DELIVERY_READ));
		return rows;
	}

	/** 回执不会显示其他租户的人群信息。 */
	public List<Recipient> recipients(Actor actor, String id, String after, int limit) {
		var scope = access.scope(actor, COUPON_DELIVERY_READ);
		Identifiers.require(id);
		Inputs.page(after, limit);
		var row = Inputs.found(mapper.find(actor.tenantId(), id));
		access.resource(actor, scope, fact(row));
		var rows = mapper.recipients(actor.tenantId(), id, after, limit);
		var current = access.scope(actor, COUPON_DELIVERY_READ);
		EmployeeAccess.requireSame(scope, current);
		var actual = Inputs.found(mapper.find(actor.tenantId(), id));
		if (!fact(row).equals(fact(actual))) throw denied();
		access.resource(actor, current, fact(actual));
		return rows;
	}

	/** 运维恢复审计：重试、取消等对停止工作的人工干预与状态变更同一事务记录。 */
	private com.lrj.commerce.runtime.recovery.RecoveryAudit audit;

	@org.springframework.beans.factory.annotation.Autowired
	void audit(com.lrj.commerce.runtime.recovery.RecoveryAudit audit) {
		this.audit = audit;
	}

	/** 撤销与停止分别建模，失败恢复保留原执行方向。 */
	public View control(Actor actor, String key, String id, Control input) {
		var scope = access.scope(actor, COUPON_DELIVERY_CONTROL);
		Identifiers.require(id);
		Inputs.require(input != null && input.expectedVersion() >= 0 && input.action() != null
				&& Set.of("CANCEL", "RETRY", "REVOKE").contains(input.action()), "任务控制参数无效");
		Inputs.text(input.reason(), 256);
		var current = Inputs.found(mapper.find(actor.tenantId(), id));
		var permit = access.resource(actor, scope, fact(current));
		var request = command(permit.scope(), id, input);
		var previous = commands.completedReceipt(actor, "coupon.delivery.control", key, request, View.class);
		boolean firstRevoke = previous == null && input.action().equals("REVOKE") && current.mode().equals("ISSUE");
		var revokeSource = firstRevoke ? executionSource(actor, permit.scope(), key) : null;
		var originalGate = firstRevoke ? (Runnable) () -> checkSourceExpiry(revokeSource)
				: sourceGate(actor.tenantId(), current, previous == null ? direction(current) : Direction.valueOf(previous.mode()));
		return commands.runGuarded(actor, "coupon.delivery.control", key, request, View.class, () -> {
			access.lock(permit.scope());
			lockContent(actor.tenantId(), current);
			originalGate.run();
		}, receipt -> {
			if (previous == null || !previous.equals(receipt)) throw conflict("命令回执已变化，请使用原键重试");
		}, () -> {
			var row = Inputs.found(mapper.lock(actor.tenantId(), id));
			if (row.version() != input.expectedVersion())
				throw conflict("批次版本已变化");
			var content = content(row);
			String target, mode = row.mode();
			switch (input.action()) {
				case "CANCEL" -> {
					if (!Set.of("RUNNING", "ISOLATED").contains(row.status()))
						throw conflict("当前批次不能取消");
					target = "CANCELLED";
				}
				case "RETRY" -> {
					if (!row.status().equals("ISOLATED")
							|| (mode.equals("ISSUE") && !now().isBefore(content.deadline())))
						throw conflict("仅隔离且仍有效的发放可重试");
					target = mode.equals("REVOKE") ? "REVOKING" : "RUNNING";
				}
				case "REVOKE" -> {
					if (!Set.of("COMPLETED", "CANCELLED", "EXPIRED", "ISOLATED").contains(row.status()))
						throw conflict("请先停止发放再撤销");
					mode = "REVOKE";
					target = "REVOKING";
				}
				default -> throw conflict("未知操作");
			}
			if (firstRevoke && (!row.mode().equals("ISSUE") || mapper.startRevoke(actor.tenantId(), id, JsonCodec.write(revokeSource)) != 1))
				throw conflict("首次撤回来源已变化");
			status(actor.tenantId(), id, row, target, mode);
			originalGate.run();
			access.lock(permit.scope());
			access.auditVersion(actor, permit.scope(), "coupon.delivery.control", key, id, row.contentVersion());
			audit.record(actor, "coupon.delivery.control", key, "coupon.delivery", id, input.action(), row.status(),
					target, null, input.reason(), com.lrj.commerce.runtime.recovery.RecoveryAudit.APPLIED, null);
			return view(mapper.lock(actor.tenantId(), id));
		});
	}

	/** 单轮上限20个收件人，正常推进与错误重试使用相同预算。 */
	public int pump(Actor actor) {
		var initial = access.scope(actor, COUPON_DELIVERY_PUMP);
		return pumpTenant(actor.tenantId(), deliveries.manual(), () -> {
			var current = access.scope(actor, COUPON_DELIVERY_PUMP);
			EmployeeAccess.requireSame(initial, current);
			return () -> access.lock(current);
		});
	}

	/** 各租户有独立批次和频控，后台按租户轮转。 */
	public int tick() {
		return deliveries.run((after, limit) -> mapper.tenants(after, now(), limit), this::pumpTenant);
	}

	/** 瞬时失败只延后批次不计次数，批次截止时间终止重试；其他失败计次，5次隔离。 */
	private int pumpTenant(String tenant, TenantRotation.Run run) {
		return pumpTenant(tenant, run, () -> () -> {});
	}

	/** 当前推进资格与原方向来源均在单收件人事务前检查，数据库锁内只执行短准入栅栏。 */
	private int pumpTenant(String tenant, TenantRotation.Run run, java.util.function.Supplier<Runnable> callerGate) {
		String id = mapper.pending(tenant, now());
		if (id == null)
			return 0;
		int count = 0;
		for (int i = 0, limit = run.limit(); i < limit && !run.exhausted(); i++) {
			run.attempted();
			try {
				var candidate = mapper.find(tenant, id);
				if (candidate == null || !Set.of("RUNNING", "REVOKING").contains(candidate.status())) break;
				var caller = callerGate.get();
				var original = sourceGate(tenant, candidate, direction(candidate));
				Runnable gate = () -> { caller.run(); original.run(); };
				if (!Boolean.TRUE.equals(tx.execute(s -> {
					gate.run();
					var locked = Inputs.found(mapper.lock(tenant, id));
					if (!locked.mode().equals(candidate.mode())) throw conflict("执行方向已变化");
					boolean done = step(tenant, id);
					original.commit();
					caller.run();
					// 准入窗口不延长业务截止：单名效果提交前再次检查原发放deadline。
					if (done && locked.mode().equals("ISSUE") && !now().isBefore(content(locked).deadline()))
						throw conflict("发券截止时间已到");
					return done;
				})))
					break;
				count++;
				run.succeeded();
			}
			catch (RuntimeException failure) {
				var type = FailureClass.of(failure);
				boolean counted = !type.transientFailure();
				String code = counted ? failure instanceof DomainException d ? d.code().name() : "STORAGE_FAILURE"
						: type.name();
				tx.executeWithoutResult(s -> {
					var row = mapper.lock(tenant, id);
					if (row != null && Set.of("RUNNING", "REVOKING").contains(row.status()))
						mapper.failed(tenant, id, code, counted,
								now().plusMillis(counted
										? ((1L << Math.min(row.attempts() + 1, 5)) * 1000
												+ java.util.concurrent.ThreadLocalRandom.current().nextInt(1000))
										: RetryPolicy.deferralMillis(
												java.util.concurrent.ThreadLocalRandom.current().nextDouble())));
				});
				org.slf4j.LoggerFactory.getLogger(getClass())
					.warn("coupon delivery retry batch={} failureClass={} errorType={}", id, type,
							failure.getClass().getSimpleName());
				run.failed(type);
				break;
			}
		}
		return count;
	}

	private boolean step(String tenant, String id) {
		var row = mapper.lock(tenant, id);
		if (row == null || !Set.of("RUNNING", "REVOKING").contains(row.status()) || row.availableAt().isAfter(now()))
			return false;
		var input = content(row);
		if (row.mode().equals("REVOKE")) {
			var recipient = mapper.nextRevoke(tenant, id, row.revokeCursor());
			if (recipient == null) {
				status(tenant, id, row, "REVOCATION_DONE", "REVOKE");
				return false;
			}
			var result = coupons.revokeTargeted(tenant, recipient.memberId(), recipient.couponId(),
					source(id, recipient.memberId()));
			boolean revoked = result.equals("REVOKED");
			if (mapper.revokeResult(tenant, id, recipient.memberId(), revoked ? "REVOKED" : "KEPT",
					revoked ? null : result) != 1)
				throw conflict("撤销回执并发变化");
			if (mapper.advanceRevoke(tenant, id, recipient.memberId(), revoked) != 1)
				throw conflict("撤销检查点写入冲突");
			return true;
		}
		if (!now().isBefore(input.deadline())) {
			status(tenant, id, row, "EXPIRED", "ISSUE");
			return false;
		}
		audiences.requireFresh(tenant, input.audience(), now());
		var next = audiences.members(tenant, input.audience(), row.cursorMember(), 1);
		if (next.isEmpty()) {
			status(tenant, id, row, "COMPLETED", "ISSUE");
			return false;
		}
		String member = next.getFirst(), coupon = null, error = null;
		var subject = members.lockForOperation(tenant, member);
		if (subject == null || !subject.status().equals("ACTIVE"))
			error = subject == null ? "MEMBER_MISSING" : "MEMBER_INACTIVE";
		else {
			var due = mapper.frequency(tenant, member);
			if (due != null && now().isBefore(due))
				error = "FREQUENCY_LIMIT";
		}
		if (error == null) {
			stores.requireActive(new Actor(tenant, "coupon-delivery", Actor.Role.ADMIN), input.storeId());
			coupon = coupons
				.grantTargeted(tenant, member, input.storeId(), source(id, member), input.definitionId(),
						input.definitionVersion())
				.couponId();
			mapper.frequencySet(tenant, member, now().plus(Duration.ofHours(input.minIntervalHours())));
		}
		mapper.recipient(tenant, id, new Recipient(member, error == null ? "ISSUED" : "SKIPPED", coupon, error, now()));
		if (mapper.advance(tenant, id, member, error == null) != 1)
			throw conflict("发券检查点写入冲突");
		return true;
	}

	private void status(String tenant, String id, CouponDeliveryMapper.Row row, String status, String mode) {
		if (mapper.status(tenant, id, row.version(), status, mode, now()) != 1)
			throw conflict("批次状态并发变化");
	}

	private enum Origin { CENTRAL, LEGACY }

	private enum Direction {
		ISSUE(COUPON_DELIVERY_CREATE), REVOKE(COUPON_DELIVERY_CONTROL);
		final EmployeeAccess.Capability capability;
		Direction(EmployeeAccess.Capability capability) { this.capability = capability; }
	}

	private record Source(Origin kind, Actor actor, EmployeeAccess.CouponDeliveryExecution execution, String commandKey, Instant createdAt) {}

	/** 新方向提交与旧方向每步均遵守准确中央期限，不因短准入窗而延长引用。 */
	private void checkSourceExpiry(Source source) {
		if (source != null && source.execution() != null && !source.execution().expiresAt().isAfter(Instant.now())) throw denied();
	}

	/** 发放与首次撤回分别固定原Actor和准确签发来源，绝不保存Token或许可结果。 */
	private Source executionSource(Actor actor, EmployeeAccess.ScopePermit permit, String key) {
		var execution = permit.identity() == null ? null : access.couponDeliveryExecution(actor, permit.capability());
		if (execution != null && (!execution.identity().equals(permit.identity()) || !execution.route().equals(permit.route()))) throw denied();
		return new Source(execution == null ? Origin.LEGACY : Origin.CENTRAL, actor, execution, key, now());
	}

	private Direction direction(CouponDeliveryMapper.Row row) {
		return Direction.valueOf(row.mode());
	}

	private String sourceJson(CouponDeliveryMapper.Row row, Direction direction) {
		return direction == Direction.ISSUE ? row.issueSourceJson() : row.revokeSourceJson();
	}

	/** 每步从持久库取得原方向；旧未知只在从未接管的原权威下兼容，不被新pump洗成中央来源。 */
	private OriginalGate sourceGate(String tenant, CouponDeliveryMapper.Row row, Direction direction) {
		String json = sourceJson(row, direction);
		var source = json == null ? null : JsonCodec.read(json, Source.class);
		Actor actor = source == null ? new Actor(tenant, "coupon-delivery", Actor.Role.ADMIN) : source.actor();
		if (actor == null || !tenant.equals(actor.tenantId()) || (source != null && (source.kind() == null || source.createdAt() == null || source.commandKey() == null))) throw denied();
		var scope = access.scope(actor, direction.capability);
		if (source != null && source.kind() == Origin.CENTRAL) {
			if (source.execution() == null || scope.identity() == null
					|| !source.execution().equals(access.couponDeliveryExecution(actor, direction.capability))
					|| !source.execution().identity().equals(scope.identity()) || !source.execution().route().equals(scope.route())) throw denied();
		} else if (scope.identity() != null || actor.executionId() != null || (source != null && source.execution() != null)) throw denied();
		var permit = direction == Direction.REVOKE ? access.resource(actor, scope, fact(row)).scope() : scope;
		return new OriginalGate(tenant, row, direction, json, source, permit);
	}

	/** 首次准入已持有路由共享锁和批次行锁；提交前只复核准确期限，避免逐会员重复读同一被锁事实。 */
	private final class OriginalGate implements Runnable {
		private final String tenant, json;
		private final CouponDeliveryMapper.Row row;
		private final Direction direction;
		private final Source source;
		private final EmployeeAccess.ScopePermit permit;

		OriginalGate(String tenant, CouponDeliveryMapper.Row row, Direction direction, String json, Source source, EmployeeAccess.ScopePermit permit) {
			this.tenant = tenant; this.row = row; this.direction = direction; this.json = json; this.source = source; this.permit = permit;
		}

		@Override public void run() {
			commit();
			access.lock(permit);
			var actual = lockContent(tenant, row);
			if (!Objects.equals(json, sourceJson(actual, direction))) throw conflict("任务原执行来源已变化");
		}

		void commit() {
			if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
				throw new IllegalStateException("原发券提交栅栏必须在持锁事务内");
			checkSourceExpiry(source);
			if (!permit.until().isAfter(Instant.now())) throw denied();
		}
	}

	/** 不可变内容事实与进度CAS分开；锁住记录后再次验证，旧回执也不能借新批次版本。 */
	private CouponDeliveryMapper.Row lockContent(String tenant, CouponDeliveryMapper.Row expected) {
		var actual = Inputs.found(mapper.lock(tenant, content(expected).batchId()));
		if (!fact(expected).equals(fact(actual)) || !expected.contentJson().equals(actual.contentJson())) throw conflict("批次内容版本已变化");
		return actual;
	}

	private EmployeeAccess.ResourceFact fact(CouponDeliveryMapper.Row row) {
		if (row.contentVersion() <= 0) throw denied();
		return new EmployeeAccess.ResourceFact(COUPON_DELIVERY_READ.resourceType(), content(row).batchId(), row.contentVersion());
	}

	private Object command(EmployeeAccess.ScopePermit permit, Object... input) {
		return permit.identity() == null ? (input.length == 1 ? input[0] : input) : new Object[] { input, permit.identity() };
	}

	private DomainException denied() {
		return new DomainException(DomainException.Code.FORBIDDEN, "原发券执行来源不再有效");
	}

	private String source(String id, String member) {
		return JsonCodec.hash(id + "/" + member);
	}

	private Create content(CouponDeliveryMapper.Row row) {
		return JsonCodec.read(row.contentJson(), Create.class);
	}

	private View view(CouponDeliveryMapper.Row row) {
		return new View(content(row), row.status(), row.mode(), row.processed(), row.issued(), row.skipped(),
				row.revoked(), row.kept(), row.cursorMember(), row.revokeCursor(), row.attempts(), row.errorCode(),
				row.version());
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

	private DomainException conflict(String reason) {
		return new DomainException(DomainException.Code.CONFLICT, reason);
	}

}
