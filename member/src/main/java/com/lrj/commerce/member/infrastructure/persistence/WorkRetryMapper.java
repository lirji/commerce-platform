package com.lrj.commerce.member.infrastructure.persistence;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 会员域后台逐项重试状态：只在某项失败期间存在，成功即删除；隔离项只能由恢复命令放回。 */
@Mapper
public interface WorkRetryMapper {

	record Row(String lane, String itemId, int attempts, int transientAttempts, Instant retryAt, String failureClass,
			String lastError, Instant firstFailedAt, Instant lastFailedAt, Instant quarantinedAt,
			int manualRecoveries) {
	}

	/** 原子累加对应计数并在任一计数达到上限时写入隔离时间；已隔离的项不再改变隔离时间。 */
	int failed(@Param("tenant") String tenant, @Param("lane") String lane, @Param("item") String item,
			@Param("transient") boolean transientFailure, @Param("retryAt") Instant retryAt,
			@Param("error") String error, @Param("failureClass") String failureClass, @Param("now") Instant now,
			@Param("max") int max, @Param("transientMax") int transientMax);

	int clear(@Param("tenant") String tenant, @Param("lane") String lane, @Param("item") String item);

	Row find(@Param("tenant") String tenant, @Param("lane") String lane, @Param("item") String item);

	/** 恢复前加行锁读取：并发恢复串行化，后到者看到前者提交后的状态。 */
	Row lock(@Param("tenant") String tenant, @Param("lane") String lane, @Param("item") String item);

	/** 恢复只放回已隔离项：重置两类计数与下次时间，保留失败分类、首末失败时间与最近错误。 */
	int recover(@Param("tenant") String tenant, @Param("lane") String lane, @Param("item") String item,
			@Param("now") Instant now);

	List<Row> quarantined(@Param("tenant") String tenant, @Param("lane") String lane,
			@Param("failureClass") String failureClass, @Param("after") String after, @Param("limit") int limit);

	long quarantinedCount(@Param("lane") String lane);

}
