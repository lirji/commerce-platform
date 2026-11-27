package com.lrj.commerce.runtime.audit.persistence;

import com.lrj.commerce.runtime.audit.OperationAudit.Evidence;
import com.lrj.commerce.runtime.command.persistence.CommandMapper.Key;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 审计平台表的追加写入和有界只读查询。 */
@Mapper
public interface OperationAuditMapper {
	record Attempt(String sourceSystem, String requestId, String traceId, String scopeType, String tenantId,
			String actorId, String action, String resourceId, String policy) {
	}

	record Row(long id, String sourceSystem, String requestId, String traceId, String action, String actorId,
			String resourceId, Instant occurredAt, String result, String errorCode, Long effectId,
			Long originalAttemptId, Long beforeVersion, Long afterVersion, String reason, String changesJson) {
	}

	record Query(Instant from, Instant to, String actorId, String resourceId, String action, String result,
			Instant cursorTime, Long cursorId, int limit) {
	}

	/** 插入已认证的请求入口证据。 */
	int insertAttempt(Attempt attempt);

	/** 用请求唯一键绑定后续写入。 */
	long findAttempt(@Param("source") String source, @Param("requestId") String requestId);

	/** 直接追加独立确认的请求结果。 */
	int insertOutcome(@Param("attemptId") long attemptId, @Param("result") String result,
			@Param("errorCode") String errorCode);

	/** 复用现有成功表，在业务事务中写入同一份成功证据。 */
	int insertEffect(@Param("key") Key key, @Param("attemptId") long attemptId,
			@Param("evidence") Evidence evidence, @Param("changes") String changes);

	/** 已确认命令回放，读取锁保护下的最近一次原提交证据。 */
	int insertReplay(@Param("key") Key key, @Param("attemptId") long attemptId);

	/** 平台记录只对平台范围查询开放。 */
	List<Row> list(Query query);

	/** 按授权范围和原始尝试流水查询。 */
	Row detail(long id);
}
