package com.lrj.commerce.member.application;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.member.infrastructure.persistence.WorkRetryMapper;
import com.lrj.commerce.runtime.FailureClass;
import com.lrj.commerce.runtime.RetryPolicy;
import com.lrj.commerce.runtime.api.RecoverableWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.BiPredicate;

/**
 * 积分过期与周期考核的逐项重试：业务事务回滚后在独立事务记录失败，该项按分类退避，其余项与其他租户照常推进。
 * 瞬时失败走TRANSIENT预算且不计入毒工作次数；任一预算用尽即隔离，只有恢复命令能放回。
 * 记录失败本身失败时该项保持到期，下次访问再试，不会丢失工作；成功时由业务事务删除重试行。
 */
@Component
class ItemRetries {

	static final String POINTS = "points", CYCLES = "cycles";

	private static final Logger log = LoggerFactory.getLogger(ItemRetries.class);

	private final WorkRetryMapper mapper;

	private final TransactionTemplate tx;

	private final Clock clock;

	ItemRetries(WorkRetryMapper mapper, PlatformTransactionManager manager, Clock clock) {
		this.mapper = mapper;
		this.clock = clock;
		tx = new TransactionTemplate(manager);
		tx.setTimeout(10);
	}

	/** attempts与transientAttempts取自到期查询（无重试行时为null）；返回本次失败是否使该项进入隔离。 */
	boolean failed(String lane, String tenant, String item, Integer attempts, Integer transientAttempts,
			FailureClass type, RuntimeException failure) {
		boolean transientFailure = type.transientFailure();
		var policy = transientFailure ? RetryPolicy.TRANSIENT : RetryPolicy.POISON;
		int failures = (transientFailure ? Objects.requireNonNullElse(transientAttempts, 0)
				: Objects.requireNonNullElse(attempts, 0)) + 1;
		var now = clock.instant();
		var retryAt = now
			.plusMillis(policy.delayMillis(failures, java.util.concurrent.ThreadLocalRandom.current().nextDouble()));
		String error = evidence(type, failure);
		try {
			tx.executeWithoutResult(s -> mapper.failed(tenant, lane, item, transientFailure, retryAt, error,
					type.name(), now, RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget()));
		}
		catch (RuntimeException unrecorded) {
			log.warn("{} item failure not recorded tenant={} item={} errorType={}", lane, tenant, item,
					unrecorded.getClass().getSimpleName());
			return false;
		}
		boolean quarantined = policy.exhausted(failures);
		if (quarantined)
			log.warn("{} item quarantined tenant={} item={} failureClass={} lastError={}", lane, tenant, item, type,
					error);
		else
			log.warn("{} item retry tenant={} item={} failureClass={} failures={} errorType={}", lane, tenant, item,
					type, failures, failure.getClass().getSimpleName());
		return quarantined;
	}

	/** 在业务事务内调用：该项已成功完成，删除重试行；无重试行时是主键上的空操作。 */
	void cleared(String lane, String tenant, String item) {
		mapper.clear(tenant, lane, item);
	}

	long quarantinedCount(String lane) {
		return mapper.quarantinedCount(lane);
	}

	/** 只记录分类与异常类型，不含异常文本或载荷。 */
	static String evidence(FailureClass type, RuntimeException failure) {
		String error = type + ":" + (failure instanceof DomainException d ? "DomainException/" + d.code()
				: failure.getClass().getSimpleName());
		return error.length() > 160 ? error.substring(0, 160) : error;
	}

	/**
	 * 车道的恢复入口：列出已隔离项；RETRY放回自动处理并保留失败证据。底层工作已不再需要处理（例如批次已由其他路径归档）时删除重试行，
	 * 状态记为RESOLVED，不把已完成的工作重新放回。stillDue在恢复事务内判断该项是否仍需处理。
	 */
	RecoverableWork recoverable(String workType, String lane, BiPredicate<String, String> stillDue) {
		return new RecoverableWork() {
			public String workType() {
				return workType;
			}

			public Set<Action> actions() {
				return EnumSet.of(Action.RETRY);
			}

			public List<Stopped> stopped(String tenant, String failureClass, String after, int limit) {
				return mapper.quarantined(tenant, lane, failureClass, after, limit).stream().map(this::view).toList();
			}

			public Stopped find(String tenant, String id) {
				var row = mapper.find(tenant, lane, id);
				return row == null || row.quarantinedAt() == null ? null : view(row);
			}

			private Stopped view(WorkRetryMapper.Row r) {
				return new Stopped(workType, r.itemId(), "QUARANTINED", r.failureClass(), r.lastError(), r.attempts(),
						r.transientAttempts(), r.firstFailedAt(), r.lastFailedAt(), r.manualRecoveries());
			}

			public Transition recover(String tenant, String id, Action action, Instant now) {
				// 加锁读取后再判断与变更：并发恢复中只有第一个看到隔离状态，其余在锁释放后看到已变化的状态而被拒绝。
				var row = mapper.lock(tenant, lane, id);
				if (row == null)
					return null;
				if (action != Action.RETRY || row.quarantinedAt() == null)
					throw new DomainException(DomainException.Code.CONFLICT, "工作不在可恢复状态");
				if (!stillDue.test(tenant, id)) {
					if (mapper.clear(tenant, lane, id) != 1)
						throw new DomainException(DomainException.Code.CONFLICT, "工作状态已变化");
					return new Transition("QUARANTINED", "RESOLVED", row.failureClass());
				}
				if (mapper.recover(tenant, lane, id, now) != 1)
					throw new DomainException(DomainException.Code.CONFLICT, "工作状态已变化");
				return new Transition("QUARANTINED", "READY", row.failureClass());
			}
		};
	}

}
