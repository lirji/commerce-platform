package com.lrj.commerce.runtime.api.recovery;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 已停止自动处理（隔离、自动核对用尽等）的工作的恢复入口：各模块登记自己的工作类型，平台恢复服务统一负责授权、范围、幂等与审计。
 * 恢复是运维把停止的工作放回可执行状态，区别于重试（同一执行在可恢复失败后的自动继续）与重放（基于历史数据刻意重复处理）。
 * 实现只在调用方事务内做条件更新，失败时不得留下部分写入；恢复不清除失败证据。
 */
public interface RecoverableWork {

	/** RETRY放回自动处理（只执行尚未完成的部分）；SKIP把工作终止为跳过，不再执行任何剩余副作用。 */
	enum Action {

		RETRY, SKIP

	}

	/** 停止项的元信息与失败证据，不含业务载荷。 */
	record Stopped(String workType, String workId, String state, String failureClass, String lastError, int attempts,
			int transientAttempts, Instant firstFailedAt, Instant lastFailedAt, int manualRecoveries) {
	}

	/** 恢复前后状态与恢复时的失败分类，写入恢复审计。 */
	record Transition(String previousState, String newState, String failureClass) {
	}

	/** 全局唯一的稳定工作类型代码，出现在恢复审计与接口中。 */
	String workType();

	Set<Action> actions();

	/** 本租户已停止项，按工作标识游标分页；failureClass非空时只返回该分类。 */
	List<Stopped> stopped(String tenant, String failureClass, String after, int limit);

	/** 本租户的单项当前状态；不存在或不是停止项时返回null。 */
	Stopped find(String tenant, String workId);

	/** 工作不存在返回null；不在可恢复状态或动作不被允许时抛CONFLICT。 */
	Transition recover(String tenant, String workId, Action action, Instant now);

}
