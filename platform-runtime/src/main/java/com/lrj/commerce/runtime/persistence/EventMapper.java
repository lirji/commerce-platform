package com.lrj.commerce.runtime.persistence;

import org.apache.ibatis.annotations.*;

/** 可靠事件表由平台运行模块维护，业务效果必须与事件同事务。 */
@Mapper
public interface EventMapper {

	/** skipReason非空时直接写入终态SKIPPED，用于发布方声明无消费者的事件类型。 */
	void insert(@Param("id") String id, @Param("tenant") String tenant, @Param("type") String type,
			@Param("aggregate") String aggregate, @Param("version") long version, @Param("json") String json,
			@Param("skipReason") String skipReason);

	java.util.List<com.lrj.commerce.runtime.api.EventHandler.Event> pending(@Param("tenant") String tenant,
			@Param("types") java.util.Set<String> types, @Param("limit") int limit);

	com.lrj.commerce.runtime.api.EventHandler.Event lock(@Param("tenant") String tenant, @Param("id") String id);

	int inbox(@Param("consumer") String consumer,
			@Param("event") com.lrj.commerce.runtime.api.EventHandler.Event event);

	int delivered(@Param("id") String id);

	/** 瞬时失败只累加transient_attempts，其余累加attempts；任一计数达到各自上限即ISOLATED；首末失败时间与分类作为隔离证据。 */
	int failed(@Param("id") String id, @Param("delayMillis") long delayMillis,
			@Param("transient") boolean transientFailure, @Param("max") int max,
			@Param("transientMax") int transientMax, @Param("error") String error,
			@Param("failureClass") String failureClass);

	com.lrj.commerce.runtime.api.EventHandler.Event find(@Param("tenant") String tenant, @Param("id") String id);

	java.util.List<String> tenants(@Param("types") java.util.Set<String> types, @Param("after") String after,
			@Param("limit") int limit);

	java.util.List<String> freshTenants(@Param("types") java.util.Set<String> types,
			@Param("windowMillis") long windowMillis, @Param("limit") int limit);

	java.util.List<EventView> list(@Param("tenant") String tenant, @Param("after") String after,
			@Param("limit") int limit);

	int retry(@Param("tenant") String tenant, @Param("id") String id);

	/** 本租户隔离事件，按事件标识游标分页；failureClass非空时只返回该分类。 */
	java.util.List<EventView> isolated(@Param("tenant") String tenant, @Param("failureClass") String failureClass,
			@Param("after") String after, @Param("limit") int limit);

	EventView view(@Param("tenant") String tenant, @Param("id") String id);

	/** 恢复前加行锁读取：并发的恢复命令串行化，审计记录的前状态就是被改变的状态。 */
	EventView lockView(@Param("tenant") String tenant, @Param("id") String id);

	/** 运维终止隔离事件：ISOLATED→SKIPPED(OPERATOR_SKIPPED)，未完成的消费者不再执行，失败证据与Inbox保留。 */
	int skip(@Param("tenant") String tenant, @Param("id") String id);

	Health health(@Param("tenant") String tenant, @Param("types") java.util.Set<String> types);

	/** 失败证据：分类、两类计数、首末失败时间、最近错误（消费者与异常类型）、跳过原因与人工重放次数，不含载荷。 */
	record EventView(String eventId, String eventType, String aggregateId, String status, int attempts,
			java.time.Instant availableAt, String lastError, int transientAttempts, String failureClass,
			java.time.Instant firstFailedAt, java.time.Instant lastFailedAt, String skipReason, int manualRetries) {
	}

	/**
	 * 积压诊断：年龄为秒，延迟为毫秒；unrouted是没有消费者也未声明的类型（缺少必需消费者），不计入积压；
	 * retrying含两类失败，transientRetrying只含依赖不可用等瞬时失败。
	 */
	record Health(long pending, long due, long retrying, long isolated, Long oldestDueAgeSeconds, long unrouted,
			Long recentMaxLatencyMillis, long transientRetrying) {
	}

}
