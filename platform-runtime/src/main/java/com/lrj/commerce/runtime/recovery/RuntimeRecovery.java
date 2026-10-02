package com.lrj.commerce.runtime.recovery;

import com.lrj.commerce.kernel.*;
import com.lrj.commerce.runtime.recovery.persistence.RecoveryMapper;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.recovery.RecoverableWork;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.work.FailureClass;

/**
 * 停止工作的统一恢复流程：查看（stopped/find）→ 按分类筛选 → 指定标识决定动作 → 执行 → 审计。
 * 只作用于凭据所属租户；每次最多MAX_ITEMS个显式标识，不提供“恢复全部”；可附带期望的失败分类作为护栏，分类已变化的项被拒绝。
 * 整个请求是一个幂等命令事务：逐项结果（含被拒绝项）与审计一起提交；同一幂等键重放只返回原结果。
 */
@Component
public class RuntimeRecovery {

	/** 单次恢复的显式标识上限：一个命令事务内完成，远低于10秒事务超时。 */
	public static final int MAX_ITEMS = 50;

	public record Request(String workType, RecoverableWork.Action action, List<String> workIds,
			String expectedFailureClass, String reason) {
	}

	public record Outcome(String workId, String result, String previousState, String newState, String rejection) {
	}

	public record Result(String workType, String action, int applied, int rejected, List<Outcome> outcomes) {
	}

	public record WorkType(String workType, Set<RecoverableWork.Action> actions) {
	}

	private final Map<String, RecoverableWork> works = new TreeMap<>();

	/** 与平台运维及旧管理员资格区分，中央员工只得到闭集 tenant runtime 能力。 */
	@org.springframework.beans.factory.annotation.Autowired private EmployeeAccess access;

	private final Commands commands;

	private final RecoveryAudit audit;

	private final RecoveryMapper history;

	private final Clock clock;

	public RuntimeRecovery(List<RecoverableWork> works, Commands commands, RecoveryAudit audit, RecoveryMapper history,
			Clock clock) {
		for (var w : works)
			if (this.works.put(w.workType(), w) != null)
				throw new IllegalStateException("重复可恢复工作类型：" + w.workType());
		this.commands = commands;
		this.audit = audit;
		this.history = history;
		this.clock = clock;
	}

	public List<WorkType> workTypes(Actor actor) {
		var permit = readPermit(actor);
		var result = works.values().stream().map(w -> new WorkType(w.workType(), w.actions())).toList();
		EmployeeAccess.requireSame(permit, readPermit(actor));
		return result;
	}

	/** 本租户停止项，failureClass为空时不过滤。 */
	public List<RecoverableWork.Stopped> stopped(Actor actor, String workType, String failureClass, String after,
			int limit) {
		var permit = readPermit(actor);
		Inputs.page(after, limit);
		var result = work(workType).stopped(actor.tenantId(), failureClass(failureClass), after, limit);
		EmployeeAccess.requireSame(permit, readPermit(actor));
		return result;
	}

	/** 恢复审计历史，可按工作类型与标识过滤。 */
	public List<RecoveryMapper.Row> history(Actor actor, String workType, String workId, long after, int limit) {
		var permit = readPermit(actor);
		Inputs.require(after >= 0, "游标无效");
		Inputs.page("", limit);
		var result = history.list(actor.tenantId(), workType, workId, after, limit);
		EmployeeAccess.requireSame(permit, readPermit(actor));
		return result;
	}

	public Result recover(Actor actor, String key, Request input) {
		var permit = access.scope(actor, RUNTIME_RECOVER);
		if (permit.identity() == null) actor.require(Actor.Capability.RUNTIME_RECOVERY_EXECUTE);
		Inputs.require(input != null && input.action() != null && input.workIds() != null && !input.workIds().isEmpty()
				&& input.workIds().size() <= MAX_ITEMS, "恢复需要1至" + MAX_ITEMS + "个显式工作标识");
		var work = work(input.workType());
		Inputs.require(work.actions().contains(input.action()), "该工作类型不支持此恢复动作");
		Inputs.text(input.reason(), 256);
		String expected = failureClass(input.expectedFailureClass());
		var ids = new LinkedHashSet<String>();
		for (var id : input.workIds()) {
			Identifiers.require(id);
			Inputs.require(ids.add(id), "工作标识不能重复");
		}
		var normalized = new Request(work.workType(), input.action(), List.copyOf(ids), expected, input.reason());
		Object command = permit.identity() == null ? normalized : new Object[] {normalized, permit.identity()};
		return commands.runGuarded(actor, "runtime.recovery", key, command, Result.class, () -> access.lock(permit), () -> {
			var outcomes = new ArrayList<Outcome>();
			int applied = 0;
			for (var id : ids) {
				var outcome = recoverOne(actor, key, "runtime.recovery", work, id, input.action(), expected,
						input.reason());
				outcomes.add(outcome);
				if (outcome.result().equals(RecoveryAudit.APPLIED))
					applied++;
			}
			access.audit(actor, permit, "runtime.recovery", key, key);
			return new Result(work.workType(), input.action().name(), applied, outcomes.size() - applied, outcomes);
		});
	}

	/**
	 * 在调用方命令事务内恢复一项并写审计；被拒绝的项同样写审计。实现只做条件更新，拒绝时没有部分写入，因此捕获领域冲突后事务仍可继续。
	 * 供旧的单项重试接口复用，保证所有恢复入口都有相同的审计。
	 */
	public Outcome recoverOne(Actor actor, String key, String operation, RecoverableWork work, String id,
			RecoverableWork.Action action, String expectedFailureClass, String reason) {
		var current = work.find(actor.tenantId(), id);
		if (current == null)
			return reject(actor, key, operation, work, id, action, null, null, reason, "NOT_STOPPED");
		if (expectedFailureClass != null && !expectedFailureClass.equals(current.failureClass()))
			return reject(actor, key, operation, work, id, action, current.state(), current.failureClass(), reason,
					"FAILURE_CLASS_MISMATCH");
		RecoverableWork.Transition transition;
		try {
			transition = work.recover(actor.tenantId(), id, action, clock.instant());
		}
		catch (DomainException conflict) {
			if (conflict.code() != DomainException.Code.CONFLICT)
				throw conflict;
			return reject(actor, key, operation, work, id, action, current.state(), current.failureClass(), reason,
					"STATE_CHANGED");
		}
		if (transition == null)
			return reject(actor, key, operation, work, id, action, current.state(), current.failureClass(), reason,
					"NOT_FOUND");
		audit.record(actor, operation, key, work.workType(), id, action.name(), transition.previousState(),
				transition.newState(), transition.failureClass(), reason, RecoveryAudit.APPLIED, null);
		return new Outcome(id, RecoveryAudit.APPLIED, transition.previousState(), transition.newState(), null);
	}

	private Outcome reject(Actor actor, String key, String operation, RecoverableWork work, String id,
			RecoverableWork.Action action, String state, String failureClass, String reason, String rejection) {
		audit.record(actor, operation, key, work.workType(), id, action.name(), state, null, failureClass, reason,
				RecoveryAudit.REJECTED, rejection);
		return new Outcome(id, RecoveryAudit.REJECTED, state, null, rejection);
	}

	public RecoverableWork work(String workType) {
		Inputs.require(workType != null, "工作类型缺失");
		var work = works.get(workType);
		if (work == null)
			throw new DomainException(DomainException.Code.NOT_FOUND, "未知工作类型");
		return work;
	}

	private EmployeeAccess.ScopePermit readPermit(Actor actor) {
		var permit = access.scope(actor, RUNTIME_READ);
		if (permit.identity() == null) actor.require(Actor.Capability.RUNTIME_RECOVERY_READ);
		return permit;
	}

	private static String failureClass(String value) {
		if (value == null || value.isBlank())
			return null;
		try {
			return FailureClass.valueOf(value).name();
		}
		catch (IllegalArgumentException e) {
			throw new DomainException(DomainException.Code.INVALID_INPUT, "失败分类无效");
		}
	}

}
