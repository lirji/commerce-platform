package com.lrj.commerce.runtime.persistence;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 保留期清理：只删除终态事件（DELIVERED、SKIPPED）及其Inbox行、已完成的命令；审计与恢复记录不在此处删除。 */
@Mapper
public interface RetentionMapper {

	/** 按(状态,可用时间)索引取最老的一批终态事件并锁定，已被其他事务锁定（投递、重放共享锁）的行跳过。 */
	List<String> terminalEvents(@Param("status") String status, @Param("cutoff") Instant cutoff,
			@Param("limit") int limit);

	int deleteInbox(@Param("ids") List<String> ids);

	int deleteEvents(@Param("ids") List<String> ids);

	/** 已完成（有结果快照）且早于截止时间的命令，按创建时间最老的一批。 */
	int deleteCommands(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

	/** 最老的可清理行时间，用于滞后观测；只读索引首行。 */
	Instant oldestTerminal(@Param("status") String status);

	Instant oldestCommand();

	/** 运行中或暂停的重放任务的最早区间起点；清理截止时间不越过它。 */
	Instant earliestActiveReplay();

	int liveDue(@Param("types") java.util.Collection<String> types, @Param("cap") int cap);

}
