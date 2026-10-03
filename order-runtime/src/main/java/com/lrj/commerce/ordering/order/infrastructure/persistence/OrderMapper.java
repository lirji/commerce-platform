package com.lrj.commerce.ordering.order.infrastructure.persistence;

import com.lrj.commerce.ordering.order.api.OrderApi.View;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 订单行只在所属域内部使用，跨域消费稳定API投影。 */
@Mapper
public interface OrderMapper {

	record Row(String orderId, String memberId, String storeId, String merchantId, String quoteId, String payable,
			String status, String paymentKind, long version, Instant createdAt, Instant expiresAt, String itemsJson,
			com.lrj.commerce.runtime.api.identity.Actor.Channel channel) {
	}

	void insert(@Param("tenant") String tenant, @Param("view") View view, @Param("items") String items,
			@Param("address") byte[] address);

	Row read(@Param("tenant") String tenant, @Param("member") String member, @Param("id") String id);

	Row lock(@Param("tenant") String tenant, @Param("member") String member, @Param("id") String id);

	List<Row> list(@Param("tenant") String tenant, @Param("member") String member, @Param("after") String after,
			@Param("limit") int limit, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省时不附加筛选条件。 */
	default List<Row> list(@Param("tenant") String tenant, @Param("member") String member, @Param("after") String after,
			@Param("limit") int limit) {
		return list(tenant, member, after, limit, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}

	/** 实际创建时间范围、稳定订单游标，效果 Owner 内部授权先于调用。 */
	List<Row> listForEffects(@Param("tenant") String tenant, @Param("store") String store,
			@Param("from") Instant from, @Param("to") Instant to, @Param("after") String after, @Param("limit") int limit);

	/** 员工列表与批次按 Owner 的真实门店列在 LIMIT 前过滤。 */
	List<Row> scopedList(@Param("tenant") String tenant, @Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
			@Param("after") String after, @Param("limit") int limit, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省时不附加筛选条件。 */
	default List<Row> scopedList(@Param("tenant") String tenant, @Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
			@Param("after") String after, @Param("limit") int limit) {
		return scopedList(tenant, scope, after, limit, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}
	List<Row> expiredScoped(@Param("tenant") String tenant, @Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
			@Param("now") Instant now, @Param("max") int max, @Param("transientMax") int transientMax);
	/** 旧批次回执仍以原目标复核当前能力，不从新的候选集重建来源。 */
	record ExpiryFact(String orderId, String storeId, long storeVersion) {}
	List<ExpiryFact> expiryFacts(@Param("actor") com.lrj.commerce.runtime.api.identity.Actor actor, @Param("key") String key);

	/** 批次命令唯一归属与逐单真实门店证据共用事务。 */
	void expiryFact(@Param("actor") com.lrj.commerce.runtime.api.identity.Actor actor, @Param("key") String key,
			@Param("order") String order, @Param("store") String store, @Param("version") long version);

	/** 租户与游标在SQL中过滤，只取投影所需列。 */
	List<com.lrj.commerce.ordering.order.api.OrderApi.BehaviorSource> behaviorSources(
			@Param("tenant") String tenant, @Param("after") String after, @Param("limit") int limit);

	int change(@Param("tenant") String tenant, @Param("id") String id, @Param("version") long version,
			@Param("status") String status);

	boolean hasPaidSince(String tenant, String member, String store, java.time.Instant since);

	Row internalRead(@Param("tenant") String tenant, @Param("id") String id);

	Row internalLock(@Param("tenant") String tenant, @Param("id") String id);

	/** 到期候选只排除退避中与已停止自动处理的订单；max与transientMax是两类失败的次数上限。 */
	record ExpiryCheck(String orderId, int expiryAttempts, int expiryTransientAttempts) {
	}

	List<Row> expired(@Param("tenant") String tenant, @Param("now") Instant now, @Param("max") int max,
			@Param("transientMax") int transientMax);

	List<String> expiryTenants(@Param("after") String after, @Param("now") Instant now, @Param("limit") int limit,
			@Param("max") int max, @Param("transientMax") int transientMax);

	List<ExpiryCheck> expiryDue(@Param("tenant") String tenant, @Param("now") Instant now, @Param("limit") int limit,
			@Param("max") int max, @Param("transientMax") int transientMax);

	Row expiredLock(@Param("tenant") String tenant, @Param("id") String id, @Param("now") Instant now,
			@Param("max") int max, @Param("transientMax") int transientMax);

	int expiryFailed(@Param("tenant") String tenant, @Param("id") String id,
			@Param("transient") boolean transientFailure, @Param("retryAt") Instant retryAt,
			@Param("error") String error);

	int expiryRetry(@Param("tenant") String tenant, @Param("id") String id, @Param("max") int max,
			@Param("transientMax") int transientMax);

	/** 停止自动到期的未支付订单与最近失败证据；failureClass非空时按证据前缀过滤。 */
	record ExpiryStopped(String orderId, String status, int expiryAttempts, int expiryTransientAttempts,
			String expiryError) {
	}

	List<ExpiryStopped> expiryStopped(@Param("tenant") String tenant, @Param("failureClass") String failureClass,
			@Param("after") String after, @Param("limit") int limit, @Param("max") int max,
			@Param("transientMax") int transientMax);

	ExpiryStopped expiryStoppedOne(@Param("tenant") String tenant, @Param("id") String id, @Param("max") int max,
			@Param("transientMax") int transientMax);

	com.lrj.commerce.runtime.work.WorkLanes.Backlog expiryBacklog(@Param("now") Instant now, @Param("max") int max,
			@Param("transientMax") int transientMax);

}
