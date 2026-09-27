package com.lrj.commerce.runtime.event;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.recovery.RecoverableWork;
import com.lrj.commerce.runtime.event.persistence.EventMapper;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/**
 * 事件的恢复入口。RETRY：ISOLATED或SKIPPED放回PENDING，重置两类计数，保留失败分类、首末失败时间与最后错误；
 * 重放只执行尚无Inbox的消费者，已成功的消费者不会再次执行。当前没有消费者的类型拒绝，否则只会变成永远无人处理的PENDING。
 * SKIP：运维确认不再处理的隔离事件终止为SKIPPED(OPERATOR_SKIPPED)，已执行消费者的效果保留，未执行的不再执行。
 */
@Component
public class EventRecovery implements RecoverableWork {

	public static final String WORK_TYPE = "event";

	private final EventMapper mapper;

	private final EventDispatcher dispatcher;

	public EventRecovery(EventMapper mapper, EventDispatcher dispatcher) {
		this.mapper = mapper;
		this.dispatcher = dispatcher;
	}

	public String workType() {
		return WORK_TYPE;
	}

	public Set<Action> actions() {
		return EnumSet.of(Action.RETRY, Action.SKIP);
	}

	public List<Stopped> stopped(String tenant, String failureClass, String after, int limit) {
		return mapper.isolated(tenant, failureClass, after, limit).stream().map(EventRecovery::view).toList();
	}

	public Stopped find(String tenant, String id) {
		var event = mapper.view(tenant, id);
		return event == null || !Set.of("ISOLATED", "SKIPPED").contains(event.status()) ? null : view(event);
	}

	public Transition recover(String tenant, String id, Action action, Instant now) {
		// 加锁读取：并发的SKIP与RETRY串行执行，后执行者看到前者提交后的状态，审计前状态准确。
		var event = mapper.lockView(tenant, id);
		if (event == null)
			return null;
		if (action == Action.SKIP) {
			if (mapper.skip(tenant, id) != 1)
				throw new DomainException(DomainException.Code.CONFLICT, "只有隔离事件可以终止");
			return new Transition(event.status(), "SKIPPED", event.failureClass());
		}
		if (!dispatcher.consumes(event.eventType()))
			throw new DomainException(DomainException.Code.CONFLICT, "事件类型当前没有消费者");
		if (mapper.retry(tenant, id) != 1)
			throw new DomainException(DomainException.Code.CONFLICT, "事件不在可重放状态");
		return new Transition(event.status(), "PENDING", event.failureClass());
	}

	private static Stopped view(EventMapper.EventView e) {
		return new Stopped(WORK_TYPE, e.eventId(), e.status(), e.failureClass(), e.lastError(), e.attempts(),
				e.transientAttempts(), e.firstFailedAt(), e.lastFailedAt(), e.manualRetries());
	}

}
