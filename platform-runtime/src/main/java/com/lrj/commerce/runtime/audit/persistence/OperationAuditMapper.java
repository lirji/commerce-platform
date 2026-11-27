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
			String actorId, String storeId, String action, String resourceId, String policy) {
	}

	record Row(long id, String sourceSystem, String requestId, String traceId, String action, String actorId,
			String resourceId, Instant occurredAt, String result, String errorCode, Long effectId,
			Long originalAttemptId, Long beforeVersion, Long afterVersion, String reason, String changesJson) {
	}

	record Query(String tenantId, String storeId, Instant from, Instant to, String actorId, String resourceId, String action, String result,
			Instant cursorTime, Long cursorId, int limit) {
	}

	/** 插入已认证的请求入口证据。 */
	int insertAttempt(Attempt attempt);

	/** 用请求唯一键绑定后续写入。 */
	long findAttempt(@Param("source") String source, @Param("requestId") String requestId);

	/** 和归档清理共用原尝试锁，确认后到的事实是否需要追加到归档引用。 */
	Long lockLiveAttempt(@Param("id") long id);

	/** 内容摘要为幂等键，稳定引用通过原档案成员解析。 */
	int insertLateEvidence(@Param("id") long id, @Param("key") String key, @Param("kind") String kind, @Param("payload") String payload);

	Long findLateEvidence(@Param("id") long id, @Param("key") String key);

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
