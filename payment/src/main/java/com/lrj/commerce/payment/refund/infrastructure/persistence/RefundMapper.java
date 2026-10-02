package com.lrj.commerce.payment.refund.infrastructure.persistence;

import org.apache.ibatis.annotations.*;
import java.util.List;
import com.lrj.commerce.payment.refund.api.RefundApi;
import com.lrj.commerce.payment.refund.application.port.RefundChannel;

/** 退款权威记录与渠道账本分开保存；金额上限由支付聚合行锁保护。 */
@Mapper
public interface RefundMapper {

	List<RefundApi.Total> totals(@Param("tenant") String tenant, @Param("ids") List<String> ids);

	RefundApi.View byCase(@Param("tenant") String tenant, @Param("caseId") String caseId);

	RefundApi.View find(@Param("tenant") String tenant, @Param("id") String id);

	RefundApi.View lock(@Param("tenant") String tenant, @Param("id") String id);

	void insert(@Param("tenant") String tenant, @Param("view") RefundApi.View view);

	int succeed(@Param("tenant") String tenant, @Param("id") String id, @Param("transaction") String transaction,
			@Param("proof") String proof);

	List<RefundApi.View> list(@Param("tenant") String tenant, @Param("after") String after, @Param("limit") int limit);

	void ensureChannel(@Param("tenant") String tenant, @Param("view") RefundApi.View view);

	RefundChannel.Evidence channel(@Param("tenant") String tenant, @Param("id") String id);

	int channelSuccess(@Param("tenant") String tenant, @Param("id") String id,
			@Param("transaction") String transaction);

	/** checkAttempts是消耗型自动核对次数（上限5），checkTransientFailures是渠道不可用等不消耗核对次数的失败。 */
	record Check(String tenantId, String refundId, int checkAttempts, int checkTransientFailures) {
	}

	List<Check> due(@Param("tenant") String tenant, @Param("limit") int limit, @Param("transientMax") int transientMax);

	List<String> tenants(@Param("after") String after, @Param("limit") int limit,
			@Param("transientMax") int transientMax);

	int checkTransient(@Param("tenant") String tenant, @Param("id") String id, @Param("claimed") int claimed,
			@Param("delayMillis") long delayMillis, @Param("error") String error);

	int checkError(@Param("tenant") String tenant, @Param("id") String id, @Param("error") String error);

	com.lrj.commerce.runtime.work.WorkLanes.Backlog checkBacklog(@Param("transientMax") int transientMax);

	int claim(@Param("check") Check check, @Param("delay") int delay);

	/** 只读订单归属投影，不写跨模块表；实际门店过滤先于分页。 */
	List<RefundApi.View> scopedList(@Param("tenant") String tenant,
			@Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
			@Param("after") String after, @Param("limit") int limit);

}
