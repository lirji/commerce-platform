package com.lrj.commerce.runtime.persistence;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 运维恢复审计只追加，不更新不删除。 */
@Mapper
public interface RecoveryMapper {

	record Entry(String tenantId, String actorId, String operation, String commandKey, String workType, String workId,
			String action, String previousState, String newState, String failureClass, String reason, String result,
			String rejection) {
	}

	record Row(long id, String actorId, String operation, String workType, String workId, String action,
			String previousState, String newState, String failureClass, String reason, String result, String rejection,
			Instant createdAt) {
	}

	void insert(@Param("e") Entry entry);

	List<Row> list(@Param("tenant") String tenant, @Param("workType") String workType, @Param("workId") String workId,
			@Param("after") long after, @Param("limit") int limit);

}
