package com.lrj.commerce.journey.application;

import com.lrj.commerce.journey.api.CouponDeliveryApi;
import com.lrj.commerce.journey.infrastructure.persistence.CouponDeliveryMapper;
import com.lrj.commerce.campaign.api.MarketingAssets;
import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.benefit.api.CouponApi;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 逐收件人提交并持久化恢复位置，不用内存队列代表发券成功。 */
@Service
public class CouponDeliveryService implements CouponDeliveryApi {

	private final CouponDeliveryMapper mapper;

	private final MarketingAssets audiences;

	private final MemberApi members;

	private final CouponApi coupons;

	private final StoreApi stores;

	private final Commands commands;

	private final Clock clock;

	private final TransactionTemplate tx;

	/** 定向发券车道：每次访问一个租户推进一个批次最多20名会员（逐名事务），每轮最多200步或500毫秒，租户轮转。 */
	public static final TenantRotation.Policy DELIVERY = new TenantRotation.Policy(20, 50, Duration.ofMillis(500), 200);

	private final TenantRotation deliveries;

	public CouponDeliveryService(CouponDeliveryMapper mapper, MarketingAssets audiences, MemberApi members,
			CouponApi coupons, StoreApi stores, Commands commands, Clock clock, PlatformTransactionManager manager,
			WorkLanes lanes) {
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
		actor.requireAdmin();
		Inputs.require(
				input != null && input.definitionVersion() > 0 && input.deadline() != null && input.audience() != null,
				"发券批次参数无效");
		Identifiers.require(input.batchId());
		Identifiers.require(input.definitionId());
		Inputs.text(input.name(), 128);
		Inputs.require(input.minIntervalHours() >= 1 && input.minIntervalHours() <= 720, "会员发券间隔应为1至720小时");
		return commands.run(actor, "coupon.delivery.create", key, input, View.class, () -> {
			Inputs.require(input.deadline().isAfter(now()) && !input.deadline().isAfter(now().plus(Duration.ofDays(7))),
					"截止时间需在未来7天内");
			stores.requireActive(actor, input.storeId());
			audiences.requireFresh(actor.tenantId(), input.audience(), now());
			audiences.requireFresh(actor.tenantId(), input.audience(), input.deadline().minusMillis(1));
			coupons.validateExchange(actor.tenantId(), input.storeId(), input.definitionId(), input.definitionVersion(),
					now(), input.deadline());
			mapper.insert(actor.tenantId(), input, JsonCodec.write(input), now());
			return view(mapper.find(actor.tenantId(), input.batchId()));
		});
	}

	/** 管理列表稳定批次游标。 */
	public List<View> list(Actor actor, String store, String after, int limit) {
		actor.requireAdmin();
		Inputs.page(after, limit);
		stores.requireActive(actor, store);
		return mapper.list(actor.tenantId(), store, after, limit).stream().map(this::view).toList();
	}

	/** 回执不会显示其他租户的人群信息。 */
	public List<Recipient> recipients(Actor actor, String id, String after, int limit) {
		actor.requireAdmin();
		Identifiers.require(id);
		Inputs.page(after, limit);
		Inputs.found(mapper.find(actor.tenantId(), id));
		return mapper.recipients(actor.tenantId(), id, after, limit);
	}

	/** 运维恢复审计：重试、取消等对停止工作的人工干预与状态变更同一事务记录。 */
	private com.lrj.commerce.runtime.RecoveryAudit audit;

	@org.springframework.beans.factory.annotation.Autowired
	void audit(com.lrj.commerce.runtime.RecoveryAudit audit) {
		this.audit = audit;
	}

	/** 撤销与停止分别建模，失败恢复保留原执行方向。 */
	public View control(Actor actor, String key, String id, Control input) {
		actor.requireAdmin();
		Identifiers.require(id);
		Inputs.require(input != null && input.expectedVersion() >= 0 && input.action() != null
				&& Set.of("CANCEL", "RETRY", "REVOKE").contains(input.action()), "任务控制参数无效");
		Inputs.text(input.reason(), 256);
		return commands.run(actor, "coupon.delivery.control", key, new Object[] { id, input }, View.class, () -> {
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
			status(actor.tenantId(), id, row, target, mode);
			audit.record(actor, "coupon.delivery.control", key, "coupon.delivery", id, input.action(), row.status(),
					target, null, input.reason(), com.lrj.commerce.runtime.RecoveryAudit.APPLIED, null);
			return view(mapper.lock(actor.tenantId(), id));
		});
	}

	/** 单轮上限20个收件人，正常推进与错误重试使用相同预算。 */
	public int pump(Actor actor) {
		actor.requireAdmin();
		return pumpTenant(actor.tenantId(), deliveries.manual());
	}

	/** 各租户有独立批次和频控，后台按租户轮转。 */
	public int tick() {
		return deliveries.run((after, limit) -> mapper.tenants(after, now(), limit), this::pumpTenant);
	}

	/** 瞬时失败只延后批次不计次数，批次截止时间终止重试；其他失败计次，5次隔离。 */
	private int pumpTenant(String tenant, TenantRotation.Run run) {
		String id = mapper.pending(tenant, now());
		if (id == null)
			return 0;
		int count = 0;
		for (int i = 0, limit = run.limit(); i < limit && !run.exhausted(); i++) {
			run.attempted();
			try {
				if (!Boolean.TRUE.equals(tx.execute(s -> step(tenant, id))))
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
