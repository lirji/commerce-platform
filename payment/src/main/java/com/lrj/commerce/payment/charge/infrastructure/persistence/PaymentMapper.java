package com.lrj.commerce.payment.charge.infrastructure.persistence;

import org.apache.ibatis.annotations.*;
import com.lrj.commerce.payment.charge.api.PaymentApi;
import com.lrj.commerce.payment.charge.application.port.PaymentChannel;

/** 支付尝试与沙箱渠道账本各自持久化，查询结果是唯一可信状态来源。 */
@Mapper
public interface PaymentMapper {

	PaymentApi.View byOrder(@Param("tenant") String tenant, @Param("order") String order);

	PaymentApi.View find(@Param("tenant") String tenant, @Param("id") String id);

	PaymentApi.View lock(@Param("tenant") String tenant, @Param("id") String id);

	void insert(@Param("tenant") String tenant, @Param("view") PaymentApi.View view);

	int change(@Param("tenant") String tenant, @Param("id") String id, @Param("version") long version,
			@Param("status") String status, @Param("transaction") String transaction,
			@Param("evidence") String evidence);

	void ensureChannel(@Param("tenant") String tenant, @Param("view") PaymentApi.View view);

	PaymentChannel.Evidence channel(@Param("tenant") String tenant, @Param("id") String id);

	int channelFact(@Param("tenant") String tenant, @Param("id") String id, @Param("status") String status,
			@Param("transaction") String transaction);

	int channelClose(@Param("tenant") String tenant, @Param("id") String id);

	/** checkAttempts是消耗型自动核对次数（上限5），checkTransientFailures是渠道不可用等不消耗核对次数的失败。 */
	record Check(String tenantId, String paymentId, String orderId, int checkAttempts, int checkTransientFailures) {
	}

	java.util.List<Check> due(@Param("tenant") String tenant, @Param("limit") int limit,
			@Param("transientMax") int transientMax);

	java.util.List<String> dueTenants(@Param("after") String after, @Param("limit") int limit,
			@Param("transientMax") int transientMax);

	/** 瞬时失败退回本次领取的核对次数，按瞬时退避重新安排；claimed为领取后的次数，防止覆盖并发领取。 */
	int checkTransient(@Param("tenant") String tenant, @Param("id") String id, @Param("claimed") int claimed,
			@Param("delayMillis") long delayMillis, @Param("error") String error);

	int checkError(@Param("tenant") String tenant, @Param("id") String id, @Param("error") String error);

	com.lrj.commerce.runtime.work.WorkLanes.Backlog checkBacklog(@Param("transientMax") int transientMax);

	int claimCheck(@Param("tenant") String tenant, @Param("id") String id, @Param("attempts") int attempts,
			@Param("delay") int delay);

	int rearmCheck(@Param("tenant") String tenant, @Param("order") String order);

	int reserveRefund(@Param("tenant") String tenant, @Param("id") String id, @Param("amount") String amount);

}
