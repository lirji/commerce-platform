package com.lrj.commerce.payment.refund.application;

import com.lrj.commerce.payment.charge.infrastructure.persistence.PaymentMapper;
import com.lrj.commerce.payment.refund.infrastructure.persistence.RefundMapper;
import com.lrj.commerce.ordering.order.api.OrderApi;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
import com.lrj.commerce.payment.charge.api.PaymentApi;
import com.lrj.commerce.payment.charge.application.PaymentService;
import com.lrj.commerce.payment.refund.api.RefundApi;
import com.lrj.commerce.payment.refund.application.port.RefundChannel;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.event.Outbox;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.work.FailureClass;
import com.lrj.commerce.runtime.work.RetryPolicy;
import com.lrj.commerce.runtime.work.TenantRotation;
import com.lrj.commerce.runtime.work.WorkLanes;

/** 退款先预留实收额度，再在事务外核对渠道；未知占用额度防止重试超退。 */
@Service
public class RefundService implements RefundApi {

	/** 生产容器强制注入，每个员工入口使用独立能力；后台责任不依赖此门禁。 */
	@org.springframework.beans.factory.annotation.Autowired
	private EmployeeAccess access;

	private final RefundMapper mapper;

	private final PaymentMapper payments;

	private final OrderApi orders;

	private final RefundChannel channel;

	private final Commands commands;

	private final Outbox outbox;

	private final TransactionTemplate tx;

	private final TenantRotation checks;

	public RefundService(RefundMapper mapper, PaymentMapper payments, OrderApi orders, RefundChannel channel,
			Commands commands, Outbox outbox, PlatformTransactionManager manager, WorkLanes lanes) {
		this.mapper = mapper;
		this.payments = payments;
		this.orders = orders;
		this.channel = channel;
		this.commands = commands;
		this.outbox = outbox;
		tx = new TransactionTemplate(manager);
		tx.setTimeout(10);
		checks = lanes.rotation("refunds", PaymentService.CHECKS,
				() -> mapper.checkBacklog(RetryPolicy.TRANSIENT.budget()));
	}

	/** 售后批准与退款意图同事务；一张售后单只对应一个退款号。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public View request(String tenant, String caseId, String orderId, String amount) {
		var money = new BigDecimal(amount);
		new Money(money);
		var order = orders.internalRead(tenant, orderId);
		if (!Set.of("PAID", "FULFILLING", "COMPLETED").contains(order.status()))
			throw conflict();
		String provider = "NO_PAYMENT_REQUIRED";
		String paymentId = null;
		if (money.signum() > 0) {
			var pay = Inputs.found(payments.byOrder(tenant, orderId));
			pay = payments.lock(tenant, pay.paymentId());
			if (pay.status() != PaymentApi.Status.PAID)
				throw conflict();
			provider = pay.provider();
			paymentId = pay.paymentId();
		}
		var previous = mapper.byCase(tenant, caseId);
		if (previous != null) {
			if (new BigDecimal(previous.amount()).compareTo(money) != 0 || !previous.orderId().equals(orderId))
				throw conflict();
			return previous;
		}
		// 条件更新而不是RR快照SUM承担最终资金上限，避免等待锁后仍读旧退款总额。
		if (paymentId != null && payments.reserveRefund(tenant, paymentId, amount) != 1)
			throw conflict();
		var view = new View(UUID.randomUUID().toString(), caseId, orderId, money.setScale(2).toPlainString(), "CNY",
				provider, money.signum() == 0 ? "SUCCEEDED" : "UNKNOWN", 0);
		mapper.insert(tenant, view);
		if (money.signum() == 0)
			outbox.append(tenant, "refund.succeeded.v1", view.refundId(), view.version(), view);
		return view;
	}

	public View internalRead(String tenant, String id) {
		return Inputs.found(mapper.find(tenant, id));
	}

	public List<View> list(Actor actor, String after, int limit) {
		Inputs.page(after, limit);
		var permit = access.scope(actor, REFUND_READ);
		var result = mapper.scopedList(actor.tenantId(), permit.filter(), after, limit);
		EmployeeAccess.requireSame(permit, access.scope(actor, REFUND_READ));
		return result;
	}

	public View reconcile(Actor actor, String id) {
		Identifiers.require(id);
		var attempt = internalRead(actor.tenantId(), id);
		var permit = orders.authorize(actor, REFUND_RECONCILE, attempt.orderId());
		return reconcileInternal(actor.tenantId(), id, actor, permit);
	}

	private View reconcileInternal(String tenant, String id) {
		return reconcileInternal(tenant, id, null, null);
	}

	/** 员工只核对原退款承诺；渠道在事务外，原来源资金责任不依赖员工离职。 */
	private View reconcileInternal(String tenant, String id, Actor actor, OrderApi.Authorization permit) {
		var attempt = internalRead(tenant, id);
		RefundChannel.Evidence proof = null;
		if (!attempt.status().equals("SUCCEEDED")) {
			channel.ensure(tenant, attempt);
			proof = channel.observe(tenant, id);
			if (!proof.tenantId().equals(tenant) || !proof.refundId().equals(id)
					|| !proof.orderId().equals(attempt.orderId()) || !proof.currency().equals(attempt.currency())
					|| new BigDecimal(proof.amount()).compareTo(new BigDecimal(attempt.amount())) != 0) throw conflict();
			if (proof.status().equals("SUCCEEDED")) Inputs.text(proof.transactionId(), 64);
		}
		var observed = proof;
		java.util.function.Supplier<View> apply = () -> {
			var current = Inputs.found(mapper.lock(tenant, id));
			if (current.status().equals("SUCCEEDED") || observed == null || !observed.status().equals("SUCCEEDED")) return current;
			if (mapper.succeed(tenant, id, observed.transactionId(), JsonCodec.write(observed)) != 1) throw conflict();
			var done = mapper.find(tenant, id);
			outbox.append(tenant, "refund.succeeded.v1", id, done.version(), done);
			return done;
		};
		if (actor == null) return tx.execute(status -> apply.get());
		// 原接口无客户端键，保留显式每次核对；资金条件更新不会重复退款或重复终态事件。
		String key = UUID.randomUUID().toString();
		return commands.runGuarded(actor, "refund.reconcile", key,
				Arrays.asList(id, permit.permit().identity()), View.class,
				() -> orders.lockAuthorization(actor, permit), () -> {
			var result = apply.get();
			access.audit(actor, permit.permit(), "refund.reconcile", key);
			return result;
		});
	}

	/** 只写沙箱账本，真实退款成功必须由后续服务端查询确认。 */
	public View sandboxSuccess(Actor actor, String key, String id) {
		actor.requireAdmin();
		var attempt = internalRead(actor.tenantId(), id);
		Inputs.require(attempt.provider().equals("SANDBOX"), "不是沙箱退款");
		channel.ensure(actor.tenantId(), attempt);
		return commands.run(actor, "sandbox.refund.success", key, id, View.class, () -> {
			var proof = Inputs.found(mapper.channel(actor.tenantId(), id));
			if (proof.status().equals("UNKNOWN"))
				mapper.channelSuccess(actor.tenantId(), id, UUID.randomUUID().toString());
			return mapper.find(actor.tenantId(), id);
		});
	}

	/** 与支付核对相同的公平契约：单租户每次最多5条，租户轮转；未知最多消耗五次自动核对，渠道不可用不消耗，人工仍可继续查同一退款号。 */
	public int tick() {
		return checks.run((after, limit) -> mapper.tenants(after, limit, RetryPolicy.TRANSIENT.budget()),
				this::checkTenant);
	}

	private int checkTenant(String tenant, TenantRotation.Run run) {
		int count = 0;
		for (var check : mapper.due(tenant, run.limit(), RetryPolicy.TRANSIENT.budget())) {
			if (run.exhausted())
				break;
			if (mapper.claim(check, (1 << Math.min(check.checkAttempts() + 2, 6))
					+ java.util.concurrent.ThreadLocalRandom.current().nextInt(2)) != 1)
				continue;
			run.attempted();
			try {
				reconcileInternal(tenant, check.refundId());
				count++;
				run.succeeded();
			}
			catch (RuntimeException failure) {
				var type = FailureClass.of(failure);
				String error = type + ":" + (failure instanceof DomainException d ? "DomainException/" + d.code()
						: failure.getClass().getSimpleName());
				try {
					if (type.transientFailure())
						mapper.checkTransient(tenant, check.refundId(), check.checkAttempts() + 1,
								RetryPolicy.TRANSIENT.delayMillis(check.checkTransientFailures() + 1,
										java.util.concurrent.ThreadLocalRandom.current().nextDouble()),
								error);
					else
						mapper.checkError(tenant, check.refundId(), error);
				}
				catch (RuntimeException unrecorded) {
					org.slf4j.LoggerFactory.getLogger(getClass())
						.warn("refund check failure not recorded id={} errorType={}", check.refundId(),
								unrecorded.getClass().getSimpleName());
				}
				org.slf4j.LoggerFactory.getLogger(getClass())
					.warn("refund check id={} failureClass={} errorType={}", check.refundId(), type,
							failure.getClass().getSimpleName());
				if (run.failed(type))
					break;
			}
		}
		return count;
	}

	private DomainException conflict() {
		return new DomainException(DomainException.Code.CONFLICT, "退款额度或渠道证据冲突");
	}

	/** 成功退款是单调终态事实；一次最多核对100订单，不读取其他租户。 */
	public java.util.List<RefundApi.Total> totals(String tenant, java.util.List<String> ids) {
		com.lrj.commerce.kernel.Identifiers.require(tenant);
		Inputs.require(ids != null && !ids.isEmpty() && ids.size() <= 100, "退款汇总批次无效");
		ids.forEach(com.lrj.commerce.kernel.Identifiers::require);
		return mapper.totals(tenant, ids);
	}

}
