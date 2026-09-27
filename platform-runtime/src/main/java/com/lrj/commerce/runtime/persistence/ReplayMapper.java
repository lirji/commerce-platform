package com.lrj.commerce.runtime.persistence;

import com.lrj.commerce.runtime.api.EventHandler;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 历史重放任务与重放扫描；只读DELIVERED事件，不改变事件状态。 */
@Mapper
public interface ReplayMapper {

	record Job(String jobId, String consumerId, String eventTypes, String mode, Instant fromAt, Instant toAt,
			int maxEvents, String status, Instant cursorCreatedAt, String cursorEventId, int examined, int executed,
			int alreadyProcessed, int failed, String lastError, String reason, String createdBy, long version,
			Instant createdAt, Instant updatedAt) {
	}

	record TypeCount(String eventType, long events, long processed) {
	}

	record Backlog(long running, Instant oldestRunning, long failed) {
	}

	void insert(@Param("tenant") String tenant, @Param("j") Job job);

	Job find(@Param("tenant") String tenant, @Param("id") String id);

	Job lock(@Param("tenant") String tenant, @Param("id") String id);

	List<Job> list(@Param("tenant") String tenant, @Param("after") String after, @Param("limit") int limit);

	int active(@Param("tenant") String tenant);

	/** 在任务行锁内推进游标与计数，版本加一。 */
	int progress(@Param("tenant") String tenant, @Param("j") Job job);

	int status(@Param("tenant") String tenant, @Param("id") String id, @Param("expected") long expected,
			@Param("status") String status);

	List<String> tenants(@Param("after") String after, @Param("limit") int limit);

	String oldestRunning(@Param("tenant") String tenant);

	/** 游标之后的下一条已投递事件标识（一致性读，不加锁，不产生间隙锁）。 */
	record Next(String eventId, Instant createdAt) {
	}

	Next next(@Param("tenant") String tenant, @Param("j") Job job, @Param("types") List<String> types);

	/** 按主键共享锁定仍为DELIVERED的事件：与保留期清理（SKIP LOCKED）互斥，只锁一行。 */
	EventHandler.Event lockDelivered(@Param("tenant") String tenant, @Param("id") String id);

	/** 与投递相同的去重边界：(消费者,事件)已有Inbox时返回0。 */
	int inbox(@Param("consumer") String consumer, @Param("event") EventHandler.Event event);

	/** 试运行：范围内至多cap条事件按类型计数，以及该消费者已处理数；只读。 */
	List<TypeCount> dryRun(@Param("tenant") String tenant, @Param("consumer") String consumer,
			@Param("types") List<String> types, @Param("from") Instant from, @Param("to") Instant to,
			@Param("cap") int cap);

	/** 当前到期的实时事件数，至多cap（有界）；重放在实时积压高时让路。 */
	int liveDue(@Param("types") java.util.Collection<String> types, @Param("cap") int cap);

	Backlog backlog();

}
